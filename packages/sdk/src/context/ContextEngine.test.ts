import { describe, expect, it, vi } from 'vitest';
import { ContextEngine } from './ContextEngine';
import type { CopilotInit, PageContextSnapshot } from '../types';

const page: PageContextSnapshot = {
  url: 'http://x',
  title: 't',
  summary: 's',
  actionableElements: [],
};

function engineWith(provider?: CopilotInit['contextProvider']) {
  const init: CopilotInit = {
    appId: 'crm',
    gatewayUrl: 'http://gw',
    getAccessToken: () => 'token',
    contextProvider: provider,
  };
  return new ContextEngine(init);
}

describe('ContextEngine', () => {
  it('returns an empty object when no provider is configured', async () => {
    await expect(engineWith().getBusinessContext()).resolves.toEqual({});
  });

  it('passes through context within the size budget', async () => {
    const engine = engineWith(() => ({ orderId: 'ORD-1', amount: 50000 }));
    await expect(engine.getBusinessContext()).resolves.toEqual({ orderId: 'ORD-1', amount: 50000 });
  });

  it('drops trailing keys when the context exceeds 4KB', async () => {
    const engine = engineWith(() => ({ keep: 'small', huge: 'x'.repeat(5000) }));

    const result = await engine.getBusinessContext();

    expect(result.keep).toBe('small');
    expect(result.huge).toBeUndefined();
  });

  it('survives a provider that throws', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const engine = engineWith(() => {
      throw new Error('host bug');
    });

    await expect(engine.getBusinessContext()).resolves.toEqual({});
  });

  it('survives non-serializable context', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const circular: Record<string, unknown> = {};
    circular.self = circular;

    await expect(engineWith(() => circular).getBusinessContext()).resolves.toEqual({});
  });

  it('builds a request payload carrying page and business context', async () => {
    const engine = engineWith(() => ({ status: '待审批' }));

    const payload = await engine.buildPayload(page, '状态？');

    expect(payload).toEqual({
      appId: 'crm',
      message: '状态？',
      pageContext: page,
      businessContext: { status: '待审批' },
    });
  });
});
