package com.ledger.txprocessor.cdc;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class DebeziumOutboxCaptureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> processorPostgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

    @Container
    static PostgreSQLContainer<?> sourcePostgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management-alpine");

    @TempDir
    static Path offsetDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", processorPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", processorPostgres::getUsername);
        registry.add("spring.datasource.password", processorPostgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        registry.add("cdc.database.hostname", sourcePostgres::getHost);
        registry.add("cdc.database.port", () -> sourcePostgres.getMappedPort(5432));
        registry.add("cdc.database.user", () -> "ledger");
        registry.add("cdc.database.password", () -> "ledger");
        registry.add("cdc.database.dbname", () -> "ledger_db");
        registry.add("cdc.offset.storage.file.filename", () -> offsetDir.resolve("offsets.dat").toString());

        // Spring's SmartLifecycle.start() fires during @SpringBootTest context refresh —
        // before JUnit ever runs @BeforeEach. If the Debezium engine autostarts here, its
        // background thread races the schema/publication setup below and (confirmed by
        // reproduction) reliably loses: it throws "Publication autocreation is disabled,
        // please create one and restart the connector" because ledger_outbox_pub does not
        // exist yet at that instant. Autostart is disabled here and the engine is started
        // explicitly, from @BeforeEach, only after the source table and publication exist.
        registry.add("cdc.engine.auto-startup", () -> "false");
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private DebeziumEngineLifecycle debeziumEngineLifecycle;

    @BeforeEach
    void setUpSourceSchemaAndCleanState() {
        JdbcTemplate sourceJdbc = new JdbcTemplate(new DriverManagerDataSource(
                sourcePostgres.getJdbcUrl(), sourcePostgres.getUsername(), sourcePostgres.getPassword()));

        sourceJdbc.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id UUID PRIMARY KEY,
                    aggregate_type VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION',
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION_POSTED',
                    payload JSONB NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        sourceJdbc.execute("DROP PUBLICATION IF EXISTS ledger_outbox_pub");
        sourceJdbc.execute("CREATE PUBLICATION ledger_outbox_pub FOR TABLE outbox");

        processedEventRepository.deleteAll();

        // Now that the outbox table and publication exist, it is safe to start the engine
        // (see the auto-startup=false note in registerProps above). start() is idempotent
        // across repeated test methods sharing this context: if a previous test already
        // started it, this is a no-op.
        debeziumEngineLifecycle.start();

        // start() only submits the engine's run loop to an executor and returns immediately
        // — it does NOT wait for the engine to actually finish attaching to Postgres and
        // creating its replication slot, which happens asynchronously on that executor
        // thread. Root-caused by reproduction: without this wait, the @Test method's INSERT
        // landed roughly 700-800ms before "CREATE_REPLICATION_SLOT" actually executed, so the
        // insert's WAL record predated the slot's start LSN and was — correctly, per normal
        // Postgres logical-replication semantics — never included in the replication stream
        // at all (confirmed via DEBUG logging: the slot's LSN never advanced past its
        // just-created starting position for the rest of the test, and the change consumer's
        // handleBatch was never invoked with a non-heartbeat record). Waiting here for the
        // slot to appear in pg_replication_slots makes the INSERT in each @Test happen only
        // after the slot is guaranteed to be capturing new changes.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer slotCount = sourceJdbc.queryForObject(
                    "SELECT count(*) FROM pg_replication_slots WHERE slot_name = 'debezium_ledger_slot'",
                    Integer.class);
            assertThat(slotCount).isEqualTo(1);
        });
    }

    @Test
    void insertingAnOutboxRowIsCapturedAndPublishedThroughToConsumption() {
        JdbcTemplate sourceJdbc = new JdbcTemplate(new DriverManagerDataSource(
                sourcePostgres.getJdbcUrl(), sourcePostgres.getUsername(), sourcePostgres.getPassword()));

        UUID outboxId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        sourceJdbc.update("""
                INSERT INTO outbox (id, aggregate_id, payload) VALUES (?, ?, ?::jsonb)
                """, outboxId, aggregateId, "{\"transactionId\":\"" + aggregateId + "\"}");

        // Awaitility's untilAsserted() only retries on AssertionError (and configured
        // exception types) — any other uncaught Throwable propagates immediately on the
        // first poll instead of being retried. Since the ProcessedEvent row does not exist
        // at all until Debezium captures the WAL insert, orElseThrow()'s NoSuchElementException
        // would abort the await after a single failed poll rather than waiting out the 30s
        // budget (confirmed by reproduction: the test failed in under 1s on the first run of
        // this fix, well before Debezium could plausibly have captured anything). Asserting
        // on isPresent() first keeps every failure mode an AssertionError so Awaitility
        // retries correctly until the full pipeline catches up.
        // 45s (not the brief's original 30s): this machine's Docker Desktop forwarded-port
        // proxy has been observed (reproducibly, across repeated runs) to drop long-idle
        // container connections after almost exactly 30s of inactivity — affecting the
        // replication connection to sourcePostgres, not just the AMQP connection to rabbitmq
        // as seen in Task 6. DebeziumEngineLifecycle now sets heartbeat.interval.ms=5000 to
        // keep the replication connection from ever going fully idle, which should prevent
        // this in practice; the wider budget here is defense in depth for the rare case a
        // drop/reconnect cycle still happens; a fresh connector restart after such a drop adds
        // real seconds (slot re-acquisition, streaming restart) beyond the fixed 10s Kafka
        // Connect backoff itself.
        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() -> {
            var found = processedEventRepository.findByOutboxEventId(outboxId);
            assertThat(found).isPresent();
            assertThat(found.get().getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
        });
    }
}
