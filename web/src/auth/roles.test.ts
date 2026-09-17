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
