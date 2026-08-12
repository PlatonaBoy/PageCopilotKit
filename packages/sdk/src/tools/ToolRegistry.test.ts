import { describe, expect, it, vi } from 'vitest';
import { ToolRegistry } from './ToolRegistry';
import type { CopilotTool } from '../types';

function tool(overrides: Partial<CopilotTool> = {}): CopilotTool {
  return {
    name: 'approveOrder',
    description: '审批当前订单',
    execute: vi.fn().mockResolvedValue({ ok: true }),
    ...overrides,
  };
}

describe('ToolRegistry', () => {
  it('rejects invalid tool names', () => {
    const registry = new ToolRegistry();
    expect(() => registry.register(tool({ name: '' }))).toThrow(/invalid tool name/);
    expect(() => registry.register(tool({ name: 'has space' }))).toThrow(/invalid tool name/);
    expect(() => registry.register(tool({ name: '1leading' }))).toThrow(/invalid tool name/);
  });

  it('rejects a tool without an execute function', () => {
    const registry = new ToolRegistry();
    expect(() =>
      registry.register({ name: 'x', description: 'x' } as unknown as CopilotTool),
    ).toThrow(/execute function/);
  });

  it('requires confirmation for write risk and skips it for read', () => {
    const registry = new ToolRegistry();
    registry.register(tool({ name: 'writeThing', risk: 'write' }));
    registry.register(tool({ name: 'readThing', risk: 'read' }));

    expect(registry.needsConfirmation('writeThing')).toBe(true);
    expect(registry.needsConfirmation('readThing')).toBe(false);
  });

  it('treats a tool with no declared risk as a write', () => {
    const registry = new ToolRegistry();
    registry.register(tool({ name: 'unclear', risk: undefined }));

    expect(registry.riskOf('unclear')).toBe('write');
    expect(registry.needsConfirmation('unclear')).toBe(true);
  });

  it('honors an explicit confirm override in both directions', () => {
    const registry = new ToolRegistry();
    registry.register(tool({ name: 'silentWrite', risk: 'write', confirm: false }));
    registry.register(tool({ name: 'guardedRead', risk: 'read', confirm: true }));

    expect(registry.needsConfirmation('silentWrite')).toBe(false);
    expect(registry.needsConfirmation('guardedRead')).toBe(true);
  });

  it('requires confirmation for an unknown tool', () => {
    expect(new ToolRegistry().needsConfirmation('nope')).toBe(true);
  });

  it('advertises schemas with a default parameters object', () => {
    const registry = new ToolRegistry();
    registry.register(tool({ name: 'noParams', parameters: undefined, risk: 'read' }));

    expect(registry.schemas()).toEqual([
      {
        name: 'noParams',
        description: '审批当前订单',
        parameters: { type: 'object', properties: {} },
        risk: 'read',
      },
    ]);
  });

  it('runs a tool and returns its result', async () => {
    const registry = new ToolRegistry();
    const execute = vi.fn().mockResolvedValue({ orderId: 'ORD-1' });
    registry.register(tool({ execute }));

    const outcome = await registry.execute('approveOrder', { orderId: 'ORD-1' });

    expect(execute).toHaveBeenCalledWith({ orderId: 'ORD-1' });
    expect(outcome).toEqual({ ok: true, result: { orderId: 'ORD-1' } });
  });

  it('converts a thrown error into a result the model can reason about', async () => {
    const registry = new ToolRegistry();
    registry.register(
      tool({
        execute: () => {
          throw new Error('订单状态不允许审批');
        },
      }),
    );

    const outcome = await registry.execute('approveOrder', {});

    expect(outcome.ok).toBe(false);
    expect(outcome.error).toBe('订单状态不允许审批');
  });

  it('reports an unknown tool instead of throwing', async () => {
    const outcome = await new ToolRegistry().execute('ghost', {});
    expect(outcome).toEqual({ ok: false, error: 'unknown tool "ghost"' });
  });

  it('unregister removes the tool from the advertised set', () => {
    const registry = new ToolRegistry();
    registry.register(tool());
    registry.unregister('approveOrder');

    expect(registry.has('approveOrder')).toBe(false);
    expect(registry.schemas()).toEqual([]);
  });
});
