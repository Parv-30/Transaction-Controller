package com.ledger.ledgerservice.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decodes the {@code Authorization: Bearer <token>} header the API Gateway forwards downstream
 * unchanged, reading the same nested {@code realm_access.roles} claim
 * {@code KeycloakRealmRoleConverter} reads at the gateway. This is a manual, explicit decode
 * point (not a Spring Security filter chain) since ledger-service has no other authorization
 * rules today -- see the design spec, Section 4, and Task 1's own file-level note.
 */
@Component
public class JwtRoleReader {

    private final JwtDecoder jwtDecoder;

    public JwtRoleReader(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @SuppressWarnings("unchecked")
    public CallerContext readRoles(String authorizationHeaderValue) {
        if (authorizationHeaderValue == null || !authorizationHeaderValue.startsWith("Bearer ")) {
            return CallerContext.NONE;
        }
        String token = authorizationHeaderValue.substring("Bearer ".length());
        Jwt jwt = jwtDecoder.decode(token);
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return CallerContext.NONE;
        }
        return new CallerContext(roles.stream().map(Object::toString).collect(Collectors.toSet()));
    }
}
