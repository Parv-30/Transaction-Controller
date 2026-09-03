package com.ledger.txprocessor.cdc;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts and stops the embedded Debezium engine alongside the Spring context. This is a
 * single-instance-only design: the engine claims a single named Postgres replication slot
 * ({@code debezium_ledger_slot}) and Postgres will reject a second concurrent consumer of the
 * same slot, so running more than one Transaction Processor instance with CDC enabled would
 * cause the second instance's engine to fail to start (or steal the slot from the first). V1
 * makes no attempt to support multiple instances of this engine; that is explicitly out of
 * scope per the architecture spec, which calls for a single CDC-capturing instance.
 */
@Component
public class DebeziumEngineLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineLifecycle.class);

    private final OutboxChangeConsumer changeConsumer;

    @Value("${cdc.database.hostname}")
    private String dbHostname;
    @Value("${cdc.database.port}")
    private String dbPort;
    @Value("${cdc.database.user}")
    private String dbUser;
    @Value("${cdc.database.password}")
    private String dbPassword;
    @Value("${cdc.database.dbname}")
    private String dbName;
    @Value("${cdc.offset.storage.file.filename}")
    private String offsetStorageFilename;

    /**
     * Whether {@link SmartLifecycle} should start this engine automatically as part of
     * Spring context refresh. Defaults to {@code true} for production. Integration tests
     * that need the source database's table/publication to exist BEFORE the engine attempts
     * to open its replication slot (Debezium's {@code publication.autocreate.mode=disabled}
     * fails immediately if the named publication is not already present) set this to
     * {@code false} and start the engine explicitly — via {@link #start()} — from a
     * {@code @BeforeEach}, after that raw-SQL setup has run. Without this flag, a
     * {@code @SpringBootTest} context refresh (which fires {@code SmartLifecycle.start()} on
     * all beans automatically, before JUnit's {@code @BeforeEach} ever runs) unavoidably
     * races the engine's startup against the test's schema/publication setup, since JUnit has
     * no hook that runs before Spring context refresh but after Testcontainers' container
     * start. This was confirmed by reproduction: with autostart left on, the engine's
     * background thread threw
     * {@code ConnectException: Publication autocreation is disabled, please create one and
     * restart the connector} every time, because it always ran before {@code @BeforeEach}.
     */
    @Value("${cdc.engine.auto-startup:true}")
    private boolean autoStartupEnabled;

    private ExecutorService executorService;
    private DebeziumEngine<?> engine;
    private volatile boolean running = false;

    public DebeziumEngineLifecycle(OutboxChangeConsumer changeConsumer) {
        this.changeConsumer = changeConsumer;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartupEnabled;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Properties props = new Properties();
        props.setProperty("name", "ledger-outbox-connector");
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offsetStorageFilename);
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("database.hostname", dbHostname);
        props.setProperty("database.port", dbPort);
        props.setProperty("database.user", dbUser);
        props.setProperty("database.password", dbPassword);
        props.setProperty("database.dbname", dbName);
        props.setProperty("topic.prefix", "ledger");
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", "debezium_ledger_slot");
        props.setProperty("publication.name", "ledger_outbox_pub");
        props.setProperty("publication.autocreate.mode", "disabled");
        props.setProperty("table.include.list", "public.outbox");
        props.setProperty("snapshot.mode", "no_data");
        // Root-caused by reproduction: DebeziumEngine.create(Json.class) uses Kafka Connect's
        // JsonConverter for the record value, which by default wraps the actual change
        // envelope in a {"schema": ..., "payload": {...}} structure (schemas.enable=true is
        // the JsonConverter default). OutboxEventPayloadMapper.parse expects the change
        // envelope's "after" field at the JSON root (i.e. {"before":..,"after":{...}}), which
        // is only true once schemas are disabled — with the default left on, root.get("after")
        // is always null (it is actually at root.get("payload").get("after")), so every
        // captured record was silently discarded as if it were a delete/non-insert event.
        // Confirmed via DEBUG logging: OutboxEventPayloadMapper.parse returned null even for
        // a genuine INSERT record whose raw JSON clearly contained a populated "after" object
        // one level down, under "payload". Disabling schemas here is the standard Debezium/
        // Kafka Connect way to get the flat, root-level envelope shape the mapper is written
        // for, and also produces smaller, more efficient records (no repeated schema blob on
        // every message).
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");
        // Keeps the replication connection from sitting fully idle. Without traffic, an
        // intermediary (e.g. Docker Desktop's forwarded-port proxy in local/dev/test setups)
        // can silently drop a long-idle TCP connection; Debezium's own probe-on-poll interval
        // is too coarse to prevent that reliably. A periodic heartbeat query is Debezium's
        // standard mitigation for this class of issue and is a reasonable production default
        // too, not just a test workaround.
        props.setProperty("heartbeat.interval.ms", "5000");

        engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(changeConsumer)
                .build();

        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(engine);
        running = true;
        log.info("Debezium embedded engine started against {}:{}/{}", dbHostname, dbPort, dbName);
    }

    @Override
    public synchronized void stop() {
        try {
            if (engine != null) {
                engine.close();
            }
        } catch (Exception e) {
            log.warn("Error closing Debezium engine", e);
        } finally {
            if (executorService != null) {
                executorService.shutdown();
            }
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
