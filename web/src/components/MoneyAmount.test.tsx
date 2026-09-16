import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MoneyAmount } from './MoneyAmount';

describe('MoneyAmount', () => {
  it('formats minor units as a decimal amount with the currency symbol', () => {
    render(<MoneyAmount amountMinor={150000} currency="USD" />);
    expect(screen.getByText('$1,500.00')).toBeInTheDocument();
  });

  it('formats EUR with the euro symbol', () => {
    render(<MoneyAmount amountMinor={999} currency="EUR" />);
    expect(screen.getByText('€9.99')).toBeInTheDocument();
  });

  it('renders negative amounts with a leading minus sign', () => {
    render(<MoneyAmount amountMinor={-2500} currency="USD" />);
    expect(screen.getByText('-$25.00')).toBeInTheDocument();
  });

  it('applies the hero size class for size="hero"', () => {
    render(<MoneyAmount amountMinor={100} currency="USD" size="hero" />);
    expect(screen.getByText('$1.00')).toHaveClass('text-4xl');
  });
});
