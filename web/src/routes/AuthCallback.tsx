import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { UserManager } from 'oidc-client-ts';
import { oidcConfig } from '../auth/oidcConfig';
import { decodeJwtPayload } from '../auth/AuthProvider';
import { landingRouteFor } from '../auth/roles';

export function AuthCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    const userManager = new UserManager(oidcConfig);
    userManager.signinRedirectCallback().then((user) => {
      // Roles live in the ACCESS token's JWT payload, not the ID token (`user.profile`).
      // See AuthProvider.tsx's `decodeJwtPayload`/`rolesFromUser` for the full explanation,
      // confirmed against a real Keycloak PKCE flow in Task 5.
      const payload = decodeJwtPayload(user.access_token);
      const realmAccess = payload?.realm_access as { roles?: string[] } | undefined;
      navigate(landingRouteFor(realmAccess?.roles ?? []), { replace: true });
    });
  }, [navigate]);

  return <div className="flex min-h-screen items-center justify-center">Completing sign-in…</div>;
}
