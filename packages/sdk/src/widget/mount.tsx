import { createRoot, type Root } from 'react-dom/client';
import type { Translator } from '../i18n';
import type { CopilotStore } from '../store';
import type { CopilotUIOptions } from '../types';
import { CopilotWidget } from './CopilotWidget';
import { WIDGET_CSS } from './styles';

export interface MountHandle {
  destroy: () => void;
}

export interface MountOptions {
  ui?: CopilotUIOptions;
  store: CopilotStore;
  t: Translator;
  onSend: (text: string) => void;
  onStop: () => void;
  onRetry: () => void;
  onClear: () => void;
}

const HOST_ID = 'enterprise-copilot-root';

export function mountWidget(options: MountOptions): MountHandle {
  // Reuse or replace any host left behind by a previous init.
  document.getElementById(HOST_ID)?.remove();

  const host = document.createElement('div');
  host.id = HOST_ID;
  host.setAttribute('data-position', options.ui?.position ?? 'bottom-right');
  document.body.appendChild(host);

  const shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = WIDGET_CSS + themeOverrides(options.ui);
  shadow.appendChild(style);

  const mountPoint = document.createElement('div');
  shadow.appendChild(mountPoint);

  const root: Root = createRoot(mountPoint);
  // The widget subscribes to the store itself, so streaming deltas never re-render from here.
  root.render(
    <CopilotWidget
      ui={options.ui}
      store={options.store}
      t={options.t}
      onSend={options.onSend}
      onStop={options.onStop}
      onRetry={options.onRetry}
      onClear={options.onClear}
    />,
  );

  return {
    destroy() {
      root.unmount();
      host.remove();
    },
  };
}

function themeOverrides(ui?: CopilotUIOptions): string {
  const rules: string[] = [];
  if (ui?.primaryColor) {
    rules.push(`--copilot-primary: ${ui.primaryColor};`);
    rules.push(`--copilot-primary-dark: ${ui.primaryColor};`);
  }
  if (typeof ui?.zIndex === 'number') {
    rules.push(`--copilot-z: ${ui.zIndex};`);
  }
  return rules.length ? `\n:host { ${rules.join(' ')} }\n` : '';
}
