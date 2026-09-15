package com.ledger.fxservice.service;

import com.ledger.fxservice.api.dto.QuoteResponse;
import com.ledger.fxservice.api.dto.RateResponse;
import com.ledger.fxservice.domain.FxQuote;
import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxQuoteRepository;
import com.ledger.fxservice.repository.FxRateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class RateService {

    private final FxRateRepository fxRateRepository;
    private final FxQuoteRepository fxQuoteRepository;
    private final long syncIntervalMs;
    private final long quoteTtlSeconds;
    private final MeterRegistry meterRegistry;

    public RateService(FxRateRepository fxRateRepository,
                        FxQuoteRepository fxQuoteRepository,
                        @Value("${fx.rate-sync.interval-ms:3600000}") long syncIntervalMs,
                        @Value("${fx.quote.ttl-seconds:60}") long quoteTtlSeconds,
                        MeterRegistry meterRegistry) {
        this.fxRateRepository = fxRateRepository;
        this.fxQuoteRepository = fxQuoteRepository;
        this.syncIntervalMs = syncIntervalMs;
        this.quoteTtlSeconds = quoteTtlSeconds;
        this.meterRegistry = meterRegistry;
    }

    public RateResponse getLatestRate(String baseCurrency, String quoteCurrency) {
        FxRate rate = findLatestOrThrow(baseCurrency, quoteCurrency);
        boolean stale = isStale(rate.getFetchedAt());
        return new RateResponse(rate.getRate(), stale, rate.getFetchedAt());
    }

    public RateResponse getRateAt(String baseCurrency, String quoteCurrency, Instant at) {
        FxRate rate = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                        baseCurrency, quoteCurrency, at)
                .orElseThrow(() -> new RateNotAvailableException(baseCurrency, quoteCurrency));
        return new RateResponse(rate.getRate(), isStale(rate.getFetchedAt()), rate.getFetchedAt());
    }

    public QuoteResponse lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
        FxRate rate = findLatestOrThrow(baseCurrency, quoteCurrency);
        boolean stale = isStale(rate.getFetchedAt());

        Instant now = Instant.now();
        FxQuote quote = new FxQuote(UUID.randomUUID(), baseCurrency, quoteCurrency,
                rate.getRate(), now, now.plusSeconds(quoteTtlSeconds));
        fxQuoteRepository.save(quote);

        return new QuoteResponse(quote.getId(), quote.getRateUsed(), quote.getExpiresAt(), stale);
    }

    private FxRate findLatestOrThrow(String baseCurrency, String quoteCurrency) {
        return fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(baseCurrency, quoteCurrency)
                .orElseThrow(() -> {
                    meterRegistry.counter("fx.quote.failure").increment();
                    return new RateNotAvailableException(baseCurrency, quoteCurrency);
                });
    }

    private boolean isStale(Instant fetchedAt) {
        return fetchedAt.isBefore(Instant.now().minusMillis(syncIntervalMs * 2));
    }
}
