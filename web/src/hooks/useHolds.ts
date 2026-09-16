import { useQuery } from '@tanstack/react-query';
import { useApiFetch } from '../api/client';
import type { HoldSummary } from '../api/types';

export function useHolds(accountRef: string) {
  const fetchWithAuth = useApiFetch();
  return useQuery({
    queryKey: ['holds', accountRef],
    queryFn: () => fetchWithAuth<HoldSummary[]>(`/holds?accountRef=${accountRef}`),
    enabled: Boolean(accountRef),
  });
}
