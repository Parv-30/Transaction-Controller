package com.ledger.ledgerservice.fx;

import java.util.UUID;

public record PendingFxTransferResponse(
        UUID pendingTransferId, String status, String sourceAccountRef, String destAccountRef,
        long sourceAmountMinor, long destAmountMinor, String errorMessage) {
}
