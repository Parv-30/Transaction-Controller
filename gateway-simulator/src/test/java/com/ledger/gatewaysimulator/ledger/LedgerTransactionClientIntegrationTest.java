package com.ledger.gatewaysimulator.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionClientIntegrationTest {

    HttpServer stubLedgerService;

    @AfterEach
    void stopStub() {
        if (stubLedgerService != null) {
            stubLedgerService.stop(0);
        }
    }

    @Test
    void postTransactionParsesTheTransactionIdFromA201Response() throws Exception {
        UUID expectedTransactionId = UUID.randomUUID();

        stubLedgerService = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedgerService.createContext("/transactions", exchange -> {
            String body = "{\"transactionId\":\"" + expectedTransactionId + "\",\"status\":\"POSTED\","
                    + "\"debitAccountRef\":\"acct-debit\",\"creditAccountRef\":\"acct-credit\","
                    + "\"amountMinor\":5000,\"currency\":\"USD\",\"replay\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubLedgerService.start();

        LedgerTransactionClient client = new LedgerTransactionClient(
                "http://localhost:" + stubLedgerService.getAddress().getPort());

        UUID transactionId = client.postTransaction("acct-debit", "acct-credit", 5000, "USD",
                "deposit credit", "idem-key-1");

        assertThat(transactionId).isEqualTo(expectedTransactionId);
    }

    @Test
    void postTransactionDoesNotTreatAConflictResponseAsSuccess() throws Exception {
        stubLedgerService = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedgerService.createContext("/transactions", exchange -> {
            String body = "{\"error\":\"idempotency key reused with a different request body\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubLedgerService.start();

        LedgerTransactionClient client = new LedgerTransactionClient(
                "http://localhost:" + stubLedgerService.getAddress().getPort());

        assertThatThrownBy(() -> client.postTransaction("acct-debit", "acct-credit", 5000, "USD",
                "deposit credit", "idem-key-2"))
                .isInstanceOf(HttpClientErrorException.class)
                .isNotInstanceOf(LedgerServiceUnavailableException.class);
    }

    @Test
    void postTransactionThrowsLedgerServiceUnavailableWhenTheServerIsUnreachable() {
        // Nothing is listening on this port -- connection refused.
        LedgerTransactionClient client = new LedgerTransactionClient("http://localhost:1");

        assertThatThrownBy(() -> client.postTransaction("acct-debit", "acct-credit", 5000, "USD",
                "deposit credit", "idem-key-3"))
                .isInstanceOf(LedgerServiceUnavailableException.class);
    }
}
