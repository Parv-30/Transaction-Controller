import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './auth/AuthProvider';
import { ToastProvider } from './components/Toast';
import { RequireAuth } from './routes/RequireAuth';
import { RequireAdmin } from './routes/RequireAdmin';
import { AuthCallback } from './routes/AuthCallback';
import { Landing } from './pages/Landing';
import { Dashboard } from './pages/app/Dashboard';
import { Transfer } from './pages/app/Transfer';
import { History } from './pages/app/History';
import { Holds } from './pages/app/Holds';
import { Deposits } from './pages/app/Deposits';
import { AdminAccounts } from './pages/admin/AdminAccounts';
import { AdminTransactions } from './pages/admin/AdminTransactions';
import { AdminHolds } from './pages/admin/AdminHolds';
import { AdminReconciliation } from './pages/admin/AdminReconciliation';

const queryClient = new QueryClient();

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ToastProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/" element={<Landing />} />
              <Route path="/auth/callback" element={<AuthCallback />} />
              <Route
                path="/app"
                element={
                  <RequireAuth>
                    <Dashboard />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/transfer"
                element={
                  <RequireAuth>
                    <Transfer />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/history"
                element={
                  <RequireAuth>
                    <History />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/holds"
                element={
                  <RequireAuth>
                    <Holds />
                  </RequireAuth>
                }
              />
              <Route
                path="/app/deposits"
                element={
                  <RequireAuth>
                    <Deposits />
                  </RequireAuth>
                }
              />
              <Route
                path="/admin"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminAccounts />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/transactions"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminTransactions />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/holds"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminHolds />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
              <Route
                path="/admin/reconciliation"
                element={
                  <RequireAuth>
                    <RequireAdmin>
                      <AdminReconciliation />
                    </RequireAdmin>
                  </RequireAuth>
                }
              />
            </Routes>
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
