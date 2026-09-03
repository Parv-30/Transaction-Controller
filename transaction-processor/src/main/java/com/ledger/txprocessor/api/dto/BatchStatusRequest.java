package com.ledger.txprocessor.api.dto;

import java.util.List;
import java.util.UUID;

public record BatchStatusRequest(List<UUID> outboxEventIds) {
}
