import type { ActionableElement, PageContextSnapshot } from '../types';
import { ElementRegistry, accessibleName, isDisabled, isVisible } from './elementRegistry';

/**
 * PageEngine — page perception for Enterprise Copilot.
 *
 * Extracts a dehydrated, structured view of the live DOM: headings, fields, tables, the user's
 * selection, and the interactive controls available. Structure is preserved (tables become Markdown,
 * forms become label/value pairs) because a flat text dump makes the model guess at relationships.
 *
 * Each interactive control gets a `ref` so page actions can target it; the registry re-verifies the
 * element before any action runs.
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
  readonly registry = new ElementRegistry();

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
    this.registry.clear();
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
    const snap = this.capture(selection);
    this.cache = snap;
    this.stale = false;
    return snap;
  }

  /** Forces a fresh capture, e.g. right after a page action changed the DOM. */
  async refresh(): Promise<PageContextSnapshot> {
    this.invalidate();
    return this.snapshot();
  }

  private capture(selection: string): PageContextSnapshot {
    // A new generation invalidates refs from the previous snapshot on purpose: acting on a stale
    // ref would risk targeting a re-rendered element.
    this.registry.begin();
    const actionableElements = this.collectActionable();
    const summary = buildSummary(actionableElements);
    return {
      url: location.href,
      title: document.title || '',
      summary,
      actionableElements,
      selection,
    };
  }

  private collectActionable(): ActionableElement[] {
    const nodes = Array.from(document.querySelectorAll<HTMLElement>(INTERACTIVE_SELECTOR));
    const inViewport: HTMLElement[] = [];
    const offscreen: HTMLElement[] = [];

    for (const el of nodes) {
      if (isIgnored(el) || !isVisible(el) || isSensitiveInput(el)) continue;
      if (!accessibleName(el)) continue;
      (isInViewport(el) ? inViewport : offscreen).push(el);
      if (inViewport.length + offscreen.length >= MAX_ELEMENTS * 3) break;
    }

    // Controls the user can actually see matter more than those far down the page.
    return [...inViewport, ...offscreen].slice(0, MAX_ELEMENTS).map((el) =>
      this.registry.register(el, {
        name: accessibleName(el).slice(0, 80),
        role: el.getAttribute('role') || el.tagName.toLowerCase(),
        hint: el.getAttribute('data-action') || el.getAttribute('name') || el.id || undefined,
        kind: kindOf(el),
        disabled: isDisabled(el) || undefined,
        value: readableValue(el),
      }),
    );
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

function kindOf(el: HTMLElement): ActionableElement['kind'] {
  if (el instanceof HTMLSelectElement) return 'select';
  if (el instanceof HTMLTextAreaElement) return 'text';
  if (el instanceof HTMLInputElement) {
    if (el.type === 'checkbox') return 'checkbox';
    if (el.type === 'radio') return 'radio';
    if (el.type === 'button' || el.type === 'submit' || el.type === 'reset') return 'button';
    return 'text';
  }
  if (el instanceof HTMLButtonElement) return 'button';
  const role = el.getAttribute('role');
  if (role === 'button') return 'button';
  if (el instanceof HTMLAnchorElement || role === 'link') return 'link';
  return 'other';
}

function readableValue(el: HTMLElement): string | undefined {
  if (isSensitiveInput(el)) return undefined;
  if (el instanceof HTMLInputElement) {
    if (el.type === 'checkbox' || el.type === 'radio') {
      return el.checked ? 'checked' : 'unchecked';
    }
    return el.value ? sanitizeText(el.value).slice(0, 120) : undefined;
  }
  if (el instanceof HTMLTextAreaElement) {
    return el.value ? sanitizeText(el.value).slice(0, 120) : undefined;
  }
  if (el instanceof HTMLSelectElement) {
    const selected = el.selectedOptions[0]?.textContent?.trim();
    return selected ? sanitizeText(selected).slice(0, 120) : undefined;
  }
  return undefined;
}

function captureSelection(): string {
  if (typeof window === 'undefined') return '';
  const text = window.getSelection()?.toString() ?? '';
  const trimmed = text.replace(/\s+/g, ' ').trim();
  return trimmed ? sanitizeText(trimmed).slice(0, MAX_SELECTION_CHARS) : '';
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
    // Refs are included so the model can target a control precisely.
    sections.push(
      `CONTROLS (use ref with page actions):\n${actions
        .map((a) => {
          const parts = [`ref=${a.ref}`, `kind=${a.kind}`, `name="${a.name}"`];
          if (a.value) parts.push(`value="${a.value}"`);
          if (a.disabled) parts.push('disabled');
          return `- ${parts.join(' ')}`;
        })
        .join('\n')}`,
    );
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
    for (const dt of Array.from(dl.querySelectorAll('dt'))) {
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
      if (index === 0 && row.querySelector('th')) {
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

function isInViewport(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect();
  return rect.top < window.innerHeight && rect.bottom > 0;
}

export { isSensitiveInput };
