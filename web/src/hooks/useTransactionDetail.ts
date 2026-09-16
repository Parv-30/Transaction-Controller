import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { TransactionDetail } from '../api/types';

export function useTransactionDetail(transactionId: string | null) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['transaction', transactionId],
    queryFn: () => fetchWithAuth<TransactionDetail>(`/transactions/${transactionId}`),
    enabled: Boolean(transactionId),
  });
}
