import { ContextEngine } from './context/ContextEngine';
import { createTranslator, errorMessageKey, type Translator } from './i18n';
import { PageEngine } from './page/PageEngine';
import { CopilotStore } from './store';
import { CopilotError, isAbort } from './transport/errors';
import { deleteThread, fetchThreadMessages, streamChat } from './transport/sseClient';
import type { ChatMessage, CopilotInit } from './types';
import { mountWidget, type MountHandle } from './widget/mount';

const THREAD_STORAGE_PREFIX = 'enterprise-copilot:thread:';

let singleton: Copilot | null = null;

export class Copilot {
  private readonly init: CopilotInit;
  private readonly page = new PageEngine();
  private readonly context: ContextEngine;
  private readonly store = new CopilotStore();
  private readonly t: Translator;

  private mount: MountHandle | null = null;
  private abort: AbortController | null = null;
  private threadId: string | undefined;
  private lastQuestion: string | undefined;
  private destroyed = false;

  private constructor(init: CopilotInit) {
    this.init = init;
    this.context = new ContextEngine(init);
    this.t = createTranslator(init.ui?.locale);
  }

  static init(options: CopilotInit): Copilot {
    validateOptions(options);
    if (singleton) {
      singleton.destroy();
    }
    singleton = new Copilot(options);
    singleton.mountUi();
    void singleton.restoreThread();
    return singleton;
  }

  static getInstance(): Copilot | null {
    return singleton;
  }

  /** Aborts the in-flight turn, keeping whatever was already streamed. */
  stop(): void {
    if (!this.store.getSnapshot().streaming) {
      return;
    }
    this.abort?.abort();
  }

  async clear(): Promise<void> {
    this.stop();
    const threadId = this.threadId;
    this.threadId = undefined;
    this.lastQuestion = undefined;
    this.store.reset();
    this.store.setThreadId(undefined);
    this.forgetStoredThread();

    if (threadId) {
      try {
        const token = await this.init.getAccessToken();
        await deleteThread(this.init.gatewayUrl, token, threadId);
      } catch {
        // A failed server-side delete must not block the user from starting fresh.
      }
    }
  }

  retry(): void {
    const question = this.lastQuestion;
    if (!question || this.store.getSnapshot().streaming) {
      return;
    }
    // Drop the failed assistant turn before replaying so history stays clean.
    const messages = this.store.getSnapshot().messages;
    const trimmed =
      messages.length > 0 && messages[messages.length - 1]!.role === 'assistant'
        ? messages.slice(0, -1)
        : messages;
    this.store.setMessages(trimmed);
    this.store.setRetryable(false);
    void this.send(question, { replay: true });
  }

  async refreshPageContext() {
    return this.page.snapshot();
  }

  destroy(): void {
    this.destroyed = true;
    this.abort?.abort();
    this.page.stop();
    this.mount?.destroy();
    this.mount = null;
    this.store.dispose();
    if (singleton === this) {
      singleton = null;
    }
  }

  private mountUi(): void {
    if (typeof document === 'undefined') {
      throw new CopilotError('unsupported', 'EnterpriseCopilot requires a browser document');
    }
    this.page.start();
    this.mount = mountWidget({
      ui: this.init.ui,
      store: this.store,
      t: this.t,
      onSend: (text) => {
        void this.send(text);
      },
      onStop: () => this.stop(),
      onRetry: () => this.retry(),
      onClear: () => {
        void this.clear();
      },
    });
  }

  private async restoreThread(): Promise<void> {
    if (this.init.persistThread === false) {
      return;
    }
    const stored = this.readStoredThread();
    if (!stored) {
      return;
    }
    this.store.setRestoring(true);
    try {
      const token = await this.init.getAccessToken();
      const messages = await fetchThreadMessages(this.init.gatewayUrl, token, stored);
      if (this.destroyed) return;
      if (messages.length === 0) {
        this.forgetStoredThread();
        return;
      }
      this.threadId = stored;
      this.store.setThreadId(stored);
      this.store.setMessages(
        messages.map((m, index) => ({
          id: `restored_${index}`,
          role: m.role === 'assistant' ? 'assistant' : 'user',
          content: m.content,
          status: 'done' as const,
        })),
      );
    } catch {
      // A stale or foreign thread id is simply discarded — never surfaced as an error.
      this.forgetStoredThread();
    } finally {
      this.store.setRestoring(false);
    }
  }

