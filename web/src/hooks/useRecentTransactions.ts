import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { TransactionSummary } from '../api/types';

export function useRecentTransactions(accountRef: string, limit = 5) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['transactions', accountRef],
    queryFn: () => fetchWithAuth<TransactionSummary[]>(`/transactions?accountRef=${accountRef}`),
    enabled: Boolean(accountRef),
    select: (transactions) =>
      [...transactions]
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
        .slice(0, limit),
  });
}
