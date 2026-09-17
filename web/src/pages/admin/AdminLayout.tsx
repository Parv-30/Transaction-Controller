import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';

const navItems = [
  { to: '/admin', label: 'Accounts' },
  { to: '/admin/transactions', label: 'Transactions' },
  { to: '/admin/holds', label: 'Holds' },
  { to: '/admin/reconciliation', label: 'Reconciliation' },
];

export function AdminLayout({ children }: { children: ReactNode }) {
  const { logout } = useAuth();
  const location = useLocation();

  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b border-ink-light/10 px-6 py-4 dark:border-ink-dark/10">
        <nav className="flex items-center gap-6">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`text-sm font-medium ${location.pathname === item.to ? 'text-accent' : 'text-ink-light/60 dark:text-ink-dark/60'}`}
            >
              {item.label}
            </Link>
          ))}
          <a href="http://localhost:3000" target="_blank" rel="noreferrer" className="text-sm text-ink-light/60 dark:text-ink-dark/60">
            Metrics ↗
          </a>
          <Link to="/app" className="text-sm text-ink-light/60 dark:text-ink-dark/60">
            View my account
          </Link>
        </nav>
        <button onClick={logout} className="text-sm text-ink-light/60 dark:text-ink-dark/60">
          Log out
        </button>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">{children}</main>
    </div>
  );
}
