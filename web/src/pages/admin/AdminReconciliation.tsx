import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from './AdminLayout';
import { Card } from '../../components/Card';
import { Table } from '../../components/Table';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Skeleton } from '../../components/Skeleton';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';
import type { ReconciliationRun } from '../../api/types';

export function AdminReconciliation() {
  const fetchWithAuth = useApiFetch();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const runsQuery = useQuery({
    queryKey: ['reconciliation-runs'],
    queryFn: () => fetchWithAuth<ReconciliationRun[]>('/reconciliation/runs'),
    refetchInterval: 5000,
  });

  const triggerMutation = useMutation({
    mutationFn: () => fetchWithAuth('/reconciliation/runs', { method: 'POST' }),
    onSuccess: () => {
      showToast('Reconciliation run triggered.', 'success');
      queryClient.invalidateQueries({ queryKey: ['reconciliation-runs'] });
    },
  });

  return (
    <AdminLayout>
      <Card title="Reconciliation runs">
        <Button className="mb-4" onClick={() => triggerMutation.mutate()} disabled={triggerMutation.isPending}>
          Run now
        </Button>
        {runsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <Table<ReconciliationRun>
            columns={[
              { header: 'Started', render: (r) => new Date(r.startedAt).toLocaleString() },
              {
                header: 'Status',
                render: (r) => {
                  // ReconciliationRun.Status is RUNNING | COMPLETED | FAILED — there is no
                  // "CLEAN" value. A run is only genuinely clean when it COMPLETED with zero
                  // imbalances/missing/stuck rows; a COMPLETED run that found problems is a
                  // real finding, not a clean bill of health, so it must not read as "success".
                  const isClean =
                    r.status === 'COMPLETED' &&
                    r.entriesImbalanceCount === 0 &&
                    r.outboxMissingCount === 0 &&
                    r.outboxStuckCount === 0;
                  const badgeStatus = r.status === 'FAILED' ? 'error' : isClean ? 'success' : 'warning';
                  const label = r.status === 'RUNNING' ? 'running' : isClean ? 'clean' : r.status.toLowerCase();
                  return <Badge status={badgeStatus}>{label}</Badge>;
                },
              },
              { header: 'Checked', render: (r) => r.transactionsChecked },
              { header: 'Imbalances', render: (r) => r.entriesImbalanceCount },
              { header: 'Outbox missing', render: (r) => r.outboxMissingCount },
              { header: 'Outbox stuck', render: (r) => r.outboxStuckCount },
            ]}
            rows={runsQuery.data ?? []}
            keyField={(r) => r.runId}
          />
        )}
      </Card>
    </AdminLayout>
  );
}
