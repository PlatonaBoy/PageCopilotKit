export type JsonSchema = Record<string, unknown>;

export interface CopilotTool {
  name: string;
  description: string;
  parameters: JsonSchema;
  execute: (args: unknown) => Promise<unknown>;
  risk?: 'read' | 'write';
  confirm?: boolean;
}

export interface CopilotUIOptions {
  position?: 'bottom-right';
  locale?: string;
  title?: string;
}

export interface CopilotInit {
  appId: string;
  gatewayUrl: string;
  getAccessToken: () => string | Promise<string>;
  contextProvider?: () => Record<string, unknown> | Promise<Record<string, unknown>>;
  tools?: CopilotTool[];
  ui?: CopilotUIOptions;
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
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface StreamHandlers {
  onThread?: (threadId: string) => void;
  onDelta?: (delta: string) => void;
  onDone?: (content: string, traceId?: string) => void;
  onError?: (message: string) => void;
}
