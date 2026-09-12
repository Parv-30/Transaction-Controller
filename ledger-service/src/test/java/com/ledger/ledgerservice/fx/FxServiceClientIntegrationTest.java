package com.ledger.ledgerservice.fx;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FxServiceClientIntegrationTest {

    HttpServer stubFxService;

    @AfterEach
    void stopStub() {
        if (stubFxService != null) {
            stubFxService.stop(0);
        }
    }

    @Test
    void lockQuoteParsesTheResponseCorrectly() throws Exception {
        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2026-09-13T12:01:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();

        FxServiceClient client = new FxServiceClient(
                "http://localhost:" + stubFxService.getAddress().getPort());

        FxQuote quote = client.lockQuote("USD", "EUR", 10_000L);

        assertThat(quote.quoteId().toString()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(quote.rateUsed()).isEqualByComparingTo("0.92000000");
        assertThat(quote.stale()).isFalse();
    }
}
