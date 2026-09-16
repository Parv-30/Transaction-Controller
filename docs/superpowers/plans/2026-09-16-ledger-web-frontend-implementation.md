# Ledger Web Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the platform's first client-facing frontend — a public landing page, an end-user banking SPA (`/app/*`), and an admin console (`/admin/*`) — backed by a small, necessary backend relaxation of `GET /holds` so end users can list their own holds, all deployed as a new `web` service in `docker-compose.yml`.

**Architecture:** A Vite + React + TypeScript SPA under a new top-level `web/` module, using `oidc-client-ts` for Keycloak Authorization Code + PKCE login, TanStack Query for server state, React Router for the landing/`/app`/`/admin` route trees, and a small Tailwind-based shared component set (no heavy UI library). One backend task relaxes `holds-service`'s existing `GET /holds` (already admin-gated by the Admin API Additions plan) to allow any authenticated caller scoped to their own `accountRef`, which requires `holds-service` to decode the caller's JWT for the first time — it has no Spring Security today and relies entirely on the gateway forwarding the `Authorization` header unchanged (confirmed already true: no `RemoveRequestHeader` filter exists in `api-gateway`'s routes). Everything else is additive frontend work with no other backend changes.

**Tech Stack:** Vite, React 18, TypeScript, React Router 6, Tailwind CSS, `oidc-client-ts`, TanStack Query (`@tanstack/react-query`), Recharts, Vitest, React Testing Library, nginx (production static serving), Docker multi-stage build. Backend task: Java 21, Spring Boot 3.3.4, Spring Security OAuth2 Resource Server (new dependency for `holds-service`).

**Spec:** `docs/superpowers/specs/2026-09-16-ledger-web-frontend-design.md`

## Global Constraints

- Stack: Vite + React + TypeScript + Tailwind CSS; React Router for client-side routing (spec, Section 3).
- No heavy UI component library — the design system is a small shared component set (button, input, table, badge, card, toast) built directly on Tailwind (spec, Section 3).
- OIDC client library: `oidc-client-ts` (spec, Section 3).
- Access token held in memory, never `localStorage`, to reduce XSS-exfiltration surface (spec, Section 3).
- New Keycloak client `ledger-web`: `publicClient: true`, `standardFlowEnabled: true`, PKCE required (`S256`), `redirectUris` pointing at the web app's own origin (spec, Section 3).
- Every request goes through the existing API Gateway (`http://localhost:8080` in local dev; same-origin via the Docker network once deployed) — the frontend never addresses `ledger-service`/`holds-service`/etc. directly (spec, Section 3).
- On login, decode the JWT's `realm_access.roles` claim — the same claim the backend's `KeycloakRealmRoleConverter` already reads — to route to `/app` or `/admin` (spec, Section 3, Section 6).
- Data visualization: small area/sparkline chart on the Dashboard, built with Recharts (spec, Section 1).
- Color: near-black/deep-navy primary text and key actions; one distinct indigo accent (avoiding collision with red/amber/green status colors); white/very-light-gray light-mode surfaces; dark mode is a first-class second theme (near-black background, elevated dark-gray cards) via CSS custom properties/Tailwind's dark-mode variant, every component defining both states together (spec, Section 1).
- Typography: Inter, a geometric sans. Money amounts render bold and larger than surrounding text; labels/metadata render smaller and lighter-weight. Sentence case throughout — no ALL CAPS labels, no Title Case button text (spec, Section 1).
- Layout: card-based grid for end-user screens; tables for admin list views (Accounts, Transactions, Holds, Reconciliation runs) (spec, Section 1).
- Motion: minimal and purposeful — brief route-change transition, skeleton/shimmer loading placeholders instead of spinners, no decorative animation (spec, Section 1).
- Landing page hero image is a bundled static asset (free-license stock photo), not fetched from an external API at runtime (spec, Section 2).
- An already-authenticated visitor landing on `/` is redirected straight to `/app` or `/admin` per the role-based landing rule, never shown the landing page again (spec, Section 2).
- State/data fetching: TanStack Query for server-state caching, deduplication, and polling (deposit/withdrawal status panels, reconciliation run list) (spec, Section 7).
- API client centralizes: `Authorization: Bearer <token>` on every request; uniform parsing of the backend's `{"error": "message"}` error-body shape; on `401`, redirect to Keycloak login (never surface a raw error); on `403`, render a distinct "you don't have access to this" state, not a generic error (spec, Section 7).
- Testing: Vitest + React Testing Library for the shared API client, the auth context/role-routing logic, and the transfer/reversal forms — not full page-level snapshot tests for every screen (spec, Section 7).
- Landing/routing rule: an `admin`-role JWT lands on `/admin` (with a "View my account" link to `/app`); a non-admin JWT lands on `/app` and never sees admin navigation; direct navigation to `/admin/*` without the `admin` role redirects to `/app` with a brief "not authorized" notice (spec, Section 6).
- No pagination, no self-registration, no E2E suite, no SSR, no offline/PWA support in this scope (spec, Non-Goals).
- Deployment: new top-level `web/` module, own multi-stage `Dockerfile` (Vite build, then nginx static serve), new `web` service in `docker-compose.yml` following the platform's existing per-service convention (own container, own port, `depends_on: api-gateway`) (spec, Section 3).
- `GET /holds` requires either the `admin` role, or a non-blank `accountRef` query parameter — a non-admin caller may only ever list holds for a specific named account, never the unscoped full list (spec, Section 4).

---

### Task 1: Holds Service — relax `GET /holds` to a self-scoped, non-admin-accessible endpoint

**Files:**
- Create: `holds-service/src/main/java/com/ledger/holdsservice/security/JwtRoleReader.java`
- Create: `holds-service/src/main/java/com/ledger/holdsservice/security/CallerContext.java`
- Create: `holds-service/src/main/java/com/ledger/holdsservice/service/AccountRefRequiredForNonAdminException.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/api/error/ApiExceptionHandler.java`
- Modify: `holds-service/pom.xml`
- Modify: `holds-service/src/main/resources/application.yml`
- Modify: `api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java`
- Test: `holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java`
- Test: `holds-service/src/test/java/com/ledger/holdsservice/security/JwtRoleReaderTest.java`

**Interfaces:**
- Consumes: `HoldRepository.search(String accountRef, HoldStatus status)` (already exists, unchanged).
- Produces: `HoldService.listHolds(String accountRefFilter, String statusFilter, CallerContext caller)` — the new required third parameter every caller of `listHolds` (including any future frontend-adjacent code) must supply. `CallerContext.isAdmin(): boolean`, `CallerContext.hasNoRoles(): boolean` (used for the case where no bearer token is forwarded at all, e.g. calls made without going through the gateway). `JwtRoleReader.readRoles(String bearerHeaderValue): CallerContext` — the single place that decodes a forwarded JWT and extracts `realm_access.roles`. `GET /holds` at the gateway is no longer admin-gated (any authenticated caller reaches `holds-service`); enforcement moves entirely into `HoldService.listHolds`.

Before writing code: this platform's gateway already forwards the `Authorization` header downstream unchanged (`api-gateway/src/main/resources/application.yml`'s route definitions carry no `RemoveRequestHeader`/`filters` entry for any route, confirmed by reading the file directly — Spring Cloud Gateway's default proxy filter forwards all incoming headers verbatim unless a route explicitly strips one). `holds-service` itself has no Spring Security dependency, no `SecurityConfig`, and no `JwtDecoder` today (confirmed: `holds-service/pom.xml` has no `spring-security*`/`oauth2-resource-server` dependency, and no file under `holds-service/src/main/java` references `JwtDecoder`, `SecurityConfig`, or `GrantedAuthority`). Standing up a full Spring Security filter chain there for one endpoint would be disproportionate; instead this task adds the OAuth2 resource-server dependency only for its `JwtDecoder` bean, and decodes the forwarded header directly inside the controller/service path — no `@PreAuthorize`, no filter chain, no change to any other `holds-service` endpoint's (lack of) authorization.

- [ ] **Step 1: Add the OAuth2 resource-server dependency and issuer config**

In `holds-service/pom.xml`, add (matching `api-gateway/pom.xml`'s exact dependency coordinates):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

In `holds-service/src/main/resources/application.yml`, add (mirroring `api-gateway`'s existing `issuer-uri` config exactly, same env var name so `docker-compose.yml` can pass the same value to both services):
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ledger}
```
(Read the real current `holds-service/src/main/resources/application.yml` first and merge this under the existing top-level `spring:` key rather than duplicating it — the file already has a `spring:` block for `datasource`/`jpa`/`rabbitmq` config.)

Adding `spring-boot-starter-oauth2-resource-server` to the classpath auto-configures a `JwtDecoder` bean from `issuer-uri` with no further code — this is the only thing this task needs from the dependency; no `@EnableWebSecurity`/`SecurityFilterChain` is added, so `holds-service` remains otherwise fully open exactly as it is today (this dependency alone does not add any authentication requirement to any endpoint in a plain Spring MVC app without a security filter chain — it only makes a `JwtDecoder` bean available for manual, explicit use).

- [ ] **Step 2: Write `JwtRoleReader` and `CallerContext` with a failing test**

```java
package com.ledger.holdsservice.security;

import java.util.List;
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
```

```java
package com.ledger.holdsservice.security;

import org.springframework.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes the {@code Authorization: Bearer <token>} header the API Gateway forwards downstream
 * unchanged, reading the same nested {@code realm_access.roles} claim
 * {@code KeycloakRealmRoleConverter} reads at the gateway. This is a manual, explicit decode
 * point (not a Spring Security filter chain) since holds-service has no other authorization
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
        return new CallerContext(roles.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
    }
}
```

Note the import `org.springframework.oauth2.jwt.Jwt` above is deliberately wrong (should be `org.springframework.security.oauth2.jwt.Jwt`) — fix it in the actual file (`import org.springframework.security.oauth2.jwt.Jwt;`) before compiling; this plan calls it out explicitly since a typo'd import is exactly the kind of thing that silently breaks a task and is easy to miss when transcribing.

Now write the failing test:

```java
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
```

`holds-service/pom.xml` needs a `mockito-core`/`org.mockito` dependency for this test — check whether `spring-boot-starter-test` (already present) already transitively provides Mockito (it does, by default, in every Spring Boot starter-test setup) before adding anything new; if the real POM's `spring-boot-starter-test` has `<exclusions>` stripping Mockito (check before assuming), add `org.mockito:mockito-core` with `<scope>test</scope>` explicitly.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl holds-service -am test -Dtest=JwtRoleReaderTest -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: compile failure (the classes don't exist yet) — fix the deliberate bad import from Step 2 now if you transcribed it as-is, then re-run to get an honest first failure/pass cycle if needed; the point of the Red step is to see a real failure before the real implementation exists, not to reproduce this plan's own planted typo as the "expected" failure.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl holds-service -am test -Dtest=JwtRoleReaderTest -Dapi.version=1.44`
Expected: PASS, all 3 cases.

- [ ] **Step 5: Add the `AccountRefRequiredForNonAdminException` and write the failing `HoldService` tests**

```java
package com.ledger.holdsservice.service;

public class AccountRefRequiredForNonAdminException extends RuntimeException {
    public AccountRefRequiredForNonAdminException() {
        super("A non-admin caller must supply a non-blank accountRef to list holds");
    }
}
```

Read `HoldServiceIntegrationTest.java`'s existing `@BeforeEach` seeding first (shown above: it seeds `acct-holds-a`, `list-holds-a`, `filter-holds-source`, `status-holds-a` balance-cache rows before each test), then add:

```java
@Test
void nonAdminCallerWithAccountRefCanListTheirOwnHolds() {
    holdService.createHold(new CreateHoldRequest("list-holds-a", "acct-merchant", 100L, "USD", 3600),
            "self-scope-key-1");

    CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));
    List<HoldResponse> results = holdService.listHolds("list-holds-a", null, nonAdmin);

    assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
}

@Test
void nonAdminCallerWithBlankAccountRefIsRejected() {
    CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));

    assertThatThrownBy(() -> holdService.listHolds(null, null, nonAdmin))
            .isInstanceOf(AccountRefRequiredForNonAdminException.class);
    assertThatThrownBy(() -> holdService.listHolds("", null, nonAdmin))
            .isInstanceOf(AccountRefRequiredForNonAdminException.class);
}

@Test
void adminCallerCanListAllHoldsWithNoAccountRef() {
    holdService.createHold(new CreateHoldRequest("list-holds-a", "acct-merchant", 100L, "USD", 3600),
            "admin-scope-key-1");

    CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
    List<HoldResponse> results = holdService.listHolds(null, null, admin);

    assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
}

@Test
void callerWithNoRolesAtAllIsTreatedAsNonAdmin() {
    assertThatThrownBy(() -> holdService.listHolds(null, null, CallerContext.NONE))
            .isInstanceOf(AccountRefRequiredForNonAdminException.class);
}
```

Add `import com.ledger.holdsservice.security.CallerContext;` and `import com.ledger.holdsservice.service.AccountRefRequiredForNonAdminException;` (same package, so the exception import is only needed if the test class isn't already in `com.ledger.holdsservice.service` — it is, per the existing file's package declaration, so no import needed for the exception; only the `CallerContext` import is required).

This also changes the 3 existing `listHolds` call sites already in `HoldServiceIntegrationTest.java` from Section 4's own earlier plan (`holdService.listHolds(null, null)`, `holdService.listHolds("filter-holds-source", null)`/`holdService.listHolds("filter-holds-dest", null)`, `holdService.listHolds(null, "ACTIVE")`/`holdService.listHolds(null, "RELEASED")`) — read the real current test file first and update every existing call site to pass a third `CallerContext` argument (use `new CallerContext(java.util.Set.of("user", "admin"))` for these pre-existing tests so their original "list everything, no scoping" semantics are preserved unchanged), rather than leaving them on the old two-argument signature.

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn -pl holds-service -am test -Dtest=HoldServiceIntegrationTest -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: compile failure — `listHolds` doesn't yet accept a third parameter and `CallerContext`/`AccountRefRequiredForNonAdminException` don't yet exist in a way `HoldService` can use.

- [ ] **Step 7: Update `HoldService.listHolds`'s signature and add the scoping check**

```java
/**
 * Backs {@code GET /holds?accountRef=&status=}. An {@code admin}-role caller may omit
 * {@code accountRef} for the full list; a non-admin caller must supply a non-blank
 * {@code accountRef} and is scoped to holds matching it (see the design spec, Section 4) --
 * a non-admin caller may never receive the unscoped "all holds" list.
 */
@Transactional(readOnly = true)
public List<HoldResponse> listHolds(String accountRefFilter, String statusFilter, CallerContext caller) {
    if (!caller.isAdmin() && (accountRefFilter == null || accountRefFilter.isBlank())) {
        throw new AccountRefRequiredForNonAdminException();
    }
    HoldStatus status = statusFilter != null ? HoldStatus.valueOf(statusFilter) : null;
    List<Hold> holds = holdRepository.search(accountRefFilter, status);
    return holds.stream().map(hold -> holdPoster.toResponse(hold, false)).toList();
}
```

Add `import com.ledger.holdsservice.security.CallerContext;` and `import com.ledger.holdsservice.security.CallerContext;`'s sibling `AccountRefRequiredForNonAdminException` is already same-package, no import needed. `HoldRepository.search`'s existing implementation (unchanged by this task) already handles a non-blank `accountRef` correctly per the pre-existing `listHoldsFiltersByAccountRefOnEitherSide` test.

- [ ] **Step 8: Update `HoldController` to decode the header and wire the exception**

```java
@GetMapping("/holds")
public ResponseEntity<List<HoldResponse>> list(
        @RequestParam(value = "accountRef", required = false) String accountRef,
        @RequestParam(value = "status", required = false) String status,
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
    CallerContext caller = jwtRoleReader.readRoles(authorizationHeader);
    return ResponseEntity.ok(holdService.listHolds(accountRef, status, caller));
}
```

Add `private final JwtRoleReader jwtRoleReader;` as a new constructor dependency on `HoldController` (add it to the existing constructor alongside `HoldService`, following the exact one-constructor-injection style the class already uses), and `import com.ledger.holdsservice.security.CallerContext;` / `import com.ledger.holdsservice.security.JwtRoleReader;`.

Add to `ApiExceptionHandler.java`, following the exact pattern of the existing handlers:
```java
@ExceptionHandler(AccountRefRequiredForNonAdminException.class)
public ResponseEntity<Map<String, String>> handleAccountRefRequired(AccountRefRequiredForNonAdminException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
}
```
Add `import com.ledger.holdsservice.service.AccountRefRequiredForNonAdminException;` to `ApiExceptionHandler.java`.

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn -pl holds-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including all new tests and the updated existing `HoldServiceIntegrationTest` call sites.

- [ ] **Step 10: Relax the API Gateway's admin gate on `GET /holds`**

In `api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java`, remove the line
```java
.pathMatchers(HttpMethod.GET, "/holds").hasAuthority("ROLE_admin")
```
from the `authorizeExchange` chain (it falls through to `.anyExchange().authenticated()`, so `GET /holds` remains authenticated-only, no longer admin-only). Update the comment block above the `pathMatchers` chain (currently describing all 5 admin-gated routes) to remove `GET /holds` from that list and note it is now any-authenticated-caller, self-scoped at `holds-service` per the design spec's Section 4 — read the current comment in full first and edit it in place rather than deleting it wholesale, since it also documents the `POST /accounts`/`POST /holds` method-qualifier reasoning that still applies to the remaining rules.

- [ ] **Step 11: Verify the header-forwarding assumption live**

Bring up `keycloak`, `holds-db`, `holds-service`, and `api-gateway` (`docker compose up -d --build keycloak holds-db holds-service api-gateway`, with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`), run `bash scripts/provision.sh`, then:
```bash
USER_TOKEN=$(bash scripts/get-token.sh alice)
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/holds?accountRef=nonexistent-account" -H "Authorization: Bearer $USER_TOKEN"
# expect 200 (empty array), not 401/403 -- proves the gateway forwarded Authorization to
# holds-service and holds-service's JwtDecoder accepted it
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/holds" -H "Authorization: Bearer $USER_TOKEN"
# expect 400 -- alice has no admin role and supplied no accountRef
ADMIN_TOKEN=$(bash scripts/get-token.sh admin)
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/holds" -H "Authorization: Bearer $ADMIN_TOKEN"
# expect 200 -- admin may omit accountRef
```
If the first call returns `401`, the header-forwarding assumption was wrong and `holds-service`'s `JwtDecoder` is rejecting a token it never received correctly, or `issuer-uri` mismatches between the two services (compare against `docker-compose.yml`'s `KEYCLOAK_ISSUER_URI` value for `api-gateway` — Task 1 Step 12 adds the same variable to `holds-service`) — do not proceed to Task 2 until this resolves cleanly, since the whole task's premise depends on it.

Tear down: `docker compose down -v`.

- [ ] **Step 12: Wire `KEYCLOAK_ISSUER_URI` into `holds-service`'s `docker-compose.yml` entry**

In `docker-compose.yml`, add to the existing `holds-service` service's `environment:` block (alongside `LEDGER_SERVICE_URL`, `RABBITMQ_HOST`, etc.):
```yaml
      KEYCLOAK_ISSUER_URI: http://host.docker.internal:8180/realms/ledger
```
(Exact same value already used for `api-gateway`'s own `KEYCLOAK_ISSUER_URI` entry — copy it verbatim so both services validate tokens against the identical issuer string, per the existing comment in `docker-compose.yml` explaining why this exact hostname is required.)

- [ ] **Step 13: Run the full module suite and the gateway module suite**

Run: `mvn -pl holds-service,api-gateway -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors across both modules.

- [ ] **Step 14: Commit**

```bash
git add holds-service/pom.xml \
        holds-service/src/main/resources/application.yml \
        holds-service/src/main/java/com/ledger/holdsservice/security/JwtRoleReader.java \
        holds-service/src/main/java/com/ledger/holdsservice/security/CallerContext.java \
        holds-service/src/main/java/com/ledger/holdsservice/service/AccountRefRequiredForNonAdminException.java \
        holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java \
        holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java \
        holds-service/src/main/java/com/ledger/holdsservice/api/error/ApiExceptionHandler.java \
        holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java \
        holds-service/src/test/java/com/ledger/holdsservice/security/JwtRoleReaderTest.java \
        api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java \
        docker-compose.yml
git commit -m "feat(holds-service): relax GET /holds to allow self-scoped access for non-admin callers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Keycloak — add the `ledger-web` public PKCE client

**Files:**
- Modify: `keycloak-realm/ledger-realm.json`

**Interfaces:**
- Consumes: nothing.
- Produces: a Keycloak client `ledger-web` — Task 4's auth module (`web/src/auth/oidcConfig.ts`) references this `clientId` and its `redirectUris` directly.

- [ ] **Step 1: Read the current realm file in full**

Read `keycloak-realm/ledger-realm.json` completely (already read during this plan's own research — reproduced above; re-read the live file before editing since it may have drifted since Task 1 of this same plan touches nothing in it, but confirm). This is a single static JSON config file — no test-first cycle applies; verification happens live in Step 3.

- [ ] **Step 2: Add the client**

In the `"clients": [ ... ]` array, add:
```json
{
  "clientId": "ledger-web",
  "enabled": true,
  "publicClient": true,
  "standardFlowEnabled": true,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "redirectUris": [
    "http://localhost:5173/*",
    "http://localhost:8085/*"
  ],
  "webOrigins": [
    "http://localhost:5173",
    "http://localhost:8085"
  ],
  "attributes": {
    "pkce.code.challenge.method": "S256"
  }
}
```
`http://localhost:5173/*` covers local Vite dev (`npm run dev`'s default port); `http://localhost:8085/*` covers the deployed container's published port (Task 10 publishes `web` on host port `8085` — confirm this matches the port Task 10 actually settles on, and keep this file's `redirectUris`/`webOrigins` in sync if that port changes before Task 10 is committed). `webOrigins` is required separately from `redirectUris` for Keycloak to allow the browser's CORS preflight from the SPA's own origin during the Authorization Code exchange.

- [ ] **Step 3: Verify live**

Bring up just Keycloak (`docker compose up -d keycloak`, with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`), wait for it to report healthy, then fetch the realm's OpenID configuration and confirm the client is importable without error:
```bash
curl -s http://localhost:8180/realms/ledger/.well-known/openid-configuration | grep -o '"authorization_endpoint":"[^"]*"'
docker compose logs keycloak | grep -i "ledger-web\|error"
```
Expected: the `authorization_endpoint` URL resolves, and the logs show no import error mentioning `ledger-web` (a malformed client entry fails realm import with a logged error naming the client). If Keycloak's admin console is reachable at `http://localhost:8180` (login `admin`/`admin` per `docker-compose.yml`), optionally confirm visually under Clients that `ledger-web` shows `Client authentication: Off` (i.e. public) and `Standard flow: On`.

Tear down: `docker compose stop keycloak`.

- [ ] **Step 4: Commit**

```bash
git add keycloak-realm/ledger-realm.json
git commit -m "feat(auth): add ledger-web public PKCE client for the browser SPA

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Frontend scaffolding — Vite project, Tailwind, routing shell, Dockerfile

**Files:**
- Create: `web/package.json`
- Create: `web/tsconfig.json`
- Create: `web/tsconfig.node.json`
- Create: `web/vite.config.ts`
- Create: `web/tailwind.config.js`
- Create: `web/postcss.config.js`
- Create: `web/index.html`
- Create: `web/src/main.tsx`
- Create: `web/src/App.tsx`
- Create: `web/src/index.css`
- Create: `web/src/vite-env.d.ts`
- Create: `web/.gitignore`
- Create: `web/Dockerfile`
- Create: `web/nginx.conf`

**Interfaces:**
- Consumes: nothing.
- Produces: `web/src/App.tsx` exporting a default `App` component mounted by `main.tsx` — every later task's routes/providers wrap into this component. The Vite dev server serves on port `5173` by default (unchanged); the production nginx image serves on port `80` inside the container (Task 10 maps this to the host).

- [ ] **Step 1: Initialize the Vite project files**

`web/package.json`:
```json
{
  "name": "ledger-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.2",
    "oidc-client-ts": "^3.1.0",
    "@tanstack/react-query": "^5.56.2",
    "recharts": "^2.12.7"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.5.0",
    "@testing-library/react": "^16.0.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "^18.3.5",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "autoprefixer": "^10.4.20",
    "jsdom": "^25.0.0",
    "postcss": "^8.4.47",
    "tailwindcss": "^3.4.11",
    "typescript": "^5.5.4",
    "vite": "^5.4.6",
    "vitest": "^2.1.1"
  }
}
```

`web/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

`web/tsconfig.node.json`:
```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true
  },
  "include": ["vite.config.ts"]
}
```

`web/vite.config.ts`:
```typescript
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

`web/src/vite-env.d.ts`:
```typescript
/// <reference types="vite/client" />
```

`web/.gitignore`:
```
node_modules
dist
.env.local
```

- [ ] **Step 2: Configure Tailwind with the design system's semantic color tokens**

`web/tailwind.config.js`:
```javascript
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        surface: {
          light: '#ffffff',
          'light-alt': '#f7f7f9',
          dark: '#0a0a0f',
          'dark-alt': '#17171f',
        },
        ink: {
          light: '#0b0e14',
          dark: '#e7e8ec',
        },
        accent: {
          DEFAULT: '#4f46e5',
          hover: '#4338ca',
        },
        danger: '#dc2626',
        warning: '#d97706',
        success: '#16a34a',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        card: '1rem',
      },
    },
  },
  plugins: [],
};
```
(The indigo `#4f46e5`/`#4338ca` accent is chosen specifically for contrast against `danger`/`warning`/`success`, per the spec's Section 1 requirement to avoid collision with status colors.)

