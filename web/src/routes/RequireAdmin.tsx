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
