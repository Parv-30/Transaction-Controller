import { useLocation } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { NotAuthorizedNotice } from '../NotAuthorized';
import { useAvailableBalance } from '../../hooks/useAvailableBalance';
import { useRecentTransactions } from '../../hooks/useRecentTransactions';
import { useHolds } from '../../hooks/useHolds';

const CURRENT_ACCOUNT_REF = 'alice-usd';

export function Dashboard() {
  const location = useLocation();
  const notAuthorized = Boolean((location.state as { notAuthorized?: boolean } | null)?.notAuthorized);
  const balanceQuery = useAvailableBalance(CURRENT_ACCOUNT_REF);
  const transactionsQuery = useRecentTransactions(CURRENT_ACCOUNT_REF, 5);
  const holdsQuery = useHolds(CURRENT_ACCOUNT_REF);

  return (
    <AppLayout>
      {notAuthorized ? <NotAuthorizedNotice /> : null}
      <div className="grid gap-6 md:grid-cols-3">
        <Card title="Balance" className="md:col-span-1">
          {balanceQuery.isLoading ? (
            <Skeleton className="h-10 w-32" />
          ) : balanceQuery.data ? (
            <div className="space-y-2">
              <MoneyAmount amountMinor={balanceQuery.data.availableBalanceMinor} currency="USD" size="hero" />
              <p className="text-sm text-ink-light/60 dark:text-ink-dark/60">available</p>
              <p className="text-sm text-ink-light/60 dark:text-ink-dark/60">
                Posted: <MoneyAmount amountMinor={balanceQuery.data.postedBalanceMinor} currency="USD" />
              </p>
            </div>
          ) : (
            <p className="text-sm text-danger">Could not load balance.</p>
          )}
        </Card>
        <Card title="Recent activity" className="md:col-span-1">
          {transactionsQuery.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <ul className="space-y-3">
              {(transactionsQuery.data ?? []).map((transaction) => (
                <li key={transaction.transactionId} className="flex justify-between text-sm">
                  <span className="text-ink-light/70 dark:text-ink-dark/70">{transaction.description}</span>
                  <MoneyAmount amountMinor={transaction.amountMinor} currency={transaction.currency} />
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Active holds" className="md:col-span-1">
          {holdsQuery.isLoading ? (
            <Skeleton className="h-10 w-16" />
          ) : (
            <p className="text-2xl font-semibold">
              {(holdsQuery.data ?? []).filter((hold) => hold.status === 'ACTIVE').length}
            </p>
          )}
        </Card>
      </div>
    </AppLayout>
  );
}
