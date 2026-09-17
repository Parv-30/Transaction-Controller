package com.ledger.apigateway;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // The deployed web container (docker compose) and the local Vite dev server
        // (see Task 2's Keycloak redirect URIs). No wildcard: a wildcard origin
        // cannot be combined with credentialed requests (the Authorization header).
        configuration.setAllowedOrigins(List.of("http://localhost:8085", "http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, CorsConfigurationSource corsConfigurationSource) {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        // A CORS preflight must always be permitted through the security filter
                        // chain unauthenticated -- browsers never send an Authorization header on
                        // an OPTIONS preflight, so requiring one here would make every
                        // cross-origin request fail before the real GET/POST is even attempted.
                        // This does not weaken authorization on the actual HTTP methods below.
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        // Only /accounts and /transactions (the bare list/search paths, no path
                        // variable) are admin-gated. GET /accounts/{accountRef} and
                        // GET /transactions/{id} are deliberately left open to any authenticated
                        // caller -- they're read-only single-resource lookups, no more sensitive
                        // than the existing ungated GET /holds/{id}, and the future end-user UI
                        // may reasonably want an authenticated user to look up their own
                        // transaction detail. See the design spec, Section 2.
                        // GET /holds is no longer admin-gated here: any authenticated caller may
                        // reach holds-service, which enforces self-scoping (a non-admin caller
                        // must supply accountRef and is scoped to it; only an admin caller may
                        // omit it for the full list) -- see the design spec, Section 4.
                        // GET /transactions is likewise no longer admin-gated here, for the same
                        // reason: pathMatchers cannot distinguish GET /transactions from
                        // GET /transactions?accountRef=..., so gating the bare path here would
                        // also block a non-admin end user's own scoped query. ledger-service now
                        // enforces the identical self-scoping rule for listTransactions.
                        .pathMatchers(HttpMethod.GET, "/accounts").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.POST, "/transactions/*/reverse").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.GET, "/reconciliation/runs").hasAuthority("ROLE_admin")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(new KeycloakRealmRoleConverter()))))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
