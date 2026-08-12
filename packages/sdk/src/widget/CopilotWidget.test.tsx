import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTranslator } from '../i18n';
import { CopilotStore } from '../store';
import { CopilotWidget } from './CopilotWidget';

function setup(overrides: Partial<Parameters<typeof CopilotWidget>[0]> = {}) {
  const store = overrides.store ?? new CopilotStore();
  const handlers = {
    onSend: vi.fn(),
    onStop: vi.fn(),
    onRetry: vi.fn(),
    onClear: vi.fn(),
  };
  render(
    <CopilotWidget
      store={store}
      t={createTranslator('zh-CN')}
      ui={{ defaultOpen: true, ...overrides.ui }}
      {...handlers}
      {...overrides}
    />,
  );
  return { store, ...handlers };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('CopilotWidget', () => {
  it('shows the empty state before any conversation', () => {
    setup();
    expect(screen.getByText(/你可以问我当前页面的内容/)).toBeTruthy();
  });

  it('sends the trimmed draft on Enter and clears the composer', async () => {
    const user = userEvent.setup();
    const { onSend } = setup();

    const textarea = screen.getByRole('textbox');
    await user.type(textarea, '  订单金额是多少  ');
    await user.keyboard('{Enter}');

    expect(onSend).toHaveBeenCalledWith('订单金额是多少');
    expect((textarea as HTMLTextAreaElement).value).toBe('');
  });

  it('inserts a newline instead of sending on Shift+Enter', async () => {
    const user = userEvent.setup();
    const { onSend } = setup();

    await user.type(screen.getByRole('textbox'), 'line1');
    await user.keyboard('{Shift>}{Enter}{/Shift}');

    expect(onSend).not.toHaveBeenCalled();
  });

  it('swaps send for stop while streaming and calls onStop', async () => {
    const user = userEvent.setup();
    const store = new CopilotStore();
    const { onStop } = setup({ store });

    store.appendMessage({ id: 'a1', role: 'assistant', content: '部分', status: 'streaming' });
    store.setStreaming(true);

    const stopButton = await screen.findByRole('button', { name: '停止' });
    await user.click(stopButton);

    expect(onStop).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '发送' })).toBeNull();
  });

  it('renders a typing indicator for an assistant turn with no content yet', async () => {
    const store = new CopilotStore();
    setup({ store });

    store.appendMessage({ id: 'a1', role: 'assistant', content: '', status: 'pending' });
    store.setStreaming(true);

    await waitFor(() => expect(screen.getByLabelText('正在思考…')).toBeTruthy());
  });

  it('offers retry on the last assistant turn after a failure', async () => {
    const user = userEvent.setup();
    const store = new CopilotStore();
    const { onRetry } = setup({ store });

    store.appendMessage({ id: 'u1', role: 'user', content: '问题', status: 'done' });
    store.appendMessage({
      id: 'a1',
      role: 'assistant',
      content: '出错了：请求失败',
      status: 'error',
      errorCode: 'model_error',
    });
    store.setRetryable(true);

    await user.click(await screen.findByRole('button', { name: '重新生成' }));
    expect(onRetry).toHaveBeenCalled();
  });

  it('copies an assistant answer to the clipboard', async () => {
    const user = userEvent.setup();
    // userEvent.setup() installs its own clipboard stub, so override it afterwards.
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    const store = new CopilotStore();
    setup({ store });

    store.appendMessage({ id: 'a1', role: 'assistant', content: '金额为 50000', status: 'done' });

    await user.click(await screen.findByRole('button', { name: '复制' }));

    expect(writeText).toHaveBeenCalledWith('金额为 50000');
    expect(await screen.findByRole('button', { name: '已复制' })).toBeTruthy();
  });

  it('clears the conversation only after confirmation', async () => {
    const user = userEvent.setup();
    const store = new CopilotStore();
    const { onClear } = setup({ store });
    store.appendMessage({ id: 'u1', role: 'user', content: 'hi', status: 'done' });

    vi.spyOn(window, 'confirm').mockReturnValue(false);
    await user.click(await screen.findByRole('button', { name: '清空会话' }));
    expect(onClear).not.toHaveBeenCalled();

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await user.click(screen.getByRole('button', { name: '清空会话' }));
    expect(onClear).toHaveBeenCalled();
  });

  it('exposes the panel as a modal dialog and hides it when closed', async () => {
    const user = userEvent.setup();
    setup();

    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');

    await user.click(screen.getByRole('button', { name: '关闭' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('renders English strings when locale is en-US', () => {
    render(
      <CopilotWidget
        store={new CopilotStore()}
        t={createTranslator('en-US')}
        ui={{ defaultOpen: true, locale: 'en-US' }}
        onSend={vi.fn()}
        onStop={vi.fn()}
        onRetry={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Send' })).toBeTruthy();
  });
});
