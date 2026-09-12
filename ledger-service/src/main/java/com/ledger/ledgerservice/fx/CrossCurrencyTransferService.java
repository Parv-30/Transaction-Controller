package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.service.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orchestrates the cross-currency transfer saga. Deliberately NOT @Transactional itself: the
 * saga spans several separate transactions (the quote-lock is a remote call in no transaction
 * at all; each leg and the compensation are their own transaction, owned by
 * {@link CrossCurrencyTransferPoster}). Calling into the poster -- a different bean -- is what
 * makes Spring's transactional proxy apply to each step.
 */
@Service
public class CrossCurrencyTransferService {

    private final FxServiceClient fxServiceClient;
    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final CrossCurrencyTransferPoster poster;
    private final AccountRepository accountRepository;

    public CrossCurrencyTransferService(FxServiceClient fxServiceClient,
                                        PendingFxTransferRepository pendingFxTransferRepository,
                                        CrossCurrencyTransferPoster poster,
                                        AccountRepository accountRepository) {
        this.fxServiceClient = fxServiceClient;
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.poster = poster;
        this.accountRepository = accountRepository;
    }

    public PendingFxTransferResponse transfer(CreateCrossCurrencyTransferRequest request) {
        var existing = pendingFxTransferRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // Currency lookup here (for the quote request) duplicates the lookup
        // CrossCurrencyTransferPoster does again inside postLeg1/postLeg2 -- deliberate,
        // since this service needs the currencies up front to know which pair to quote,
        // before any PendingFxTransfer row exists for the poster to work from.
        String sourceCurrency = resolveCurrency(request.sourceAccountRef());
        String destCurrency = resolveCurrency(request.destAccountRef());

        FxQuote quote = fxServiceClient.lockQuote(sourceCurrency, destCurrency, request.sourceAmountMinor());
        long destAmountMinor = quote.rateUsed()
                .multiply(BigDecimal.valueOf(request.sourceAmountMinor()))
                .longValue();

        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), request.idempotencyKey(),
                quote.quoteId(), request.sourceAccountRef(), request.destAccountRef(),
                request.sourceAmountMinor(), quote.rateUsed(), destAmountMinor);
        pendingFxTransferRepository.save(transfer);

        try {
            poster.postLeg1(transfer.getId());
            poster.postLeg2(transfer.getId());
        } catch (Exception legFailure) {
            poster.compensate(transfer.getId());
        }

        PendingFxTransfer finalState = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        return toResponse(finalState);
    }

    private String resolveCurrency(String accountRef) {
        return accountRepository.findByAccountRef(accountRef)
                .orElseThrow(() -> new AccountNotFoundException(accountRef))
                .getCurrency();
    }

    private PendingFxTransferResponse toResponse(PendingFxTransfer transfer) {
        return new PendingFxTransferResponse(transfer.getId(), transfer.getStatus().name(),
                transfer.getSourceAccountRef(), transfer.getDestAccountRef(),
                transfer.getSourceAmountMinor(), transfer.getDestAmountMinor(), null);
    }
}
