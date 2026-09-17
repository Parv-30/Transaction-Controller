import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders nothing when closed', () => {
    render(
      <ConfirmDialog isOpen={false} title="Reverse transaction" body="Are you sure?" confirmLabel="Reverse" onConfirm={vi.fn()} onCancel={vi.fn()} />,
    );
    expect(screen.queryByText('Reverse transaction')).not.toBeInTheDocument();
  });

  it('calls onConfirm when the confirm button is clicked', async () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog isOpen title="Reverse transaction" body="This posts a new compensating transaction." confirmLabel="Reverse" onConfirm={onConfirm} onCancel={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));

    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('calls onCancel when the cancel button is clicked', async () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog isOpen title="Reverse transaction" body="This posts a new compensating transaction." confirmLabel="Reverse" onConfirm={vi.fn()} onCancel={onCancel} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));

    expect(onCancel).toHaveBeenCalledOnce();
  });
});
