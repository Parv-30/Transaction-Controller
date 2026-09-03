package com.ledger.txprocessor.messaging;

public final class MessagingConstants {
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_QUEUE = "ledger.transaction.posted.queue";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private MessagingConstants() {
    }
}
