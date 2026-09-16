package com.ledger.apigateway;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps Keycloak's nested {@code realm_access.roles} claim into Spring Security
 * {@link GrantedAuthority} objects.
 *
 * <p>Spring Security's default {@code JwtAuthenticationConverter} /
 * {@code JwtGrantedAuthoritiesConverter} only reads a flat top-level claim (e.g. {@code scope});
 * {@code setAuthoritiesClaimName(...)} does not support a dotted/nested path like
 * {@code "realm_access.roles"}. Keycloak's realm roles live nested one level down, under the
 * {@code realm_access} claim's own {@code roles} array, so a custom converter is required.
 */
@Component
public class KeycloakRealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<GrantedAuthority> authorities;
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .map(GrantedAuthority.class::cast)
                    .toList();
        } else {
            authorities = List.of();
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
