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
    // realm_access.roles lives on the ACCESS TOKEN's own JWT payload for this
    // platform's Keycloak realm, not on the ID-token-derived `profile` (see
    // AuthProvider.tsx's rolesFromUser comment and task-5-report.md for the
    // live verification behind this). The access_token below is a real
    // base64url-encoded JWT-shaped token carrying that claim, decoded by
    // rolesFromUser via decodeJwtPayload.
    mockGetUser.mockResolvedValue({
      access_token:
        'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsidXNlciIsImFkbWluIl19LCJzdWIiOiJ0ZXN0LXVzZXIifQ.sig',
      profile: {},
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