`web/postcss.config.js`:
```javascript
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};
```

`web/src/index.css`:
```css
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  body {
    @apply bg-surface-light text-ink-light dark:bg-surface-dark dark:text-ink-dark font-sans antialiased;
  }
}
```

- [ ] **Step 3: Add `index.html`, `main.tsx`, and a placeholder `App.tsx`**

`web/index.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet" />
    <title>Ledger</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`web/src/main.tsx`:
```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
```

`web/src/App.tsx` (placeholder — Task 6 replaces its contents with the real route tree and providers):
```typescript
export default function App() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <p className="text-sm text-ink-light/60 dark:text-ink-dark/60">Ledger web — under construction.</p>
    </div>
  );
}
```

- [ ] **Step 4: Install dependencies and verify the dev server boots**

Run: `cd web && npm install`
Expected: installs cleanly, no peer-dependency errors that fail the install.

Run: `cd web && npm run build`
Expected: `tsc -b` and `vite build` both succeed, producing `web/dist/`.

- [ ] **Step 5: Write the production Dockerfile and nginx config**

`web/Dockerfile`:
```dockerfile
FROM node:20-alpine AS build
WORKDIR /workspace
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```
(Matches the platform's existing multi-stage Dockerfile convention — see `api-gateway/Dockerfile` — build stage then a minimal runtime image; here nginx instead of a JRE, per the spec's Section 3 "lightweight-static-serve pattern rather than running a Node server in production.")

`web/nginx.conf`:
```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```
(The `try_files ... /index.html` fallback is required for React Router's client-side routes — e.g. a hard refresh on `/app/history` must still serve `index.html` rather than nginx's default 404, since there is no server-side route for that path.)

- [ ] **Step 6: Commit**

```bash
git add web/package.json web/tsconfig.json web/tsconfig.node.json web/vite.config.ts \
        web/tailwind.config.js web/postcss.config.js web/index.html web/src/main.tsx \
        web/src/App.tsx web/src/index.css web/src/vite-env.d.ts web/.gitignore \
        web/Dockerfile web/nginx.conf
git commit -m "feat(web): scaffold Vite + React + TypeScript + Tailwind project with production Dockerfile

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Shared component library (button, input, card, badge, table, toast, skeleton)

**Files:**
- Create: `web/src/components/Button.tsx`
- Create: `web/src/components/Input.tsx`
- Create: `web/src/components/Card.tsx`
- Create: `web/src/components/Badge.tsx`
- Create: `web/src/components/Table.tsx`
- Create: `web/src/components/Toast.tsx`
- Create: `web/src/components/Skeleton.tsx`
- Create: `web/src/components/MoneyAmount.tsx`
- Test: `web/src/components/MoneyAmount.test.tsx`
- Test: `web/src/test/setup.ts`

