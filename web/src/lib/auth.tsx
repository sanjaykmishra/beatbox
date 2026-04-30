import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, getToken, setToken, type Workspace } from './api';
import { AuthContext } from './AuthContext';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  async function loadSession() {
    if (!getToken()) {
      setLoading(false);
      return;
    }
    try {
      setWorkspace(await api.workspace());
    } catch {
      setToken(null);
      setWorkspace(null);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSession();
  }, []);

  async function signIn(token: string) {
    setToken(token);
    setLoading(true);
    setWorkspace(await api.workspace());
    setLoading(false);
  }

  async function signOut() {
    try {
      await api.logout();
    } catch {
      /* ignore */
    }
    setToken(null);
    setWorkspace(null);
    navigate('/login');
  }

  async function refreshWorkspace() {
    if (!getToken()) return;
    try {
      setWorkspace(await api.workspace());
    } catch {
      /* leave existing state in place; caller surfaces the error */
    }
  }

  return (
    <AuthContext.Provider value={{ workspace, loading, signIn, signOut, refreshWorkspace }}>
      {children}
    </AuthContext.Provider>
  );
}
