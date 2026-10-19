import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  AgentResponse,
  ConfirmationProposal,
  ProviderInfo,
  ReservationCommit,
  Trace,
  chat,
  confirmationCommitted,
  confirmationFrom,
  confirmReservation,
  getProviders,
  getTrace,
  newIdempotencyKey,
  shortId
} from './api';
import './styles.css';

const SAMPLE_PROMPTS = [
  { label: 'Inventory', mode: 'BASELINE', text: 'Check inventory for SKU-1001' },
  { label: 'Product', mode: 'BASELINE', text: 'Show product details for SKU-1001' },
  { label: 'Order', mode: 'BASELINE', text: 'Check order 123' },
  { label: 'Protected reserve', mode: 'PROTECTED', text: 'Reserve 3 units of SKU-1001' },
  { label: 'Insufficient stock', mode: 'PROTECTED', text: 'Reserve 1 unit of SKU-1002' }
] as const;

function App() {
  const [providers, setProviders] = useState<ProviderInfo[]>([]);
  const [provider, setProvider] = useState('deepseek');
  const [mode, setMode] = useState('BASELINE');
  const [message, setMessage] = useState('Check inventory for SKU-1001');
  const [response, setResponse] = useState<AgentResponse | null>(null);
  const [proposal, setProposal] = useState<ConfirmationProposal | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [commit, setCommit] = useState<ReservationCommit | null>(null);
  const [trace, setTrace] = useState<Trace | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void refreshProviders();
  }, []);

  async function refreshProviders() {
    setError(null);
    try {
      const next = await getProviders();
      setProviders(next);
      if (next.length > 0 && !next.some((item) => item.id === provider)) setProvider(next[0].id);
    } catch (failure) {
      setError(messageFrom(failure));
    }
  }

  async function submitChat(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setCommit(null);
    setTrace(null);
    try {
      const next = await chat(message, provider, mode);
      setResponse(next);
      const nextProposal = confirmationFrom(next);
      setProposal(nextProposal);
      setIdempotencyKey(nextProposal ? newIdempotencyKey(nextProposal.confirmationId) : '');
    } catch (failure) {
      setError(messageFrom(failure));
    } finally {
      setBusy(false);
    }
  }

  async function submitConfirmation() {
    if (!proposal) return;
    setBusy(true);
    setError(null);
    try {
      setCommit(await confirmReservation(proposal.confirmationId, idempotencyKey));
    } catch (failure) {
      setError(messageFrom(failure));
    } finally {
      setBusy(false);
    }
  }

  async function loadTrace() {
    if (!response?.traceId) return;
    setBusy(true);
    setError(null);
    try {
      setTrace(await getTrace(response.traceId));
    } catch (failure) {
      setError(messageFrom(failure));
    } finally {
      setBusy(false);
    }
  }

  async function copy(value: string, label: string) {
    await navigator.clipboard?.writeText(value);
    setCopied(label);
    window.setTimeout(() => setCopied(null), 1400);
  }

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">Agent Reliability Lab</p>
        <h1>Operate agents with deterministic safety rails.</h1>
        <p>
          The model can propose tool calls. The application validates arguments, records traces and
          controls protected reservations.
        </p>
      </section>

      <section className="panel providers">
        <div className="panel-title">
          <h2>Providers</h2>
          <button type="button" onClick={refreshProviders}>Refresh</button>
        </div>
        <div className="provider-grid">
          {providers.map((item) => (
            <article key={item.id} className="provider-card">
              <span className={item.status === 'CONFIGURED' ? 'ok' : 'muted'}>{item.status}</span>
              <strong>{item.id}</strong>
              <small>{item.model}</small>
            </article>
          ))}
        </div>
      </section>

      <section className="workspace">
        <form className="panel chat" onSubmit={submitChat}>
          <h2>Agent Chat</h2>
          <div className="samples" aria-label="Sample prompts">
            {SAMPLE_PROMPTS.map((sample) => (
              <button
                key={sample.label}
                type="button"
                className="sample-button"
                onClick={() => {
                  setMessage(sample.text);
                  setMode(sample.mode);
                }}
              >
                {sample.label}
              </button>
            ))}
          </div>
          <label>
            Message
            <textarea value={message} onChange={(event) => setMessage(event.target.value)} />
          </label>
          <div className="field-row">
            <label>
              Provider
              <select value={provider} onChange={(event) => setProvider(event.target.value)}>
                {providers.map((item) => <option key={item.id}>{item.id}</option>)}
                {providers.length === 0 && <option>deepseek</option>}
              </select>
            </label>
            <label>
              Mode
              <select value={mode} onChange={(event) => setMode(event.target.value)}>
                <option>BASELINE</option>
                <option>PROTECTED</option>
              </select>
            </label>
          </div>
          <button type="submit" disabled={busy || message.trim().length === 0}>
            {busy ? 'Working...' : 'Send'}
          </button>
        </form>

        <section className="panel response">
          <h2>Response</h2>
          {error && <p className="error">{error}</p>}
          {copied && <p className="notice">Copied {copied}</p>}
          {response ? (
            <ResponseView response={response} onTrace={loadTrace} onCopy={copy} busy={busy} />
          ) : (
            <p>No response yet.</p>
          )}
          {proposal && (
            <ConfirmationCard
              proposal={proposal}
              idempotencyKey={idempotencyKey}
              setIdempotencyKey={setIdempotencyKey}
              onConfirm={submitConfirmation}
              commit={commit}
              busy={busy}
              onCopy={copy}
            />
          )}
        </section>
      </section>

      {trace && <TraceView trace={trace} />}
    </main>
  );
}

