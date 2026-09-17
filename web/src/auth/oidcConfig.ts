import { InMemoryWebStorage, WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts';

const keycloakBaseUrl = import.meta.env.VITE_KEYCLOAK_BASE_URL ?? 'http://localhost:8180';

// Security requirement (design spec Section 3 / plan Global Constraints): the OIDC
// access token must be held in memory only, never persisted to `sessionStorage` or
// `localStorage`, to reduce XSS-exfiltration surface. `oidc-client-ts`'s `UserManager`
// defaults `userStore` to `window.sessionStorage`, which would persist the entire
// `User` object -- including the raw `access_token` -- into storage that's exactly as
// readable by an XSS payload as `localStorage`. Overriding `userStore` with a
// `WebStorageStateStore` backed by `InMemoryWebStorage` (a plain in-memory object,
// exported by oidc-client-ts itself) keeps the persisted-user store scoped to this
// page's JS heap: a refresh or tab close naturally clears it, requiring a fresh login.
//
// Note: this does NOT affect `stateStore` (left at its default). `stateStore` only
// holds the transient PKCE `code_verifier`/`state` for the signin-redirect round trip
// to Keycloak and back -- short-lived, cleared immediately after use, and never
// contains the access token -- so it's a separate, much lower-risk concern.
const inMemoryUserStore = new WebStorageStateStore({ store: new InMemoryWebStorage() });

export const oidcConfig: UserManagerSettings = {
  authority: `${keycloakBaseUrl}/realms/ledger`,
  client_id: 'ledger-web',
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  loadUserInfo: false,
  userStore: inMemoryUserStore,
};
