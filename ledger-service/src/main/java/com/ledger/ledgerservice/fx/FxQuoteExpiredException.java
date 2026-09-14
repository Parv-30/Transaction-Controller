package com.ledger.ledgerservice.fx;

import java.util.UUID;

public class FxQuoteExpiredException extends RuntimeException {
    public FxQuoteExpiredException(UUID pendingTransferId) {
        super("FX quote expired before leg 2 could be posted for transfer: " + pendingTransferId);
    }
}
