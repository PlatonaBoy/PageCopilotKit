import { Copilot } from './Copilot';
import type { CopilotInit, CopilotTool } from './types';

export type {
  ActionableElement,
  ChatMessage,
  ChatMessageStatus,
  ChatRole,
  CopilotInit,
  CopilotLocale,
  CopilotPosition,
  CopilotState,
  CopilotTool,
  CopilotUIOptions,
  JsonSchema,
  PageActionOptions,
  PageContextSnapshot,
  PendingToolCall,
  ToolActivity,
  ToolRisk,
} from './types';

export { Copilot };
export { CopilotError } from './transport/errors';
export { PageEngine } from './page/PageEngine';
export { ContextEngine } from './context/ContextEngine';
export { ToolRegistry } from './tools/ToolRegistry';

const api = {
  /** Mounts the copilot. Calling it again replaces the previous instance. */
  init(options: CopilotInit) {
    return Copilot.init(options);
  },
  getInstance() {
    return Copilot.getInstance();
  },
  /** Registers a business capability after init. */
  registerTool(tool: CopilotTool) {
    const instance = Copilot.getInstance();
    if (!instance) {
      throw new Error('[EnterpriseCopilot] call Copilot.init() before registerTool()');
    }
    instance.registerTool(tool);
  },
  unregisterTool(name: string) {
    Copilot.getInstance()?.unregisterTool(name);
  },
  stop() {
    Copilot.getInstance()?.stop();
  },
  clear() {
    return Copilot.getInstance()?.clear();
  },
  destroy() {
    Copilot.getInstance()?.destroy();
  },
};

export default api;

export type EnterpriseCopilotApi = typeof api;

declare global {
  interface Window {
    EnterpriseCopilot: EnterpriseCopilotApi;
    Copilot: EnterpriseCopilotApi;
  }
}

function installGlobals(): void {
  if (typeof window === 'undefined') return;
  window.EnterpriseCopilot = api;
  window.Copilot = api;
}

installGlobals();
// The IIFE wrapper assigns its own namespace object to a global after this module body runs,
// so re-install on the next tick to guarantee `window.EnterpriseCopilot.init` stays callable.
if (typeof queueMicrotask === 'function') {
  queueMicrotask(installGlobals);
} else if (typeof setTimeout === 'function') {
  setTimeout(installGlobals, 0);
}
