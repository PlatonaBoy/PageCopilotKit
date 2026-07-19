import { Copilot } from './Copilot';
import type { CopilotInit } from './types';

export type {
  ActionableElement,
  ChatMessage,
  CopilotInit,
  CopilotTool,
  CopilotUIOptions,
  PageContextSnapshot,
} from './types';

export { Copilot };
export { PageEngine } from './page/PageEngine';
export { ContextEngine } from './context/ContextEngine';

const api = {
  init(options: CopilotInit) {
    return Copilot.init(options);
  },
  getInstance() {
    return Copilot.getInstance();
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
// IIFE builds may assign the module namespace to a global after this module runs;
// re-install on next tick so window.EnterpriseCopilot keeps .init().
if (typeof queueMicrotask === 'function') {
  queueMicrotask(installGlobals);
} else if (typeof setTimeout === 'function') {
  setTimeout(installGlobals, 0);
}
