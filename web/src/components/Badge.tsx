import type { ReactNode } from 'react';

type BadgeStatus = 'active' | 'success' | 'warning' | 'error' | 'neutral';

interface BadgeProps {
  status: BadgeStatus;
  children: ReactNode;
}

const statusClasses: Record<BadgeStatus, string> = {
  active: 'bg-accent/10 text-accent',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  error: 'bg-danger/10 text-danger',
  neutral: 'bg-ink-light/10 text-ink-light/70 dark:bg-ink-dark/10 dark:text-ink-dark/70',
};

export function Badge({ status, children }: BadgeProps) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${statusClasses[status]}`}>
      {children}
    </span>
  );
}
