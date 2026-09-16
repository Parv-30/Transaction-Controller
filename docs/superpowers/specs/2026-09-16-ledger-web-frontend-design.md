# Ledger Web Frontend: Design Spec

## Context

The platform (V1-V5, a hardening pass, and the Admin API Additions backend) has no
frontend today — every interaction happens via `curl`/scripts against the API Gateway. This
spec covers a single web application providing both an end-user banking UI and an admin
console, the first client-facing surface for the whole platform.

This is the first of a two-part frontend effort: this spec covers the application itself
(pages, auth, API integration). A small, necessary backend addition (self-scoped hold
listing for end users, see Section 2) is included here since the frontend's Holds page
cannot be built without it, but it is implemented and reviewed as its own early task before
any frontend code depends on it.

## Goals

- One React SPA covering both experiences: `/app/*` for end users, `/admin/*` for admins.
- Real login against Keycloak via a new, browser-safe public OAuth2 client — no more
  standing tokens fetched via shell scripts.
- Every capability the backend already exposes gets a corresponding UI surface: accounts,
  transfers (same-currency and cross-currency), transaction history, holds, Gateway
  Simulator deposits/withdrawals (end-user side); account/transaction/hold search,
  transaction reversal, reconciliation run history/triggering (admin side).
- Deployed the same way as every other service in this platform: its own module, its own
  Dockerfile, its own `docker-compose.yml` entry.

## Non-Goals

- No server-side rendering, no Next.js — this is a pure client-rendered SPA fetching from an
  already-complete REST API; there is no SEO or first-paint requirement that would justify
  the added complexity.
- No metrics/dashboard visualization inside this app — the existing Grafana instance already
  does this; the admin console links out to it rather than re-implementing charts.
- No E2E test suite in this initial scope (Playwright could be a later addition).
- No offline support, no PWA features, no mobile-native wrapper.
- No self-registration flow — users are still provisioned via the Keycloak realm-import file,
  matching how `alice`/`bob`/`admin` already work.

## Section 1 — Architecture & Auth

**Stack**: Vite + React + TypeScript + Tailwind CSS. React Router for client-side routing
(`/app/*` vs `/admin/*` route trees under one app shell). No heavy UI component library — a
small shared component set (button, input, table, badge, card, toast) built directly on
Tailwind, since a from-scratch look better demonstrates frontend capability than a wrapped
component library for a portfolio project.

**Deployment**: a new top-level module, `web/`, with its own `Dockerfile` (multi-stage: Vite
build, then a static file server — nginx, matching the lightweight-static-serve pattern
rather than running a Node server in production) and a new `web` service in
`docker-compose.yml`, following the exact structural convention of every other service in
this platform (own container, own port, added to the `depends_on` graph only where a genuine
startup-order dependency exists — here, `api-gateway`).

**Auth**: a new Keycloak client, `ledger-web`, added to `keycloak-realm/ledger-realm.json`:
`publicClient: true`, `standardFlowEnabled: true` (Authorization Code flow), PKCE required
(`S256`), a `redirectUris` entry pointing at the web app's own origin (e.g.
`http://localhost:5173/*` for local dev, the deployed container's origin in
docker-compose). This is necessary because all three existing Keycloak clients
(`chaos-suite-client`, `smoke-test-client`, `demo-users-client`) are confidential clients
with a shared secret — safe for server-side/CLI use, unsafe to embed in browser-shipped
JavaScript. A public client with PKCE is the standard, secure pattern for a browser SPA and
requires no secret at all.

The frontend uses an OIDC client library (`oidc-client-ts`, a maintained, framework-agnostic
library with a clean React integration story) to drive the Authorization Code + PKCE flow:
redirect to Keycloak's hosted login page, handle the callback, hold the access token in
memory (not `localStorage`, to reduce XSS-exfiltration surface), and use the library's silent
-renewal support to refresh before expiry. On login, the app decodes the JWT's
`realm_access.roles` claim — the exact claim the backend's own `KeycloakRealmRoleConverter`
already reads — to determine whether the user lands on `/app` or `/admin` (Section 3
covers the routing rule), and to conditionally render admin-only UI.

