import { useState } from 'react';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Badge } from '../../components/Badge';
import { Skeleton } from '../../components/Skeleton';
import { useRecentTransactions } from '../../hooks/useRecentTransactions';
import { useTransactionDetail } from '../../hooks/useTransactionDetail';
import type { TransactionSummary } from '../../api/types';

const CURRENT_ACCOUNT_REF = 'alice-usd';

function statusBadge(status: string) {
  if (status === 'POSTED') return <Badge status="success">posted</Badge>;
  if (status === 'REVERSED') return <Badge status="warning">reversed</Badge>;
  return <Badge status="neutral">{status.toLowerCase()}</Badge>;
}

export function History() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const transactionsQuery = useRecentTransactions(CURRENT_ACCOUNT_REF, 100);
  const detailQuery = useTransactionDetail(selectedId);

  return (
    <AppLayout>
      <Card title="Transaction history">
        {transactionsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<TransactionSummary>
            columns={[
              { header: 'Description', render: (t) => t.description },
              { header: 'Amount', render: (t) => <MoneyAmount amountMinor={t.amountMinor} currency={t.currency} /> },
              { header: 'Status', render: (t) => statusBadge(t.status) },
              { header: 'Date', render: (t) => new Date(t.createdAt).toLocaleString() },
            ]}
            rows={transactionsQuery.data ?? []}
            keyField={(t) => t.transactionId}
            onRowClick={(t) => setSelectedId(t.transactionId)}
          />
        )}
      </Card>
      {selectedId ? (
        <Card title="Transaction detail" className="mt-6">
          {detailQuery.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : detailQuery.data ? (
            <ul className="space-y-2 text-sm">
              {detailQuery.data.entries.map((entry) => (
                <li key={entry.accountId} className="flex justify-between">
                  <span>
                    {entry.accountRef} ({entry.direction.toLowerCase()})
                  </span>
                  <MoneyAmount amountMinor={entry.amountMinor} currency={entry.currency} />
                </li>
              ))}
            </ul>
          ) : null}
        </Card>
      ) : null}
    </AppLayout>
  );
}
