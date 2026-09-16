import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Input } from '../../components/Input';
import { Button } from '../../components/Button';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';

const CURRENT_ACCOUNT_REF = 'alice-usd';

interface DepositResponse {
  externalReference: string;
  status: string;
}

interface DepositStatus {
  status: 'RECEIVED' | 'CREDITED' | 'REJECTED';
}

interface WithdrawalTransactionResponse {
  transactionId: string;
}

export function Deposits() {
  const [depositAmount, setDepositAmount] = useState('');
  const [withdrawalAmount, setWithdrawalAmount] = useState('');
  const [depositRef, setDepositRef] = useState<string | null>(null);
  const [withdrawalTransactionId, setWithdrawalTransactionId] = useState<string | null>(null);
  const fetchWithAuth = useApiFetch();
  const { showToast } = useToast();

  const depositMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<DepositResponse>('/simulator/deposits', {
        method: 'POST',
        body: JSON.stringify({ accountRef: CURRENT_ACCOUNT_REF, amountMinor: Math.round(Number(depositAmount) * 100), currency: 'USD' }),
      }),
    onSuccess: (data) => setDepositRef(data.externalReference),
  });

  const depositStatusQuery = useQuery({
    queryKey: ['deposit-status', depositRef],
    queryFn: () => fetchWithAuth<DepositStatus>(`/external-deposits/${depositRef}`),
    enabled: Boolean(depositRef),
    refetchInterval: (query) => (query.state.data?.status === 'CREDITED' ? false : 2000),
  });

  const withdrawalMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<WithdrawalTransactionResponse>('/transactions', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          debitAccountRef: CURRENT_ACCOUNT_REF,
          creditAccountRef: 'external-clearing-USD',
          amountMinor: Math.round(Number(withdrawalAmount) * 100),
          currency: 'USD',
          description: 'Withdrawal',
          transactionType: 'WITHDRAWAL_EXTERNAL',
        }),
      }),
    onSuccess: (data) => setWithdrawalTransactionId(data.transactionId),
  });

  const confirmMutation = useMutation({
    mutationFn: (outcome: 'CONFIRMED' | 'FAILED') =>
      fetchWithAuth(`/simulator/withdrawals/by-transaction/${withdrawalTransactionId}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ outcome }),
      }),
    onSuccess: () => showToast('Withdrawal resolved.', 'success'),
    onError: () => showToast('Could not resolve the withdrawal yet — try again shortly.', 'error'),
  });

  return (
    <AppLayout>
      <div className="grid gap-6 md:grid-cols-2">
        <Card title="Simulate a deposit">
          <div className="space-y-4">
            <Input label="Amount (USD)" type="number" value={depositAmount} onChange={(e) => setDepositAmount(e.target.value)} />
            <Button onClick={() => depositMutation.mutate()} disabled={!depositAmount || depositMutation.isPending}>
              Deposit
            </Button>
            {depositRef ? (
              <p className="text-sm text-ink-light/70 dark:text-ink-dark/70">
                Status: {depositStatusQuery.data?.status ?? 'checking…'}
              </p>
            ) : null}
          </div>
        </Card>
        <Card title="Withdraw funds">
          <div className="space-y-4">
            <Input label="Amount (USD)" type="number" value={withdrawalAmount} onChange={(e) => setWithdrawalAmount(e.target.value)} />
            <Button onClick={() => withdrawalMutation.mutate()} disabled={!withdrawalAmount || withdrawalMutation.isPending}>
              Withdraw
            </Button>
            {withdrawalTransactionId ? (
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => confirmMutation.mutate('CONFIRMED')}>
                  Simulate confirm
                </Button>
                <Button variant="danger" onClick={() => confirmMutation.mutate('FAILED')}>
                  Simulate fail
                </Button>
              </div>
            ) : null}
          </div>
        </Card>
      </div>
    </AppLayout>
  );
}
