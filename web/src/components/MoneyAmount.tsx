interface MoneyAmountProps {
  amountMinor: number;
  currency: string;
  size?: 'hero' | 'large' | 'normal';
}

const sizeClasses: Record<NonNullable<MoneyAmountProps['size']>, string> = {
  hero: 'text-4xl font-bold',
  large: 'text-2xl font-semibold',
  normal: 'text-base font-medium',
};

export function MoneyAmount({ amountMinor, currency, size = 'normal' }: MoneyAmountProps) {
  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
  }).format(amountMinor / 100);
  return <span className={sizeClasses[size]}>{formatted}</span>;
}
