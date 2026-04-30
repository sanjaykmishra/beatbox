import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './lib/auth';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Layout } from './components/Layout';
import { ErrorBoundary } from './components/ErrorBoundary';
import { Signup } from './routes/Signup';
import { Login } from './routes/Login';
import { Clients } from './routes/Clients';
import { ClientDashboard } from './routes/ClientDashboard';
import { ClientEdit } from './routes/ClientEdit';
import { Settings } from './routes/Settings';
import { NewReport } from './routes/NewReport';
import { ReportReview } from './routes/ReportReview';
import { ReportPreview } from './routes/ReportPreview';
import { ClientContextEditor } from './routes/ClientContextEditor';
import { Calendar } from './routes/Calendar';
import { AdminDashboard } from './routes/AdminDashboard';
import { NotFound } from './routes/NotFound';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <Routes>
              <Route path="/signup" element={<Signup />} />
              <Route path="/login" element={<Login />} />
              <Route
                element={
                  <ProtectedRoute>
                    <Layout />
                  </ProtectedRoute>
                }
              >
                <Route path="/calendar" element={<Calendar />} />
                <Route path="/clients" element={<Clients />} />
                <Route path="/clients/:id" element={<ClientDashboard />} />
                <Route path="/clients/:id/edit" element={<ClientEdit />} />
                <Route path="/clients/:id/context" element={<ClientContextEditor />} />
                <Route path="/clients/:id/reports/new" element={<NewReport />} />
                <Route path="/reports/:id" element={<ReportReview />} />
                <Route path="/reports/:id/preview" element={<ReportPreview />} />
                <Route path="/settings" element={<Settings />} />
                <Route path="/admin/dashboard" element={<AdminDashboard />} />
                <Route path="*" element={<NotFound />} />
              </Route>
              <Route path="*" element={<Navigate to="/clients" replace />} />
            </Routes>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>,
);
