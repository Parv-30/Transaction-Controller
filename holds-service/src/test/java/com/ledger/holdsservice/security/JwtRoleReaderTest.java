package com.ledger.holdsservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtRoleReaderTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final JwtRoleReader reader = new JwtRoleReader(jwtDecoder);

    @Test
    void readsRolesFromNestedRealmAccessClaim() {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("user", "admin")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);

        CallerContext context = reader.readRoles("Bearer token-value");

        assertThat(context.isAdmin()).isTrue();
        assertThat(context.roles()).containsExactlyInAnyOrder("user", "admin");
    }

    @Test
    void nonAdminRolesYieldNonAdminContext() {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);

        CallerContext context = reader.readRoles("Bearer token-value");

        assertThat(context.isAdmin()).isFalse();
    }

    @Test
    void missingOrMalformedHeaderYieldsNoRoles() {
        assertThat(reader.readRoles(null).hasNoRoles()).isTrue();
        assertThat(reader.readRoles("not-a-bearer-token").hasNoRoles()).isTrue();
    }
}
