import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminTransactions } from './AdminTransactions';

const mockFetchWithAuth = vi.fn();
vi.mock('../../api/client', () => ({
  useApiFetch: () => mockFetchWithAuth,
}));

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: { access_token: 'token' }, roles: ['admin'], logout: vi.fn() }),
}));

beforeEach(() => {
  mockFetchWithAuth.mockReset();
});

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminTransactions />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminTransactions reversal flow', () => {
  it('shows a confirmation dialog before reversing, and only calls the reverse endpoint on confirm', async () => {
    mockFetchWithAuth.mockResolvedValue([
      {
        transactionId: 'tx-1',
        status: 'POSTED',
        transactionType: 'TRANSFER',
        debitAccountRef: 'acct-a',
        creditAccountRef: 'acct-b',
        amountMinor: 1000,
        currency: 'USD',
        description: 'test transfer',
        createdAt: new Date().toISOString(),
        reversalOfTransactionId: null,
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('test transfer')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /reverse/i }));

    expect(screen.getByText(/posts a new compensating transaction/i)).toBeInTheDocument();
    expect(mockFetchWithAuth).not.toHaveBeenCalledWith(expect.stringContaining('/reverse'), expect.anything());

    const dialogHeading = screen.getByRole('heading', { name: 'Reverse transaction' });
    const dialog = dialogHeading.parentElement!;
    mockFetchWithAuth.mockResolvedValueOnce({ transactionId: 'tx-2' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reverse' }));

    await waitFor(() =>
      expect(mockFetchWithAuth).toHaveBeenCalledWith('/transactions/tx-1/reverse', expect.objectContaining({ method: 'POST' })),
    );
  });

  it('does not show a reverse action for a transaction that is already REVERSED', async () => {
    mockFetchWithAuth.mockResolvedValue([
      {
        transactionId: 'tx-3',
        status: 'REVERSED',
        transactionType: 'TRANSFER',
        debitAccountRef: 'acct-a',
        creditAccountRef: 'acct-b',
        amountMinor: 500,
        currency: 'USD',
        description: 'already reversed',
        createdAt: new Date().toISOString(),
        reversalOfTransactionId: null,
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('already reversed')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /^reverse$/i })).not.toBeInTheDocument();
  });
});
