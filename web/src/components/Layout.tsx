import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';
import { Avatar } from './Avatar';

export function Layout() {
  const { workspace, signOut } = useAuth();
  return (
    <div className="min-h-screen bg-app text-ink">
      <header className="bg-white/70 backdrop-blur border-b border-gray-200 sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-6 h-12 flex items-center justify-between">
          <Link to="/clients" className="flex items-center gap-2.5 group">
            {workspace && (
              <Avatar
                name={workspace.name}
                logoUrl={workspace.logo_url}
                primaryColor={workspace.primary_color}
                size="sm"
              />
            )}
            <span
              className="font-semibold tracking-tightish text-ink truncate max-w-[20ch]"
              title={workspace?.name}
            >
              {workspace?.name ?? 'Beat'}
            </span>
          </Link>
          <nav className="flex items-center gap-1 text-[13px]">
            <NavItem to="/clients">Clients</NavItem>
            <NavItem to="/calendar">Calendar</NavItem>
            <NavItem to="/settings">Settings</NavItem>
            <span className="mx-2 h-4 w-px bg-gray-200" aria-hidden />
            <button
              onClick={() => void signOut()}
              className="px-2 py-1 text-gray-500 hover:text-gray-900 transition-colors"
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
        `px-2.5 py-1 rounded-md transition-colors ${
          isActive
            ? 'text-gray-900 bg-gray-100'
            : 'text-gray-500 hover:text-gray-900 hover:bg-gray-50'
        }`
      }
    >
      {children}
    </NavLink>
  );
}
