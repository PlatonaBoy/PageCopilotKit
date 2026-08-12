import type { ChatRequestBody } from '../transport/sseClient';
import type { CopilotInit, PageContextSnapshot } from '../types';

const MAX_BUSINESS_BYTES = 4096;

export class ContextEngine {
  constructor(private readonly init: CopilotInit) {}

  async getBusinessContext(): Promise<Record<string, unknown>> {
    if (!this.init.contextProvider) {
      return {};
    }
    let raw: Record<string, unknown>;
    try {
      raw = (await this.init.contextProvider()) ?? {};
    } catch (err) {
      // A broken provider must not take the whole turn down.
      console.warn('[EnterpriseCopilot] contextProvider threw; continuing without it', err);
      return {};
    }

    const serialized = safeStringify(raw);
    if (serialized === null) {
      console.warn('[EnterpriseCopilot] contextProvider returned non-serializable data; ignoring');
      return {};
    }
    if (byteLength(serialized) <= MAX_BUSINESS_BYTES) {
      return raw;
    }
    console.warn(
      `[EnterpriseCopilot] businessContext exceeds ${MAX_BUSINESS_BYTES} bytes; dropping trailing keys`,
    );
    return truncateObject(raw, MAX_BUSINESS_BYTES);
  }

  async buildPayload(page: PageContextSnapshot, message: string): Promise<ChatRequestBody> {
    const businessContext = await this.getBusinessContext();
    return {
      appId: this.init.appId,
      message,
      pageContext: page,
      businessContext,
    };
  }
}

function truncateObject(input: Record<string, unknown>, maxBytes: number): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(input)) {
    const candidate = { ...out, [key]: value };
    const serialized = safeStringify(candidate);
    if (serialized === null || byteLength(serialized) > maxBytes) {
      break;
    }
    out[key] = value;
  }
  return out;
}

function safeStringify(value: unknown): string | null {
  try {
    return JSON.stringify(value) ?? null;
  } catch {
    return null;
  }
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}
