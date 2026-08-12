import type { PageEngine } from '../page/PageEngine';
import type { CopilotTool, PageActionOptions } from '../types';

/**
 * Built-in DOM automation tools.
 *
 * Disabled unless the host opts in via `pageActions.enabled`. Every action targets a control by the
 * `ref` from the latest page snapshot, and the registry re-verifies the element immediately before
 * acting, so a re-render between planning and execution fails instead of hitting the wrong control.
 *
 * Risk classification: reading and scrolling are `read` (no side effects). Clicking, filling and
 * selecting are `write` — a click may submit, and a fill may trigger input handlers. Hosts can
 * auto-approve fill/select explicitly when smooth form filling matters more than a prompt.
 */
export function createPageActionTools(
  page: PageEngine,
  options: PageActionOptions | undefined,
): CopilotTool[] {
  if (!options?.enabled) {
    return [];
  }
  const autoApprove = new Set(options.autoApprove ?? []);

  const refSchema = {
    type: 'object',
    properties: {
      ref: { type: 'string', description: 'Control ref from the CONTROLS list in the page context' },
    },
    required: ['ref'],
  };

  return [
    {
      name: 'page_read',
      description:
        'Read the current text or value of one control on the page. Use to verify state before or after acting.',
      parameters: refSchema,
      risk: 'read',
      execute: (args) => {
        const el = resolve(page, args);
        const described = page.registry.describe(String(args.ref));
        return {
          name: described?.name ?? '',
          kind: described?.kind ?? 'other',
          value: currentValue(el),
        };
      },
    },
    {
      name: 'page_scroll',
      description: 'Scroll a control into view so the user can see what is being discussed.',
      parameters: refSchema,
      risk: 'read',
      execute: (args) => {
        const el = resolve(page, args);
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        return { scrolled: true };
      },
    },
    {
      name: 'page_fill',
      description:
        'Type a value into a text input or textarea. Fires the input and change events the application listens to.',
      parameters: {
        type: 'object',
        properties: {
          ref: refSchema.properties.ref,
          value: { type: 'string', description: 'Value to type' },
        },
        required: ['ref', 'value'],
      },
      risk: 'write',
      confirm: autoApprove.has('page_fill') ? false : undefined,
      execute: async (args) => {
        const el = resolve(page, args);
        const value = String(args.value ?? '');
        if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) {
          throw new Error(`control "${describe(page, args)}" is not a text field`);
        }
        if (el.readOnly || el.disabled) {
          throw new Error(`control "${describe(page, args)}" is not editable`);
        }
        setNativeValue(el, value);
        el.focus();
        await settle();
        return { filled: true, value };
      },
    },
    {
      name: 'page_select',
      description: 'Choose an option in a dropdown by its visible label or value.',
      parameters: {
        type: 'object',
        properties: {
          ref: refSchema.properties.ref,
          option: { type: 'string', description: 'Visible option label, or its value' },
        },
        required: ['ref', 'option'],
      },
      risk: 'write',
      confirm: autoApprove.has('page_select') ? false : undefined,
      execute: async (args) => {
        const el = resolve(page, args);
        if (!(el instanceof HTMLSelectElement)) {
          throw new Error(`control "${describe(page, args)}" is not a dropdown`);
        }
        const wanted = String(args.option ?? '').trim();
        if (!wanted) {
          // Substring matching would otherwise treat "" as matching every label and silently
          // select the first option.
          throw new Error('option is required');
        }
        const match =
          Array.from(el.options).find(
            (opt) => opt.textContent?.trim() === wanted || opt.value === wanted,
          ) ?? Array.from(el.options).find((opt) => opt.textContent?.trim().includes(wanted));
        if (!match) {
          const available = Array.from(el.options)
            .map((o) => o.textContent?.trim())
            .filter(Boolean)
            .join(', ');
          throw new Error(`option "${wanted}" not found. Available: ${available}`);
        }
        el.value = match.value;
        el.dispatchEvent(new Event('change', { bubbles: true }));
        await settle();
        return { selected: match.textContent?.trim() ?? match.value };
      },
    },
    {
      name: 'page_click',
      description:
        'Click a button, link, checkbox or radio on the page. May submit a form or navigate.',
      parameters: refSchema,
      risk: 'write',
      execute: async (args) => {
        const el = resolve(page, args);
        const label = describe(page, args);
        el.click();
        await settle();
        // The DOM almost certainly changed; drop the cached snapshot so the next observation is fresh.
        page.invalidate();
        return { clicked: label };
      },
    },
  ];
}

function resolve(page: PageEngine, args: Record<string, unknown>): HTMLElement {
  const ref = String(args.ref ?? '');
  if (!ref) {
    throw new Error('ref is required');
  }
  const result = page.registry.resolve(ref);
  if ('error' in result) {
    throw new Error(result.error);
  }
  return result.element;
}

function describe(page: PageEngine, args: Record<string, unknown>): string {
  return page.registry.describe(String(args.ref ?? ''))?.name ?? String(args.ref ?? '');
}

function currentValue(el: HTMLElement): string {
  if (el instanceof HTMLInputElement) {
    if (el.type === 'checkbox' || el.type === 'radio') return el.checked ? 'checked' : 'unchecked';
    return el.value;
  }
  if (el instanceof HTMLTextAreaElement) return el.value;
  if (el instanceof HTMLSelectElement) {
    return el.selectedOptions[0]?.textContent?.trim() ?? el.value;
  }
  return (el.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 500);
}

/**
 * Assigns through the native value setter so React/Vue controlled inputs observe the change.
 * Assigning `el.value` directly is swallowed by React's synthetic event system.
 */
function setNativeValue(el: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  const prototype = el instanceof HTMLTextAreaElement
    ? HTMLTextAreaElement.prototype
    : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
  if (setter) {
    setter.call(el, value);
  } else {
    el.value = value;
  }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

/** Yields a frame so the application can react before the next action observes the DOM. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 50));
}
