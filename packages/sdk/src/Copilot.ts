import { ContextEngine } from './context/ContextEngine';
import { PageEngine } from './page/PageEngine';
import { streamChat } from './transport/sseClient';
import type { ChatMessage, CopilotInit } from './types';
import { mountWidget, type MountHandle } from './widget/mount';

let singleton: Copilot | null = null;

export class Copilot {
  private readonly init: CopilotInit;
  private readonly page = new PageEngine();
  private readonly context: ContextEngine;
  private mount: MountHandle | null = null;
  private messages: ChatMessage[] = [];
  private streaming = false;
  private threadId: string | undefined;
  private abort: AbortController | null = null;

  private constructor(init: CopilotInit) {
    this.init = init;
    this.context = new ContextEngine(init);
  }

  static init(options: CopilotInit): Copilot {
    if (singleton) {
      singleton.destroy();
    }
    singleton = new Copilot(options);
    singleton.mountUi();
    return singleton;
  }

  static getInstance(): Copilot | null {
    return singleton;
  }

  destroy(): void {
    this.abort?.abort();
    this.page.stop();
    this.mount?.destroy();
    this.mount = null;
    if (singleton === this) {
      singleton = null;
    }
  }

  async refreshPageContext() {
    return this.page.snapshot();
  }

  private mountUi(): void {
    if (typeof document === 'undefined') {
      throw new Error('EnterpriseCopilot requires a browser document');
    }
    this.page.start();
    this.mount = mountWidget({
      ui: this.init.ui,
      onSend: (text) => {
        void this.send(text);
      },
    });
    this.sync();
  }

  private sync(): void {
    this.mount?.update({ messages: this.messages, streaming: this.streaming });
  }

  private async send(text: string): Promise<void> {
    if (this.streaming) return;

    const userMsg: ChatMessage = {
      id: id(),
      role: 'user',
      content: text,
    };
    const assistantMsg: ChatMessage = {
      id: id(),
      role: 'assistant',
      content: '',
    };
    this.messages = [...this.messages, userMsg, assistantMsg];
    this.streaming = true;
    this.sync();

    this.abort?.abort();
    this.abort = new AbortController();

    try {
      const token = await this.init.getAccessToken();
      const pageContext = await this.page.snapshot();
      const body = await this.context.buildPayload(pageContext, text);
      if (this.threadId) {
        (body as { threadId?: string }).threadId = this.threadId;
      }

      await streamChat({
        gatewayUrl: this.init.gatewayUrl,
        token,
        body,
        signal: this.abort.signal,
        handlers: {
          onThread: (threadId) => {
            this.threadId = threadId;
          },
          onDelta: (delta) => {
            assistantMsg.content += delta;
            this.messages = [...this.messages.slice(0, -1), { ...assistantMsg }];
            this.sync();
          },
          onError: (message) => {
            assistantMsg.content = `错误：${message}`;
            this.messages = [...this.messages.slice(0, -1), { ...assistantMsg }];
            this.sync();
          },
          onDone: (content) => {
            // Do not overwrite an error message already shown via onError
            if (content && !assistantMsg.content.startsWith('错误：')) {
              assistantMsg.content = content;
              this.messages = [...this.messages.slice(0, -1), { ...assistantMsg }];
            }
          },
        },
      });
    } catch (err) {
      if ((err as Error)?.name === 'AbortError') {
        return;
      }
      const message = err instanceof Error ? err.message : String(err);
      if (!assistantMsg.content.startsWith('错误：')) {
        assistantMsg.content = `请求失败：${message}`;
        this.messages = [...this.messages.slice(0, -1), { ...assistantMsg }];
      }
    } finally {
      this.streaming = false;
      this.sync();
    }
  }
}

function id(): string {
  return `msg_${Math.random().toString(36).slice(2, 10)}`;
}
