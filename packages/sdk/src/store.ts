import type { ChatMessage, ChatMessageStatus, CopilotState } from './types';

type Listener = () => void;

/**
 * Minimal external store consumed via `useSyncExternalStore`.
 *
 * Streaming deltas mutate only the trailing assistant message and notifications are coalesced on a
 * frame budget, so a long answer does not trigger one full React reconciliation per token.
 */
export class CopilotStore {
  private state: CopilotState = {
    messages: [],
    streaming: false,
    restoring: false,
    retryable: false,
  };

  private listeners = new Set<Listener>();
  private notifyScheduled = false;
  private flushTimer: ReturnType<typeof setTimeout> | undefined;

  /** Deltas arriving faster than this are batched into one render. */
  private static readonly BATCH_MS = 50;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = (): CopilotState => this.state;

  setMessages(messages: ChatMessage[]): void {
    this.state = { ...this.state, messages };
    this.notifyNow();
  }

  appendMessage(message: ChatMessage): void {
    this.state = { ...this.state, messages: [...this.state.messages, message] };
    this.notifyNow();
  }

  /** Streaming hot path: appends to the last message without rebuilding the whole list eagerly. */
  appendDelta(id: string, delta: string): void {
    const messages = this.state.messages;
    const last = messages[messages.length - 1];
    if (!last || last.id !== id) {
      return;
    }
    const updated: ChatMessage = {
      ...last,
      content: last.content + delta,
      status: 'streaming',
    };
    this.state = { ...this.state, messages: [...messages.slice(0, -1), updated] };
    this.notifyBatched();
  }

  updateMessage(id: string, patch: Partial<ChatMessage>): void {
    const messages = this.state.messages.map((m) => (m.id === id ? { ...m, ...patch } : m));
    this.state = { ...this.state, messages };
    this.notifyNow();
  }

  setMessageStatus(id: string, status: ChatMessageStatus, errorCode?: string): void {
    this.updateMessage(id, { status, errorCode });
  }

  setStreaming(streaming: boolean): void {
    this.state = { ...this.state, streaming };
    this.notifyNow();
  }

  setRestoring(restoring: boolean): void {
    this.state = { ...this.state, restoring };
    this.notifyNow();
  }

  setThreadId(threadId: string | undefined): void {
    this.state = { ...this.state, threadId };
    this.notifyNow();
  }

  setRetryable(retryable: boolean): void {
    this.state = { ...this.state, retryable };
    this.notifyNow();
  }

  reset(): void {
    this.state = { messages: [], streaming: false, restoring: false, retryable: false };
    this.notifyNow();
  }

  /** Flushes any batched delta notification. Call before flipping streaming off. */
  flush(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = undefined;
    }
    if (this.notifyScheduled) {
      this.notifyScheduled = false;
      this.emit();
    }
  }

  dispose(): void {
    this.flush();
    this.listeners.clear();
  }

  private notifyNow(): void {
    this.flush();
    this.emit();
  }

  private notifyBatched(): void {
    if (this.notifyScheduled) {
      return;
    }
    this.notifyScheduled = true;
    this.flushTimer = setTimeout(() => {
      this.notifyScheduled = false;
      this.flushTimer = undefined;
      this.emit();
    }, CopilotStore.BATCH_MS);
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }
}
