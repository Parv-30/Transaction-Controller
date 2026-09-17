import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Input } from '../../components/Input';
import { Badge } from '../../components/Badge';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import type { AccountSummary } from '../../api/types';

export function AdminAccounts() {
  const [accountRefFilter, setAccountRefFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const fetchWithAuth = useApiFetch();

  const accountsQuery = useQuery({
    queryKey: ['admin-accounts', accountRefFilter, statusFilter],
    queryFn: () => {
      const params = new URLSearchParams();
      if (accountRefFilter) params.set('accountRef', accountRefFilter);
      if (statusFilter) params.set('status', statusFilter);
      return fetchWithAuth<AccountSummary[]>(`/accounts?${params.toString()}`);
    },
  });

  return (
    <AdminLayout>
      <Card title="Accounts">
        <div className="mb-4 flex gap-4">
          <Input label="Account ref" value={accountRefFilter} onChange={(e) => setAccountRefFilter(e.target.value)} />
          <Input label="Status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} />
        </div>
        {accountsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : accountsQuery.error ? (
          <p className="text-sm text-danger">Could not load accounts.</p>
        ) : (
          <Table<AccountSummary>
            columns={[
              { header: 'Account ref', render: (a) => a.accountRef },
              { header: 'Currency', render: (a) => a.currency },
              {
                header: 'Status',
                render: (a) => <Badge status={a.status === 'ACTIVE' ? 'active' : 'neutral'}>{a.status.toLowerCase()}</Badge>,
              },
            ]}
            rows={accountsQuery.data ?? []}
            keyField={(a) => a.accountRef}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
