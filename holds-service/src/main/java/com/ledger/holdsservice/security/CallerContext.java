package com.ledger.holdsservice.security;

import java.util.Set;

/**
 * The forwarded caller's realm roles, decoded from the {@code Authorization} header the API
 * Gateway forwards downstream unchanged. {@link #NONE} represents a request with no bearer
 * token at all (e.g. a call made directly against holds-service, bypassing the gateway).
 */
public record CallerContext(Set<String> roles) {

    public static final CallerContext NONE = new CallerContext(Set.of());

    public boolean isAdmin() {
        return roles.contains("admin");
    }

    public boolean hasNoRoles() {
        return roles.isEmpty();
    }
}