function ResponseView({
  response,
  onTrace,
  onCopy,
  busy
}: {
  response: AgentResponse;
  onTrace: () => void;
  onCopy: (value: string, label: string) => void;
  busy: boolean;
}) {
  return (
    <div className="response-body">
      <div className={`status ${response.status.toLowerCase()}`}>{response.status}</div>
      <p>{response.message}</p>
      {response.errorCode && <p className="error">{response.errorCode}</p>}
      <dl>
        <dt>Provider</dt><dd>{response.provider ?? 'n/a'}</dd>
        <dt>Model</dt><dd>{response.model ?? 'n/a'}</dd>
        <dt>Trace</dt>
        <dd>
          {shortId(response.traceId)}
          <button type="button" className="link-button" onClick={() => onCopy(response.traceId, 'trace ID')}>
            Copy
          </button>
        </dd>
        <dt>Tools</dt><dd>{response.toolCalls.join(', ') || 'none'}</dd>
      </dl>
      <button type="button" onClick={onTrace} disabled={busy}>{busy ? 'Loading...' : 'Load trace'}</button>
      <pre>{JSON.stringify(response.results, null, 2)}</pre>
    </div>
  );
}

function ConfirmationCard({
  proposal,
  idempotencyKey,
  setIdempotencyKey,
  onConfirm,
  commit,
  busy,
  onCopy
}: {
  proposal: ConfirmationProposal;
  idempotencyKey: string;
  setIdempotencyKey: (value: string) => void;
  onConfirm: () => void;
  commit: ReservationCommit | null;
  busy: boolean;
  onCopy: (value: string, label: string) => void;
}) {
  const committed = confirmationCommitted(commit);
  return (
    <article className="confirmation">
      <h3>Protected Reservation</h3>
      <p>{proposal.quantity} units of {proposal.sku} require confirmation before stock changes.</p>
      <p>
        Confirmation <code>{shortId(proposal.confirmationId)}</code>
        <button
          type="button"
          className="link-button"
          onClick={() => onCopy(proposal.confirmationId, 'confirmation ID')}
        >
          Copy
        </button>
      </p>
      <small>Expires at {proposal.expiresAt}</small>
      <label>
        Idempotency key
        <input
          value={idempotencyKey}
          disabled={committed}
          onChange={(event) => setIdempotencyKey(event.target.value)}
        />
      </label>
      <button type="button" onClick={onConfirm} disabled={busy || committed || idempotencyKey.length < 8}>
        {committed ? 'Reservation confirmed' : busy ? 'Confirming...' : 'Confirm reservation'}
      </button>
      {commit?.remaining !== null && commit?.remaining !== undefined && (
        <p className="stock">Remaining stock: <strong>{commit.remaining}</strong></p>
      )}
      {commit && <pre>{JSON.stringify(commit, null, 2)}</pre>}
    </article>
  );
}

function TraceView({ trace }: { trace: Trace }) {
  return (
    <section className="panel trace">
      <h2>Trace Events</h2>
      <ol>
        {trace.events.map((event, index) => (
          <li key={`${event.type}-${index}`}>
            <strong>{event.type}</strong>
            <span>{event.status}</span>
            {event.tool && <em>{event.tool}</em>}
            {event.errorCode && <code>{event.errorCode}</code>}
            <small>{event.durationMillis} ms</small>
          </li>
        ))}
      </ol>
    </section>
  );
}

function messageFrom(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'Request failed';
}

createRoot(document.getElementById('root')!).render(<App />);
