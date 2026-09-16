import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';
import { Card } from '../../components/Card';
import { Input } from '../../components/Input';
import { Button } from '../../components/Button';
import { useApiFetch } from '../../api/client';
import { useToast } from '../../components/Toast';
import type { ConversionQuote } from '../../api/types';

const SOURCE_ACCOUNT_REF = 'alice-usd';

export function Transfer() {
  const [destinationAccountRef, setDestinationAccountRef] = useState('');
  const [amount, setAmount] = useState('');
  const [crossCurrency, setCrossCurrency] = useState(false);
  const [destCurrency, setDestCurrency] = useState('EUR');
  const [quote, setQuote] = useState<ConversionQuote | null>(null);
  const fetchWithAuth = useApiFetch();
  const { showToast } = useToast();

  const amountMinor = Math.round(Number(amount) * 100);
  const amountError = amount.length > 0 && amountMinor <= 0 ? 'Amount must be greater than zero' : undefined;
  const canSubmit = destinationAccountRef.length > 0 && amountMinor > 0;

  const quoteMutation = useMutation({
    mutationFn: () =>
      fetchWithAuth<ConversionQuote>('/conversions/quote', {
        method: 'POST',
        body: JSON.stringify({
          sourceAmountMinor: amountMinor,
          sourceCurrency: 'USD',
          destCurrency,
        }),
      }),
    onSuccess: (data) => setQuote(data),
    onError: () => showToast('Could not fetch a quote for this transfer.', 'error'),
  });

  const transferMutation = useMutation({
    mutationFn: () => {
      if (crossCurrency) {
        return fetchWithAuth('/transfers/cross-currency', {
          method: 'POST',
          body: JSON.stringify({
            sourceAccountRef: SOURCE_ACCOUNT_REF,
            destAccountRef: destinationAccountRef,
            sourceAmountMinor: amountMinor,
            idempotencyKey: crypto.randomUUID(),
          }),
        });
      }
      return fetchWithAuth('/transactions', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          debitAccountRef: SOURCE_ACCOUNT_REF,
          creditAccountRef: destinationAccountRef,
          amountMinor,
          currency: 'USD',
          description: 'Send money',
        }),
      });
    },
    onSuccess: () => {
      showToast('Transfer sent.', 'success');
      setDestinationAccountRef('');
      setAmount('');
      setQuote(null);
    },
    onError: () => showToast('Transfer failed.', 'error'),
  });

  return (
    <AppLayout>
      <Card title="Send money" className="max-w-md">
        <div className="space-y-4">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={crossCurrency} onChange={(e) => setCrossCurrency(e.target.checked)} />
            Cross-currency transfer
          </label>
          <Input
            label="Destination account"
            value={destinationAccountRef}
            onChange={(e) => setDestinationAccountRef(e.target.value)}
          />
          <Input label="Amount (USD)" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} error={amountError} />
          {crossCurrency ? (
            <Input label="Destination currency" value={destCurrency} onChange={(e) => setDestCurrency(e.target.value)} />
          ) : null}
          {crossCurrency && !quote ? (
            <Button variant="secondary" disabled={!canSubmit} onClick={() => quoteMutation.mutate()}>
              Get quote
            </Button>
          ) : null}
          {quote ? (
            <p className="text-sm text-ink-light/70 dark:text-ink-dark/70">
              Rate locked: {quote.sourceAmountMinor / 100} {quote.sourceCurrency} = {quote.destAmountMinor / 100}{' '}
              {quote.destCurrency}
            </p>
          ) : null}
          <Button
            onClick={() => transferMutation.mutate()}
            disabled={!canSubmit || (crossCurrency && !quote) || transferMutation.isPending}
          >
            Send
          </Button>
        </div>
      </Card>
    </AppLayout>
  );
}
