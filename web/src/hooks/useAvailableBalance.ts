import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { AvailableBalanceResponse } from '../api/types';

export function useAvailableBalance(accountRef: string) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['available-balance', accountRef],
    queryFn: () => fetchWithAuth<AvailableBalanceResponse>(`/accounts/${accountRef}/available-balance`),
    enabled: Boolean(accountRef),
  });
}
