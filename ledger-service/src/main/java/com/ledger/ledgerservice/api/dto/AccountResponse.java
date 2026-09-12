package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record AccountResponse(String accountRef, String currency, long balanceMinor,
                               String status, UUID accountGroupId) {
}
