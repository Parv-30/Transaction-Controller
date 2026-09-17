export interface AvailableBalanceResponse {
  accountRef: string;
  postedBalanceMinor: number;
  heldBalanceMinor: number;
  availableBalanceMinor: number;
}

export interface TransactionSummary {
  transactionId: string;
  status: string;
  transactionType: string;
  debitAccountRef: string;
  creditAccountRef: string;
  amountMinor: number;
  currency: string;
  description: string;
  createdAt: string;
  reversalOfTransactionId: string | null;
}

export interface EntryDto {
  accountId: string;
  accountRef: string;
  direction: 'DEBIT' | 'CREDIT';
  amountMinor: number;
  currency: string;
}

export interface TransactionDetail {
  transactionId: string;
  status: string;
  transactionType: string;
  description: string;
  createdAt: string;
  reversalOfTransactionId: string | null;
  entries: EntryDto[];
}

export interface HoldSummary {
  holdId: string;
  accountRef: string;
  destinationAccountRef: string;
  amountMinor: number;
  currency: string;
  status: string;
  expiresAt: string;
}

export interface AccountSummary {
  accountRef: string;
  currency: string;
  status: string;
}

export interface ReconciliationRun {
  runId: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  transactionsChecked: number;
  entriesImbalanceCount: number;
  outboxMissingCount: number;
  outboxStuckCount: number;
}

export interface ConversionQuote {
  sourceAmountMinor: number;
  sourceCurrency: string;
  destAmountMinor: number;
  destCurrency: string;
  rate: number;
  expiresAt: string;
}
