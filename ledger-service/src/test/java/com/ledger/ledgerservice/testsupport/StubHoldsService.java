package com.ledger.ledgerservice.testsupport;

import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared stub for the Holds Service's {@code GET /accounts/{accountRef}/held-balance} endpoint,
 * used by integration tests that exercise {@code TransactionPoster} (directly, or indirectly via
 * the cross-currency saga) but are not themselves testing held-balance behavior. Every one of
 * those tests just needs a reachable Holds Service that always reports zero held funds so the
 * held-balance check in {@code TransactionPoster.postInTransaction} never blocks them.
 *
 * <p>Tests that DO care about a specific held balance (e.g.
 * {@code TransactionServiceIntegrationTest}) should register their own stub with a mutable
 * {@link AtomicLong} instead of this fixed-zero helper -- this class is only for the "just make
 * the call succeed" case.
 */
public final class StubHoldsService {

    private StubHoldsService() {
    }

    /**
     * Starts an {@code HttpServer} stub that reports {@code heldBalanceMinor: 0} for any account,
     * and registers {@code holds.base-url} (and a generous timeout) against it. Callers must keep
     * a reference to the returned server for the lifetime of the test JVM/class; there is no need
     * to stop it since the whole process exits at the end of the test run, matching this
     * codebase's existing stub-server precedent (e.g. {@code CrossCurrencyTransferServiceIntegrationTest}'s
     * {@code stubFxService}, which is likewise never explicitly stopped).
     */
    public static HttpServer startAlwaysZero(DynamicPropertyRegistry registry) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String accountRef = path.substring("/accounts/".length(), path.length() - "/held-balance".length());
                String body = "{\"accountRef\":\"" + accountRef + "\",\"heldBalanceMinor\":0}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            registry.add("holds.base-url", () -> "http://localhost:" + server.getAddress().getPort());
            registry.add("holds.held-balance-timeout-ms", () -> "2000");
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub Holds Service", e);
        }
    }
}
