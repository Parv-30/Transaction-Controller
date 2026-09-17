import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { MoneyAmount } from '../../components/MoneyAmount';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import { useTransactionDetail } from '../../hooks/useTransactionDetail';
import type { TransactionSummary } from '../../api/types';

function statusBadge(status: string) {
  if (status === 'POSTED') return <Badge status="success">posted</Badge>;
  if (status === 'REVERSED') return <Badge status="warning">reversed</Badge>;
  return <Badge status="neutral">{status.toLowerCase()}</Badge>;
}

export function AdminTransactions() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pendingReversalId, setPendingReversalId] = useState<string | null>(null);
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();
  const detailQuery = useTransactionDetail(selectedId);

  const transactionsQuery = useQuery({
    queryKey: ['admin-transactions'],
    queryFn: () => fetchWithAuth<TransactionSummary[]>('/transactions'),
  });

  const reverseMutation = useMutation({
    mutationFn: (transactionId: string) => fetchWithAuth(`/transactions/${transactionId}/reverse`, { method: 'POST' }),
    onSuccess: () => {
      setPendingReversalId(null);
      queryClient.invalidateQueries({ queryKey: ['admin-transactions'] });
    },
  });

  return (
    <AdminLayout>
      <Card title="Transactions">
        {transactionsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<TransactionSummary>
            columns={[
              { header: 'Description', render: (t) => t.description },
              { header: 'Amount', render: (t) => <MoneyAmount amountMinor={t.amountMinor} currency={t.currency} /> },
              { header: 'Status', render: (t) => statusBadge(t.status) },
              {
                header: 'Actions',
                render: (t) =>
                  t.status !== 'REVERSED' && t.transactionType !== 'REVERSAL' ? (
                    <Button
                      variant="danger"
                      onClick={(e) => {
                        e.stopPropagation();
                        setPendingReversalId(t.transactionId);
                      }}
                    >
                      Reverse
                    </Button>
                  ) : null,
              },
            ]}
            rows={transactionsQuery.data ?? []}
            keyField={(t) => t.transactionId}
            onRowClick={(t) => setSelectedId(t.transactionId)}
          />
        )}
      </Card>
      {selectedId ? (
        <Card title="Transaction detail" className="mt-6">
          {detailQuery.data ? (
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
      <ConfirmDialog
        isOpen={pendingReversalId !== null}
        title="Reverse transaction"
        body="This posts a new compensating transaction with the debit and credit legs swapped. It does not undo or delete the original transaction."
        confirmLabel="Reverse"
        onConfirm={() => pendingReversalId && reverseMutation.mutate(pendingReversalId)}
        onCancel={() => setPendingReversalId(null)}
      />
    </AdminLayout>
  );
}
