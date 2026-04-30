import { Navigate } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';
import type { ReactNode } from 'react';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { workspace, loading } = useAuth();
  if (loading) return <div className="p-8 text-gray-500">Loading…</div>;
  if (!workspace) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
