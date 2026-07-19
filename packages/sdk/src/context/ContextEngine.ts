import type { CopilotInit, PageContextSnapshot } from '../types';

const MAX_BUSINESS_BYTES = 4096;

export class ContextEngine {
  constructor(private readonly init: CopilotInit) {}

  async getBusinessContext(): Promise<Record<string, unknown>> {
    if (!this.init.contextProvider) {
      return {};
    }
    const raw = await this.init.contextProvider();
    const json = JSON.stringify(raw ?? {});
    if (new TextEncoder().encode(json).length > MAX_BUSINESS_BYTES) {
      console.warn('[EnterpriseCopilot] businessContext exceeds 4KB; truncating keys');
      return truncateObject(raw ?? {}, MAX_BUSINESS_BYTES);
    }
    return raw ?? {};
  }

  async buildPayload(page: PageContextSnapshot, message: string) {
    const businessContext = await this.getBusinessContext();
    return {
      appId: this.init.appId,
      message,
      pageContext: page,
      businessContext,
      clientTools: [],
    };
  }
}

function truncateObject(input: Record<string, unknown>, maxBytes: number): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(input)) {
    const candidate = { ...out, [key]: value };
    if (new TextEncoder().encode(JSON.stringify(candidate)).length > maxBytes) {
      break;
    }
    out[key] = value;
  }
  return out;
}
