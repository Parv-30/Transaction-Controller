package com.ledger.ledgerservice.holds;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldsServiceClientIntegrationTest {

    HttpServer stubHoldsService;

    @AfterEach
    void stopStub() {
        if (stubHoldsService != null) {
            stubHoldsService.stop(0);
        }
    }

    @Test
    void getHeldBalanceParsesTheResponseCorrectly() throws Exception {
        stubHoldsService = HttpServer.create(new InetSocketAddress(0), 0);
        stubHoldsService.createContext("/accounts/held-test-acct/held-balance", exchange -> {
            String body = "{\"accountRef\":\"held-test-acct\",\"heldBalanceMinor\":4200}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubHoldsService.start();

        HoldsServiceClient client = new HoldsServiceClient(
                "http://localhost:" + stubHoldsService.getAddress().getPort(), 2000);

        long heldBalance = client.getHeldBalance("held-test-acct");

        assertThat(heldBalance).isEqualTo(4200L);
    }

    @Test
    void getHeldBalanceThrowsHoldsServiceUnavailableWhenTheServerIsUnreachable() {
        // Nothing is listening on this port -- connection refused.
        HoldsServiceClient client = new HoldsServiceClient("http://localhost:1", 500);

        assertThatThrownBy(() -> client.getHeldBalance("held-test-acct"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }

    @Test
    void getHeldBalanceThrowsHoldsServiceUnavailableOnTimeout() throws Exception {
        stubHoldsService = HttpServer.create(new InetSocketAddress(0), 0);
        stubHoldsService.createContext("/accounts/slow-acct/held-balance", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] response = "{\"accountRef\":\"slow-acct\",\"heldBalanceMinor\":0}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubHoldsService.start();

        HoldsServiceClient client = new HoldsServiceClient(
                "http://localhost:" + stubHoldsService.getAddress().getPort(), 200);

        assertThatThrownBy(() -> client.getHeldBalance("slow-acct"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }
}
