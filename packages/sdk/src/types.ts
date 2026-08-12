export type CopilotLocale = 'zh-CN' | 'en-US';

export type CopilotPosition = 'bottom-right' | 'bottom-left';

export interface CopilotUIOptions {
  /** Launcher / panel corner. */
  position?: CopilotPosition;
  /** UI language. Unknown values fall back to `zh-CN`. */
  locale?: CopilotLocale | string;
  /** Panel title. Defaults to a localized "AI Copilot". */
  title?: string;
  /** Brand color for the launcher, header and primary buttons. */
  primaryColor?: string;
  /** Optional logo shown in the header. */
  logoUrl?: string;
  /** Launcher label. Defaults to "AI". */
  launcherText?: string;
  /** Stacking order, in case the host uses very high z-indexes. */
  zIndex?: number;
  /** Open the panel automatically on init. */
  defaultOpen?: boolean;
}

export interface CopilotInit {
  appId: string;
  gatewayUrl: string;
  /** Must return a JWT issued by your identity provider. Never pass a raw user id. */
  getAccessToken: () => string | Promise<string>;
  /** Business facts for the current view. Kept under 4KB. */
  contextProvider?: () => Record<string, unknown> | Promise<Record<string, unknown>>;
  ui?: CopilotUIOptions;
  /** Persist the thread id so a page reload can resume the conversation. Defaults to true. */
  persistThread?: boolean;
  /** Per-request timeout in ms. Defaults to 60000. */
  requestTimeoutMs?: number;
  /** Called on unrecoverable errors, for host-side logging. */
  onError?: (error: { code: string; message: string }) => void;
}

export interface ActionableElement {
  name: string;
  role: string;
  hint?: string;
}

export interface PageContextSnapshot {
  url: string;
  title: string;
  summary: string;
  actionableElements: ActionableElement[];
  /** Text currently selected by the user, when any. */
  selection?: string;
}

export type ChatRole = 'user' | 'assistant';

export type ChatMessageStatus = 'pending' | 'streaming' | 'done' | 'error' | 'stopped';

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  status: ChatMessageStatus;
  /** Populated when `status === 'error'`. */
  errorCode?: string;
}

export interface CopilotState {
  messages: ChatMessage[];
  streaming: boolean;
  /** True while a previous conversation is being restored. */
  restoring: boolean;
  threadId?: string;
  /** Set when the last turn failed and can be retried. */
  retryable: boolean;
}

export interface StreamHandlers {
  onThread?: (threadId: string) => void;
  onDelta?: (delta: string) => void;
  onDone?: (content: string, traceId?: string) => void;
  onError?: (code: string, message: string) => void;
}
