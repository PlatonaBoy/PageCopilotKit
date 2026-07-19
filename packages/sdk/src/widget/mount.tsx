import { createRoot, type Root } from 'react-dom/client';
import type { ChatMessage, CopilotUIOptions } from '../types';
import { CopilotWidget } from './CopilotWidget';
import { WIDGET_CSS } from './styles';

export interface MountHandle {
  update: (state: { messages: ChatMessage[]; streaming: boolean }) => void;
  destroy: () => void;
}

export function mountWidget(options: {
  ui?: CopilotUIOptions;
  onSend: (text: string) => void;
}): MountHandle {
  const host = document.createElement('div');
  host.id = 'enterprise-copilot-root';
  document.body.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = WIDGET_CSS;
  shadow.appendChild(style);

  const mountPoint = document.createElement('div');
  shadow.appendChild(mountPoint);

  let root: Root = createRoot(mountPoint);
  let messages: ChatMessage[] = [];
  let streaming = false;

  const render = () => {
    root.render(
      <CopilotWidget
        ui={options.ui}
        messages={messages}
        streaming={streaming}
        onSend={options.onSend}
      />,
    );
  };

  render();

  return {
    update(state) {
      messages = state.messages;
      streaming = state.streaming;
      render();
    },
    destroy() {
      root.unmount();
      host.remove();
    },
  };
}