**Interfaces:**
- Consumes: nothing (Tailwind tokens from Task 3).
- Produces: `<Button variant="primary"|"secondary"|"danger" ...>`, `<Input label type value onChange error? ...>`, `<Card title? ...>`, `<Badge status="active"|"success"|"warning"|"error"|"neutral">`, `<Table columns rows keyField>`, `<ToastProvider>`/`useToast(): { showToast(message, kind) }`, `<Skeleton className? >`, `<MoneyAmount amountMinor currency size="hero"|"large"|"normal">`. Every later page-level task builds its UI from these exact components/props — no page task defines its own button/input/card markup.

- [ ] **Step 1: Write `web/src/test/setup.ts`**

```typescript
import '@testing-library/jest-dom/vitest';
```

- [ ] **Step 2: Write the failing test for `MoneyAmount` (the one shared component with real formatting logic)**

```typescript
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MoneyAmount } from './MoneyAmount';

describe('MoneyAmount', () => {
  it('formats minor units as a decimal amount with the currency symbol', () => {
    render(<MoneyAmount amountMinor={150000} currency="USD" />);
    expect(screen.getByText('$1,500.00')).toBeInTheDocument();
  });

  it('formats EUR with the euro symbol', () => {
    render(<MoneyAmount amountMinor={999} currency="EUR" />);
    expect(screen.getByText('€9.99')).toBeInTheDocument();
  });

  it('renders negative amounts with a leading minus sign', () => {
    render(<MoneyAmount amountMinor={-2500} currency="USD" />);
    expect(screen.getByText('-$25.00')).toBeInTheDocument();
  });

  it('applies the hero size class for size="hero"', () => {
    render(<MoneyAmount amountMinor={100} currency="USD" size="hero" />);
    expect(screen.getByText('$1.00')).toHaveClass('text-4xl');
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web && npx vitest run src/components/MoneyAmount.test.tsx`
Expected: FAIL — `MoneyAmount` module doesn't exist yet.

- [ ] **Step 4: Implement `MoneyAmount`**

```typescript
interface MoneyAmountProps {
  amountMinor: number;
  currency: string;
  size?: 'hero' | 'large' | 'normal';
}

const sizeClasses: Record<NonNullable<MoneyAmountProps['size']>, string> = {
  hero: 'text-4xl font-bold',
  large: 'text-2xl font-semibold',
  normal: 'text-base font-medium',
};

export function MoneyAmount({ amountMinor, currency, size = 'normal' }: MoneyAmountProps) {
  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
  }).format(amountMinor / 100);
  return <span className={sizeClasses[size]}>{formatted}</span>;
}
```
(`Intl.NumberFormat` with `style: 'currency'` already renders a leading `-` before the currency symbol for a negative value, e.g. `-$25.00` — confirmed standard ECMA-402 behavior, matching the test's expectation exactly.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web && npx vitest run src/components/MoneyAmount.test.tsx`
Expected: PASS, all 4 cases.

- [ ] **Step 6: Implement the remaining structural components (no TDD — presentational scaffolding)**

`web/src/components/Button.tsx`:
```typescript
import type { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger';
}

const variantClasses: Record<NonNullable<ButtonProps['variant']>, string> = {
  primary: 'bg-accent text-white hover:bg-accent-hover disabled:opacity-50',
  secondary:
    'bg-surface-light-alt text-ink-light border border-ink-light/10 hover:bg-surface-light-alt/70 dark:bg-surface-dark-alt dark:text-ink-dark dark:border-ink-dark/10',
  danger: 'bg-danger text-white hover:bg-danger/90 disabled:opacity-50',
};

export function Button({ variant = 'primary', className = '', ...props }: ButtonProps) {
  return (
    <button
      className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed ${variantClasses[variant]} ${className}`}
      {...props}
    />
  );
}
```

`web/src/components/Input.tsx`:
```typescript
import type { InputHTMLAttributes } from 'react';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
}

export function Input({ label, error, id, className = '', ...props }: InputProps) {
  const inputId = id ?? label.toLowerCase().replace(/\s+/g, '-');
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-medium text-ink-light/70 dark:text-ink-dark/70">
        {label}
      </label>
      <input
        id={inputId}
        className={`rounded-lg border border-ink-light/15 bg-surface-light px-3 py-2 text-sm text-ink-light focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent dark:border-ink-dark/15 dark:bg-surface-dark-alt dark:text-ink-dark ${className}`}
        {...props}
      />
      {error ? <span className="text-sm text-danger">{error}</span> : null}
    </div>
  );
}
```

`web/src/components/Card.tsx`:
```typescript
import type { ReactNode } from 'react';

interface CardProps {
  title?: string;
  children: ReactNode;
  className?: string;
}

export function Card({ title, children, className = '' }: CardProps) {
  return (
    <div
      className={`rounded-card border border-ink-light/10 bg-surface-light p-6 shadow-sm dark:border-ink-dark/10 dark:bg-surface-dark-alt ${className}`}
    >
      {title ? <h3 className="mb-4 text-sm font-medium text-ink-light/60 dark:text-ink-dark/60">{title}</h3> : null}
      {children}
    </div>
  );
}
```

`web/src/components/Badge.tsx`:
```typescript
type BadgeStatus = 'active' | 'success' | 'warning' | 'error' | 'neutral';

interface BadgeProps {
  status: BadgeStatus;
  children: React.ReactNode;
}

const statusClasses: Record<BadgeStatus, string> = {
  active: 'bg-accent/10 text-accent',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  error: 'bg-danger/10 text-danger',
  neutral: 'bg-ink-light/10 text-ink-light/70 dark:bg-ink-dark/10 dark:text-ink-dark/70',
};

export function Badge({ status, children }: BadgeProps) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${statusClasses[status]}`}>
      {children}
    </span>
  );
}
```
Add `import type { ReactNode } from 'react';` and change `children: React.ReactNode` to `children: ReactNode` for consistency with the other files' import style (avoid the bare `React.ReactNode` global reference since this project doesn't import `React` by default under the `react-jsx` JSX transform).

`web/src/components/Table.tsx`:
```typescript
interface Column<T> {
  header: string;
  render: (row: T) => React.ReactNode;
}

interface TableProps<T> {
  columns: Column<T>[];
  rows: T[];
  keyField: (row: T) => string;
  onRowClick?: (row: T) => void;
}

export function Table<T>({ columns, rows, keyField, onRowClick }: TableProps<T>) {
  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="border-b border-ink-light/10 dark:border-ink-dark/10">
          {columns.map((col) => (
            <th key={col.header} className="px-4 py-2 font-medium text-ink-light/60 dark:text-ink-dark/60">
              {col.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={keyField(row)}
            onClick={() => onRowClick?.(row)}
            className={`border-b border-ink-light/5 dark:border-ink-dark/5 ${onRowClick ? 'cursor-pointer hover:bg-surface-light-alt dark:hover:bg-surface-dark' : ''}`}
          >
            {columns.map((col) => (
              <td key={col.header} className="px-4 py-3">
                {col.render(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```
Add `import type { ReactNode } from 'react';` at the top and replace both `React.ReactNode` occurrences with `ReactNode`, matching `Badge.tsx`'s corrected style above.

`web/src/components/Toast.tsx`:
```typescript
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

type ToastKind = 'info' | 'success' | 'error';
interface ToastMessage {
  id: number;
  message: string;
  kind: ToastKind;
}

interface ToastContextValue {
  showToast: (message: string, kind?: ToastKind) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

let nextId = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback((message: string, kind: ToastKind = 'info') => {
    const id = nextId++;
    setToasts((current) => [...current, { id, message, kind }]);
    setTimeout(() => {
      setToasts((current) => current.filter((toast) => toast.id !== id));
    }, 4000);
  }, []);

  const kindClasses: Record<ToastKind, string> = {
    info: 'bg-ink-light text-surface-light dark:bg-ink-dark dark:text-surface-dark',
    success: 'bg-success text-white',
    error: 'bg-danger text-white',
  };

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((toast) => (
          <div key={toast.id} className={`rounded-lg px-4 py-3 text-sm shadow-lg ${kindClasses[toast.kind]}`}>
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}
```

`web/src/components/Skeleton.tsx`:
```typescript
export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-ink-light/10 dark:bg-ink-dark/10 ${className}`} />;
}
```
(Tailwind's built-in `animate-pulse` utility is the shimmer/skeleton motion the spec's Section 1 calls for — no extra animation library needed.)

- [ ] **Step 7: Run the full test suite and the build**

Run: `cd web && npm run test && npm run build`
Expected: all tests pass, build succeeds with no TypeScript errors (in particular, confirm the `Badge.tsx`/`Table.tsx` `ReactNode` import fix from Step 6 compiles cleanly — `React.ReactNode` without importing `React` fails under `"jsx": "react-jsx"` with `noUnusedLocals`/strict mode, so this is worth double-checking against the real compiler output rather than assuming).

- [ ] **Step 8: Commit**

```bash
git add web/src/components/ web/src/test/setup.ts
git commit -m "feat(web): add shared Tailwind-based component library (button, input, card, badge, table, toast, skeleton, money amount)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Auth — OIDC config, `AuthProvider`/`useAuth`, role-based routing logic

**Files:**
- Create: `web/src/auth/oidcConfig.ts`
- Create: `web/src/auth/AuthProvider.tsx`
- Create: `web/src/auth/roles.ts`
- Test: `web/src/auth/roles.test.ts`
- Test: `web/src/auth/AuthProvider.test.tsx`

**Interfaces:**
- Consumes: `oidc-client-ts`'s `UserManager`.
- Produces: `oidcConfig: UserManagerSettings` (the `ledger-web` client config from Task 2). `useAuth(): { user: User | null, isLoading: boolean, login(): void, logout(): void, roles: string[] }` — every later task's route guards and API client (Task 6) call this. `landingRouteFor(roles: string[]): '/admin' | '/app'` and `isAdmin(roles: string[]): boolean` — pure functions Task 6's route guards and Task 2/Section-6's landing rule both depend on by these exact names.

- [ ] **Step 1: Write the failing test for the pure role-routing logic**

```typescript
import { describe, expect, it } from 'vitest';
import { isAdmin, landingRouteFor } from './roles';

describe('roles', () => {
  it('identifies an admin caller from the admin role', () => {
    expect(isAdmin(['user', 'admin'])).toBe(true);
  });

  it('identifies a non-admin caller with only the user role', () => {
    expect(isAdmin(['user'])).toBe(false);
  });

  it('treats an empty roles list as non-admin', () => {
    expect(isAdmin([])).toBe(false);
  });

  it('routes an admin caller to /admin', () => {
    expect(landingRouteFor(['user', 'admin'])).toBe('/admin');
  });

  it('routes a non-admin caller to /app', () => {
    expect(landingRouteFor(['user'])).toBe('/app');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd web && npx vitest run src/auth/roles.test.ts`
Expected: FAIL — `./roles` module doesn't exist yet.

- [ ] **Step 3: Implement `roles.ts`**

```typescript
export function isAdmin(roles: string[]): boolean {
  return roles.includes('admin');
}

export function landingRouteFor(roles: string[]): '/admin' | '/app' {
  return isAdmin(roles) ? '/admin' : '/app';
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd web && npx vitest run src/auth/roles.test.ts`
Expected: PASS, all 5 cases.

- [ ] **Step 5: Write `oidcConfig.ts`**

```typescript
import type { UserManagerSettings } from 'oidc-client-ts';

const keycloakBaseUrl = import.meta.env.VITE_KEYCLOAK_BASE_URL ?? 'http://localhost:8180';

export const oidcConfig: UserManagerSettings = {
  authority: `${keycloakBaseUrl}/realms/ledger`,
  client_id: 'ledger-web',
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  loadUserInfo: false,
};
```
(`VITE_KEYCLOAK_BASE_URL` lets the built container point at a different Keycloak origin than local dev without a rebuild, following Vite's standard `import.meta.env.VITE_*` convention; Task 10 sets this at container build/runtime for the docker-compose deployment. `redirect_uri` is computed from `window.location.origin` rather than hardcoded, so the same build works whether it's reached at `http://localhost:5173` in dev or the deployed container's own origin, matching Task 2's two `redirectUris` entries in the realm config.)

- [ ] **Step 6: Write the failing test for `AuthProvider`/`useAuth`**

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { AuthProvider, useAuth } from './AuthProvider';

const mockGetUser = vi.fn();
const mockSigninRedirect = vi.fn();
const mockSignoutRedirect = vi.fn();

vi.mock('oidc-client-ts', () => ({
  UserManager: vi.fn().mockImplementation(() => ({
    getUser: mockGetUser,
    signinRedirect: mockSigninRedirect,
    signoutRedirect: mockSignoutRedirect,
    events: { addUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), addAccessTokenExpired: vi.fn() },
  })),
}));

function TestConsumer() {
  const { isLoading, roles, user } = useAuth();
  if (isLoading) return <span>loading</span>;
  return <span>{user ? `roles:${roles.join(',')}` : 'anonymous'}</span>;
}

describe('AuthProvider', () => {
  beforeEach(() => {
    mockGetUser.mockReset();
  });

  it('exposes roles decoded from the loaded user profile', async () => {
    mockGetUser.mockResolvedValue({
      access_token: 'token',
      profile: { realm_access: { roles: ['user', 'admin'] } },
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('roles:user,admin')).toBeInTheDocument());
  });

  it('exposes no user and empty roles when nobody is signed in', async () => {
    mockGetUser.mockResolvedValue(null);

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
  });
});
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `cd web && npx vitest run src/auth/AuthProvider.test.tsx`
Expected: FAIL — `./AuthProvider` module doesn't exist yet.

- [ ] **Step 8: Implement `AuthProvider.tsx`**

