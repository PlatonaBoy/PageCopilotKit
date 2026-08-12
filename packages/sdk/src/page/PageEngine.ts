import type { ActionableElement, PageContextSnapshot } from '../types';

/**
 * PageEngine — page perception for Enterprise Copilot.
 *
 * Extracts a dehydrated, structured view of the live DOM: headings, tables, form fields, the user's
 * selection, and the interactive controls available. Structure is preserved (tables become Markdown,
 * forms become label/value pairs) because a flat text dump makes the model guess at relationships.
 *
 * Scope: the current document only. Cross-origin iframes and deep shadow roots are out of scope;
 * hosts can exclude regions with `data-copilot-ignore`.
 */

const MAX_SUMMARY_CHARS = 12_000;
const MAX_ELEMENTS = 40;
const MAX_TABLES = 5;
const MAX_TABLE_ROWS = 25;
const MAX_SELECTION_CHARS = 2_000;
const IGNORE_ATTR = 'data-copilot-ignore';
const HISTORY_PATCHED = '__enterpriseCopilotHistoryPatched';

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

const SENSITIVE_NAME = /password|passwd|pwd|secret|token|apikey|api_key|ssn|idcard|身份证|密码|密钥/i;

/** Patterns redacted before any page text leaves the browser. */
const REDACTIONS: Array<[RegExp, string]> = [
  [/\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b/g, '[redacted-email]'],
  [/\bsk-[A-Za-z0-9_-]{16,}\b/g, '[redacted-key]'],
  [/\b(?:\d[ -]?){13,19}\b/g, '[redacted-number]'],
  [/\b1[3-9]\d{9}\b/g, '[redacted-phone]'],
];

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

    // Observe the main content container when present; watching the whole document on a busy SPA
    // produces a mutation storm for no extra fidelity.
    const target =
      document.querySelector('main') ||
      document.querySelector('[role="main"]') ||
      document.body ||
      document.documentElement;
    this.observer = new MutationObserver(() => this.scheduleInvalidate());
    this.observer.observe(target, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  stop(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('popstate', this.invalidate);
      window.removeEventListener('copilot:location', this.invalidate);
    }
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.observer?.disconnect();
    this.observer = null;
    this.started = false;
    this.cache = null;
    this.stale = true;
  }

  invalidate = (): void => {
    this.stale = true;
  };

  async snapshot(): Promise<PageContextSnapshot> {
    this.start();
    const selection = captureSelection();
    if (!this.stale && this.cache) {
      // Selection changes constantly and is cheap, so refresh it without re-scanning the DOM.
      return { ...this.cache, selection };
    }
    const snap = captureSnapshot(selection);
    this.cache = snap;
    this.stale = false;
    return snap;
  }

  private scheduleInvalidate(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
    }
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = null;
      this.invalidate();
    }, 1000);
  }

  private patchHistory(): void {
    const flags = history as unknown as Record<string, unknown>;
    if (flags[HISTORY_PATCHED]) {
      return;
    }
    flags[HISTORY_PATCHED] = true;

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

function captureSnapshot(selection: string): PageContextSnapshot {
  const actionableElements = collectActionable();
  const summary = buildSummary(actionableElements);
  return {
    url: location.href,
    title: document.title || '',
    summary,
    actionableElements,
    selection,
  };
}

function captureSelection(): string {
  if (typeof window === 'undefined') return '';
  const text = window.getSelection()?.toString() ?? '';
  const trimmed = text.replace(/\s+/g, ' ').trim();
  return trimmed ? sanitizeText(trimmed).slice(0, MAX_SELECTION_CHARS) : '';
}

function collectActionable(): ActionableElement[] {
  const nodes = Array.from(document.querySelectorAll<HTMLElement>(INTERACTIVE_SELECTOR));
  const withinViewport: ActionableElement[] = [];
  const offscreen: ActionableElement[] = [];

  for (const el of nodes) {
    if (isIgnored(el) || !isVisible(el)) continue;
    if (isSensitiveInput(el)) continue;

    const name = getAccessibleName(el);
    if (!name) continue;

    const entry: ActionableElement = {
      name: name.slice(0, 80),
      role: el.getAttribute('role') || el.tagName.toLowerCase(),
      hint: el.getAttribute('data-action') || el.getAttribute('name') || el.id || undefined,
    };
    // Controls the user can actually see matter more than those far down the page.
    (isInViewport(el) ? withinViewport : offscreen).push(entry);
    if (withinViewport.length + offscreen.length >= MAX_ELEMENTS * 3) break;
  }

  return [...withinViewport, ...offscreen].slice(0, MAX_ELEMENTS);
}

function buildSummary(actions: ActionableElement[]): string {
  const sections: string[] = [`TITLE: ${document.title}`, `URL: ${location.href}`];

  const headings = queryVisible('h1,h2,h3')
    .slice(0, 12)
    .map((h) => textOf(h))
    .filter(Boolean);
  if (headings.length) {
    sections.push(`HEADINGS:\n- ${headings.join('\n- ')}`);
  }

  const fields = extractFields();
  if (fields.length) {
    sections.push(`FIELDS:\n${fields.map((f) => `- ${f.label}: ${f.value}`).join('\n')}`);
  }

  const tables = extractTables();
  if (tables.length) {
    sections.push(`TABLES:\n${tables.join('\n\n')}`);
  }

  if (actions.length) {
    sections.push(`ACTIONS:\n${actions.map((a) => `- [${a.role}] ${a.name}`).join('\n')}`);
  }

  const main =
    document.querySelector('main') || document.querySelector('[role="main"]') || document.body;
  const bodyText = visibleTextOf(main);
  if (bodyText) {
    sections.push(`PAGE_TEXT:\n${bodyText}`);
  }

  return sections.join('\n\n').slice(0, MAX_SUMMARY_CHARS);
}

/**
 * Text of a container with excluded subtrees removed.
 *
 * A raw `textContent` read would still include `data-copilot-ignore` regions and password fields,
 * so the container is cloned and those subtrees are dropped before reading.
 */
function visibleTextOf(container: Element | null): string {
  if (!container) return '';
  const clone = container.cloneNode(true) as HTMLElement;
  clone
    .querySelectorAll(`[${IGNORE_ATTR}], script, style, noscript, template, input[type="password"]`)
    .forEach((node) => node.remove());
  return textOf(clone);
}

/** Pulls label/value pairs from definition lists, form controls and common detail layouts. */
function extractFields(): Array<{ label: string; value: string }> {
  const out: Array<{ label: string; value: string }> = [];
  const seen = new Set<string>();

  const push = (label: string, value: string) => {
    const cleanLabel = label.replace(/[:：]\s*$/, '').trim();
    const cleanValue = value.trim();
    if (!cleanLabel || !cleanValue || cleanLabel === cleanValue) return;
    const key = `${cleanLabel}=${cleanValue}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ label: cleanLabel.slice(0, 60), value: cleanValue.slice(0, 200) });
  };

  for (const dl of queryVisible('dl')) {
    const terms = Array.from(dl.querySelectorAll('dt'));
    for (const dt of terms) {
      const dd = dt.nextElementSibling;
      if (dd && dd.tagName === 'DD') {
        push(textOf(dt), textOf(dd));
      }
    }
  }

  for (const label of queryVisible('label')) {
    const forId = label.getAttribute('for');
    const control = forId
      ? document.getElementById(forId)
      : label.querySelector('input,select,textarea');
    if (control instanceof HTMLElement && !isSensitiveInput(control)) {
      push(textOf(label), readControlValue(control));
    }
  }

  // Detail grids: a small label element followed by its value sibling.
  for (const wrapper of queryVisible('[class*="field"],[class*="item"],[class*="row"]')) {
    if (wrapper.children.length !== 2) continue;
    const [first, second] = Array.from(wrapper.children) as HTMLElement[];
    if (!first || !second) continue;
    const labelText = textOf(first);
    if (!labelText || labelText.length > 30) continue;
    push(labelText, textOf(second));
    if (out.length > 60) break;
  }

  return out.slice(0, 60);
}

/** Renders visible tables as Markdown so row/column relationships survive. */
function extractTables(): string[] {
  const out: string[] = [];
  for (const table of queryVisible('table')) {
    const rows = Array.from(table.querySelectorAll('tr')).slice(0, MAX_TABLE_ROWS);
    if (rows.length === 0) continue;

    const rendered: string[] = [];
    rows.forEach((row, index) => {
      const cells = Array.from(row.querySelectorAll('th,td')).map((cell) => textOf(cell) || '-');
      if (cells.length === 0) return;
      rendered.push(`| ${cells.join(' | ')} |`);
      const isHeaderRow = index === 0 && row.querySelector('th');
      if (isHeaderRow) {
        rendered.push(`| ${cells.map(() => '---').join(' | ')} |`);
      }
    });

    if (rendered.length) {
      const caption = textOf(table.querySelector('caption'));
      out.push((caption ? `${caption}\n` : '') + rendered.join('\n'));
    }
    if (out.length >= MAX_TABLES) break;
  }
  return out;
}

function readControlValue(control: HTMLElement): string {
  if (control instanceof HTMLInputElement) {
    if (control.type === 'checkbox' || control.type === 'radio') {
      return control.checked ? 'checked' : 'unchecked';
    }
    return control.value;
  }
  if (control instanceof HTMLTextAreaElement) {
    return control.value;
  }
  if (control instanceof HTMLSelectElement) {
    return control.selectedOptions[0]?.textContent?.trim() ?? control.value;
  }
  return textOf(control);
}

function queryVisible(selector: string): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(selector)).filter(
    (el) => !isIgnored(el) && isVisible(el),
  );
}

function getAccessibleName(el: HTMLElement): string {
  const aria = el.getAttribute('aria-label');
  if (aria?.trim()) return aria.trim();

  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    const label = el.labels?.[0] ? textOf(el.labels[0]) : '';
    return (label || el.placeholder || el.name || el.id || '').trim();
  }
  if (el instanceof HTMLSelectElement) {
    return (el.name || el.id || textOf(el)).trim();
  }
  return textOf(el).trim();
}

function isSensitiveInput(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement && el.type === 'password') return true;
  const name = `${el.getAttribute('name') ?? ''} ${el.id} ${el.getAttribute('autocomplete') ?? ''}`;
  return SENSITIVE_NAME.test(name);
}

function isIgnored(el: HTMLElement): boolean {
  return el.closest(`[${IGNORE_ATTR}]`) !== null;
}

function textOf(node: Element | null | undefined): string {
  if (!node) return '';
  return sanitizeText((node.textContent || '').replace(/\s+/g, ' ').trim());
}

function sanitizeText(text: string): string {
  let out = text;
  for (const [pattern, replacement] of REDACTIONS) {
    out = out.replace(pattern, replacement);
  }
  return out;
}

function isVisible(el: HTMLElement): boolean {
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false;
  }
  const rect = el.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}

function isInViewport(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect();
  return rect.top < window.innerHeight && rect.bottom > 0;
}
