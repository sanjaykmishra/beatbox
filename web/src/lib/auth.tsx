import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, getToken, setToken, type User, type Workspace } from './api';
import { AuthContext } from './AuthContext';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  async function loadSession() {
    if (!getToken()) {
      setLoading(false);
      return;
    }
    try {
      const [w, u] = await Promise.all([api.workspace(), api.me()]);
      setWorkspace(w);
      setUser(u);
    } catch {
      setToken(null);
      setWorkspace(null);
      setUser(null);
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
    const [w, u] = await Promise.all([api.workspace(), api.me()]);
    setWorkspace(w);
    setUser(u);
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
    setUser(null);
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
    <AuthContext.Provider value={{ workspace, user, loading, signIn, signOut, refreshWorkspace }}>
      {children}
    </AuthContext.Provider>
  );
}