**API integration**: every request goes through the existing API Gateway
(`http://localhost:8080` in local dev; same-origin via the Docker network once deployed) —
the frontend never addresses `ledger-service`/`holds-service`/etc. directly, mirroring how
every other client (chaos scripts, smoke tests) already only ever talks to the gateway. A
single shared API client module wraps `fetch`, attaches the current access token as a Bearer
header, and centralizes error handling (Section 4).

## Section 2 — Backend addition: self-scoped hold listing

**Problem**: `GET /holds` (Admin API Additions) is entirely admin-gated — there is no way for
an authenticated non-admin user to list holds on their own account. The end-user Holds page
(Section 3) needs this.

**Fix**: relax `HoldController`'s existing `GET /holds` endpoint (not add a new route) to
allow any authenticated caller when the `accountRef` query parameter is present and the
caller is querying their own account — but since this platform's JWT does not currently carry
an account-ownership claim (a user's Keycloak identity and their ledger `accountRef` are not
formally linked anywhere in the system today), the practical, minimal-risk implementation is
simpler: **`GET /holds` requires either the `admin` role, or a non-blank `accountRef` query
parameter** (i.e., a non-admin caller may only ever list holds for a *specific, named*
account — never an unscoped "all holds" query — while an admin may omit `accountRef` for the
full list). Enforcing this per-parameter rule is beyond what Spring Cloud Gateway's
declarative `pathMatchers` DSL can express (it only sees the path/method, not query
parameters or role-conditional logic), so the enforcement point is `HoldService.listHolds`
itself, inside `holds-service`: the API Gateway's `SecurityConfig` is relaxed to let any
authenticated caller through to `GET /holds` (removing the `hasAuthority("ROLE_admin")` gate
on this one route), and `HoldService` gains a check — a non-admin caller (no `admin`
authority in their forwarded identity; see below) with a blank `accountRef` gets
`400 Bad Request` (a new `AccountRefRequiredForNonAdminException`), never the full unscoped
list.

This requires the backend to know the caller's roles inside `holds-service`, not just at the
gateway. The gateway must forward the caller's roles to downstream services — check whether
this already happens (Spring Cloud Gateway's OAuth2 resource server setup may already
propagate the `Authorization` header downstream unchanged, in which case `holds-service`
can decode the same JWT itself using its own, already-present `JwtDecoder` if one is
configured, or by adding a minimal one) — resolve this exact mechanism during
implementation, verifying against the real, current gateway/holds-service configuration
rather than assuming a mechanism exists.

**Scope boundary**: this is a small, self-contained addition (one relaxed gateway rule, one
new check + exception in `HoldService`, and the JWT-forwarding verification above) — sized
and reviewed as its own early task in the implementation plan, completed and verified before
any frontend Holds-page work begins.

## Section 3 — End-user banking UI (`/app/*`)

- **`/app` (Dashboard)**: current account balance — both `postedBalanceMinor` and
  `availableBalanceMinor` from Holds Service's `GET /accounts/{accountRef}/available-balance`
  — plus a short recent-activity list (`GET /transactions?accountRef=`, most recent N) and an
  active-holds count/summary.
- **`/app/transfer` (Send Money)**: a form posting to `POST /transactions` for a same-currency
  transfer. A toggle switches to cross-currency mode, which first calls
  `POST /conversions/quote` (FX Service) to lock and display a rate before the user confirms,
  then posts to `POST /transfers/cross-currency`.
- **`/app/history` (Transaction History)**: `GET /transactions?accountRef=` with client-side
  status/date filtering (reusing the same query params the admin console's own Transactions
  page uses), row click-through to a detail view (`GET /transactions/{id}`) showing both
  entries.
- **`/app/holds` (Holds)**: `GET /holds?accountRef=<my-account>` (Section 2's relaxed
  endpoint), read-only for end users — no user-facing release/capture action, since those
  remain semantically administrative/merchant-side actions in this platform's existing
  design (a hold is created and released by the party initiating the hold flow, not by the
  account holder browsing their own holds).
