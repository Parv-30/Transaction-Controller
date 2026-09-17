import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { useHolds } from '../../hooks/useHolds';
import type { HoldSummary } from '../../api/types';

const CURRENT_ACCOUNT_REF = 'alice-usd';

export function Holds() {
  const holdsQuery = useHolds(CURRENT_ACCOUNT_REF);

  return (
    <AppLayout>
      <Card title="Holds">
        {holdsQuery.isLoading ? (
          <Skeleton className="h-48 w-full" />
        ) : (
          <Table<HoldSummary>
            columns={[
              { header: 'Amount', render: (h) => <MoneyAmount amountMinor={h.amountMinor} currency={h.currency} /> },
              { header: 'Destination', render: (h) => h.destinationAccountRef },
              {
                header: 'Status',
                render: (h) => <Badge status={h.status === 'ACTIVE' ? 'active' : 'neutral'}>{h.status.toLowerCase()}</Badge>,
              },
              { header: 'Expires', render: (h) => new Date(h.expiresAt).toLocaleString() },
            ]}
            rows={holdsQuery.data ?? []}
            keyField={(h) => h.holdId}
          />
        )}
      </Card>
    </AppLayout>
  );
}