```typescript
import { UserManager, type User } from 'oidc-client-ts';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { oidcConfig } from './oidcConfig';

interface AuthContextValue {
  user: User | null;
  isLoading: boolean;
  roles: string[];
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function rolesFromUser(user: User | null): string[] {
  if (!user) return [];
  const realmAccess = user.profile.realm_access as { roles?: string[] } | undefined;
  return realmAccess?.roles ?? [];
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const userManager = useMemo(() => new UserManager(oidcConfig), []);
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    userManager.getUser().then((loadedUser) => {
      setUser(loadedUser);
      setIsLoading(false);
    });
    const handleUserLoaded = (loadedUser: User) => setUser(loadedUser);
    const handleUserUnloaded = () => setUser(null);
    userManager.events.addUserLoaded(handleUserLoaded);
    userManager.events.addUserUnloaded(handleUserUnloaded);
    userManager.events.addAccessTokenExpired(() => userManager.signinRedirect());
    return () => {
      userManager.events.removeUserLoaded(handleUserLoaded);
      userManager.events.removeUserUnloaded(handleUserUnloaded);
    };
  }, [userManager]);

  const value: AuthContextValue = {
    user,
    isLoading,
    roles: rolesFromUser(user),
    login: () => userManager.signinRedirect(),
    logout: () => userManager.signoutRedirect(),
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
```
Verify before finalizing: decode a real token fetched via `scripts/get-token.sh alice` (`echo "$TOKEN" | cut -d. -f2 | base64 -d` — pad the base64 string with `=` if it errors on invalid length, a common raw-JWT-segment decoding quirk) and confirm `oidc-client-ts`'s `User.profile` genuinely surfaces the ID token's claims with `realm_access` present at the top level exactly as `rolesFromUser` above assumes — `oidc-client-ts` populates `profile` from the **ID token**, not the access token, so confirm the `ledger-web` client's ID token (not just its access token) carries `realm_access.roles`; Keycloak includes `realm_access` in both by default for a standard realm role mapper, but this must be confirmed against a real token before trusting `rolesFromUser`, adjusting to read `user.access_token`'s own decoded claims instead (e.g. via a small manual JWT-payload decode) if the ID token turns out not to carry the claim.

- [ ] **Step 9: Run the test to verify it passes**

Run: `cd web && npx vitest run src/auth/AuthProvider.test.tsx`
Expected: PASS, both cases.

- [ ] **Step 10: Run the full test suite and build**

Run: `cd web && npm run test && npm run build`
Expected: all pass, clean build.

- [ ] **Step 11: Commit**

```bash
git add web/src/auth/
git commit -m "feat(web): add OIDC auth provider with realm-role-based landing route logic

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: API client, TanStack Query setup, and the app shell/route tree

**Files:**
- Create: `web/src/api/client.ts`
- Create: `web/src/api/types.ts`
- Test: `web/src/api/client.test.ts`
- Modify: `web/src/App.tsx`
- Create: `web/src/routes/RequireAuth.tsx`
- Create: `web/src/routes/RequireAdmin.tsx`
- Create: `web/src/routes/AuthCallback.tsx`
- Create: `web/src/pages/NotAuthorized.tsx`

**Interfaces:**
- Consumes: `useAuth()` (Task 5), `ToastProvider`/`useToast()` (Task 4).
- Produces: `apiFetch<T>(path: string, options?: RequestInit): Promise<T>` and `ApiError` (with `.status: number`, `.message: string`) — every page task's data-fetching hook calls `apiFetch` directly; no page task calls `fetch` itself. `<RequireAuth>`/`<RequireAdmin>` route wrapper components — Task 7-9's route definitions in `App.tsx` use these exact names.

- [ ] **Step 1: Write `web/src/api/types.ts` with the response shapes later tasks consume**

```typescript
export interface AvailableBalanceResponse {
  accountRef: string;
  postedBalanceMinor: number;
  heldBalanceMinor: number;
  availableBalanceMinor: number;
}

export interface TransactionSummary {
  transactionId: string;
  status: string;
  transactionType: string;
  debitAccountRef: string;
  creditAccountRef: string;
  amountMinor: number;
  currency: string;
  description: string;
  createdAt: string;
  reversalOfTransactionId: string | null;
}

export interface EntryDto {
  accountId: string;
  accountRef: string;
  direction: 'DEBIT' | 'CREDIT';
  amountMinor: number;
  currency: string;
}

export interface TransactionDetail {
  transactionId: string;
  status: string;
  transactionType: string;
  description: string;
  createdAt: string;
  reversalOfTransactionId: string | null;
  entries: EntryDto[];
}

export interface HoldSummary {
  id: string;
  accountRef: string;
  destinationAccountRef: string;
  amountMinor: number;
  currency: string;
  status: string;
  expiresAt: string;
}

export interface AccountSummary {
  accountRef: string;
  currency: string;
  status: string;
}

export interface ReconciliationRun {
  runId: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  transactionsChecked: number;
  entriesImbalanceCount: number;
  outboxMissingCount: number;
  outboxStuckCount: number;
}

export interface ConversionQuote {
  sourceAmountMinor: number;
  sourceCurrency: string;
  destAmountMinor: number;
  destCurrency: string;
  rate: number;
  expiresAt: string;
}
```
(Field names match Task 3's `TransactionSummaryResponse`/`TransactionDetailResponse`/`EntryResponse`/`ReconciliationRunResponse` records and `HoldResponse`/`AccountResponse` from the Admin API Additions plan exactly — verify each against the real current backend DTO files before finalizing, since this plan's own draft transcribes them from that earlier plan's text rather than a fresh read of the now-implemented classes; if any field was renamed during that plan's own "verify against the real file" steps, mirror the real name here.)

- [ ] **Step 2: Write the failing tests for `apiFetch`**

```typescript
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { apiFetch, ApiError } from './client';

describe('apiFetch', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it('attaches the Authorization header with the current access token', async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    );

    await apiFetch('/accounts', {}, 'token-abc');

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/accounts'),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer token-abc' }),
      }),
    );
  });

  it('parses the backend error body shape and throws ApiError with the message and status', async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      new Response(JSON.stringify({ error: 'account not found' }), { status: 404 }),
    );

    await expect(apiFetch('/accounts/missing', {}, 'token-abc')).rejects.toMatchObject({
      status: 404,
      message: 'account not found',
    });
  });

  it('throws an ApiError with status 401 on an unauthorized response', async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      new Response(JSON.stringify({ error: 'unauthorized' }), { status: 401 }),
    );

    await expect(apiFetch('/accounts', {}, 'token-abc')).rejects.toBeInstanceOf(ApiError);
    await expect(apiFetch('/accounts', {}, 'token-abc')).rejects.toMatchObject({ status: 401 });
  });

  it('returns the parsed JSON body on success', async () => {
    vi.mocked(global.fetch).mockResolvedValue(
      new Response(JSON.stringify({ accountRef: 'alice-usd' }), { status: 200 }),
    );

    const result = await apiFetch<{ accountRef: string }>('/accounts/alice-usd', {}, 'token-abc');

    expect(result.accountRef).toBe('alice-usd');
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd web && npx vitest run src/api/client.test.ts`
Expected: FAIL — `./client` module doesn't exist yet.

- [ ] **Step 4: Implement `client.ts`**

```typescript
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = 'ApiError';
  }
}

export async function apiFetch<T>(path: string, options: RequestInit = {}, accessToken?: string): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });

  if (!response.ok) {
    let message = response.statusText;
    try {
      const body = (await response.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      // response had no JSON body -- fall back to statusText
    }
    throw new ApiError(response.status, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
```
(`VITE_API_BASE_URL` mirrors `VITE_KEYCLOAK_BASE_URL`'s pattern from Task 5 — defaults to local-dev's gateway port, overridden at build time for the deployed container per Task 10, per the spec's Section 3 same-origin-via-Docker-network note.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd web && npx vitest run src/api/client.test.ts`
Expected: PASS, all 4 cases.

- [ ] **Step 6: Add a query client wrapper hook consuming `apiFetch` + the current access token**

Add to `web/src/api/client.ts`:
```typescript
import { useAuth } from '../auth/AuthProvider';

export function useApiFetch() {
  const { user, login } = useAuth();
  return async function fetchWithAuth<T>(path: string, options?: RequestInit): Promise<T> {
    try {
      return await apiFetch<T>(path, options, user?.access_token);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        login();
      }
      throw error;
    }
  };
}
```
(This is the concrete implementation of the spec's Section 7 rule: on `401`, trigger re-authentication rather than surfacing a raw error. `403` is deliberately NOT special-cased here — it re-throws as an ordinary `ApiError` with `status === 403`, and each page-level task's own error rendering checks `error.status === 403` to show the "you don't have access to this" state per the spec, rather than this shared hook swallowing or redirecting on it.)

- [ ] **Step 7: Write `RequireAuth`, `RequireAdmin`, `AuthCallback`, and `NotAuthorized`**

`web/src/routes/RequireAuth.tsx`:
```typescript
import type { ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, isLoading, login } = useAuth();

  if (isLoading) {
    return <div className="flex min-h-screen items-center justify-center">Loading…</div>;
  }
  if (!user) {
    login();
    return null;
  }
  return <>{children}</>;
}
```

`web/src/routes/RequireAdmin.tsx`:
```typescript
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { isAdmin } from '../auth/roles';

export function RequireAdmin({ children }: { children: ReactNode }) {
  const { roles } = useAuth();
  if (!isAdmin(roles)) {
    return <Navigate to="/app" state={{ notAuthorized: true }} replace />;
  }
  return <>{children}</>;
}
```
(Per the spec's Section 6 landing rule: direct navigation to an `/admin/*` route without the `admin` role redirects to `/app` with a brief "not authorized" notice — the `state={{ notAuthorized: true }}` is read by `/app`'s Dashboard page in Task 7 to show that notice once, matching React Router's standard pattern for a one-time redirect-reason flag.)

`web/src/routes/AuthCallback.tsx`:
```typescript
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { UserManager } from 'oidc-client-ts';
import { oidcConfig } from '../auth/oidcConfig';
import { landingRouteFor } from '../auth/roles';

export function AuthCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    const userManager = new UserManager(oidcConfig);
    userManager.signinRedirectCallback().then((user) => {
      const realmAccess = user.profile.realm_access as { roles?: string[] } | undefined;
      navigate(landingRouteFor(realmAccess?.roles ?? []), { replace: true });
    });
  }, [navigate]);

  return <div className="flex min-h-screen items-center justify-center">Completing sign-in…</div>;
}
```

`web/src/pages/NotAuthorized.tsx`:
```typescript
export function NotAuthorizedNotice() {
  return (
    <div className="mb-4 rounded-lg bg-warning/10 px-4 py-3 text-sm text-warning">
      You don't have access to that page.
    </div>
  );
}
```

- [ ] **Step 8: Wire the route tree into `App.tsx`**

```typescript
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './auth/AuthProvider';
import { ToastProvider } from './components/Toast';
import { RequireAuth } from './routes/RequireAuth';
import { RequireAdmin } from './routes/RequireAdmin';
import { AuthCallback } from './routes/AuthCallback';
import { Landing } from './pages/Landing';
import { Dashboard } from './pages/app/Dashboard';
import { Transfer } from './pages/app/Transfer';
import { History } from './pages/app/History';
import { Holds } from './pages/app/Holds';
import { Deposits } from './pages/app/Deposits';
import { AdminAccounts } from './pages/admin/AdminAccounts';
import { AdminTransactions } from './pages/admin/AdminTransactions';
import { AdminHolds } from './pages/admin/AdminHolds';
import { AdminReconciliation } from './pages/admin/AdminReconciliation';

const queryClient = new QueryClient();

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ToastProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/" element={<Landing />} />
              <Route path="/auth/callback" element={<AuthCallback />} />
              <Route
                path="/app"
                element={
                  <RequireAuth>
                    <Dashboard />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/transfer"
                element={
                  <RequireAuth>
                    <Transfer />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/history"
                element={
                  <RequireAuth>
                    <History />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/holds"
                element={
                  <RequireAuth>
                    <Holds />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/deposits"
                element={
                  <RequireAuth>
                    <Deposits />
                  </RequireAuth>
                }
              />
              <Route
                path="/admin"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminAccounts />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/transactions"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminTransactions />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/holds"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminHolds />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/reconciliation"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminReconciliation />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
            </Routes>
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
```
This task only wires the route tree's shape; Tasks 7-9 create every page component imported above (`Landing`, `Dashboard`, `Transfer`, `History`, `Holds`, `Deposits`, `AdminAccounts`, `AdminTransactions`, `AdminHolds`, `AdminReconciliation`) — until those tasks land, `npm run build` on this task alone will fail with "module not found" for each import, which is expected and resolved task-by-task; do not attempt to run `npm run build` as this task's own final verification (Step 9 below scopes verification to what this task alone can prove).

- [ ] **Step 9: Run this task's own test suite**

Run: `cd web && npx vitest run src/api/client.test.ts src/auth src/routes`
Expected: all pass. (Full-project `npm run build` is deferred until Task 9 lands every page component `App.tsx` now imports.)

- [ ] **Step 10: Commit**

```bash
git add web/src/api/ web/src/routes/ web/src/pages/NotAuthorized.tsx web/src/App.tsx
git commit -m "feat(web): add API client with 401/403 handling, TanStack Query setup, and route guards

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Landing page and Dashboard (`/`, `/app`)

**Files:**
- Create: `web/src/pages/Landing.tsx`
- Create: `web/src/assets/hero.jpg`
- Create: `web/src/pages/app/Dashboard.tsx`
- Create: `web/src/pages/app/AppLayout.tsx`
- Create: `web/src/hooks/useAvailableBalance.ts`
- Create: `web/src/hooks/useRecentTransactions.ts`
- Create: `web/src/hooks/useHolds.ts`
- Test: `web/src/pages/Landing.test.tsx`

**Interfaces:**
- Consumes: `useAuth()` (Task 5), `landingRouteFor` (Task 5), `useApiFetch()` (Task 6), `MoneyAmount`/`Card`/`Button`/`Skeleton` (Task 4).
- Produces: `<AppLayout>` — the shared end-user page chrome (nav + content slot) Tasks 7's remaining pages and Task 8 both wrap their content in. `useAvailableBalance(accountRef): UseQueryResult<AvailableBalanceResponse>`, `useRecentTransactions(accountRef, limit): UseQueryResult<TransactionSummary[]>`, `useHolds(accountRef): UseQueryResult<HoldSummary[]>` — Task 8's Holds page reuses `useHolds` directly rather than redefining it.

- [ ] **Step 1: Add the hero image asset**

Place a free-license stock photograph of a bank/office building at `web/src/assets/hero.jpg` (per the spec's Section 2: "a free-license stock photo bundled with the app, not fetched from an external API at runtime"). Since this plan cannot embed binary image bytes, the implementer must source one file from a free-license stock library (e.g. Unsplash, Pexels — both offer license terms permitting redistribution in a bundled app asset) before this task's build will render the intended hero visual; until sourced, use any placeholder JPG/PNG at this exact path so the import in Step 2 resolves and the build succeeds — replace it with the real sourced asset before considering this task's visual design complete, and confirm the final chosen image's license explicitly permits this redistribution.

- [ ] **Step 2: Write the failing test for `Landing`'s auth-aware redirect**

```typescript
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Landing } from './Landing';

const mockUseAuth = vi.fn();
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => mockUseAuth(),
}));

