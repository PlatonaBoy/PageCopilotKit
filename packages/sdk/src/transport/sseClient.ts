import type { PageContextSnapshot, StreamHandlers, ToolCallRequest } from '../types';
import type { ToolSchema } from '../tools/ToolRegistry';
import { CopilotError, fromStatus, isAbort } from './errors';

export interface ChatRequestBody {
  appId: string;
  threadId?: string;
  message: string;
  pageContext: PageContextSnapshot;
  businessContext: Record<string, unknown>;
  clientTools?: ToolSchema[];
}

export interface ToolResultBody {
  appId: string;
  toolCallId: string;
  name: string;
  result?: unknown;
  error?: string;
  /** Fresh snapshot: a page action may have changed the DOM. */
  pageContext: PageContextSnapshot;
  businessContext: Record<string, unknown>;
  clientTools?: ToolSchema[];
}

export interface TurnOutcome {
  content: string;
  toolCall?: ToolCallRequest;
  traceId?: string;
}

export interface StreamOptions {
  gatewayUrl: string;
  token: string;
  handlers: StreamHandlers;
  signal?: AbortSignal;
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 60_000;

export async function streamChat(
  options: StreamOptions & { body: ChatRequestBody },
): Promise<TurnOutcome> {
  return request(options, '/v1/chat', options.body);
}

/** Posts a tool result; the response is the SSE continuation of the same turn. */
export async function streamToolResult(
  options: StreamOptions & { threadId: string; body: ToolResultBody },
): Promise<TurnOutcome> {
  return request(
    options,
    `/v1/chat/${encodeURIComponent(options.threadId)}/tool-result`,
    options.body,
  );
}

/**
 * Sends one request and consumes its SSE response.
 *
 * The gateway ends a turn with a `done` event but Spring may keep the TCP stream open, so `done`
 * is treated as end-of-turn rather than waiting for the reader to close. A separate idle timeout
 * guards against a gateway that stops sending entirely.
 */
async function request(
  options: StreamOptions,
  path: string,
  body: unknown,
): Promise<TurnOutcome> {
  const url = joinUrl(options.gatewayUrl, path);
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  const controller = new AbortController();
  const abortFromCaller = () => controller.abort();
  options.signal?.addEventListener('abort', abortFromCaller, { once: true });

  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  let timedOut = false;
  const resetIdleTimer = () => {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs);
  };
  const cleanup = () => {
    if (idleTimer) clearTimeout(idleTimer);
    options.signal?.removeEventListener('abort', abortFromCaller);
  };

  let response: Response;
  resetIdleTimer();
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${options.token}`,
        'Content-Type': 'application/json',
        // JSON is listed too: contract errors (400/413/429) come back as a JSON envelope, and an
        // Accept of only text/event-stream would leave the server unable to represent them.
        Accept: 'text/event-stream, application/json',
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
  } catch (err) {
    cleanup();
    if (timedOut) throw new CopilotError('timeout', 'Request timed out', { retryable: true });
    if (isAbort(err)) throw err;
    throw new CopilotError('network', 'Network request failed', { retryable: true });
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    cleanup();
    throw fromStatus(response.status, text);
  }
  if (!response.body) {
    cleanup();
    throw new CopilotError('network', 'Streaming not supported by this browser');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let assembled = '';
  let traceId: string | undefined;
  let toolCall: ToolCallRequest | undefined;
  let streamError: { code: string; message: string } | undefined;
  let finished = false;

  const dispatch = (rawEvent: string) => {
    const parsed = parseSseBlock(rawEvent);
    if (!parsed) return;

    switch (parsed.event) {
      case 'thread': {
        const threadId = String((parsed.data as { threadId?: string }).threadId || '');
        if (threadId) options.handlers.onThread?.(threadId);
        break;
      }
      case 'text.delta': {
        const delta = String((parsed.data as { delta?: string }).delta || '');
        assembled += delta;
        options.handlers.onDelta?.(delta);
        break;
      }
      case 'text.done': {
        assembled = String((parsed.data as { content?: string }).content || assembled);
        break;
      }
      case 'tool.call': {
        const data = parsed.data as { id?: string; name?: string; arguments?: unknown };
        if (data.name) {
          toolCall = {
            id: String(data.id || ''),
            name: String(data.name),
            arguments: coerceArgs(data.arguments),
          };
          options.handlers.onToolCall?.(toolCall);
        }
        break;
      }
      case 'error': {
        const data = parsed.data as { code?: string; message?: string };
        streamError = { code: data.code || 'model_error', message: data.message || 'error' };
        break;
      }
      case 'done': {
        traceId = (parsed.data as { traceId?: string }).traceId;
        // Spring may keep the connection open after complete(); `done` is authoritative.
        finished = true;
        break;
      }
      default:
        break;
    }
  };

  const consume = (chunk: string, flush: boolean) => {
    // Some proxies rewrite the event separator; normalize before splitting.
    buffer = (buffer + chunk).replace(/\r\n/g, '\n');
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      dispatch(rawEvent);
      if (finished) return;
    }
    if (flush && buffer.trim()) {
      dispatch(buffer);
      buffer = '';
    }
  };

  try {
    while (!finished) {
      const { done, value } = await reader.read();
      if (done) {
        consume(decoder.decode(), true);
        break;
      }
      resetIdleTimer();
      consume(decoder.decode(value, { stream: true }), false);
    }
  } catch (err) {
    if (timedOut) throw new CopilotError('timeout', 'Stream timed out', { retryable: true });
    if (isAbort(err)) throw err;
    throw new CopilotError('network', 'Stream interrupted', { retryable: true });
  } finally {
    cleanup();
    reader.cancel().catch(() => undefined);
  }

  if (streamError) {
    options.handlers.onError?.(streamError.code, streamError.message);
    throw new CopilotError(streamError.code, streamError.message, { retryable: true });
  }

  if (!toolCall) {
    options.handlers.onDone?.(assembled, traceId);
  }
  return { content: assembled, toolCall, traceId };
}

/** Models sometimes emit arguments as a JSON string rather than an object. */
function coerceArgs(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      // fall through
    }
  }
  return {};
}

export async function fetchThreadMessages(
  gatewayUrl: string,
  token: string,
  threadId: string,
): Promise<Array<{ role: string; content: string }>> {
  const res = await fetch(
    joinUrl(gatewayUrl, `/v1/threads/${encodeURIComponent(threadId)}/messages`),
    { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } },
  );
  if (!res.ok) {
    throw fromStatus(res.status, await res.text().catch(() => ''));
  }
  const data = (await res.json()) as { messages?: Array<{ role: string; content: string }> };
  return data.messages ?? [];
}

export async function deleteThread(
  gatewayUrl: string,
  token: string,
  threadId: string,
): Promise<void> {
  const res = await fetch(joinUrl(gatewayUrl, `/v1/threads/${encodeURIComponent(threadId)}`), {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok && res.status !== 404) {
    throw fromStatus(res.status, await res.text().catch(() => ''));
  }
}

function parseSseBlock(block: string): { event: string; data: unknown } | null {
  const lines = block.split('\n');
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim());
    }
  }
  if (!dataLines.length) return null;
  const raw = dataLines.join('\n');
  try {
    return { event, data: JSON.parse(raw) };
  } catch {
    return { event, data: { raw } };
  }
}

function joinUrl(base: string, path: string): string {
  return `${base.replace(/\/+$/, '')}${path}`;
}
