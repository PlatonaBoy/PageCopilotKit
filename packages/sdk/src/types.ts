export type CopilotLocale = 'zh-CN' | 'en-US';

export type CopilotPosition = 'bottom-right' | 'bottom-left';

/**
 * `read` tools have no side effects and run without asking.
 * `write` tools change state and require explicit user confirmation by default.
 */
export type ToolRisk = 'read' | 'write';

export type JsonSchema = Record<string, unknown>;

export interface CopilotTool {
  name: string;
  description: string;
  /** JSON Schema for the arguments object. */
  parameters?: JsonSchema;
  risk?: ToolRisk;
  /** Overrides the risk-based default. `true` always asks, `false` never asks. */
  confirm?: boolean;
  execute: (args: Record<string, unknown>) => unknown | Promise<unknown>;
}

export interface PageActionOptions {
  /** Enables the built-in DOM tools (click / fill / select / read). Defaults to false. */
  enabled?: boolean;
  /**
   * Built-in write actions that may run without confirmation. Opt in deliberately — this trades
   * safety for smoother form filling.
   */
  autoApprove?: Array<'page_fill' | 'page_select'>;
}

export interface CopilotUIOptions {
  position?: CopilotPosition;
  /** UI language. Unknown values fall back to `zh-CN`. */
  locale?: CopilotLocale | string;
  title?: string;
  primaryColor?: string;
  logoUrl?: string;
  launcherText?: string;
  zIndex?: number;
  defaultOpen?: boolean;
}

export interface CopilotInit {
  appId: string;
  gatewayUrl: string;
  /** Must return a JWT issued by your identity provider. Never pass a raw user id. */
  getAccessToken: () => string | Promise<string>;
  /** Business facts for the current view. Kept under 4KB. */
  contextProvider?: () => Record<string, unknown> | Promise<Record<string, unknown>>;
  /** Business capabilities the assistant may invoke. */
  tools?: CopilotTool[];
  /** Generic DOM automation. Disabled unless explicitly enabled. */
  pageActions?: PageActionOptions;
  ui?: CopilotUIOptions;
  /** Persist the thread id so a page reload can resume the conversation. Defaults to true. */
  persistThread?: boolean;
  /** Per-request timeout in ms. Defaults to 60000. */
  requestTimeoutMs?: number;
  /** Maximum tool round-trips per user turn. Defaults to 5. */
  maxToolSteps?: number;
  onError?: (error: { code: string; message: string }) => void;
  /** Observability hook for every tool invocation. */
  onToolCall?: (event: { name: string; args: Record<string, unknown>; approved: boolean }) => void;
}

export interface ActionableElement {
  /**
   * Opaque handle valid for the snapshot it came from. Actions target elements by `ref`; the SDK
   * re-resolves and re-verifies the element before acting.
   */
  ref: string;
  name: string;
  role: string;
  hint?: string;
  /** Coarse control kind, so the model knows which action applies. */
  kind: 'button' | 'link' | 'text' | 'select' | 'checkbox' | 'radio' | 'other';
  disabled?: boolean;
  /** Current value for inputs and selects. Never populated for sensitive fields. */
  value?: string;
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
  errorCode?: string;
}

/** A completed tool invocation, rendered in the transcript so actions are always visible. */
export interface ToolActivity {
  id: string;
  name: string;
  label: string;
  args: Record<string, unknown>;
  outcome: 'done' | 'rejected' | 'failed';
  detail?: string;
}

/** A tool call awaiting the user's decision. */
export interface PendingToolCall {
  id: string;
  name: string;
  label: string;
  args: Record<string, unknown>;
  risk: ToolRisk;
}

export interface CopilotState {
  messages: ChatMessage[];
  activities: ToolActivity[];
  pending?: PendingToolCall;
  streaming: boolean;
  restoring: boolean;
  threadId?: string;
  retryable: boolean;
}

export interface ToolCallRequest {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
}

export interface StreamHandlers {
  onThread?: (threadId: string) => void;
  onDelta?: (delta: string) => void;
  onToolCall?: (call: ToolCallRequest) => void;
  onDone?: (content: string, traceId?: string) => void;
  onError?: (code: string, message: string) => void;
}
