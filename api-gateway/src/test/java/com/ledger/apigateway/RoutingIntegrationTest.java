package com.ledger.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingIntegrationTest {

    static HttpServer stubLedger;
    static HttpServer stubHolds;

    @LocalServerPort
    private int gatewayPort;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeAll
    static void startStubs() throws Exception {
        stubLedger = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedger.createContext("/transactions", exchange -> respond(exchange, "ledger-stub"));
        stubLedger.start();

        stubHolds = HttpServer.create(new InetSocketAddress(0), 0);
        stubHolds.createContext("/holds", exchange -> respond(exchange, "holds-stub"));
        stubHolds.start();
    }

    @AfterAll
    static void stopStubs() {
        stubLedger.stop(0);
        stubHolds.stop(0);
    }

    @DynamicPropertySource
    static void registerStubUrls(DynamicPropertyRegistry registry) {
        registry.add("LEDGER_SERVICE_URL", () -> "http://localhost:" + stubLedger.getAddress().getPort());
        registry.add("HOLDS_SERVICE_URL", () -> "http://localhost:" + stubHolds.getAddress().getPort());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    void routesTransactionsPathToLedgerService() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/transactions", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ledger-stub");
    }

    @Test
    void routesHoldsPathToHoldsService() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/holds", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("holds-stub");
    }
}
