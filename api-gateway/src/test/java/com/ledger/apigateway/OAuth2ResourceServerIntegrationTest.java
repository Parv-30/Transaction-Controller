package com.ledger.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MultiValueMapAdapter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuth2ResourceServerIntegrationTest {

    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:25.0")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../keycloak-realm/ledger-realm.json"),
                    "/opt/keycloak/data/import/ledger-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/ledger").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static HttpServer stubLedger;

    @BeforeAll
    static void startAll() throws Exception {
        keycloak.start();
        stubLedger = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedger.createContext("/transactions", exchange -> {
            byte[] response = "ledger-stub".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubLedger.start();
    }

    @AfterAll
    static void stopAll() {
        keycloak.stop();
        stubLedger.stop(0);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        String issuerUri = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ledger";
        registry.add("KEYCLOAK_ISSUER_URI", () -> issuerUri);
        registry.add("LEDGER_SERVICE_URL", () -> "http://localhost:" + stubLedger.getAddress().getPort());
    }

    @LocalServerPort
    private int gatewayPort;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String issuerBaseUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ledger";
    }

    private String obtainClientCredentialsToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMapAdapter<String, String> form = new MultiValueMapAdapter<>(Map.of(
                "grant_type", java.util.List.of("client_credentials"),
                "client_id", java.util.List.of("chaos-suite-client"),
                "client_secret", java.util.List.of("chaos-suite-secret")
        ));
        ResponseEntity<Map> response = restTemplate.postForEntity(
                issuerBaseUrl() + "/protocol/openid-connect/token",
                new HttpEntity<>(form, headers), Map.class);
        return (String) response.getBody().get("access_token");
    }

    @Test
    void requestWithoutTokenIsRejected() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/transactions", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithValidClientCredentialsTokenIsRoutedThrough() {
        String token = obtainClientCredentialsToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + gatewayPort + "/transactions", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ledger-stub");
    }
}
