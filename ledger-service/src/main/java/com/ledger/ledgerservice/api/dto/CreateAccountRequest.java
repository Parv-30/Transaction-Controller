package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record CreateAccountRequest(String accountRef, String currency, UUID accountGroupId) {
}
