import type { PageContextSnapshot, StreamHandlers } from '../types';

export interface ChatRequestBody {
  appId: string;
  threadId?: string;
  message: string;
  pageContext: PageContextSnapshot;
  businessContext: Record<string, unknown>;
  clientTools: unknown[];
}

export async function streamChat(options: {
  gatewayUrl: string;
  token: string;
  body: ChatRequestBody;
  handlers: StreamHandlers;
  signal?: AbortSignal;
}): Promise<void> {
  const url = joinUrl(options.gatewayUrl, '/v1/chat');
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${options.token}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(options.body),
    signal: options.signal,
  });

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `Chat failed (${response.status})`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let assembled = '';
  let traceId: string | undefined;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const parsed = parseSseBlock(rawEvent);
      if (!parsed) continue;

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
          const content = String((parsed.data as { content?: string }).content || assembled);
          assembled = content;
          break;
        }
        case 'error': {
          const message = String((parsed.data as { message?: string }).message || 'error');
          options.handlers.onError?.(message);
          break;
        }
        case 'done': {
          traceId = (parsed.data as { traceId?: string }).traceId;
          break;
        }
        default:
          break;
      }
    }
  }

  options.handlers.onDone?.(assembled, traceId);
}

function parseSseBlock(block: string): { event: string; data: unknown } | null {
  const lines = block.split(/\r?\n/);
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
