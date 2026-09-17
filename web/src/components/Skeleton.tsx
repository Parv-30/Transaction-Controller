export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-ink-light/10 dark:bg-ink-dark/10 ${className}`} />;
}
