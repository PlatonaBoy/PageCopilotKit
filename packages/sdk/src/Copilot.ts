import { ContextEngine } from './context/ContextEngine';
import { createTranslator, errorMessageKey, type Translator } from './i18n';
import { PageEngine } from './page/PageEngine';
import { CopilotStore } from './store';
import { ToolRegistry } from './tools/ToolRegistry';
import { createPageActionTools } from './tools/pageActions';
import { CopilotError, isAbort } from './transport/errors';
import {
  deleteThread,
  fetchThreadMessages,
  streamChat,
  streamToolResult,
  type TurnOutcome,
} from './transport/sseClient';
import type { ChatMessage, CopilotInit, CopilotTool, ToolCallRequest } from './types';
import { mountWidget, type MountHandle } from './widget/mount';

const THREAD_STORAGE_PREFIX = 'enterprise-copilot:thread:';
const DEFAULT_MAX_TOOL_STEPS = 5;

let singleton: Copilot | null = null;

export class Copilot {
  private readonly init: CopilotInit;
  private readonly page = new PageEngine();
  private readonly context: ContextEngine;
  private readonly store = new CopilotStore();
  private readonly tools = new ToolRegistry();
  private readonly t: Translator;

  private mount: MountHandle | null = null;
  private abort: AbortController | null = null;
  private threadId: string | undefined;
  private lastQuestion: string | undefined;
  private destroyed = false;
  /** Resolver for the confirmation card currently shown, if any. */
  private confirmResolver: ((approved: boolean) => void) | null = null;
  /** A turn that already performed a write must never be replayed automatically. */
  private turnDidWrite = false;

