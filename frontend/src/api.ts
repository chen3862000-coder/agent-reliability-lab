export type ProviderStatus = 'CONFIGURED' | 'NOT_CONFIGURED';

export interface ProviderInfo {
  id: string;
  model: string;
  status: ProviderStatus;
  capabilities: Record<string, string>;
}

export interface ToolResult {
  tool: string;
  message: string;
  data: unknown;
  errorCode: string | null;
}

export interface AgentResponse {
  status: string;
  message: string;
  errorCode: string | null;
  traceId: string;
  provider: string | null;
  model: string | null;
  toolCalls: string[];
  results: ToolResult[];
}

export interface TraceEvent {
  timestamp: string;
  type: string;
  tool: string | null;
  status: string;
  errorCode: string | null;
  durationMillis: number;
}

export interface Trace {
  traceId: string;
  provider: string | null;
  model: string | null;
  events: TraceEvent[];
}

export interface ReservationCommit {
  status: string;
  message: string;
  errorCode: string | null;
  confirmationId: string;
  idempotencyKey: string;
  sku: string | null;
  quantity: number;
  remaining: number | null;
}

export interface ConfirmationProposal {
  confirmationId: string;
  sku: string;
  quantity: number;
  expiresAt: string;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers
    }
  });
  const body = (await response.json()) as T;
  if (!response.ok) return body;
  return body;
}

export function getProviders(): Promise<ProviderInfo[]> {
  return request<ProviderInfo[]>('/providers');
}

export function chat(message: string, provider: string, mode: string): Promise<AgentResponse> {
  return request<AgentResponse>('/agent/chat', {
    method: 'POST',
    body: JSON.stringify({ message, provider: provider || undefined, mode })
  });
}

export function confirmReservation(
  confirmationId: string,
  idempotencyKey: string
): Promise<ReservationCommit> {
  return request<ReservationCommit>('/agent/confirm', {
    method: 'POST',
    body: JSON.stringify({ confirmationId, idempotencyKey })
  });
}

export function getTrace(traceId: string): Promise<Trace> {
  return request<Trace>(`/traces/${encodeURIComponent(traceId)}`);
}

export function confirmationFrom(response: AgentResponse | null): ConfirmationProposal | null {
  for (const result of response?.results ?? []) {
    if (result.tool !== 'reserve_inventory' || result.errorCode) continue;
    if (!isRecord(result.data)) continue;
    const { confirmationId, sku, quantity, expiresAt } = result.data;
    if (
      typeof confirmationId === 'string' &&
      typeof sku === 'string' &&
      typeof quantity === 'number' &&
      typeof expiresAt === 'string'
    ) {
      return { confirmationId, sku, quantity, expiresAt };
    }
  }
  return null;
}

export function newIdempotencyKey(confirmationId: string): string {
  const suffix = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2);
  return `frontend-${confirmationId.slice(0, 8)}-${suffix}`;
}

export function confirmationCommitted(commit: ReservationCommit | null): boolean {
  return commit?.errorCode === null;
}

export function shortId(value: string | null | undefined): string {
  if (!value) return 'n/a';
  return value.length <= 16 ? value : `${value.slice(0, 8)}...${value.slice(-6)}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
