package com.ledger.holdsservice.api.dto;

public record HeldBalanceResponse(String accountRef, long heldBalanceMinor) {
}
