package com.ledger.fxservice.sync;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(RateSyncJob.class);
    private static final List<String> BASE_CURRENCIES = List.of("USD", "EUR", "GBP");

    private final FrankfurterClient frankfurterClient;
    private final FxRateRepository fxRateRepository;

    public RateSyncJob(FrankfurterClient frankfurterClient, FxRateRepository fxRateRepository) {
        this.frankfurterClient = frankfurterClient;
        this.fxRateRepository = fxRateRepository;
    }

    @Scheduled(fixedDelayString = "${fx.rate-sync.interval-ms:3600000}")
    public void run() {
        syncNow(BASE_CURRENCIES);
    }

    public void syncNow(List<String> baseCurrencies) {
        Instant fetchedAt = Instant.now();
        for (String base : baseCurrencies) {
            List<String> targets = baseCurrencies.stream().filter(c -> !c.equals(base)).toList();
            try {
                FrankfurterRatesResponse response = frankfurterClient.fetchLatestRates(base, targets);
                response.rates().forEach((quoteCurrency, rate) ->
                        fxRateRepository.save(new FxRate(UUID.randomUUID(), base, quoteCurrency,
                                rate, "frankfurter", fetchedAt)));
            } catch (Exception e) {
                // One base currency's sync failing (provider down, rate-limited, network error)
                // must not block syncing the others -- log and continue. The staleness flag in
                // Task 5's quote logic is what surfaces this to callers, not an exception here.
                log.warn("Failed to sync rates for base currency {}: {}", base, e.getMessage());
            }
        }
    }
}
