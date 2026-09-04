package com.ledger.holdsservice.messaging;

public final class MessagingConstants {
    // Must match transaction-processor's MessagingConstants.LEDGER_EXCHANGE /
    // TRANSACTION_POSTED_ROUTING_KEY exactly — this is V1's existing exchange, not a new one.
    // Verified against the real, current
    // transaction-processor/src/main/java/com/ledger/txprocessor/messaging/MessagingConstants.java
    // as of this task.
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String HOLDS_TRANSACTION_POSTED_QUEUE = "holds.ledger.transaction.posted.queue";

    public static final String HOLD_EVENTS_ROUTING_KEY_PREFIX = "holds.";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private MessagingConstants() {
    }
}
