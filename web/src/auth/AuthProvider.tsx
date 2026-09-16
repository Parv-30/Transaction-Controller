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

// oidc-client-ts's `User.profile` is decoded from the ID TOKEN, not the access token.
// This platform's Keycloak realm attaches the built-in "roles" client scope's
// "realm roles" protocol mapper to every client by default, but that mapper's
// config only sets `access.token.claim` (and `introspection.token.claim`) --
// it does NOT set `id.token.claim`, so `realm_access.roles` is present on the
// access token but absent from the ID token. Confirmed against a real Keycloak
// instance via a genuine Authorization Code + PKCE flow against the `ledger-web`
// client (see task-5-report.md): the ID token payload has no `realm_access` key
// at all, while the access token's does. So roles must be decoded from the
// access token's own JWT payload, not from `user.profile`.
function rolesFromUser(user: User | null): string[] {
  if (!user) return [];
  const payload = decodeJwtPayload(user.access_token);
  const realmAccess = payload?.realm_access as { roles?: string[] } | undefined;
  return realmAccess?.roles ?? [];
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const segments = token.split('.');
  if (segments.length < 2) return null;
  try {
    const base64 = segments[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const json = atob(padded);
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
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
      userManager.events.removeUserLoaded?.(handleUserLoaded);
      userManager.events.removeUserUnloaded?.(handleUserUnloaded);
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
