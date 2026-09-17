import type { ReactNode } from 'react';

interface Column<T> {
  header: string;
  render: (row: T) => ReactNode;
}

interface TableProps<T> {
  columns: Column<T>[];
  rows: T[];
  keyField: (row: T) => string;
  onRowClick?: (row: T) => void;
}

export function Table<T>({ columns, rows, keyField, onRowClick }: TableProps<T>) {
  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="border-b border-ink-light/10 dark:border-ink-dark/10">
          {columns.map((col) => (
            <th key={col.header} className="px-4 py-2 font-medium text-ink-light/60 dark:text-ink-dark/60">
              {col.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={keyField(row)}
            onClick={() => onRowClick?.(row)}
            className={`border-b border-ink-light/5 dark:border-ink-dark/5 ${onRowClick ? 'cursor-pointer hover:bg-surface-light-alt dark:hover:bg-surface-dark' : ''}`}
          >
            {columns.map((col) => (
              <td key={col.header} className="px-4 py-3">
                {col.render(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
