package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record EntryResponse(UUID accountId, String accountRef, String direction,
                             long amountMinor, String currency) {
}
