import type { ActionableElement, PageContextSnapshot } from '../types';

/**
 * PageEngine — page perception for Enterprise Copilot.
 *
 * Alibaba PageAgent is the planned execution runtime (phase 2: click/fill via agent loop).
 * For MVP chat grounding we extract a dehydrated DOM snapshot ourselves (same-document only),
 * matching PageAgent's "live in the page / read DOM as text" approach without invoking an LLM
 * on every message.
 *
 * Out of scope for MVP: cross-origin iframes, deep Shadow DOM piercing, visual (screenshot) agents.
 */

const MAX_SUMMARY_CHARS = 12_000;
const MAX_ELEMENTS = 40;

const INTERACTIVE_SELECTOR = [
  'button',
  'a[href]',
  'input',
  'select',
  'textarea',
  '[role="button"]',
  '[role="link"]',
  '[role="tab"]',
  '[role="menuitem"]',
  '[onclick]',
].join(',');

const SENSITIVE_INPUT =
  /password|passwd|pwd|secret|token|ssn|idcard|身份证|密码/i;

const HISTORY_PATCHED = '__enterpriseCopilotHistoryPatched';

export class PageEngine {
  private stale = true;
  private cache: PageContextSnapshot | null = null;
  private observer: MutationObserver | null = null;
  private started = false;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  start(): void {
    if (this.started || typeof window === 'undefined') {
      return;
    }
    this.started = true;
    this.patchHistory();
    window.addEventListener('popstate', this.invalidate);
    window.addEventListener('copilot:location', this.invalidate);
    this.observer = new MutationObserver(() => this.scheduleInvalidate());
    this.observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      characterData: true,
    });
  }

  stop(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('popstate', this.invalidate);
      window.removeEventListener('copilot:location', this.invalidate);
    }
    this.observer?.disconnect();
    this.observer = null;
    this.started = false;
  }

  invalidate = (): void => {
    this.stale = true;
  };

  async snapshot(): Promise<PageContextSnapshot> {
    this.start();
    if (!this.stale && this.cache) {
      return this.cache;
    }
    const snap = captureSnapshot();
    this.cache = snap;
    this.stale = false;
    return snap;
  }

  private scheduleInvalidate(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
    }
    this.debounceTimer = setTimeout(() => {
      this.invalidate();
    }, 1000);
  }

  private patchHistory(): void {
    if ((history as unknown as Record<string, unknown>)[HISTORY_PATCHED]) {
      return;
    }
    (history as unknown as Record<string, unknown>)[HISTORY_PATCHED] = true;

    const wrap = (type: 'pushState' | 'replaceState') => {
      const original = history[type].bind(history);
      history[type] = function (this: History, ...args: Parameters<History['pushState']>) {
        const result = original(...args);
        window.dispatchEvent(new Event('copilot:location'));
        return result;
      };
    };
    wrap('pushState');
    wrap('replaceState');
  }
}

function captureSnapshot(): PageContextSnapshot {
  const actionableElements = collectActionable();
  const summary = buildSummary(actionableElements);
  return {
    url: location.href,
    title: document.title || '',
    summary,
    actionableElements,
  };
}

function collectActionable(): ActionableElement[] {
  const nodes = Array.from(document.querySelectorAll<HTMLElement>(INTERACTIVE_SELECTOR));
  const out: ActionableElement[] = [];
  for (const el of nodes) {
    if (!isVisible(el)) continue;
    if (el instanceof HTMLInputElement && (el.type === 'password' || SENSITIVE_INPUT.test(el.name) || SENSITIVE_INPUT.test(el.id))) {
      continue;
    }
    const name = getAccessibleName(el);
    if (!name) continue;
    const role = el.getAttribute('role') || el.tagName.toLowerCase();
    const hint =
      el.getAttribute('data-action') ||
      el.getAttribute('name') ||
      el.id ||
      undefined;
    out.push({ name: name.slice(0, 80), role, hint });
    if (out.length >= MAX_ELEMENTS) break;
  }
  return out;
}

function buildSummary(actions: ActionableElement[]): string {
  const headings = Array.from(document.querySelectorAll('h1,h2,h3'))
    .slice(0, 12)
    .map((h) => textOf(h))
    .filter(Boolean);

  const main =
    document.querySelector('main') ||
    document.querySelector('[role="main"]') ||
    document.body;

  const bodyText = sanitizeText(textOf(main)).slice(0, MAX_SUMMARY_CHARS);

  const lines = [
    `TITLE: ${document.title}`,
    `URL: ${location.href}`,
    headings.length ? `HEADINGS:\n- ${headings.join('\n- ')}` : '',
    actions.length
      ? `ACTIONS:\n${actions.map((a) => `- [${a.role}] ${a.name}`).join('\n')}`
      : '',
    'VISIBLE_TEXT:',
    bodyText,
  ].filter(Boolean);

  return lines.join('\n\n').slice(0, MAX_SUMMARY_CHARS);
}

function getAccessibleName(el: HTMLElement): string {
  const aria = el.getAttribute('aria-label');
  if (aria?.trim()) return aria.trim();
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    const label = el.labels?.[0] ? textOf(el.labels[0]) : '';
    const ph = el.placeholder || '';
    return (label || ph || el.name || el.id || '').trim();
  }
  if (el instanceof HTMLSelectElement) {
    return (el.name || el.id || textOf(el)).trim();
  }
  return textOf(el).trim();
}

function textOf(node: Element | null | undefined): string {
  if (!node) return '';
  return sanitizeText((node.textContent || '').replace(/\s+/g, ' ').trim());
}

function sanitizeText(text: string): string {
  // Redact obvious long digit sequences that may be IDs / cards
  return text.replace(/\b\d{15,19}\b/g, '[redacted-number]');
}

function isVisible(el: HTMLElement): boolean {
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false;
  }
  const rect = el.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}
