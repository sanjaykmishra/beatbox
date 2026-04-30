import { createContext } from 'react';
import type { Workspace } from './api';

export type AuthState = {
  workspace: Workspace | null;
  loading: boolean;
  signIn: (token: string) => Promise<void>;
  signOut: () => Promise<void>;
  refreshWorkspace: () => Promise<void>;
};

export const AuthContext = createContext<AuthState | null>(null);
