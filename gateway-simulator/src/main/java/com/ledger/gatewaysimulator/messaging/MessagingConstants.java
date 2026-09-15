package com.ledger.gatewaysimulator.messaging;

public final class MessagingConstants {
    // Gateway Simulator's own exchange, for events it publishes (deposit.credited,
    // withdrawal.reversed, etc.) via the polling outbox publisher below.
    public static final String GATEWAY_SIM_EXCHANGE = "gateway-sim.events";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    // Must match transaction-processor's MessagingConstants.LEDGER_EXCHANGE /
    // TRANSACTION_POSTED_ROUTING_KEY exactly — this is V1's existing exchange, not a new one.
    // Verified against the real, current
    // transaction-processor/src/main/java/com/ledger/txprocessor/messaging/MessagingConstants.java
    // as of this task (Task 8 in this plan consumes it).
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String GATEWAY_SIM_TRANSACTION_POSTED_QUEUE =
            "gateway-sim.ledger.transaction.posted.queue";

    private MessagingConstants() {
    }
}
