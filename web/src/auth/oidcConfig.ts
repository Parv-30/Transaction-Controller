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
