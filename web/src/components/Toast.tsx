import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

type ToastKind = 'info' | 'success' | 'error';
interface ToastMessage {
  id: number;
  message: string;
  kind: ToastKind;
}

interface ToastContextValue {
  showToast: (message: string, kind?: ToastKind) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

let nextId = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback((message: string, kind: ToastKind = 'info') => {
    const id = nextId++;
    setToasts((current) => [...current, { id, message, kind }]);
    setTimeout(() => {
      setToasts((current) => current.filter((toast) => toast.id !== id));
    }, 4000);
  }, []);

  const kindClasses: Record<ToastKind, string> = {
    info: 'bg-ink-light text-surface-light dark:bg-ink-dark dark:text-surface-dark',
    success: 'bg-success text-white',
    error: 'bg-danger text-white',
  };

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((toast) => (
          <div key={toast.id} className={`rounded-lg px-4 py-3 text-sm shadow-lg ${kindClasses[toast.kind]}`}>
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}
