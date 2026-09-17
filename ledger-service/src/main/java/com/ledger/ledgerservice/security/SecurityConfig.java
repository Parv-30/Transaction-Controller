package com.ledger.ledgerservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Adding {@code spring-boot-starter-oauth2-resource-server} to the classpath (needed so
 * {@link JwtRoleReader} can decode the forwarded {@code Authorization} header via a
 * {@code JwtDecoder} bean) would otherwise trigger Spring Boot's default security
 * auto-configuration, which requires authentication on every request. That is wrong for
 * ledger-service: the API Gateway is the sole point that authenticates and authorizes
 * incoming traffic (see {@code api-gateway}'s {@code SecurityConfig}); ledger-service only
 * needs to manually decode the already-validated bearer token the gateway forwards
 * downstream unchanged, purely to read the caller's roles for self-scoping (see
 * {@link JwtRoleReader}, {@link CallerContext}). This config disables the default
 * "authenticated by default" filter chain so ledger-service's own HTTP layer keeps behaving
 * exactly as it did before this dependency was added.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
