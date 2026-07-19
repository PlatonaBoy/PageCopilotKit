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

declare global {
  interface Window {
    EnterpriseCopilot: typeof api;
    Copilot: typeof api;
  }
}

if (typeof window !== 'undefined') {
  window.EnterpriseCopilot = api;
  window.Copilot = api;
}
