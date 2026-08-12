import { afterEach, describe, expect, it, vi } from 'vitest';
import { CopilotError } from './errors';
import { streamChat, type ChatRequestBody } from './sseClient';

const body: ChatRequestBody = {
  appId: 'crm',
  message: 'hi',
  pageContext: { url: 'http://x', title: 't', summary: 's', actionableElements: [] },
  businessContext: {},
};

function sseResponse(chunks: string[], init: ResponseInit = {}): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return new Response(stream, { status: 200, ...init });
}

function mockFetch(response: Response | Promise<Response>) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** Runs a turn that is expected to fail and returns the thrown CopilotError. */
async function captureError(): Promise<CopilotError> {
  try {
    await streamChat({ gatewayUrl: 'http://gw', token: 't', body, handlers: {} });
  } catch (err) {
    return err as CopilotError;
  }
  throw new Error('expected streamChat to reject');
}

describe('streamChat', () => {
  it('parses thread, deltas and done events', async () => {
    mockFetch(
      sseResponse([
        'event:thread\ndata:{"threadId":"thr_1"}\n\n',
        'event:text.delta\ndata:{"delta":"你好"}\n\n',
        'event:text.delta\ndata:{"delta":"世界"}\n\n',
        'event:text.done\ndata:{"content":"你好世界"}\n\n',
        'event:done\ndata:{"traceId":"trc_1"}\n\n',
      ]),
    );

    const deltas: string[] = [];
    let threadId = '';
    let final = '';
    let trace = '';

    await streamChat({
      gatewayUrl: 'http://gw',
      token: 't',
      body,
      handlers: {
        onThread: (id) => (threadId = id),
        onDelta: (d) => deltas.push(d),
        onDone: (content, traceId) => {
          final = content;
          trace = traceId ?? '';
        },
      },
    });

    expect(threadId).toBe('thr_1');
    expect(deltas).toEqual(['你好', '世界']);
    expect(final).toBe('你好世界');
    expect(trace).toBe('trc_1');
  });

  it('handles events split across network chunks', async () => {
    mockFetch(
      sseResponse([
        'event:text.delta\ndata:{"del',
        'ta":"部分"}\n\nevent:done\ndata:{"traceId":"t"}\n\n',
      ]),
    );

    const deltas: string[] = [];
    await streamChat({
      gatewayUrl: 'http://gw',
      token: 't',
      body,
      handlers: { onDelta: (d) => deltas.push(d) },
    });

    expect(deltas).toEqual(['部分']);
  });

  it('accepts CRLF event separators from proxies', async () => {
    mockFetch(
      sseResponse(['event:text.delta\r\ndata:{"delta":"crlf"}\r\n\r\nevent:done\r\ndata:{}\r\n\r\n']),
    );

    const deltas: string[] = [];
    await streamChat({
      gatewayUrl: 'http://gw',
      token: 't',
      body,
      handlers: { onDelta: (d) => deltas.push(d) },
    });

    expect(deltas).toEqual(['crlf']);
  });

  it('parses a trailing event that lacks the final blank line', async () => {
    mockFetch(sseResponse(['event:text.delta\ndata:{"delta":"tail"}']));

    const deltas: string[] = [];
    await streamChat({
      gatewayUrl: 'http://gw',
      token: 't',
      body,
      handlers: { onDelta: (d) => deltas.push(d) },
    });

    expect(deltas).toEqual(['tail']);
  });

  it('throws with the gateway error code when the stream reports an error', async () => {
    mockFetch(
      sseResponse([
        'event:text.delta\ndata:{"delta":"部分答案"}\n\n',
        'event:error\ndata:{"code":"model_error","message":"upstream down"}\n\n',
        'event:done\ndata:{"traceId":"t"}\n\n',
      ]),
    );

    const onDone = vi.fn();
    await expect(
      streamChat({
        gatewayUrl: 'http://gw',
        token: 't',
        body,
        handlers: { onDone },
      }),
    ).rejects.toMatchObject({ code: 'model_error' });

    // A failed turn must not also report success.
    expect(onDone).not.toHaveBeenCalled();
  });

  it('maps HTTP failures onto the error taxonomy', async () => {
    mockFetch(
      new Response(JSON.stringify({ code: 'rate_limited', message: 'slow down' }), { status: 429 }),
    );

    const error = await captureError();

    expect(error).toBeInstanceOf(CopilotError);
    expect(error.code).toBe('rate_limited');
    expect(error.retryable).toBe(true);
  });

  it('marks 401 as non-retryable so the caller can refresh the token instead', async () => {
    mockFetch(new Response('{}', { status: 401 }));

    const error = await captureError();

    expect(error.code).toBe('unauthorized');
    expect(error.retryable).toBe(false);
  });

  it('resolves on the done event even if the socket never closes', async () => {
    const encoder = new TextEncoder();
    let enqueued = false;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (!enqueued) {
          enqueued = true;
          controller.enqueue(encoder.encode('event:done\ndata:{"traceId":"t"}\n\n'));
        }
        // Deliberately never closes — mirrors Spring holding the connection after complete().
      },
    });
    mockFetch(new Response(stream, { status: 200 }));

    const onDone = vi.fn();
    // Would hang forever if the client waited for the reader to close instead of honoring `done`.
    await Promise.race([
      streamChat({ gatewayUrl: 'http://gw', token: 't', body, handlers: { onDone } }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('stream did not finish')), 1000)),
    ]);

    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
