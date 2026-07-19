import { useEffect, useMemo, useRef, useState } from 'react';
import Markdown from 'react-markdown';
import type { ChatMessage, CopilotUIOptions } from '../types';

export interface CopilotWidgetProps {
  ui?: CopilotUIOptions;
  messages: ChatMessage[];
  streaming: boolean;
  onSend: (text: string) => void;
}

export function CopilotWidget({ ui, messages, streaming, onSend }: CopilotWidgetProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const title = ui?.title || 'AI Copilot';

  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, streaming, open]);

  const canSend = useMemo(() => draft.trim().length > 0 && !streaming, [draft, streaming]);

  const submit = () => {
    const text = draft.trim();
    if (!text || streaming) return;
    setDraft('');
    onSend(text);
    setOpen(true);
  };

  return (
    <>
      <button
        type="button"
        className="launcher"
        aria-label="Open AI Copilot"
        onClick={() => setOpen((v) => !v)}
      >
        AI
      </button>
      <div className="panel" hidden={!open} role="dialog" aria-label={title}>
        <div className="header">
          <h1>{title}</h1>
          <button type="button" aria-label="Close" onClick={() => setOpen(false)}>
            ×
          </button>
        </div>
        <div className="messages" ref={listRef}>
          {messages.length === 0 ? (
            <div className="empty">
              问我当前页面上有什么按钮，或订单状态是什么。
            </div>
          ) : (
            messages.map((m) => (
              <div key={m.id} className={`bubble ${m.role}`}>
                {m.role === 'assistant' ? <Markdown>{m.content || '…'}</Markdown> : m.content}
              </div>
            ))
          )}
        </div>
        <div className="composer">
          <textarea
            rows={2}
            placeholder="输入问题…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
          />
          <button type="button" disabled={!canSend} onClick={submit}>
            发送
          </button>
        </div>
      </div>
    </>
  );
}
