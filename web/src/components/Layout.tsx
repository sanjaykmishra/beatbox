import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';
import { Avatar } from './Avatar';

export function Layout() {
  const { workspace, signOut } = useAuth();
  return (
    <div className="min-h-screen bg-gray-50 text-gray-900">
      <header className="bg-white border-b border-gray-200 sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            {workspace && (
              <Avatar
                name={workspace.name}
                logoUrl={workspace.logo_url}
                primaryColor={workspace.primary_color}
                size="sm"
              />
            )}
            <Link
              to="/clients"
              className="font-semibold tracking-tight text-gray-900 truncate max-w-[20ch]"
              title={workspace?.name}
            >
              {workspace?.name ?? 'Beat'}
            </Link>
          </div>
          <nav className="flex items-center gap-1 text-sm">
            <NavItem to="/clients">Clients</NavItem>
            <NavItem to="/settings">Settings</NavItem>
            <span className="mx-2 h-5 w-px bg-gray-200" aria-hidden />
            <button
              onClick={() => void signOut()}
              className="px-2 py-1.5 text-gray-500 hover:text-gray-900 transition-colors"
            >
              Log out
            </button>
          </nav>
        </div>
      </header>
      <main className="max-w-6xl mx-auto px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}

function NavItem({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `px-2.5 py-1.5 rounded-md transition-colors ${
          isActive
            ? 'text-gray-900 bg-gray-100'
            : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
        }`
      }
    >
      {children}
    </NavLink>
  );
}