describe('Landing', () => {
  it('shows the hero and login call-to-action when not authenticated', () => {
    mockUseAuth.mockReturnValue({ user: null, isLoading: false, roles: [], login: vi.fn() });

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: /log in/i })).toBeInTheDocument();
  });

  it('does not show the login button once authenticated (redirect takes over)', () => {
    mockUseAuth.mockReturnValue({
      user: { profile: { realm_access: { roles: ['user'] } } },
      isLoading: false,
      roles: ['user'],
      login: vi.fn(),
    });

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('button', { name: /log in/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web && npx vitest run src/pages/Landing.test.tsx`
Expected: FAIL — `./Landing` module doesn't exist yet.

- [ ] **Step 4: Implement `Landing.tsx`**

```typescript
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { landingRouteFor } from '../auth/roles';
import { Button } from '../components/Button';
import heroImage from '../assets/hero.jpg';

export function Landing() {
  const { user, isLoading, roles, login } = useAuth();

  if (isLoading) {
    return <div className="flex min-h-screen items-center justify-center">Loading…</div>;
  }
  if (user) {
    return <Navigate to={landingRouteFor(roles)} replace />;
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center">
      <img src={heroImage} alt="" className="absolute inset-0 h-full w-full object-cover brightness-50" />
      <div className="relative z-10 max-w-xl px-6 text-center text-white">
        <h1 className="text-4xl font-bold">Ledger</h1>
        <p className="mt-3 text-lg text-white/80">
          A fault-tolerant double-entry ledger platform — accounts, transfers, and holds, built to survive
          concurrent load and mid-process failure without ever losing or duplicating a transaction.
        </p>
        <Button className="mt-6" onClick={login}>
          Log in
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web && npx vitest run src/pages/Landing.test.tsx`
Expected: PASS, both cases.

- [ ] **Step 6: Implement the data-fetching hooks**

`web/src/hooks/useAvailableBalance.ts`:
```typescript
import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { AvailableBalanceResponse } from '../api/types';

export function useAvailableBalance(accountRef: string) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['available-balance', accountRef],
    queryFn: () => fetchWithAuth<AvailableBalanceResponse>(`/accounts/${accountRef}/available-balance`),
    enabled: Boolean(accountRef),
  });
}
```

`web/src/hooks/useRecentTransactions.ts`:
```typescript
import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { TransactionSummary } from '../api/types';

export function useRecentTransactions(accountRef: string, limit = 5) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['transactions', accountRef],
    queryFn: () => fetchWithAuth<TransactionSummary[]>(`/transactions?accountRef=${accountRef}`),
    enabled: Boolean(accountRef),
    select: (transactions) =>
      [...transactions]
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
        .slice(0, limit),
  });
}
```

`web/src/hooks/useHolds.ts`:
```typescript
import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { HoldSummary } from '../api/types';

export function useHolds(accountRef: string) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['holds', accountRef],
    queryFn: () => fetchWithAuth<HoldSummary[]>(`/holds?accountRef=${accountRef}`),
    enabled: Boolean(accountRef),
  });
}
```
(`GET /transactions?accountRef=` has no admin gate for a non-blank `accountRef` — confirmed unchanged from the Admin API Additions plan's own gateway rule, which only gates the bare `GET /transactions` with no query parameter awareness at the gateway layer... but re-check this specific point: `SecurityConfig.java`'s `pathMatchers(HttpMethod.GET, "/transactions").hasAuthority("ROLE_admin")` matches by path only, not by query string, meaning Spring Cloud Gateway's `pathMatchers` cannot distinguish `GET /transactions` from `GET /transactions?accountRef=alice-usd` — both hit the identical path `/transactions` and are therefore BOTH admin-gated today. This means the end-user History/Dashboard pages calling `GET /transactions?accountRef=` as a non-admin user will get `403`, contradicting the spec's Section 5 assumption that this route works for end users. Flag this explicitly: this is a second, smaller gap in the spec beyond the one already scoped as Task 1 — resolve it now, in this task, by using the exact same fix pattern as Task 1: relax `api-gateway`'s `SecurityConfig.java` to remove `.pathMatchers(HttpMethod.GET, "/transactions").hasAuthority("ROLE_admin")`, and instead push the same admin-or-self-scoped check into `TransactionService.listTransactions` in `ledger-service`, mirroring `HoldService.listHolds`'s Task 1 shape exactly. See Step 6a below before writing the hooks above as final.)

- [ ] **Step 6a: Apply the same self-scoping relaxation to `GET /transactions` in `ledger-service`**

This mirrors Task 1's `GET /holds` fix, against `ledger-service` instead of `holds-service` —
but `TransactionService.listTransactions` is a **4-parameter** method
(`accountRefFilter, statusFilter, since, until`), not `HoldService.listHolds`'s 2-parameter
one, so the new `CallerContext caller` parameter and its guard placement are spelled out
explicitly below rather than left to be inferred from Task 1's shape.

Read `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java`
and `TransactionQueryController.java` first — confirmed current shapes:
```java
public List<TransactionSummaryResponse> listTransactions(String accountRefFilter, String statusFilter,
                                                            Instant since, Instant until) {
    List<Transaction> transactions;
    if (accountRefFilter != null) {
        transactions = transactionRepository.findByAccountRef(accountRefFilter);
    } else {
        transactions = transactionRepository.findAll();
    }
    return transactions.stream()
            .filter(t -> statusFilter == null || t.getStatus().name().equals(statusFilter))
            .filter(t -> since == null || !t.getCreatedAt().isBefore(since))
            .filter(t -> until == null || !t.getCreatedAt().isAfter(until))
            .map(this::toSummary)
            .toList();
}
```
```java
@GetMapping("/transactions")
public ResponseEntity<List<TransactionSummaryResponse>> list(
        @RequestParam(value = "accountRef", required = false) String accountRef,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
        @RequestParam(value = "until", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
    return ResponseEntity.ok(transactionService.listTransactions(accountRef, status, since, until));
}
```
Check whether `ledger-service` already has `spring-boot-starter-oauth2-resource-server` on its
classpath and an `issuer-uri` configured (it very likely does not, matching `holds-service`'s
pre-Task-1 state — confirm by reading `ledger-service/pom.xml` and
`ledger-service/src/main/resources/application.yml` directly) before assuming so.

Add the identical `spring-boot-starter-oauth2-resource-server` dependency and `issuer-uri`
config to `ledger-service/pom.xml`/`application.yml` (same shape as Task 1 Step 1). Create
`ledger-service/src/main/java/com/ledger/ledgerservice/security/JwtRoleReader.java` and
`CallerContext.java` — byte-for-byte the same two classes from Task 1 Step 2, just in
`com.ledger.ledgerservice.security` instead of `com.ledger.holdsservice.security` (do not
import across modules; each service owns its own copy, matching this platform's existing
no-shared-library convention of duplicating small pieces of logic per service rather than
extracting a shared module). Create
`ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountRefRequiredForNonAdminException.java`,
identical shape to Task 1's.

Following the exact TDD Red/Green discipline of Task 1 Steps 5-9 (write failing tests against
`TransactionServiceIntegrationTest.java` first — one non-admin-with-accountRef-succeeds case,
one non-admin-blank-accountRef-rejected case, one admin-with-no-accountRef-succeeds case,
mirroring Task 1's 4 test names/shapes but calling `listTransactions` instead of
`listHolds` — confirm they fail to compile, then implement), change the method to:

```java
public List<TransactionSummaryResponse> listTransactions(String accountRefFilter, String statusFilter,
                                                            Instant since, Instant until, CallerContext caller) {
    if (!caller.isAdmin() && (accountRefFilter == null || accountRefFilter.isBlank())) {
        throw new AccountRefRequiredForNonAdminException();
    }
    List<Transaction> transactions;
    if (accountRefFilter != null) {
        transactions = transactionRepository.findByAccountRef(accountRefFilter);
    } else {
        transactions = transactionRepository.findAll();
    }
    return transactions.stream()
            .filter(t -> statusFilter == null || t.getStatus().name().equals(statusFilter))
            .filter(t -> since == null || !t.getCreatedAt().isBefore(since))
            .filter(t -> until == null || !t.getCreatedAt().isAfter(until))
            .map(this::toSummary)
            .toList();
}
```
(`caller` is appended as the new 5th parameter, after the 4 existing filter parameters, rather
than inserted first — this keeps every existing call site's first 4 arguments unchanged in
position, so updating them only means appending one new trailing argument, not reordering
existing ones. The guard runs before any repository query, exactly mirroring
`HoldService.listHolds`'s check-first placement from Task 1.)

Add `import com.ledger.ledgerservice.security.CallerContext;` to `TransactionService.java`.
Update `TransactionQueryController.list(...)` to:
```java
@GetMapping("/transactions")
public ResponseEntity<List<TransactionSummaryResponse>> list(
        @RequestParam(value = "accountRef", required = false) String accountRef,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
        @RequestParam(value = "until", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
    CallerContext caller = jwtRoleReader.readRoles(authorizationHeader);
    return ResponseEntity.ok(transactionService.listTransactions(accountRef, status, since, until, caller));
}
```
Add `private final JwtRoleReader jwtRoleReader;` as a new constructor dependency on
`TransactionQueryController` (alongside the existing `TransactionService` dependency, same
one-constructor-injection style already used), and
`import com.ledger.ledgerservice.security.CallerContext;` /
`import com.ledger.ledgerservice.security.JwtRoleReader;`. Leave the controller's other two
methods (`get`, `reverse`) completely unchanged — only `list(...)` is affected.

Wire `AccountRefRequiredForNonAdminException` into `ledger-service`'s `ApiExceptionHandler.java`
the same way as Task 1's handler, `400 BAD_REQUEST`.

Update every existing call site of `listTransactions(...)` in
`TransactionServiceIntegrationTest.java` (there are several, from the already-shipped Admin
API Additions plan's own test suite — read the real current file to find every one) by
appending a trailing `CallerContext` argument: use
`new CallerContext(java.util.Set.of("user", "admin"))` for every pre-existing test, so their
original "no scoping" semantics are preserved unchanged, exactly matching Task 1's Step 5
instruction for `HoldServiceIntegrationTest.java`'s pre-existing call sites.

In `api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java`, remove
`.pathMatchers(HttpMethod.GET, "/transactions").hasAuthority("ROLE_admin")` alongside the
`/holds` line already removed in Task 1 Step 10 (if Task 1 already ran, this is a second,
separate edit to the same file in this task; if executing tasks out of order, do both
removals together).

Wire `KEYCLOAK_ISSUER_URI` into `ledger-service`'s `docker-compose.yml` environment block,
same value as `holds-service`/`api-gateway`.

Run: `mvn -pl ledger-service,api-gateway -am test -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

Commit this backend sub-step on its own before continuing with the frontend hooks:
```bash
git add ledger-service/pom.xml \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/main/java/com/ledger/ledgerservice/security/ \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountRefRequiredForNonAdminException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/TransactionQueryController.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java \
        api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java \
        docker-compose.yml
git commit -m "feat(ledger-service): relax GET /transactions to allow self-scoped access for non-admin callers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Implement `AppLayout.tsx` (shared end-user nav chrome)**

```typescript
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';

const navItems = [
  { to: '/app', label: 'Dashboard' },
  { to: '/app/transfer', label: 'Send money' },
  { to: '/app/history', label: 'History' },
  { to: '/app/holds', label: 'Holds' },
  { to: '/app/deposits', label: 'Deposits & withdrawals' },
];

export function AppLayout({ children }: { children: ReactNode }) {
  const { logout } = useAuth();
  const location = useLocation();

  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b border-ink-light/10 px-6 py-4 dark:border-ink-dark/10">
        <nav className="flex gap-6">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`text-sm font-medium ${location.pathname === item.to ? 'text-accent' : 'text-ink-light/60 dark:text-ink-dark/60'}`}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <button onClick={logout} className="text-sm text-ink-light/60 dark:text-ink-dark/60">
          Log out
        </button>
      </header>
      <main className="mx-auto max-w-5xl px-6 py-8">{children}</main>
    </div>
  );
}
```

- [ ] **Step 8: Implement `Dashboard.tsx`**

```typescript
import { useLocation } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { NotAuthorizedNotice } from '../NotAuthorized';
import { useAvailableBalance } from '../../hooks/useAvailableBalance';
import { useRecentTransactions } from '../../hooks/useRecentTransactions';
import { useHolds } from '../../hooks/useHolds';

const CURRENT_ACCOUNT_REF = 'alice-usd';

