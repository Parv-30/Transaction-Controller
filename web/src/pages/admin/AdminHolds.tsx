import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input } from '../../components/Input';
import { MoneyAmount } from '../../components/MoneyAmount';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import type { HoldSummary } from '../../api/types';

export function AdminHolds() {
  const [accountRefFilter, setAccountRefFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();

  const holdsQuery = useQuery({
    queryKey: ['admin-holds', accountRefFilter, statusFilter],
    queryFn: () => {
      const params = new URLSearchParams();
      if (accountRefFilter) params.set('accountRef', accountRefFilter);
      if (statusFilter) params.set('status', statusFilter);
      return fetchWithAuth<HoldSummary[]>(`/holds?${params.toString()}`);
    },
  });

  const releaseMutation = useMutation({
    mutationFn: (holdId: string) => fetchWithAuth(`/holds/${holdId}/release`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-holds'] }),
  });

  return (
    <AdminLayout>
      <Card title="Holds">
        <div className="mb-4 flex gap-4">
          <Input label="Account ref" value={accountRefFilter} onChange={(e) => setAccountRefFilter(e.target.value)} />
          <Input label="Status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} />
        </div>
        {holdsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<HoldSummary>
            columns={[
              { header: 'Account', render: (h) => h.accountRef },
              { header: 'Amount', render: (h) => <MoneyAmount amountMinor={h.amountMinor} currency={h.currency} /> },
              {
                header: 'Status',
                render: (h) => <Badge status={h.status === 'ACTIVE' ? 'active' : 'neutral'}>{h.status.toLowerCase()}</Badge>,
              },
              {
                header: 'Actions',
                render: (h) =>
                  h.status === 'ACTIVE' ? (
                    <Button variant="secondary" onClick={() => releaseMutation.mutate(h.holdId)}>
                      Release
                    </Button>
                  ) : null,
              },
            ]}
            rows={holdsQuery.data ?? []}
            keyField={(h) => h.holdId}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