- **`/app/deposits` (Deposits & Withdrawals)**: a form to trigger a simulated external
  deposit (`POST /simulator/deposits`), polling `GET /external-deposits/{ref}` until
  `CREDITED`; a form to initiate a withdrawal (posts an ordinary
  `POST /transactions` with `transactionType=WITHDRAWAL_EXTERNAL`), with a status panel
  polling the withdrawal's resolution (the account's own transaction history will show the
  debit immediately; a "simulate confirm/fail" control lets the user resolve their own
  pending withdrawal via `POST /simulator/withdrawals/by-transaction/{sourceTransactionId}/confirm`,
  since this platform has no separate operator role driving that resolution — the same
  control that chaos scenario 9 and the smoke test already use).

## Section 4 — Admin console (`/admin/*`)

- **`/admin` (Accounts, landing page)**: `GET /accounts?accountRef=&status=` list/search,
  row click-through to `GET /accounts/{accountRef}` detail.
- **`/admin/transactions` (Transactions)**: `GET /transactions?accountRef=&status=&since=&until=`
  list/search, detail view (`GET /transactions/{id}`, showing entries), and a "Reverse"
  action on any transaction not already reversed and not itself a reversal — calls
  `POST /transactions/{id}/reverse` behind a confirmation dialog (a money-moving action;
  the dialog states plainly that this posts a new compensating transaction, consistent with
  the backend's own irreversible-mutation design).
- **`/admin/holds` (Holds)**: `GET /holds?accountRef=&status=` list/search (the unscoped
  admin form of Section 2's relaxed endpoint), a "Release" action per active hold
  (`POST /holds/{id}/release`).
- **`/admin/reconciliation` (Reconciliation)**: `GET /reconciliation/runs` history table, a
  "Run now" button (`POST /reconciliation/runs`) that triggers a run and refreshes the list.
- **Metrics**: not a page inside this app — a nav link opening the existing Grafana instance
  (`http://localhost:3000`) in a new tab. Rebuilding metrics visualization here would
  duplicate infrastructure that already exists and is already reviewed/working.

**Landing/routing rule**: on successful login, a caller whose JWT carries the `admin` role
lands on `/admin` (with a visible "View my account" link to `/app`, since the seeded `admin`
user also carries the `user` role per the Admin API Additions design); a caller without the
`admin` role lands on `/app` and never sees any admin navigation entry. Attempting to
navigate directly to an `/admin/*` route without the `admin` role redirects to `/app` with a
brief "not authorized" notice, rather than showing a broken or empty admin page.

## Section 5 — Shared Concerns

**API client**: one module wrapping `fetch`, responsible for: attaching
`Authorization: Bearer <token>` to every request; parsing the backend's existing error-body
shape (`{"error": "message"}`) uniformly; on `401`, triggering re-authentication (redirect to
Keycloak login) rather than surfacing a raw error, since a `401` here always means the access
token has expired past what silent renewal caught; on `403`, rendering a clear
"you don't have access to this" state distinct from a generic error (this is a legitimate,
expected outcome — e.g. a `user`-role caller whose token somehow reaches an admin-gated
route — not a bug to alarm the user about).

**State/data fetching**: React Query (TanStack Query) for server-state caching, request
deduplication, and polling (used by the deposit/withdrawal status panels and the reconciliation
run list) — avoids hand-rolling loading/error/refetch logic per page.

**Styling**: Tailwind CSS with a small shared token set (a handful of semantic color/spacing
choices) rather than a full design-system dependency, kept intentionally light for a project
this size.

**Testing**: Vitest + React Testing Library for the shared API client, the auth
context/role-routing logic, and the transfer/reversal forms (the pieces with real logic
worth protecting from regression) — not full page-level snapshot tests for every screen.

## Verification

- `npm run build` produces a clean production bundle with no TypeScript errors.
- The full Docker Compose stack (17 containers including `web`) comes up healthy.
- Manual walkthrough: log in as `alice`, land on `/app`, complete a transfer, see it in
  history, view holds, simulate a deposit and a withdrawal end-to-end. Log in as `admin`,
  land on `/admin`, search accounts, view and reverse a transaction, search/release a hold,
  trigger a reconciliation run, follow the Grafana link. Attempt to navigate to `/admin` as
  `alice` and confirm the redirect/notice behavior.
- README updated documenting the new `web` service, its port, and the new `ledger-web`
  Keycloak client.
