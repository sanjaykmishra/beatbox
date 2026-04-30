import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, api } from '../lib/api';

/**
 * "Second brain" editor for a client. Persists to /v1/clients/:id/context (PUT). Fields here
 * map directly into the LLM extraction prompt (extraction-v1.1) per docs/15-additions.md §15.1.
 *
 * Excluded from the prompt by design: do_not_pitch, important_dates. They're surfaced here so
 * the agency has a single place for client knowledge but they don't bias LLM output.
 */
export function ClientContextEditor() {
  const { id: clientId = '' } = useParams();
  const qc = useQueryClient();
  const client = useQuery({ queryKey: ['client', clientId], queryFn: () => api.getClient(clientId) });
  const ctx = useQuery({
    queryKey: ['client-context', clientId],
    queryFn: async () => {
      try {
        return await api.getClientContext(clientId);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });

  const [keyMessages, setKeyMessages] = useState('');
  const [doNotPitch, setDoNotPitch] = useState('');
  const [competitiveSet, setCompetitiveSet] = useState('');
  const [importantDates, setImportantDates] = useState('');
  const [styleNotes, setStyleNotes] = useState('');
  const [notesMarkdown, setNotesMarkdown] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!ctx.data) return;
    setKeyMessages(ctx.data.key_messages ?? '');
    setDoNotPitch(ctx.data.do_not_pitch ?? '');
    setCompetitiveSet(ctx.data.competitive_set ?? '');
    setImportantDates(ctx.data.important_dates ?? '');
    setStyleNotes(ctx.data.style_notes ?? '');
    setNotesMarkdown(ctx.data.notes_markdown ?? '');
  }, [ctx.data]);

  const save = useMutation({
    mutationFn: () =>
      api.putClientContext(clientId, {
        key_messages: keyMessages,
        do_not_pitch: doNotPitch,
        competitive_set: competitiveSet,
        important_dates: importantDates,
        style_notes: styleNotes,
        notes_markdown: notesMarkdown,
      }),
    onSuccess: (data) => {
      qc.setQueryData(['client-context', clientId], data);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <nav className="text-sm text-gray-500">
        <Link to="/clients" className="hover:text-gray-900">
          Clients
        </Link>{' '}
        ›{' '}
        {client.data ? (
          <Link to={`/clients/${clientId}`} className="hover:text-gray-900">
            {client.data.name}
          </Link>
        ) : (
          <span>…</span>
        )}{' '}
        › <span className="text-gray-900">Context</span>
      </nav>

      <div>
        <h1 className="text-2xl font-semibold tracking-tight">
          Context{client.data ? ` for ${client.data.name}` : ''}
        </h1>
        <p className="mt-1 text-sm text-gray-500">
          Persistent client knowledge that flows into the LLM extraction prompt. Empty fields are
          ignored.{' '}
          {ctx.data && (
            <span>
              Version {ctx.data.version} · updated{' '}
              {new Date(ctx.data.updated_at).toLocaleDateString()}.
            </span>
          )}
        </p>
      </div>

      <section className="bg-white rounded border border-gray-200 p-6 space-y-5">
        <Field
          label="Key messages"
          help="What does this client want the world to know? Two or three sentences."
        >
          <textarea
            rows={3}
            className="w-full rounded border border-gray-300 px-3 py-2"
            value={keyMessages}
            onChange={(e) => setKeyMessages(e.target.value)}
          />
        </Field>
        <Field
          label="Style notes"
          help="Preferred names/spellings, brand pronunciation, tone preferences. Used by the LLM to keep summaries consistent."
        >
          <textarea
            rows={3}
            className="w-full rounded border border-gray-300 px-3 py-2"
            placeholder='e.g. "CEO is Mike, never Michael."'
            value={styleNotes}
            onChange={(e) => setStyleNotes(e.target.value)}
          />
        </Field>
        <Field label="Competitive set" help="Comma-separated competitor names.">
          <input
            className="w-full rounded border border-gray-300 px-3 py-2"
            placeholder="Competitor A, Competitor B"
            value={competitiveSet}
            onChange={(e) => setCompetitiveSet(e.target.value)}
          />
        </Field>
        <Field
          label="Do NOT pitch"
          help="Journalists, outlets, or topics to avoid. NOT sent to the LLM — for your reference only."
        >
          <textarea
            rows={2}
            className="w-full rounded border border-gray-300 px-3 py-2"
            value={doNotPitch}
            onChange={(e) => setDoNotPitch(e.target.value)}
          />
        </Field>
        <Field
          label="Important dates"
          help="Embargo dates, earnings, launches. NOT sent to the LLM — for your reference only."
        >
          <textarea
            rows={2}
            className="w-full rounded border border-gray-300 px-3 py-2"
            value={importantDates}
            onChange={(e) => setImportantDates(e.target.value)}
          />
        </Field>
        <Field
          label="Notes (markdown)"
          help="Catch-all. The first 300 characters are summarized into the LLM prompt."
        >
          <textarea
            rows={8}
            className="w-full font-mono text-sm rounded border border-gray-300 px-3 py-2"
            value={notesMarkdown}
            onChange={(e) => setNotesMarkdown(e.target.value)}
          />
        </Field>
        <p className="text-xs text-gray-500">
          Heads-up: content here is sent to our AI processing pipeline and stored. Don't paste
          passwords, contracts, or anything you wouldn't want logged.
        </p>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <div className="flex justify-end">
          <button
            onClick={() => save.mutate()}
            disabled={save.isPending}
            className="rounded bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-800 disabled:opacity-60"
          >
            {save.isPending ? 'Saving…' : 'Save context'}
          </button>
        </div>
      </section>
    </div>
  );
}

function Field({
  label,
  help,
  children,
}: {
  label: string;
  help?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="block text-sm font-medium text-gray-700">{label}</span>
      {help && <span className="block text-xs text-gray-500 mt-0.5">{help}</span>}
      <div className="mt-1.5">{children}</div>
    </label>
  );
}
