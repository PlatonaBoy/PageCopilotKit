import { describe, expect, it, vi } from 'vitest';
import { CopilotStore } from './store';

describe('CopilotStore', () => {
  it('batches streaming deltas into a single notification', async () => {
    const store = new CopilotStore();
    store.appendMessage({ id: 'a1', role: 'assistant', content: '', status: 'pending' });

    const listener = vi.fn();
    store.subscribe(listener);

    for (let i = 0; i < 20; i += 1) {
      store.appendDelta('a1', 'x');
    }

    // Content is already complete even though listeners have not been notified per delta.
    expect(store.getSnapshot().messages[0]!.content).toBe('x'.repeat(20));
    expect(listener.mock.calls.length).toBeLessThan(20);

    store.flush();
    expect(listener).toHaveBeenCalled();
  });

  it('ignores deltas addressed to a stale message id', () => {
    const store = new CopilotStore();
    store.appendMessage({ id: 'a1', role: 'assistant', content: 'keep', status: 'streaming' });

    store.appendDelta('other', 'nope');

    expect(store.getSnapshot().messages[0]!.content).toBe('keep');
  });

  it('tracks streaming and retryable flags', () => {
    const store = new CopilotStore();
    expect(store.getSnapshot().streaming).toBe(false);

    store.setStreaming(true);
    store.setRetryable(true);

    expect(store.getSnapshot().streaming).toBe(true);
    expect(store.getSnapshot().retryable).toBe(true);
  });

  it('reset clears messages and flags', () => {
    const store = new CopilotStore();
    store.appendMessage({ id: 'u1', role: 'user', content: 'hi', status: 'done' });
    store.setStreaming(true);

    store.reset();

    expect(store.getSnapshot().messages).toEqual([]);
    expect(store.getSnapshot().streaming).toBe(false);
  });

  it('stops notifying after dispose', () => {
    const store = new CopilotStore();
    const listener = vi.fn();
    store.subscribe(listener);
    store.dispose();
    listener.mockClear();

    store.appendMessage({ id: 'x', role: 'user', content: 'y', status: 'done' });

    expect(listener).not.toHaveBeenCalled();
  });
});
