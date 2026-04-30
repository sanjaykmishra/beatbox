import { Component, type ReactNode } from 'react';

type State = { error: Error | null };

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: { componentStack?: string | null }) {
    console.error('Render error:', error, info.componentStack);
  }

  override render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="min-h-screen bg-app flex flex-col items-center justify-center p-6">
        <div className="w-full max-w-md bg-white rounded-2xl shadow-sm border border-gray-200 p-8 text-center">
          <h1 className="text-xl font-semibold tracking-tightish text-ink">
            Something broke on this page
          </h1>
          <p className="text-sm text-gray-600 mt-2">
            The error has been logged. Reloading usually fixes it; if it doesn't, drop the URL and
            a screenshot to support.
          </p>
          <pre className="mt-4 text-left text-xs font-mono bg-gray-50 border border-gray-200 rounded-md p-3 overflow-auto max-h-40">
            {this.state.error.message}
          </pre>
          <div className="mt-5 flex items-center justify-center gap-2">
            <button
              onClick={() => {
                this.setState({ error: null });
              }}
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50"
            >
              Try again
            </button>
            <button
              onClick={() => window.location.reload()}
              className="ink-btn rounded-lg text-white px-4 py-2.5 text-sm font-medium"
            >
              Reload page
            </button>
          </div>
        </div>
      </div>
    );
  }
}
