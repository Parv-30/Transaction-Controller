package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.WebhookDedup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDedupRepository extends JpaRepository<WebhookDedup, String> {
}
