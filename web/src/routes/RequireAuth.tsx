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
