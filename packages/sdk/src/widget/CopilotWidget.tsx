import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import Markdown from 'react-markdown';
import type { Translator } from '../i18n';
import type { CopilotStore } from '../store';
import type { ChatMessage, CopilotUIOptions, ToolActivity } from '../types';

export interface CopilotWidgetProps {
  ui?: CopilotUIOptions;
  store: CopilotStore;
  t: Translator;
  onSend: (text: string) => void;
  onStop: () => void;
  onRetry: () => void;
  onClear: () => void;
  onConfirm: (approved: boolean) => void;
}

export function CopilotWidget({
  ui,
  store,
  t,
  onSend,
  onStop,
  onRetry,
  onClear,
  onConfirm,
}: CopilotWidgetProps) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  const [open, setOpen] = useState(Boolean(ui?.defaultOpen));
  const [draft, setDraft] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const listRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const approveRef = useRef<HTMLButtonElement>(null);
  const pinnedToBottom = useRef(true);

  const title = ui?.title || t('title');
  const { messages, activities, pending, streaming, restoring, retryable } = state;

  // Only autoscroll while the user is already at the bottom, so reading history is not hijacked.
  useEffect(() => {
    const el = listRef.current;
    if (el && pinnedToBottom.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, activities, pending, streaming, open]);

  useEffect(() => {
    if (open) {
      textareaRef.current?.focus();
    }
  }, [open]);

  // A confirmation must be impossible to miss: open the panel and focus the approve button.
  useEffect(() => {
    if (pending) {
      setOpen(true);
      approveRef.current?.focus();
    }
  }, [pending]);

  const close = useCallback(() => {
    setOpen(false);
    launcherRef.current?.focus();
  }, []);

  // Escape closes; Tab is trapped inside the dialog while it is open.
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        close();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusables = panelRef.current?.querySelectorAll<HTMLElement>(
        'button:not([disabled]), textarea, a[href]',
      );
      if (!focusables || focusables.length === 0) return;
      const first = focusables[0]!;
      const last = focusables[focusables.length - 1]!;
      const root = panelRef.current?.getRootNode() as ShadowRoot;
      const current = root?.activeElement;
      if (!event.shiftKey && current === last) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && current === first) {
        event.preventDefault();
        last.focus();
      }
    };
    const root = panelRef.current;
    root?.addEventListener('keydown', onKeyDown);
    return () => root?.removeEventListener('keydown', onKeyDown);
  }, [open, close]);

  const canSend = useMemo(
    () => draft.trim().length > 0 && !streaming && !pending,
    [draft, streaming, pending],
  );

  const submit = () => {
    const text = draft.trim();
    if (!text || streaming || pending) return;
    setDraft('');
    pinnedToBottom.current = true;
    onSend(text);
    setOpen(true);
  };

  const copy = async (message: ChatMessage) => {
    try {
      await navigator.clipboard.writeText(message.content);
      setCopiedId(message.id);
      setTimeout(() => setCopiedId(null), 1500);
    } catch {
      // Clipboard denied — silently ignore rather than showing a scary error.
    }
  };

  const lastAssistantId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i]!.role === 'assistant') return messages[i]!.id;
    }
    return null;
  }, [messages]);

  return (
    <>
      <button
        type="button"
        ref={launcherRef}
        className="launcher"
        aria-label={t('openLabel')}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {ui?.logoUrl ? <img src={ui.logoUrl} alt="" /> : (ui?.launcherText || t('launcher'))}
      </button>

      <div
        className="panel"
        ref={panelRef}
        hidden={!open}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        data-streaming={streaming ? 'true' : 'false'}
        data-pending={pending ? 'true' : 'false'}
      >
        <div className="header">
          {ui?.logoUrl ? <img src={ui.logoUrl} alt="" /> : null}
          <h1>{title}</h1>
          <button
            type="button"
            className="icon-button"
            onClick={() => {
              if (messages.length === 0) return;
              if (typeof window !== 'undefined' && !window.confirm(t('clearConfirm'))) return;
              onClear();
            }}
          >
            {t('clear')}
          </button>
          <button
            type="button"
            className="icon-button close"
            aria-label={t('closeLabel')}
            onClick={close}
          >
            ×
          </button>
        </div>

        <div
          className="messages"
          ref={listRef}
          onScroll={(e) => {
            const el = e.currentTarget;
            pinnedToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
          }}
        >
          {restoring ? <div className="empty">{t('restoring')}</div> : null}

          {!restoring && messages.length === 0 && activities.length === 0 ? (
            <div className="empty">{t('emptyState')}</div>
          ) : (
            messages.map((m) => (
              <div key={m.id} className={`row ${m.role}`}>
                <div className={`bubble ${m.status === 'error' ? 'error' : ''}`}>
                  {m.role === 'assistant' ? (
                    m.content ? (
                      <Markdown>{m.content}</Markdown>
                    ) : (
                      <span className="typing" aria-label={t('thinking')}>
                        <span />
                        <span />
                        <span />
                      </span>
                    )
                  ) : (
                    m.content
                  )}
                </div>

                {m.role === 'assistant' && m.content && m.status !== 'pending' ? (
                  <div className="row-actions">
                    <button type="button" onClick={() => void copy(m)}>
                      {copiedId === m.id ? t('copied') : t('copy')}
                    </button>
                    {retryable && m.id === lastAssistantId ? (
                      <button type="button" onClick={onRetry}>
                        {t('retry')}
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ))
          )}

          {activities.map((activity) => (
            <ActivityRow key={activity.id} activity={activity} t={t} />
          ))}

          {pending ? (
            <div className="confirm" role="alertdialog" aria-label={t('confirmTitle')}>
              <div className="confirm-title">{t('confirmTitle')}</div>
              <div className="confirm-action">{pending.label}</div>
              {Object.keys(pending.args).length > 0 ? (
                <pre className="confirm-args">{JSON.stringify(pending.args, null, 2)}</pre>
              ) : null}
              <div className="confirm-hint">{t('confirmHint')}</div>
              <div className="confirm-buttons">
                <button
                  type="button"
                  ref={approveRef}
                  className="approve"
                  onClick={() => onConfirm(true)}
                >
                  {t('approve')}
                </button>
                <button type="button" className="decline" onClick={() => onConfirm(false)}>
                  {t('decline')}
                </button>
              </div>
            </div>
          ) : null}

          {/* Streaming text is announced politely rather than character by character. */}
          <div className="sr-only" aria-live="polite" aria-atomic="false">
            {pending ? t('confirmTitle') : streaming ? t('thinking') : ''}
          </div>
        </div>

        <div className="composer">
          <textarea
            ref={textareaRef}
            rows={2}
            placeholder={t('placeholder')}
            value={draft}
            disabled={Boolean(pending)}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
          />
          {streaming ? (
            <button type="button" className="stop" onClick={onStop}>
              {t('stop')}
            </button>
          ) : (
            <button type="button" disabled={!canSend} onClick={submit}>
              {t('send')}
            </button>
          )}
        </div>
      </div>
    </>
  );
}

function ActivityRow({ activity, t }: { activity: ToolActivity; t: Translator }) {
  const status =
    activity.outcome === 'done'
      ? t('toolDone')
      : activity.outcome === 'rejected'
        ? t('toolRejected')
        : t('toolFailed');

  return (
    <div className={`activity ${activity.outcome}`} role="status">
      <span className="activity-status">{status}</span>
      <span className="activity-label">{activity.label}</span>
      {activity.detail ? <span className="activity-detail">{activity.detail}</span> : null}
    </div>
  );
}
