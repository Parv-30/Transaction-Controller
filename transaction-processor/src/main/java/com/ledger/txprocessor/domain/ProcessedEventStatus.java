package com.ledger.txprocessor.domain;

public enum ProcessedEventStatus {
    CAPTURED, PUBLISHED, CONSUMED, DUPLICATE_IGNORED, PUBLISH_FAILED
}
