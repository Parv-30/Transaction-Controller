import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Transfer } from './Transfer';
import { ToastProvider } from '../../components/Toast';

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: { access_token: 'token' }, roles: ['user'], logout: vi.fn() }),
}));

function renderTransfer() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ToastProvider>
          <Transfer />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Transfer', () => {
  it('disables the submit button until a destination account and amount are entered', async () => {
    renderTransfer();

    const submitButton = screen.getByRole('button', { name: /send/i });
    expect(submitButton).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/destination account/i), 'bob-usd');
    await userEvent.type(screen.getByLabelText(/amount/i), '10.00');

    expect(submitButton).toBeEnabled();
  });

  it('rejects a zero or negative amount', async () => {
    renderTransfer();

    await userEvent.type(screen.getByLabelText(/destination account/i), 'bob-usd');
    await userEvent.type(screen.getByLabelText(/amount/i), '0');

    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled();
    expect(screen.getByText(/amount must be greater than zero/i)).toBeInTheDocument();
  });
});