  private async send(text: string, options: { replay?: boolean } = {}): Promise<void> {
    if (this.store.getSnapshot().streaming) {
      return;
    }
    this.lastQuestion = text;
    this.store.setRetryable(false);

    if (!options.replay) {
      this.store.appendMessage({
        id: id(),
        role: 'user',
        content: text,
        status: 'done',
      });
    }

    const assistant: ChatMessage = {
      id: id(),
      role: 'assistant',
      content: '',
      status: 'pending',
    };
    this.store.appendMessage(assistant);
    this.store.setStreaming(true);

    this.abort = new AbortController();

    try {
      await this.runTurn(text, assistant.id, false);
    } catch (err) {
      this.handleTurnFailure(err, assistant.id);
    } finally {
      this.store.flush();
      this.store.setStreaming(false);
      this.abort = null;
    }
  }

  /**
   * One request attempt. On 401 the token is re-fetched once and the turn replayed, which covers
   * the common case of a JWT expiring while the panel was open.
   */
  private async runTurn(text: string, assistantId: string, isRetryAfterAuth: boolean): Promise<void> {
    const token = await this.init.getAccessToken();
    const pageContext = await this.page.snapshot();
    const body = await this.context.buildPayload(pageContext, text);
    if (this.threadId) {
      body.threadId = this.threadId;
    }

    try {
      await streamChat({
        gatewayUrl: this.init.gatewayUrl,
        token,
        body,
        signal: this.abort?.signal,
        timeoutMs: this.init.requestTimeoutMs,
        handlers: {
          onThread: (threadId) => {
            this.threadId = threadId;
            this.store.setThreadId(threadId);
            this.rememberThread(threadId);
          },
          onDelta: (delta) => this.store.appendDelta(assistantId, delta),
          onDone: () => {
            this.store.flush();
            this.store.setMessageStatus(assistantId, 'done');
          },
        },
      });
    } catch (err) {
      const unauthorized = err instanceof CopilotError && err.code === 'unauthorized';
      if (unauthorized && !isRetryAfterAuth) {
        await this.runTurn(text, assistantId, true);
        return;
      }
      throw err;
    }
  }

  private handleTurnFailure(err: unknown, assistantId: string): void {
    if (isAbort(err)) {
      const current = this.store
        .getSnapshot()
        .messages.find((m) => m.id === assistantId);
      const stoppedNote = this.t('stopped');
      this.store.updateMessage(assistantId, {
        status: 'stopped',
        content: current?.content ? `${current.content}\n\n${stoppedNote}` : stoppedNote,
      });
      this.store.setRetryable(true);
      return;
    }

    const code = err instanceof CopilotError ? err.code : 'unknown';
    const message = this.t(errorMessageKey(code));
    const existing = this.store.getSnapshot().messages.find((m) => m.id === assistantId);

    this.store.updateMessage(assistantId, {
      status: 'error',
      errorCode: code,
      // Keep any partial answer and append the reason, so nothing the model produced is lost.
      content: existing?.content ? `${existing.content}\n\n${this.t('errorPrefix')}${message}` : message,
    });
    this.store.setRetryable(true);
    this.init.onError?.({ code, message: err instanceof Error ? err.message : String(err) });
  }

  private storageKey(): string {
    return `${THREAD_STORAGE_PREFIX}${this.init.appId}`;
  }

  private rememberThread(threadId: string): void {
    if (this.init.persistThread === false) return;
    try {
      sessionStorage.setItem(this.storageKey(), threadId);
    } catch {
      // Private browsing / disabled storage — conversation simply will not survive reloads.
    }
  }

  private readStoredThread(): string | null {
    try {
      return sessionStorage.getItem(this.storageKey());
    } catch {
      return null;
    }
  }

  private forgetStoredThread(): void {
    try {
      sessionStorage.removeItem(this.storageKey());
    } catch {
      // ignore
    }
  }
}

function validateOptions(options: CopilotInit): void {
  if (!options || typeof options !== 'object') {
    throw new CopilotError('bad_config', 'Copilot.init requires an options object');
  }
  if (!options.appId) {
    throw new CopilotError('bad_config', 'Copilot.init: appId is required');
  }
  if (!options.gatewayUrl) {
    throw new CopilotError('bad_config', 'Copilot.init: gatewayUrl is required');
  }
  if (typeof options.getAccessToken !== 'function') {
    throw new CopilotError('bad_config', 'Copilot.init: getAccessToken must be a function');
  }
}

function id(): string {
  return `msg_${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}
