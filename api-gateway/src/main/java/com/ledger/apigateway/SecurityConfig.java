package com.ledger.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**").permitAll()
                        // Only /accounts and /transactions (the bare list/search paths, no path
                        // variable) are admin-gated. GET /accounts/{accountRef} and
                        // GET /transactions/{id} are deliberately left open to any authenticated
                        // caller -- they're read-only single-resource lookups, no more sensitive
                        // than the existing ungated GET /holds/{id}, and the future end-user UI
                        // may reasonably want an authenticated user to look up their own
                        // transaction detail. See the design spec, Section 2.
                        .pathMatchers(HttpMethod.GET, "/accounts").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.GET, "/transactions").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.POST, "/transactions/*/reverse").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.GET, "/reconciliation/runs").hasAuthority("ROLE_admin")
                        .pathMatchers(HttpMethod.GET, "/holds").hasAuthority("ROLE_admin")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(new KeycloakRealmRoleConverter()))))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
