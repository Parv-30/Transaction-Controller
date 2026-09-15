package com.ledger.gatewaysimulator.domain;

import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.ledger.gatewaysimulator.repository.WebhookDedupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EntityPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ExternalDepositRepository depositRepository;
    @Autowired private ExternalWithdrawalRepository withdrawalRepository;
    @Autowired private WebhookDedupRepository webhookDedupRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void findByExternalReferenceRoundTrips() {
        ExternalDeposit deposit = new ExternalDeposit(UUID.randomUUID(), "ext-ref-1", "acct-1",
                1000L, "USD", DepositStatus.RECEIVED, "{}");
        depositRepository.saveAndFlush(deposit);

        assertThat(depositRepository.findByExternalReference("ext-ref-1")).isPresent();
        assertThat(depositRepository.findByExternalReference("does-not-exist")).isEmpty();
    }

    @Test
    void findByStatusAndSubmittedAtBeforeFindsOnlyStuckSubmittedRows() {
        UUID stuckId = UUID.randomUUID();
        ExternalWithdrawal stuck = new ExternalWithdrawal(stuckId, UUID.randomUUID(), "acct-1", 500L, "USD");
        withdrawalRepository.saveAndFlush(stuck);

        UUID freshId = UUID.randomUUID();
        ExternalWithdrawal fresh = new ExternalWithdrawal(freshId, UUID.randomUUID(), "acct-2", 750L, "USD");
        withdrawalRepository.saveAndFlush(fresh);

        Instant backdated = Instant.now().minus(2, ChronoUnit.MINUTES);
        jdbcTemplate.update("UPDATE external_withdrawals SET submitted_at = ? WHERE id = ?",
                Timestamp.from(backdated), stuckId);

        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);
        List<ExternalWithdrawal> stuckRows = withdrawalRepository
                .findByStatusAndSubmittedAtBefore(WithdrawalStatus.SUBMITTED, cutoff);

        assertThat(stuckRows).extracting(ExternalWithdrawal::getId).contains(stuckId);
        assertThat(stuckRows).extracting(ExternalWithdrawal::getId).doesNotContain(freshId);
    }

    @Test
    void webhookDedupRecordsRedeliveryCount() {
        WebhookDedup dedup = new WebhookDedup("wh-ref-1");
        webhookDedupRepository.saveAndFlush(dedup);
        dedup.recordRedelivery();
        webhookDedupRepository.saveAndFlush(dedup);

        WebhookDedup reloaded = webhookDedupRepository.findById("wh-ref-1").orElseThrow();
        assertThat(reloaded.getWebhookCount()).isEqualTo(2);
    }
}
