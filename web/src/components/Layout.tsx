import { Link, Outlet } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';

export function Layout() {
  const { workspace, signOut } = useAuth();
  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b border-gray-200">
        <div className="max-w-5xl mx-auto px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            {workspace?.logo_url ? (
              <img src={workspace.logo_url} alt="" className="h-7 w-7 rounded object-cover" />
            ) : (
              <div className="h-7 w-7 rounded bg-gray-200" aria-hidden />
            )}
            <Link to="/clients" className="font-semibold tracking-tight text-gray-900">
              {workspace?.name ?? 'Beat'}
            </Link>
          </div>
          <nav className="flex items-center gap-5 text-sm">
            <Link to="/clients" className="text-gray-700 hover:text-gray-900">
              Clients
            </Link>
            <Link to="/settings" className="text-gray-700 hover:text-gray-900">
              Settings
            </Link>
            <button
              onClick={() => void signOut()}
              className="text-gray-500 hover:text-gray-900"
            >
              Log out
            </button>
          </nav>
        </div>
      </header>
      <main className="max-w-5xl mx-auto px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
