/** Stable error taxonomy shared by transport and UI. */
export class CopilotError extends Error {
  readonly code: string;
  readonly retryable: boolean;
  readonly status?: number;

  constructor(code: string, message: string, options: { retryable?: boolean; status?: number } = {}) {
    super(message);
    this.name = 'CopilotError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.status = options.status;
  }
}

export function isAbort(error: unknown): boolean {
  return (
    error instanceof DOMException && error.name === 'AbortError'
  ) || (error as { name?: string } | null)?.name === 'AbortError';
}

/** HTTP status -> error taxonomy. Only transient classes are marked retryable. */
export function fromStatus(status: number, body: string): CopilotError {
  const parsed = safeJson(body);
  const code = parsed?.code ?? defaultCodeFor(status);
  const message = parsed?.message ?? (body || `Request failed (${status})`);
  const retryable = status === 429 || status === 408 || status >= 500;
  return new CopilotError(code, message, { retryable, status });
}

function defaultCodeFor(status: number): string {
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 413) return 'context_too_large';
  if (status === 429) return 'rate_limited';
  if (status >= 500) return 'server_error';
  return 'bad_request';
}

function safeJson(body: string): { code?: string; message?: string } | null {
  try {
    const parsed = JSON.parse(body);
    return typeof parsed === 'object' && parsed ? parsed : null;
  } catch {
    return null;
  }
}
