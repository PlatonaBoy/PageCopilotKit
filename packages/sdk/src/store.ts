import type {
  ChatMessage,
  ChatMessageStatus,
  CopilotState,
  PendingToolCall,
  ToolActivity,
} from './types';

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
    activities: [],
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
    this.patch({ messages });
  }

  appendMessage(message: ChatMessage): void {
    this.patch({ messages: [...this.state.messages, message] });
  }

  /** Streaming hot path: appends to the last message without notifying per delta. */
  appendDelta(id: string, delta: string): void {
    const messages = this.state.messages;
    const last = messages[messages.length - 1];
    if (!last || last.id !== id) {
      return;
    }
    const updated: ChatMessage = { ...last, content: last.content + delta, status: 'streaming' };
    this.state = { ...this.state, messages: [...messages.slice(0, -1), updated] };
    this.notifyBatched();
  }

  updateMessage(id: string, patch: Partial<ChatMessage>): void {
    this.patch({ messages: this.state.messages.map((m) => (m.id === id ? { ...m, ...patch } : m)) });
  }

  setMessageStatus(id: string, status: ChatMessageStatus, errorCode?: string): void {
    this.updateMessage(id, { status, errorCode });
  }

  /** Drops a message that turned out to carry nothing (e.g. the model only called a tool). */
  removeMessage(id: string): void {
    this.patch({ messages: this.state.messages.filter((m) => m.id !== id) });
  }

  addActivity(activity: ToolActivity): void {
    this.patch({ activities: [...this.state.activities, activity] });
  }

  setPending(pending: PendingToolCall | undefined): void {
    this.patch({ pending });
  }

  setStreaming(streaming: boolean): void {
    this.patch({ streaming });
  }

  setRestoring(restoring: boolean): void {
    this.patch({ restoring });
  }

  setThreadId(threadId: string | undefined): void {
    this.patch({ threadId });
  }

  setRetryable(retryable: boolean): void {
    this.patch({ retryable });
  }

  reset(): void {
    this.state = {
      messages: [],
      activities: [],
      pending: undefined,
      streaming: false,
      restoring: false,
      retryable: false,
    };
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

  private patch(partial: Partial<CopilotState>): void {
    this.state = { ...this.state, ...partial };
    this.notifyNow();
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
