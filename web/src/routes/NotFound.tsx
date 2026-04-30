import { Link } from 'react-router-dom';

export function NotFound() {
  return (
    <div className="min-h-[60vh] flex flex-col items-center justify-center text-center">
      <div className="text-6xl font-semibold tracking-tightish text-ink">404</div>
      <p className="mt-3 text-sm text-gray-600 max-w-sm">
        That page doesn't exist or you don't have access. If you got here from a Beat email, the
        report or share link may have been revoked.
      </p>
      <Link
        to="/clients"
        className="mt-6 ink-btn rounded-lg text-white px-4 py-2.5 text-sm font-medium"
      >
        Back to clients
      </Link>
    </div>
  );
}
