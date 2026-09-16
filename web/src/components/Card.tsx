import type { ReactNode } from 'react';

interface CardProps {
  title?: string;
  children: ReactNode;
  className?: string;
}

export function Card({ title, children, className = '' }: CardProps) {
  return (
    <div
      className={`rounded-card border border-ink-light/10 bg-surface-light p-6 shadow-sm dark:border-ink-dark/10 dark:bg-surface-dark-alt ${className}`}
    >
      {title ? <h3 className="mb-4 text-sm font-medium text-ink-light/60 dark:text-ink-dark/60">{title}</h3> : null}
      {children}
    </div>
  );
}