export function Dashboard() {
  const location = useLocation();
  const notAuthorized = Boolean((location.state as { notAuthorized?: boolean } | null)?.notAuthorized);
  const balanceQuery = useAvailableBalance(CURRENT_ACCOUNT_REF);
  const transactionsQuery = useRecentTransactions(CURRENT_ACCOUNT_REF, 5);
  const holdsQuery = useHolds(CURRENT_ACCOUNT_REF);

  return (
    <AppLayout>
      {notAuthorized ? <NotAuthorizedNotice /> : null}
      <div className="grid gap-6 md:grid-cols-3">
        <Card title="Balance" className="md:col-span-1">
          {balanceQuery.isLoading ? (
            <Skeleton className="h-10 w-32" />
          ) : balanceQuery.data ? (
            <div className="space-y-2">
              <MoneyAmount amountMinor={balanceQuery.data.availableBalanceMinor} currency="USD" size="hero" />
              <p className="text-sm text-ink-light/60 dark:text-ink-dark/60">available</p>
              <p className="text-sm text-ink-light/60 dark:text-ink-dark/60">
                Posted: <MoneyAmount amountMinor={balanceQuery.data.postedBalanceMinor} currency="USD" />
              </p>
            </div>
          ) : (
            <p className="text-sm text-danger">Could not load balance.</p>
          )}
        </Card>
        <Card title="Recent activity" className="md:col-span-1">
          {transactionsQuery.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <ul className="space-y-3">
              {(transactionsQuery.data ?? []).map((transaction) => (
                <li key={transaction.transactionId} className="flex justify-between text-sm">
                  <span className="text-ink-light/70 dark:text-ink-dark/70">{transaction.description}</span>
                  <MoneyAmount amountMinor={transaction.amountMinor} currency={transaction.currency} />
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Active holds" className="md:col-span-1">
          {holdsQuery.isLoading ? (
            <Skeleton className="h-10 w-16" />
          ) : (
            <p className="text-2xl font-semibold">
              {(holdsQuery.data ?? []).filter((hold) => hold.status === 'ACTIVE').length}
            </p>
          )}
        </Card>
      </div>
    </AppLayout>
  );
}
```
(`CURRENT_ACCOUNT_REF` is hardcoded to `alice-usd` here since this platform has no account-to-identity linkage yet — the spec's Section 4 explicitly notes "a user's Keycloak identity and their ledger `accountRef` are not formally linked anywhere in the system today." A real multi-account-per-user experience is out of scope per the spec's Non-Goals; this hardcoded value is a deliberate, documented placeholder for the demo, matching how `scripts/smoke-test.sh` and the chaos scenarios already hardcode account refs like `acct-a`/`alice-usd`-style names rather than deriving them from a real user-account directory.)

- [ ] **Step 9: Run the full test suite for this task**

Run: `cd web && npx vitest run src/pages/Landing.test.tsx`
Expected: PASS. (`Dashboard`/`AppLayout`/hooks are not independently unit-tested per the spec's Section 7 scope — "not full page-level snapshot tests for every screen" — their correctness is covered by Task 11's build/manual-walkthrough verification instead.)

- [ ] **Step 10: Commit**

```bash
git add web/src/pages/Landing.tsx web/src/pages/Landing.test.tsx web/src/assets/hero.jpg \
        web/src/pages/app/Dashboard.tsx web/src/pages/app/AppLayout.tsx \
        web/src/hooks/useAvailableBalance.ts web/src/hooks/useRecentTransactions.ts web/src/hooks/useHolds.ts
git commit -m "feat(web): add landing page and end-user Dashboard

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: End-user pages — Transfer, History, Holds, Deposits & Withdrawals

**Files:**
- Create: `web/src/pages/app/Transfer.tsx`
- Create: `web/src/pages/app/History.tsx`
- Create: `web/src/pages/app/Holds.tsx`
- Create: `web/src/pages/app/Deposits.tsx`
- Create: `web/src/hooks/useTransactionDetail.ts`
- Test: `web/src/pages/app/Transfer.test.tsx`

**Interfaces:**
- Consumes: `AppLayout` (Task 7), `useApiFetch()` (Task 6), `useRecentTransactions`/`useHolds` (Task 7), `Card`/`Button`/`Input`/`Table`/`Badge`/`MoneyAmount` (Task 4), `useToast()` (Task 4).
- Produces: `useTransactionDetail(transactionId): UseQueryResult<TransactionDetail>` — Task 9's admin Transactions detail view reuses this same hook.

- [ ] **Step 1: Write the failing test for the Transfer form's validation logic**

```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Transfer } from './Transfer';

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: { access_token: 'token' }, roles: ['user'], logout: vi.fn() }),
}));

function renderTransfer() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Transfer />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Transfer', () => {
  it('disables the submit button until a destination account and amount are entered', async () => {
    renderTransfer();

    const submitButton = screen.getByRole('button', { name: /send/i });
    expect(submitButton).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/destination account/i), 'bob-usd');
    await userEvent.type(screen.getByLabelText(/amount/i), '10.00');

    expect(submitButton).toBeEnabled();
  });

  it('rejects a zero or negative amount', async () => {
    renderTransfer();

    await userEvent.type(screen.getByLabelText(/destination account/i), 'bob-usd');
    await userEvent.type(screen.getByLabelText(/amount/i), '0');

    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled();
    expect(screen.getByText(/amount must be greater than zero/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd web && npx vitest run src/pages/app/Transfer.test.tsx`
Expected: FAIL — `./Transfer` module doesn't exist yet.

- [ ] **Step 3: Implement `Transfer.tsx`**

```typescript
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Input } from '../../components/Input';
import { Button } from '../../components/Button';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';
import type { ConversionQuote } from '../../api/types';

const SOURCE_ACCOUNT_REF = 'alice-usd';

export function Transfer() {
  const [destinationAccountRef, setDestinationAccountRef] = useState('');
  const [amount, setAmount] = useState('');
  const [crossCurrency, setCrossCurrency] = useState(false);
  const [destCurrency, setDestCurrency] = useState('EUR');
  const [quote, setQuote] = useState<ConversionQuote | null>(null);
  const fetchWithAuth = useApiFetch();
  const { showToast } = useToast();

  const amountMinor = Math.round(Number(amount) * 100);
  const amountError = amount.length > 0 && amountMinor <= 0 ? 'Amount must be greater than zero' : undefined;
  const canSubmit = destinationAccountRef.length > 0 && amountMinor > 0;

  const quoteMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<ConversionQuote>('/conversions/quote', {
        method: 'POST',
        body: JSON.stringify({
          sourceAmountMinor: amountMinor,
          sourceCurrency: 'USD',
          destCurrency,
        }),
      }),
    onSuccess: (data) => setQuote(data),
    onError: () => showToast('Could not fetch a quote for this transfer.', 'error'),
  });

  const transferMutation = useMutation({
    mutationFn: () => {
      if (crossCurrency) {
        return fetchWithAuth('/transfers/cross-currency', {
          method: 'POST',
          body: JSON.stringify({
            sourceAccountRef: SOURCE_ACCOUNT_REF,
            destAccountRef: destinationAccountRef,
            sourceAmountMinor: amountMinor,
            idempotencyKey: crypto.randomUUID(),
          }),
        });
      }
      return fetchWithAuth('/transactions', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          debitAccountRef: SOURCE_ACCOUNT_REF,
          creditAccountRef: destinationAccountRef,
          amountMinor,
          currency: 'USD',
          description: 'Send money',
        }),
      });
    },
    onSuccess: () => {
      showToast('Transfer sent.', 'success');
      setDestinationAccountRef('');
      setAmount('');
      setQuote(null);
    },
    onError: () => showToast('Transfer failed.', 'error'),
  });

  return (
    <AppLayout>
      <Card title="Send money" className="max-w-md">
        <div className="space-y-4">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={crossCurrency} onChange={(e) => setCrossCurrency(e.target.checked)} />
            Cross-currency transfer
          </label>
          <Input
            label="Destination account"
            value={destinationAccountRef}
            onChange={(e) => setDestinationAccountRef(e.target.value)}
          />
          <Input label="Amount (USD)" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} error={amountError} />
          {crossCurrency ? (
            <Input label="Destination currency" value={destCurrency} onChange={(e) => setDestCurrency(e.target.value)} />
          ) : null}
          {crossCurrency && !quote ? (
            <Button variant="secondary" disabled={!canSubmit} onClick={() => quoteMutation.mutate()}>
              Get quote
            </Button>
          ) : null}
          {quote ? (
            <p className="text-sm text-ink-light/70 dark:text-ink-dark/70">
              Rate locked: {quote.sourceAmountMinor / 100} {quote.sourceCurrency} = {quote.destAmountMinor / 100}{' '}
              {quote.destCurrency}
            </p>
          ) : null}
          <Button
            onClick={() => transferMutation.mutate()}
            disabled={!canSubmit || (crossCurrency && !quote) || transferMutation.isPending}
          >
            Send
          </Button>
        </div>
      </Card>
    </AppLayout>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd web && npx vitest run src/pages/app/Transfer.test.tsx`
Expected: PASS, both cases.

- [ ] **Step 5: Implement `useTransactionDetail.ts`**

```typescript
import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { TransactionDetail } from '../api/types';

export function useTransactionDetail(transactionId: string | null) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['transaction', transactionId],
    queryFn: () => fetchWithAuth<TransactionDetail>(`/transactions/${transactionId}`),
    enabled: Boolean(transactionId),
  });
}
```

- [ ] **Step 6: Implement `History.tsx`**

```typescript
import { useState } from 'react';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Badge } from '../../components/Badge';
import { Skeleton } from '../../components/Skeleton';
import { useRecentTransactions } from '../../hooks/useRecentTransactions';
import { useTransactionDetail } from '../../hooks/useTransactionDetail';
import type { TransactionSummary } from '../../api/types';

const CURRENT_ACCOUNT_REF = 'alice-usd';

function statusBadge(status: string) {
  if (status === 'POSTED') return <Badge status="success">posted</Badge>;
  if (status === 'REVERSED') return <Badge status="warning">reversed</Badge>;
  return <Badge status="neutral">{status.toLowerCase()}</Badge>;
}

export function History() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const transactionsQuery = useRecentTransactions(CURRENT_ACCOUNT_REF, 100);
  const detailQuery = useTransactionDetail(selectedId);

  return (
    <AppLayout>
      <Card title="Transaction history">
        {transactionsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<TransactionSummary>
            columns={[
              { header: 'Description', render: (t) => t.description },
              { header: 'Amount', render: (t) => <MoneyAmount amountMinor={t.amountMinor} currency={t.currency} /> },
              { header: 'Status', render: (t) => statusBadge(t.status) },
              { header: 'Date', render: (t) => new Date(t.createdAt).toLocaleString() },
            ]}
            rows={transactionsQuery.data ?? []}
            keyField={(t) => t.transactionId}
            onRowClick={(t) => setSelectedId(t.transactionId)}
          />
        )}
      </Card>
      {selectedId ? (
        <Card title="Transaction detail" className="mt-6">
          {detailQuery.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : detailQuery.data ? (
            <ul className="space-y-2 text-sm">
              {detailQuery.data.entries.map((entry) => (
                <li key={entry.accountId} className="flex justify-between">
                  <span>
                    {entry.accountRef} ({entry.direction.toLowerCase()})
                  </span>
                  <MoneyAmount amountMinor={entry.amountMinor} currency={entry.currency} />
                </li>
              ))}
            </ul>
          ) : null}
        </Card>
      ) : null}
    </AppLayout>
  );
}
```

- [ ] **Step 7: Implement `Holds.tsx`**

```typescript
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { useHolds } from '../../hooks/useHolds';
import type { HoldSummary } from '../../api/types';

const CURRENT_ACCOUNT_REF = 'alice-usd';

export function Holds() {
  const holdsQuery = useHolds(CURRENT_ACCOUNT_REF);

  return (
    <AppLayout>
      <Card title="Holds">
        {holdsQuery.isLoading ? (
          <Skeleton className="h-48 w-full" />
        ) : (
          <Table<HoldSummary>
            columns={[
              { header: 'Amount', render: (h) => <MoneyAmount amountMinor={h.amountMinor} currency={h.currency} /> },
              { header: 'Destination', render: (h) => h.destinationAccountRef },
              {
                header: 'Status',
                render: (h) => <Badge status={h.status === 'ACTIVE' ? 'active' : 'neutral'}>{h.status.toLowerCase()}</Badge>,
              },
              { header: 'Expires', render: (h) => new Date(h.expiresAt).toLocaleString() },
            ]}
            rows={holdsQuery.data ?? []}
            keyField={(h) => h.id}
          />
        )}
      </Card>
    </AppLayout>
  );
}
```
(Read-only, no release/capture action — per the spec's Section 5: "no user-facing release/capture action, since those remain semantically administrative/merchant-side actions.")

- [ ] **Step 8: Implement `Deposits.tsx`**

```typescript
import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Input } from '../../components/Input';
import { Button } from '../../components/Button';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';

const CURRENT_ACCOUNT_REF = 'alice-usd';

interface DepositResponse {
  externalReference: string;
  status: string;
}

interface DepositStatus {
  status: 'RECEIVED' | 'CREDITED' | 'REJECTED';
}

interface WithdrawalTransactionResponse {
  transactionId: string;
}

export function Deposits() {
  const [depositAmount, setDepositAmount] = useState('');
  const [withdrawalAmount, setWithdrawalAmount] = useState('');
  const [depositRef, setDepositRef] = useState<string | null>(null);
  const [withdrawalTransactionId, setWithdrawalTransactionId] = useState<string | null>(null);
  const fetchWithAuth = useApiFetch();
  const { showToast } = useToast();

  const depositMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<DepositResponse>('/simulator/deposits', {
        method: 'POST',
        body: JSON.stringify({ accountRef: CURRENT_ACCOUNT_REF, amountMinor: Math.round(Number(depositAmount) * 100), currency: 'USD' }),
      }),
    onSuccess: (data) => setDepositRef(data.externalReference),
  });

  const depositStatusQuery = useQuery({
    queryKey: ['deposit-status', depositRef],
    queryFn: () => fetchWithAuth<DepositStatus>(`/external-deposits/${depositRef}`),
    enabled: Boolean(depositRef),
    refetchInterval: (query) => (query.state.data?.status === 'CREDITED' ? false : 2000),
  });

  const withdrawalMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<WithdrawalTransactionResponse>('/transactions', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          debitAccountRef: CURRENT_ACCOUNT_REF,
          creditAccountRef: 'external-clearing-USD',
          amountMinor: Math.round(Number(withdrawalAmount) * 100),
          currency: 'USD',
          description: 'Withdrawal',
          transactionType: 'WITHDRAWAL_EXTERNAL',
        }),
      }),
    onSuccess: (data) => setWithdrawalTransactionId(data.transactionId),
  });

  const confirmMutation = useMutation({
    mutationFn: (outcome: 'CONFIRMED' | 'FAILED') =>
      fetchWithAuth(`/simulator/withdrawals/by-transaction/${withdrawalTransactionId}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ outcome }),
      }),
    onSuccess: () => showToast('Withdrawal resolved.', 'success'),
    onError: () => showToast('Could not resolve the withdrawal yet — try again shortly.', 'error'),
  });

  return (
    <AppLayout>
      <div className="grid gap-6 md:grid-cols-2">
        <Card title="Simulate a deposit">
          <div className="space-y-4">
            <Input label="Amount (USD)" type="number" value={depositAmount} onChange={(e) => setDepositAmount(e.target.value)} />
            <Button onClick={() => depositMutation.mutate()} disabled={!depositAmount || depositMutation.isPending}>
              Deposit
            </Button>
            {depositRef ? (
              <p className="text-sm text-ink-light/70 dark:text-ink-dark/70">
                Status: {depositStatusQuery.data?.status ?? 'checking…'}
              </p>
            ) : null}
          </div>
        </Card>
        <Card title="Withdraw funds">
          <div className="space-y-4">
            <Input label="Amount (USD)" type="number" value={withdrawalAmount} onChange={(e) => setWithdrawalAmount(e.target.value)} />
            <Button onClick={() => withdrawalMutation.mutate()} disabled={!withdrawalAmount || withdrawalMutation.isPending}>
              Withdraw
            </Button>
            {withdrawalTransactionId ? (
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => confirmMutation.mutate('CONFIRMED')}>
                  Simulate confirm
                </Button>
                <Button variant="danger" onClick={() => confirmMutation.mutate('FAILED')}>
                  Simulate fail
                </Button>
              </div>
            ) : null}
          </div>
        </Card>
      </div>
    </AppLayout>
  );
}
```
(The `refetchInterval` callback signature — `(query) => ...` reading `query.state.data` — matches TanStack Query v5's function-form API, which replaced v4's plain `(data) => ...` callback; verify this against the installed `@tanstack/react-query` version's actual type signature before finalizing, since this is exactly the kind of minor-version API surface that's worth confirming live rather than assuming from memory.)

- [ ] **Step 9: Run the full frontend test suite and build**

Run: `cd web && npm run test`
Expected: all tests across every task so far pass. (Full `npm run build` is still deferred until Task 9's admin pages exist, since `App.tsx` already imports them per Task 6 Step 8.)

- [ ] **Step 10: Commit**

```bash
git add web/src/pages/app/Transfer.tsx web/src/pages/app/Transfer.test.tsx \
        web/src/pages/app/History.tsx web/src/pages/app/Holds.tsx web/src/pages/app/Deposits.tsx \
        web/src/hooks/useTransactionDetail.ts
