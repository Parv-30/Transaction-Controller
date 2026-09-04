package com.ledger.holdsservice.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Applies a {@code ledger.transaction.posted} payload to {@code account_balance_cache}, in its
 * own transaction. Kept as a separate bean (rather than a private method on
 * {@link LedgerTransactionPostedConsumer}) so that {@code @Transactional} is applied via
 * Spring's proxy — a self-invocation from within the same class would silently bypass the
 * proxy and run with no transaction, the same pitfall avoided elsewhere in this codebase by
 * {@code HoldExpirySweep} delegating to {@code HoldPoster}.
 */
@Component
public class LedgerTransactionPostedApplier {

    private final AccountBalanceCacheRepository accountBalanceCacheRepository;
    private final ObjectMapper objectMapper;

    public LedgerTransactionPostedApplier(AccountBalanceCacheRepository accountBalanceCacheRepository,
                                           ObjectMapper objectMapper) {
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void applyBalanceUpdate(byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            String debitAccountRef = payload.get("debitAccountRef").asText();
            String creditAccountRef = payload.get("creditAccountRef").asText();
            long debitBalanceAfter = payload.get("debitAccountBalanceAfter").asLong();
            long creditBalanceAfter = payload.get("creditAccountBalanceAfter").asLong();

            upsertPostedBalance(debitAccountRef, debitBalanceAfter);
            upsertPostedBalance(creditAccountRef, creditBalanceAfter);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply ledger.transaction.posted payload", e);
        }
    }

    private void upsertPostedBalance(String accountRef, long postedBalanceMinor) {
        var cache = accountBalanceCacheRepository.lockByAccountRef(accountRef)
                .orElseGet(() -> new AccountBalanceCache(accountRef, 0L, 0L));
        cache.setPostedBalanceMinor(postedBalanceMinor);
        accountBalanceCacheRepository.save(cache);
    }
}