  private constructor(init: CopilotInit) {
    this.init = init;
    this.context = new ContextEngine(init);
    this.t = createTranslator(init.ui?.locale);
    this.tools.registerAll(init.tools);
    this.tools.registerAll(createPageActionTools(this.page, init.pageActions));
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

  /** Registers a business capability after init. Replaces a tool with the same name. */
  registerTool(tool: CopilotTool): void {
    this.tools.register(tool);
  }

  unregisterTool(name: string): void {
    this.tools.unregister(name);
  }

  /** Aborts the in-flight turn, keeping whatever was already streamed. */
  stop(): void {
    if (!this.store.getSnapshot().streaming) {
      return;
    }
    // A pending confirmation is treated as declined so the turn can unwind.
    this.resolveConfirmation(false);
    this.abort?.abort();
  }

  async clear(): Promise<void> {
    this.stop();
    const threadId = this.threadId;
    this.threadId = undefined;
    this.lastQuestion = undefined;
    this.store.reset();
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

  /** Called by the confirmation card. */
  resolveConfirmation(approved: boolean): void {
    const resolver = this.confirmResolver;
    if (!resolver) return;
    this.confirmResolver = null;
    this.store.setPending(undefined);
    resolver(approved);
  }

  async refreshPageContext() {
    return this.page.snapshot();
  }

  destroy(): void {
    this.destroyed = true;
    this.resolveConfirmation(false);
    this.abort?.abort();
    this.page.stop();
    this.mount?.destroy();
    this.mount = null;
    this.tools.clear();
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
      onConfirm: (approved) => this.resolveConfirmation(approved),
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
    this.turnDidWrite = false;
    this.store.setRetryable(false);

    if (!options.replay) {
      this.store.appendMessage({ id: id(), role: 'user', content: text, status: 'done' });
    }

    this.store.setStreaming(true);
    this.abort = new AbortController();

    let assistantId: string | undefined;
    try {
      assistantId = await this.runTurnLoop(text);
    } catch (err) {
      this.handleTurnFailure(err, assistantId);
    } finally {
      this.store.flush();
      this.store.setStreaming(false);
      this.store.setPending(undefined);
      this.confirmResolver = null;
      this.abort = null;
    }
  }

  /**
   * Drives one user turn to completion.
   *
   * The model may answer directly or request a tool. Each tool result starts a fresh SSE
   * continuation, so the loop runs until the model produces text or the step budget is exhausted.
   */
  private async runTurnLoop(text: string): Promise<string> {
    const maxSteps = this.init.maxToolSteps ?? DEFAULT_MAX_TOOL_STEPS;
    let assistantId = this.beginAssistantMessage();
    let outcome = await this.requestTurn(text, assistantId, false);

    for (let step = 0; outcome.toolCall; step += 1) {
      if (step >= maxSteps) {
        this.store.updateMessage(assistantId, {
          status: 'error',
          errorCode: 'tool_step_limit',
          content: this.t('toolStepLimit'),
        });
        this.store.setRetryable(false);
        return assistantId;
      }

      const call = outcome.toolCall;
      // The assistant turn carried no prose, only a tool request; drop the empty bubble.
      if (!outcome.content) {
        this.store.removeMessage(assistantId);
      } else {
        this.store.setMessageStatus(assistantId, 'done');
      }

      const settled = await this.runToolCall(call);
      assistantId = this.beginAssistantMessage();
      outcome = await this.continueAfterTool(call, settled, assistantId);
    }

    this.store.setMessageStatus(assistantId, 'done');
    return assistantId;
  }

  private beginAssistantMessage(): string {
    const message: ChatMessage = { id: id(), role: 'assistant', content: '', status: 'pending' };
    this.store.appendMessage(message);
    return message.id;
  }

  /**
   * Executes one tool call: validates it, asks the user when the risk requires it, runs it, and
   * records the outcome in the transcript so every action stays visible.
   */
  private async runToolCall(
    call: ToolCallRequest,
  ): Promise<{ result?: unknown; error?: string }> {
    const tool = this.tools.get(call.name);
    const label = this.describeCall(call);

    if (!tool) {
      // The model invented a tool, or the gateway allowed one this client does not expose.
      this.store.addActivity({
        id: call.id || id(),
        name: call.name,
        label,
        args: call.arguments,
        outcome: 'failed',
        detail: this.t('toolUnknown'),
      });
      return { error: `unknown tool "${call.name}"` };
    }

    if (this.tools.needsConfirmation(call.name)) {
      const approved = await this.askConfirmation(call, label);
      this.init.onToolCall?.({ name: call.name, args: call.arguments, approved });
      if (!approved) {
        // The `rejected` outcome already renders "cancelled"; a detail would duplicate it.
        this.store.addActivity({
          id: call.id || id(),
          name: call.name,
          label,
          args: call.arguments,
          outcome: 'rejected',
        });
        return { error: 'user_declined: the user did not approve this action' };
      }
    } else {
      this.init.onToolCall?.({ name: call.name, args: call.arguments, approved: true });
    }

    if (this.tools.riskOf(call.name) === 'write') {
      // Prevents the transport-level retry path from replaying a side effect.
      this.turnDidWrite = true;
    }

    const execution = await this.tools.execute(call.name, call.arguments);
    this.store.addActivity({
      id: call.id || id(),
      name: call.name,
      label,
      args: call.arguments,
      outcome: execution.ok ? 'done' : 'failed',
      detail: execution.ok ? undefined : execution.error,
    });

    return execution.ok ? { result: execution.result } : { error: execution.error };
  }

  private askConfirmation(call: ToolCallRequest, label: string): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      this.confirmResolver = resolve;
      this.store.setPending({
        id: call.id || id(),
        name: call.name,
        label,
        args: call.arguments,
        risk: this.tools.riskOf(call.name),
      });
    });
  }

  /** Human-readable action label, preferring the page control's name over a raw ref. */
  private describeCall(call: ToolCallRequest): string {
    const ref = call.arguments.ref;
    if (typeof ref === 'string') {
      const described = this.page.registry.describe(ref);
      if (described) {
        const suffix =
          typeof call.arguments.value === 'string'
            ? `：${call.arguments.value}`
            : typeof call.arguments.option === 'string'
              ? `：${call.arguments.option}`
              : '';
        return `${call.name} → ${described.name}${suffix}`;
      }
    }
    return call.name;
  }

  private async requestTurn(
    text: string,
    assistantId: string,
    isRetryAfterAuth: boolean,
  ): Promise<TurnOutcome> {
    const token = await this.init.getAccessToken();
    const pageContext = await this.page.snapshot();
    const body = await this.context.buildPayload(pageContext, text);
    body.clientTools = this.tools.schemas();
    if (this.threadId) {
      body.threadId = this.threadId;
    }

    try {
      return await streamChat({
        gatewayUrl: this.init.gatewayUrl,
        token,
        body,
        signal: this.abort?.signal,
        timeoutMs: this.init.requestTimeoutMs,
        handlers: this.streamHandlers(assistantId),
      });
    } catch (err) {
      // Replaying this request would re-run any tool the model asks for, so a turn that already
      // wrote must not be retried.
      if (this.canRetryAfterAuth(err, isRetryAfterAuth, { replaysActions: true })) {
        return this.requestTurn(text, assistantId, true);
      }
      throw err;
    }
  }

  private async continueAfterTool(
    call: ToolCallRequest,
    settled: { result?: unknown; error?: string },
    assistantId: string,
    isRetryAfterAuth = false,
  ): Promise<TurnOutcome> {
    const token = await this.init.getAccessToken();
    // Re-observe: a page action almost certainly changed the DOM.
    const pageContext = await this.page.refresh();
    const businessContext = await this.context.getBusinessContext();

    try {
      return await streamToolResult({
        gatewayUrl: this.init.gatewayUrl,
        token,
        threadId: this.threadId!,
        signal: this.abort?.signal,
        timeoutMs: this.init.requestTimeoutMs,
        handlers: this.streamHandlers(assistantId),
        body: {
          appId: this.init.appId,
          toolCallId: call.id,
          name: call.name,
          result: settled.result,
          error: settled.error,
          pageContext,
          businessContext,
          clientTools: this.tools.schemas(),
        },
      });
    } catch (err) {
      // Reporting an outcome does not re-execute anything, so this is safe to replay even after a
      // write — and it must be, or the side effect stands while the server never learns the result.
      if (this.canRetryAfterAuth(err, isRetryAfterAuth, { replaysActions: false })) {
        return this.continueAfterTool(call, settled, assistantId, true);
      }
      throw err;
    }
  }

  /**
   * A 401 mid-turn usually means the JWT expired while the panel was open; refetching the token and
   * replaying once is safe — unless the replay would re-execute an action that already ran.
   */
  private canRetryAfterAuth(
    err: unknown,
    alreadyRetried: boolean,
    options: { replaysActions: boolean },
  ): boolean {
    if (!(err instanceof CopilotError) || err.code !== 'unauthorized' || alreadyRetried) {
      return false;
    }
    return !(options.replaysActions && this.turnDidWrite);
  }

  private streamHandlers(assistantId: string) {
    return {
      onThread: (threadId: string) => {
        this.threadId = threadId;
        this.store.setThreadId(threadId);
        this.rememberThread(threadId);
      },
      onDelta: (delta: string) => this.store.appendDelta(assistantId, delta),
    };
  }

  private handleTurnFailure(err: unknown, assistantId: string | undefined): void {
    const targetId = assistantId ?? this.lastAssistantId();
    if (!targetId) return;

    if (isAbort(err)) {
      const current = this.store.getSnapshot().messages.find((m) => m.id === targetId);
      const note = this.t('stopped');
      this.store.updateMessage(targetId, {
        status: 'stopped',
        content: current?.content ? `${current.content}\n\n${note}` : note,
      });
      this.store.setRetryable(!this.turnDidWrite);
      return;
    }

    const code = err instanceof CopilotError ? err.code : 'unknown';
    const message = this.t(errorMessageKey(code));
    const existing = this.store.getSnapshot().messages.find((m) => m.id === targetId);

    this.store.updateMessage(targetId, {
      status: 'error',
      errorCode: code,
      // Keep any partial answer and append the reason, so nothing the model produced is lost.
      content: existing?.content
        ? `${existing.content}\n\n${this.t('errorPrefix')}${message}`
        : message,
    });
    // Offering retry after a completed write could duplicate the side effect.
    this.store.setRetryable(!this.turnDidWrite);
    this.init.onError?.({ code, message: err instanceof Error ? err.message : String(err) });
  }

  private lastAssistantId(): string | undefined {
    const messages = this.store.getSnapshot().messages;
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i]!.role === 'assistant') return messages[i]!.id;
    }
    return undefined;
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
