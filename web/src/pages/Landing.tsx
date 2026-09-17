import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { landingRouteFor } from '../auth/roles';
import { Button } from '../components/Button';
import heroImage from '../assets/hero.jpg';

export function Landing() {
  const { user, isLoading, roles, login } = useAuth();

  if (isLoading) {
    return <div className="flex min-h-screen items-center justify-center">Loading…</div>;
  }
  if (user) {
    return <Navigate to={landingRouteFor(roles)} replace />;
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center">
      <img src={heroImage} alt="" className="absolute inset-0 h-full w-full object-cover brightness-50" />
      <div className="relative z-10 max-w-xl px-6 text-center text-white">
        <h1 className="text-4xl font-bold">Ledger</h1>
        <p className="mt-3 text-lg text-white/80">
          A fault-tolerant double-entry ledger platform — accounts, transfers, and holds, built to survive
          concurrent load and mid-process failure without ever losing or duplicating a transaction.
        </p>
        <Button className="mt-6" onClick={login}>
          Log in
        </Button>
      </div>
    </div>
  );
}