git commit -m "feat(web): add Transfer, History, Holds, and Deposits & Withdrawals pages

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: Admin console — Accounts, Transactions (with reversal), Holds, Reconciliation

**Files:**
- Create: `web/src/pages/admin/AdminLayout.tsx`
- Create: `web/src/pages/admin/AdminAccounts.tsx`
- Create: `web/src/pages/admin/AdminTransactions.tsx`
- Create: `web/src/pages/admin/AdminHolds.tsx`
- Create: `web/src/pages/admin/AdminReconciliation.tsx`
- Create: `web/src/components/ConfirmDialog.tsx`
- Test: `web/src/components/ConfirmDialog.test.tsx`
- Test: `web/src/pages/admin/AdminTransactions.test.tsx`

**Interfaces:**
- Consumes: `Table`/`Card`/`Badge`/`Button`/`Input`/`MoneyAmount` (Task 4), `useApiFetch()` (Task 6), `useTransactionDetail` (Task 8).
- Produces: `<ConfirmDialog isOpen title body confirmLabel onConfirm onCancel>` — a general-purpose confirmation modal other admin actions (hold release) also use.

- [ ] **Step 1: Write the failing test for `ConfirmDialog`**

```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders nothing when closed', () => {
    render(
      <ConfirmDialog isOpen={false} title="Reverse transaction" body="Are you sure?" confirmLabel="Reverse" onConfirm={vi.fn()} onCancel={vi.fn()} />,
    );
    expect(screen.queryByText('Reverse transaction')).not.toBeInTheDocument();
  });

  it('calls onConfirm when the confirm button is clicked', async () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog isOpen title="Reverse transaction" body="This posts a new compensating transaction." confirmLabel="Reverse" onConfirm={onConfirm} onCancel={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));

    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('calls onCancel when the cancel button is clicked', async () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog isOpen title="Reverse transaction" body="This posts a new compensating transaction." confirmLabel="Reverse" onConfirm={vi.fn()} onCancel={onCancel} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));

    expect(onCancel).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd web && npx vitest run src/components/ConfirmDialog.test.tsx`
Expected: FAIL — `./ConfirmDialog` module doesn't exist yet.

- [ ] **Step 3: Implement `ConfirmDialog.tsx`**

```typescript
import { Button } from './Button';

interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({ isOpen, title, body, confirmLabel, onConfirm, onCancel }: ConfirmDialogProps) {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-sm rounded-card bg-surface-light p-6 dark:bg-surface-dark-alt">
        <h3 className="text-lg font-semibold">{title}</h3>
        <p className="mt-2 text-sm text-ink-light/70 dark:text-ink-dark/70">{body}</p>
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="danger" onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd web && npx vitest run src/components/ConfirmDialog.test.tsx`
Expected: PASS, all 3 cases.

- [ ] **Step 5: Implement `AdminLayout.tsx`**

```typescript
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';

const navItems = [
  { to: '/admin', label: 'Accounts' },
  { to: '/admin/transactions', label: 'Transactions' },
  { to: '/admin/holds', label: 'Holds' },
  { to: '/admin/reconciliation', label: 'Reconciliation' },
];

export function AdminLayout({ children }: { children: ReactNode }) {
  const { logout } = useAuth();
  const location = useLocation();

  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b border-ink-light/10 px-6 py-4 dark:border-ink-dark/10">
        <nav className="flex items-center gap-6">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`text-sm font-medium ${location.pathname === item.to ? 'text-accent' : 'text-ink-light/60 dark:text-ink-dark/60'}`}
            >
              {item.label}
            </Link>
          ))}
          <a href="http://localhost:3000" target="_blank" rel="noreferrer" className="text-sm text-ink-light/60 dark:text-ink-dark/60">
            Metrics ↗
          </a>
          <Link to="/app" className="text-sm text-ink-light/60 dark:text-ink-dark/60">
            View my account
          </Link>
        </nav>
        <button onClick={logout} className="text-sm text-ink-light/60 dark:text-ink-dark/60">
          Log out
        </button>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">{children}</main>
    </div>
  );
}
```
(The "View my account" link and the Grafana link both per the spec's Section 6/2: "a visible 'View my account' link to `/app`", and "a nav link opening the existing Grafana instance (`http://localhost:3000`) in a new tab.")

- [ ] **Step 6: Implement `AdminAccounts.tsx`**

```typescript
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Input } from '../../components/Input';
import { Badge } from '../../components/Badge';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import type { AccountSummary } from '../../api/types';

export function AdminAccounts() {
  const [accountRefFilter, setAccountRefFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const fetchWithAuth = useApiFetch();

  const accountsQuery = useQuery({
    queryKey: ['admin-accounts', accountRefFilter, statusFilter],
    queryFn: () => {
      const params = new URLSearchParams();
      if (accountRefFilter) params.set('accountRef', accountRefFilter);
      if (statusFilter) params.set('status', statusFilter);
      return fetchWithAuth<AccountSummary[]>(`/accounts?${params.toString()}`);
    },
  });

  return (
    <AdminLayout>
      <Card title="Accounts">
        <div className="mb-4 flex gap-4">
          <Input label="Account ref" value={accountRefFilter} onChange={(e) => setAccountRefFilter(e.target.value)} />
          <Input label="Status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} />
        </div>
        {accountsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : accountsQuery.error ? (
          <p className="text-sm text-danger">Could not load accounts.</p>
        ) : (
          <Table<AccountSummary>
            columns={[
              { header: 'Account ref', render: (a) => a.accountRef },
              { header: 'Currency', render: (a) => a.currency },
              {
                header: 'Status',
                render: (a) => <Badge status={a.status === 'ACTIVE' ? 'active' : 'neutral'}>{a.status.toLowerCase()}</Badge>,
              },
            ]}
            rows={accountsQuery.data ?? []}
            keyField={(a) => a.accountRef}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
```

- [ ] **Step 7: Write the failing test for `AdminTransactions`'s reversal-confirmation flow**

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { AdminTransactions } from './AdminTransactions';

const mockFetchWithAuth = vi.fn();
vi.mock('../../api/client', () => ({
  useApiFetch: () => mockFetchWithAuth,
}));

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AdminTransactions />
    </QueryClientProvider>,
  );
}

