package com.ledger.ledgerservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHasherTest {

    private final IdempotencyHasher hasher = new IdempotencyHasher();

    @Test
    void sameInputsProduceSameHash() {
        String h1 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        String h2 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void differentAmountsProduceDifferentHashes() {
        String h1 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        String h2 = hasher.hash("acct-a", "acct-b", 600L, "USD");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hashIsSixtyFourHexCharacters() {
        String hash = hasher.hash("acct-a", "acct-b", 500L, "USD");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
