import type { InputHTMLAttributes } from 'react';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
}

export function Input({ label, error, id, className = '', ...props }: InputProps) {
  const inputId = id ?? label.toLowerCase().replace(/\s+/g, '-');
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-medium text-ink-light/70 dark:text-ink-dark/70">
        {label}
      </label>
      <input
        id={inputId}
        className={`rounded-lg border border-ink-light/15 bg-surface-light px-3 py-2 text-sm text-ink-light focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent dark:border-ink-dark/15 dark:bg-surface-dark-alt dark:text-ink-dark ${className}`}
        {...props}
      />
      {error ? <span className="text-sm text-danger">{error}</span> : null}
    </div>
  );
}
