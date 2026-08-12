import type { CopilotTool, ToolRisk } from '../types';

export interface ToolSchema {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  risk: ToolRisk;
}

export interface ToolExecution {
  ok: boolean;
  result?: unknown;
  error?: string;
}

const NAME_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;

export class ToolRegistry {
  private tools = new Map<string, CopilotTool>();

  register(tool: CopilotTool): void {
    if (!tool || !NAME_PATTERN.test(tool.name)) {
      throw new Error(
        `[EnterpriseCopilot] invalid tool name "${tool?.name}": use letters, digits and underscore`,
      );
    }
    if (typeof tool.execute !== 'function') {
      throw new Error(`[EnterpriseCopilot] tool "${tool.name}" needs an execute function`);
    }
    this.tools.set(tool.name, tool);
  }

  registerAll(tools: CopilotTool[] | undefined): void {
    for (const tool of tools ?? []) {
      this.register(tool);
    }
  }

  unregister(name: string): void {
    this.tools.delete(name);
  }

  get(name: string): CopilotTool | undefined {
    return this.tools.get(name);
  }

  has(name: string): boolean {
    return this.tools.has(name);
  }

  riskOf(name: string): ToolRisk {
    return this.tools.get(name)?.risk ?? 'write';
  }

  /**
   * Whether a call needs the user's explicit approval.
   *
   * `confirm` is an explicit override; otherwise risk decides: `write` asks, `read` does not.
   * An unknown tool is treated as requiring confirmation, but it will be rejected before that.
   */
  needsConfirmation(name: string): boolean {
    const tool = this.tools.get(name);
    if (!tool) return true;
    if (typeof tool.confirm === 'boolean') return tool.confirm;
    return (tool.risk ?? 'write') === 'write';
  }

  /** Schemas advertised to the gateway for the current turn. */
  schemas(): ToolSchema[] {
    return Array.from(this.tools.values()).map((tool) => ({
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters ?? { type: 'object', properties: {} },
      risk: tool.risk ?? 'write',
    }));
  }

  /** Runs a tool, converting thrown errors into a result the model can reason about. */
  async execute(name: string, args: Record<string, unknown>): Promise<ToolExecution> {
    const tool = this.tools.get(name);
    if (!tool) {
      return { ok: false, error: `unknown tool "${name}"` };
    }
    try {
      const result = await tool.execute(args ?? {});
      return { ok: true, result: result ?? { ok: true } };
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : String(err) };
    }
  }

  clear(): void {
    this.tools.clear();
  }
}