describe('AdminTransactions reversal flow', () => {
  it('shows a confirmation dialog before reversing, and only calls the reverse endpoint on confirm', async () => {
    mockFetchWithAuth.mockResolvedValue([
      {
        transactionId: 'tx-1',
        status: 'POSTED',
        transactionType: 'TRANSFER',
        debitAccountRef: 'acct-a',
        creditAccountRef: 'acct-b',
        amountMinor: 1000,
        currency: 'USD',
        description: 'test transfer',
        createdAt: new Date().toISOString(),
        reversalOfTransactionId: null,
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('test transfer')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /reverse/i }));

    expect(screen.getByText(/posts a new compensating transaction/i)).toBeInTheDocument();
    expect(mockFetchWithAuth).not.toHaveBeenCalledWith(expect.stringContaining('/reverse'), expect.anything());

    mockFetchWithAuth.mockResolvedValueOnce({ transactionId: 'tx-2' });
    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));

    await waitFor(() =>
      expect(mockFetchWithAuth).toHaveBeenCalledWith('/transactions/tx-1/reverse', expect.objectContaining({ method: 'POST' })),
    );
  });

  it('does not show a reverse action for a transaction that is already REVERSED', async () => {
    mockFetchWithAuth.mockResolvedValue([
      {
        transactionId: 'tx-3',
        status: 'REVERSED',
        transactionType: 'TRANSFER',
        debitAccountRef: 'acct-a',
        creditAccountRef: 'acct-b',
        amountMinor: 500,
        currency: 'USD',
        description: 'already reversed',
        createdAt: new Date().toISOString(),
        reversalOfTransactionId: null,
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('already reversed')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /^reverse$/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `cd web && npx vitest run src/pages/admin/AdminTransactions.test.tsx`
Expected: FAIL — `./AdminTransactions` module doesn't exist yet.

- [ ] **Step 9: Implement `AdminTransactions.tsx`**

```typescript
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { MoneyAmount } from '../../components/MoneyAmount';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import { useTransactionDetail } from '../../hooks/useTransactionDetail';
import type { TransactionSummary } from '../../api/types';

function statusBadge(status: string) {
  if (status === 'POSTED') return <Badge status="success">posted</Badge>;
  if (status === 'REVERSED') return <Badge status="warning">reversed</Badge>;
  return <Badge status="neutral">{status.toLowerCase()}</Badge>;
}

export function AdminTransactions() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pendingReversalId, setPendingReversalId] = useState<string | null>(null);
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();
  const detailQuery = useTransactionDetail(selectedId);

  const transactionsQuery = useQuery({
    queryKey: ['admin-transactions'],
    queryFn: () => fetchWithAuth<TransactionSummary[]>('/transactions'),
  });

  const reverseMutation = useMutation({
    mutationFn: (transactionId: string) => fetchWithAuth(`/transactions/${transactionId}/reverse`, { method: 'POST' }),
    onSuccess: () => {
      setPendingReversalId(null);
      queryClient.invalidateQueries({ queryKey: ['admin-transactions'] });
    },
  });

  return (
    <AdminLayout>
      <Card title="Transactions">
        {transactionsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<TransactionSummary>
            columns={[
              { header: 'Description', render: (t) => t.description },
              { header: 'Amount', render: (t) => <MoneyAmount amountMinor={t.amountMinor} currency={t.currency} /> },
              { header: 'Status', render: (t) => statusBadge(t.status) },
              {
                header: 'Actions',
                render: (t) =>
                  t.status !== 'REVERSED' && t.transactionType !== 'REVERSAL' ? (
                    <Button variant="danger" onClick={() => setPendingReversalId(t.transactionId)}>
                      Reverse
                    </Button>
                  ) : null,
              },
            ]}
            rows={transactionsQuery.data ?? []}
            keyField={(t) => t.transactionId}
            onRowClick={(t) => setSelectedId(t.transactionId)}
          />
        )}
      </Card>
      {selectedId ? (
        <Card title="Transaction detail" className="mt-6">
          {detailQuery.data ? (
            <ul className="space-y-2 text-sm">
              {detailQuery.data.entries.map((entry) => (
                <li key={entry.accountId} className="flex justify-between">
                  <span>
                    {entry.accountRef} ({entry.direction.toLowerCase()})
                  </span>
                  <MoneyAmount amountMinor={entry.amountMinor} currency={entry.currency} />
                </li>
              ))}
            </ul>
          ) : null}
        </Card>
      ) : null}
      <ConfirmDialog
        isOpen={pendingReversalId !== null}
        title="Reverse transaction"
        body="This posts a new compensating transaction with the debit and credit legs swapped. It does not undo or delete the original transaction."
        confirmLabel="Reverse"
        onConfirm={() => pendingReversalId && reverseMutation.mutate(pendingReversalId)}
        onCancel={() => setPendingReversalId(null)}
      />
    </AdminLayout>
  );
}
```
(A transaction whose `status === 'REVERSED'` OR whose `transactionType === 'REVERSAL'` gets no Reverse action — matching the backend's own `CannotReverseAReversalException`/`TransactionAlreadyReversedException` guard pair exactly, so the button is never shown for a call that would `409` anyway.)

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd web && npx vitest run src/pages/admin/AdminTransactions.test.tsx`
Expected: PASS, both cases.

- [ ] **Step 11: Implement `AdminHolds.tsx`**

```typescript
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input } from '../../components/Input';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import type { HoldSummary } from '../../api/types';

export function AdminHolds() {
  const [accountRefFilter, setAccountRefFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();

  const holdsQuery = useQuery({
    queryKey: ['admin-holds', accountRefFilter, statusFilter],
    queryFn: () => {
      const params = new URLSearchParams();
      if (accountRefFilter) params.set('accountRef', accountRefFilter);
      if (statusFilter) params.set('status', statusFilter);
      return fetchWithAuth<HoldSummary[]>(`/holds?${params.toString()}`);
    },
  });

  const releaseMutation = useMutation({
    mutationFn: (holdId: string) => fetchWithAuth(`/holds/${holdId}/release`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-holds'] }),
  });

  return (
    <AdminLayout>
      <Card title="Holds">
        <div className="mb-4 flex gap-4">
          <Input label="Account ref" value={accountRefFilter} onChange={(e) => setAccountRefFilter(e.target.value)} />
          <Input label="Status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} />
        </div>
        {holdsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<HoldSummary>
            columns={[
              { header: 'Account', render: (h) => h.accountRef },
              { header: 'Amount', render: (h) => <MoneyAmount amountMinor={h.amountMinor} currency={h.currency} /> },
              {
                header: 'Status',
                render: (h) => <Badge status={h.status === 'ACTIVE' ? 'active' : 'neutral'}>{h.status.toLowerCase()}</Badge>,
              },
              {
                header: 'Actions',
                render: (h) =>
                  h.status === 'ACTIVE' ? (
                    <Button variant="secondary" onClick={() => releaseMutation.mutate(h.id)}>
                      Release
                    </Button>
                  ) : null,
              },
            ]}
            rows={holdsQuery.data ?? []}
            keyField={(h) => h.id}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
```
(This is the unscoped admin form of `GET /holds` per the spec's Section 6 — an empty `accountRefFilter` here sends `GET /holds?` with no `accountRef` param, which Task 1's relaxed `HoldService.listHolds` permits only because the caller's JWT carries the `admin` role.)

- [ ] **Step 12: Implement `AdminReconciliation.tsx`**

```typescript
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';
import type { ReconciliationRun } from '../../api/types';

export function AdminReconciliation() {
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const runsQuery = useQuery({
    queryKey: ['reconciliation-runs'],
    queryFn: () => fetchWithAuth<ReconciliationRun[]>('/reconciliation/runs'),
    refetchInterval: 5000,
  });

  const triggerMutation = useMutation({
    mutationFn: () => fetchWithAuth('/reconciliation/runs', { method: 'POST' }),
    onSuccess: () => {
      showToast('Reconciliation run triggered.', 'success');
      queryClient.invalidateQueries({ queryKey: ['reconciliation-runs'] });
    },
  });

  return (
    <AdminLayout>
      <Card title="Reconciliation runs">
        <Button className="mb-4" onClick={() => triggerMutation.mutate()} disabled={triggerMutation.isPending}>
          Run now
        </Button>
        {runsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<ReconciliationRun>
            columns={[
              { header: 'Started', render: (r) => new Date(r.startedAt).toLocaleString() },
              {
                header: 'Status',
                render: (r) => {
                  // ReconciliationRun.Status is RUNNING | COMPLETED | FAILED — there is no
                  // "CLEAN" value. A run is only genuinely clean when it COMPLETED with zero
                  // imbalances/missing/stuck rows; a COMPLETED run that found problems is a
                  // real finding, not a clean bill of health, so it must not read as "success".
                  const isClean =
                    r.status === 'COMPLETED' &&
                    r.entriesImbalanceCount === 0 &&
                    r.outboxMissingCount === 0 &&
                    r.outboxStuckCount === 0;
                  const badgeStatus = r.status === 'FAILED' ? 'error' : isClean ? 'success' : 'warning';
                  const label = r.status === 'RUNNING' ? 'running' : isClean ? 'clean' : r.status.toLowerCase();
                  return <Badge status={badgeStatus}>{label}</Badge>;
                },
              },
              { header: 'Checked', render: (r) => r.transactionsChecked },
              { header: 'Imbalances', render: (r) => r.entriesImbalanceCount },
              { header: 'Outbox missing', render: (r) => r.outboxMissingCount },
              { header: 'Outbox stuck', render: (r) => r.outboxStuckCount },
            ]}
            rows={runsQuery.data ?? []}
            keyField={(r) => r.runId}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
```
(`GET /reconciliation/runs` polls every 5 seconds via `refetchInterval` so the "Run now" button's effect becomes visible without a manual page reload — per the spec's Section 6: "a 'Run now' button that triggers a run and refreshes the list." `ReconciliationRunResponse.status` is one of `RUNNING`/`COMPLETED`/`FAILED` — confirmed against the real `ReconciliationRun.Status` domain enum, which has no `CLEAN` value. "Clean" is not a status value at all; it's a derived condition — a `COMPLETED` run with zero `entriesImbalanceCount`/`outboxMissingCount`/`outboxStuckCount`. The `isClean` predicate above computes this directly so a `COMPLETED` run that actually found problems renders as a warning, not a false "success," and `outboxMissingCount`/`outboxStuckCount` are now shown as their own columns so an admin can see which of the three checks actually failed, not just the imbalance count.)

- [ ] **Step 13: Run the full frontend build**

Run: `cd web && npm run build`
Expected: clean production build, no TypeScript errors — this is the first point in the plan where every module `App.tsx` imports (Task 6 Step 8) now exists, so this is also the first task where a full build is a meaningful check.

Run: `cd web && npm run test`
Expected: all tests across every task pass.

- [ ] **Step 14: Commit**

```bash
git add web/src/pages/admin/ web/src/components/ConfirmDialog.tsx web/src/components/ConfirmDialog.test.tsx
git commit -m "feat(web): add admin console (Accounts, Transactions with reversal, Holds, Reconciliation)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Docker deployment — `web` service in `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`
- Modify: `web/Dockerfile`

**Interfaces:**
- Consumes: `web/Dockerfile` (Task 3), the `ledger-web` Keycloak client (Task 2).
- Produces: a `web` service reachable at `http://localhost:8085`.

- [ ] **Step 1: Add build-time env vars to the Dockerfile so the production build points at the right origins**

Modify `web/Dockerfile`'s build stage to accept build args for the two `VITE_*` variables Tasks 5/6 introduced:
```dockerfile
FROM node:20-alpine AS build
WORKDIR /workspace
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web .
ARG VITE_KEYCLOAK_BASE_URL=http://localhost:8180
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_KEYCLOAK_BASE_URL=$VITE_KEYCLOAK_BASE_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```
(Vite inlines `import.meta.env.VITE_*` values at build time, not runtime, so these must be build args passed via `docker-compose.yml`'s `build.args`, not `environment:` — an nginx-served static bundle has no runtime process to read environment variables from, unlike every other service in this platform's `environment:`-based convention. This is a deliberate, necessary deviation from that convention, not an oversight — document it inline in `docker-compose.yml` per Step 2's comment.)

- [ ] **Step 2: Add the `web` service to `docker-compose.yml`**

```yaml
  web:
    build:
      context: .
      dockerfile: web/Dockerfile
      # Vite inlines VITE_* values into the built JS bundle at build time -- unlike every other
      # service in this file, this static nginx-served app has no runtime process to read an
      # `environment:` block from, so these must be passed as build args instead.
      args:
        VITE_KEYCLOAK_BASE_URL: http://localhost:8180
        VITE_API_BASE_URL: http://localhost:8080
    ports:
      - "8085:80"
    depends_on:
      api-gateway:
        condition: service_started
```
(Both `VITE_*` values point at the host-published ports, `8180`/`8080`, matching how a browser tab loaded from `http://localhost:8085` — the host machine, not another container — must reach Keycloak and the gateway; this mirrors `KEYCLOAK_ISSUER_URI`'s existing `host.docker.internal:8180` pattern in spirit, but the *browser*, unlike a server-side container, reaches published host ports directly via `localhost`, so `http://localhost:8180`/`http://localhost:8080` are correct here, not `host.docker.internal` — verify this distinction holds by testing the login flow live in Task 11, since a browser running on the same host as Docker Desktop resolves `localhost:<published-port>` correctly, but this assumption breaks if the stack is ever accessed from a different machine and would need this build arg changed to that host's real address.)

- [ ] **Step 3: Bring the new service up and verify it serves the app**

Run: `docker compose up -d --build web` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`), after the full stack (`docker compose up -d --build`) and `scripts/provision.sh` have already brought up every other service including `keycloak` and `api-gateway`.

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/
# expect 200
curl -s http://localhost:8085/ | grep -o '<title>[^<]*</title>'
# expect <title>Ledger</title>
```

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml web/Dockerfile
git commit -m "feat(web): add web service to docker-compose.yml with build-time Vite env wiring

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Full-stack verification and README update

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-10.
- Produces: the final, verified, documented state of the platform — the last task of this plan.

- [ ] **Step 1: Run the full Maven reactor build**

Run: `mvn clean verify -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`) from the repo root.
Expected: `BUILD SUCCESS` across all 6 Java modules (including `holds-service` and `ledger-service`'s Task 1/7 changes).

- [ ] **Step 2: Run the full frontend build and test suite one final time**

Run: `cd web && npm run test && npm run build`
Expected: all tests pass, clean production build with no TypeScript errors (per the spec's Verification section: "`npm run build` produces a clean production bundle with no TypeScript errors").

- [ ] **Step 3: Bring up the full stack including `web`**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
```
Expected: 17 containers (the prior 16 plus `web`) report as up; `docker compose ps` shows `web` running and `api-gateway`/`keycloak` healthy.

- [ ] **Step 4: Run the existing smoke test and chaos scenarios to confirm no backend regression**

```bash
bash scripts/smoke-test.sh
for s in chaos/scenarios/*.sh; do bash "$s" || break; done
```
Expected: smoke test passes; all 9 chaos scenarios pass — Task 1/7's changes only relax an existing admin gate for a specific query-parameter shape and add a new, unused-by-any-existing-caller `CallerContext` parameter, so no regression is expected in either the money-movement paths or the chaos suite's own account-scoped calls, which either don't touch `GET /holds`/`GET /transactions` at all or already pass an `accountRef`.

- [ ] **Step 5: Manual walkthrough — end-user flow**

Open `http://localhost:8085` in a browser. Confirm:
- The landing page shows the hero image and a "Log in" button, no admin/app content visible pre-login.
- Clicking "Log in" redirects to Keycloak's hosted login page (not an in-app form).
- Logging in as `alice`/`alice-password` redirects back to `/app` (not `/admin`).
- The Dashboard shows a balance, recent activity, and an active-holds count.
- `/app/transfer` completes a same-currency transfer to another seeded account and it appears in `/app/history`.
- `/app/holds` shows only holds where `alice-usd` is a party.
- `/app/deposits` completes a simulated deposit (status transitions to `CREDITED`) and a simulated withdrawal (resolved via the "Simulate confirm"/"Simulate fail" controls).

- [ ] **Step 6: Manual walkthrough — admin flow**

Log out, then log in as `admin`/`admin-password`. Confirm:
- Login redirects to `/admin` (not `/app`), and a "View my account" link to `/app` is visible.
- `/admin` (Accounts) lists all seeded accounts and supports the `accountRef`/`status` filters.
- `/admin/transactions` lists all transactions; clicking one shows its entry detail; reversing a non-reversed transaction shows the confirmation dialog first, then posts the reversal and the list refreshes to show the new `REVERSED` status and the new compensating transaction.
- `/admin/holds` lists all holds (no `accountRef` required) and releasing an active hold updates its status.
- `/admin/reconciliation` shows run history; "Run now" triggers a new run that appears in the list within 5 seconds (the polling interval).
- The Grafana nav link opens `http://localhost:3000` in a new tab.

- [ ] **Step 7: Manual walkthrough — the admin-role boundary**

Log out, log back in as `alice`. Attempt to navigate directly to `http://localhost:8085/admin` in the browser's address bar. Confirm the app redirects to `/app` and shows the "You don't have access to that page" notice (from `NotAuthorizedNotice`, Task 6) rather than a broken or empty admin page.

Tear down: `docker compose down -v`.

- [ ] **Step 8: Update the README**

Read the current `README.md` in full (already read during this plan's own research — reproduced above), then add:

A new bullet under "What this demonstrates":
```markdown
- **A browser-based frontend**: a React SPA (`web/`, served by nginx in production) providing
  both an end-user banking UI (`/app/*`) and a role-gated admin console (`/admin/*`), the
  platform's first client-facing surface beyond `curl`/scripts. Logs in against Keycloak via
  a new public, PKCE-only OAuth2 client (`ledger-web`) — no shared secret, unlike every other
  existing Keycloak client in this realm.
```

A new "Web Frontend" subsection under "Architecture" (after the existing Keycloak bullet):
```markdown
- **Web** (`:8085`) — a Vite + React + TypeScript SPA, served by nginx in production. The
  public landing page (`/`) starts the Keycloak Authorization Code + PKCE flow via the new
  `ledger-web` public client; a caller with the `admin` realm role lands on `/admin`, every
  other authenticated caller lands on `/app`. Every request goes through the API Gateway,
  same as every other client. `GET /holds` and `GET /transactions` are no longer unconditionally
  admin-gated: a non-admin, authenticated caller may list either scoped to a specific
  `accountRef` they name (never the unscoped full list), enforced inside Holds Service and
  Ledger Service respectively via a new, minimal JWT-role-decoding path
  (`JwtRoleReader`/`CallerContext` in each service) — the first time either service has needed
  to know the caller's identity rather than trusting the gateway's authorization decision
  alone.
```

Update the "New admin API additions" bullet list under "Known limitations" — the existing bullet "Coarse-grained role model... any `admin`-role token can read or reverse anything" should get a trailing clause noting the new exception:
```markdown
  (`GET /holds` and `GET /transactions` are now the first exception: a non-admin caller may
  read their own named account's holds/transactions, though "own" here means only "the
  account ref they supply," since this platform still has no formal link between a Keycloak
  identity and a ledger `accountRef`).
```

Add a new "New in the web frontend" subsection under "Known limitations":
```markdown
**New in the web frontend:**

- **No real user-to-account linkage**: every end-user page hardcodes `alice-usd` as "the
  current user's account" rather than deriving it from the logged-in identity, since this
  platform has no such mapping today (see the design spec's Section 4). A real multi-account
  frontend would need this linkage built first.
- **Vite build-time config, not runtime config**: `VITE_KEYCLOAK_BASE_URL`/`VITE_API_BASE_URL`
  are baked into the static JS bundle at Docker build time (`web/Dockerfile`'s build args),
  not read from a runtime environment variable like every other service in this platform —
  an nginx-served static bundle has no server-side process to read `environment:` from.
  Changing either value requires a rebuild of the `web` image, not just a container restart.
- **No E2E test suite**: Vitest + React Testing Library cover the API client, auth/role-routing
  logic, and the transfer/reversal confirmation flows; no browser-driven end-to-end suite
  exists yet (Playwright, per the design spec's Non-Goals, is a plausible later addition).
```

Add a new bullet to "Getting a token" or a new "Running the web frontend" subsection under "Running locally":
```markdown
### Running the web frontend

`make up` now also builds and starts `web` (`http://localhost:8085`), nginx-served in
production. For local frontend development with hot reload instead:

```bash
cd web
npm install
npm run dev   # http://localhost:5173, proxied against the already-running API Gateway/Keycloak
```

Log in with any of the demo users (`alice`, `bob`, `admin` — see "Getting a token" above for
their passwords); the web app drives the same Keycloak Authorization Code + PKCE flow a real
browser client would use, distinct from the password-grant flow `scripts/get-token.sh` uses
for scripting/testing.
```

- [ ] **Step 9: Commit**

```bash
git add README.md
git commit -m "docs: document the web frontend, the new ledger-web Keycloak client, and the relaxed self-scoped holds/transactions listing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
