export function isAdmin(roles: string[]): boolean {
  return roles.includes('admin');
}

export function landingRouteFor(roles: string[]): '/admin' | '/app' {
  return isAdmin(roles) ? '/admin' : '/app';
}
