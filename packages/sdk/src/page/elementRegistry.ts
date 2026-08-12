import type { ActionableElement } from '../types';

/**
 * Maps snapshot refs back to live DOM elements.
 *
 * A ref is only meaningful for the snapshot that produced it. Because a SPA can re-render between
 * the model deciding to act and the action running, resolution re-verifies that the element is
 * still attached, visible, enabled, and still carries the accessible name the model was shown.
 * Anything else fails loudly — clicking a control that silently changed identity is worse than
 * reporting that the page moved on.
 */
export class ElementRegistry {
  private generation = 0;
  private entries = new Map<string, { element: WeakRef<HTMLElement>; described: ActionableElement }>();

  /** Starts a new snapshot generation, invalidating all previous refs. */
  begin(): void {
    this.generation += 1;
    this.entries.clear();
  }

  register(element: HTMLElement, described: Omit<ActionableElement, 'ref'>): ActionableElement {
    const ref = `e${this.generation}_${this.entries.size + 1}`;
    const full: ActionableElement = { ref, ...described };
    this.entries.set(ref, { element: new WeakRef(element), described: full });
    return full;
  }

  resolve(ref: string): { element: HTMLElement } | { error: string } {
    const entry = this.entries.get(ref);
    if (!entry) {
      return { error: `unknown element ref "${ref}" — request a fresh page snapshot` };
    }
    const element = entry.element.deref();
    if (!element || !element.isConnected) {
      return { error: `element "${entry.described.name}" is no longer on the page` };
    }
    if (!isVisible(element)) {
      return { error: `element "${entry.described.name}" is not visible` };
    }
    if (isDisabled(element)) {
      return { error: `element "${entry.described.name}" is disabled` };
    }
    const currentName = accessibleName(element);
    if (currentName && entry.described.name && !namesMatch(currentName, entry.described.name)) {
      return {
        error: `element "${entry.described.name}" now reads "${currentName}" — the page changed, request a fresh snapshot`,
      };
    }
    return { element };
  }

  describe(ref: string): ActionableElement | undefined {
    return this.entries.get(ref)?.described;
  }

  clear(): void {
    this.entries.clear();
  }
}

export function isVisible(el: HTMLElement): boolean {
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false;
  }
  const rect = el.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}

export function isDisabled(el: HTMLElement): boolean {
  if ('disabled' in el && (el as HTMLButtonElement).disabled) {
    return true;
  }
  return el.getAttribute('aria-disabled') === 'true';
}

export function accessibleName(el: HTMLElement): string {
  const aria = el.getAttribute('aria-label');
  if (aria?.trim()) return aria.trim();

  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    const label = el.labels?.[0]?.textContent ?? '';
    return normalize(label || el.placeholder || el.name || el.id);
  }
  if (el instanceof HTMLSelectElement) {
    const label = el.labels?.[0]?.textContent ?? '';
    return normalize(label || el.name || el.id);
  }
  return normalize(el.textContent ?? '');
}

/** Truncation and whitespace differences are tolerated; a different label is not. */
function namesMatch(current: string, described: string): boolean {
  const a = normalize(current);
  const b = normalize(described);
  if (!a || !b) return true;
  return a === b || a.startsWith(b) || b.startsWith(a);
}

function normalize(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}
