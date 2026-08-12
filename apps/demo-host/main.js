const GATEWAY = import.meta.env.VITE_GATEWAY_URL || 'http://localhost:8080';

const order = {
  orderId: 'ORD-123456',
  status: '待审批',
  amount: 50000,
  currency: 'CNY',
  customerId: '10001',
  customerName: '华东制造有限公司',
  level: 'VIP',
  applicant: '李四',
};

/**
 * Demo-only token minting. A real host passes its own CAS/OIDC JWT here — the gateway only trusts
 * claims it can verify, never a client-supplied user id.
 */
async function fetchDemoToken() {
  const res = await fetch(`${GATEWAY}/v1/demo/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sub: 'zhangsan',
      name: '张三',
      tenantId: 'demo',
      roles: ['manager'],
      permissions: ['order:view', 'order:approve', 'audit:read'],
    }),
  });
  if (!res.ok) {
    throw new Error(
      `无法获取演示令牌 (${res.status})。请确认 Gateway 以 dev profile 启动（SPRING_PROFILES_ACTIVE=dev）。`,
    );
  }
  const data = await res.json();
  return data;
}

function setStatus(next) {
  order.status = next;
  const el = document.getElementById('order-status');
  if (el) el.textContent = next;
}

function bindActions() {
  document.getElementById('btn-approve')?.addEventListener('click', () => {
    setStatus('审批中');
    notify('已提交审批（演示）');
  });
  document.getElementById('btn-reject')?.addEventListener('click', () => {
    setStatus('已拒绝');
    notify('已拒绝该订单（演示）');
  });
  document.getElementById('btn-export')?.addEventListener('click', () => {
    notify('已导出 Excel（演示）');
  });
}

function notify(message) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
  setTimeout(() => {
    el.hidden = true;
  }, 2200);
}

/**
 * Resolves the SDK API across load styles (IIFE global, namespace object, or ESM default) so a
 * bundler quirk can never leave the host without an `init`.
 */
function resolveCopilotApi() {
  const candidates = [
    window.EnterpriseCopilot,
    window.Copilot,
    window.EnterpriseCopilotBundle?.default,
    window.EnterpriseCopilotBundle,
  ];
  for (const candidate of candidates) {
    if (candidate && typeof candidate.init === 'function') return candidate;
    if (candidate?.default && typeof candidate.default.init === 'function') return candidate.default;
  }
  return null;
}

async function boot() {
  bindActions();

  const api = resolveCopilotApi();
  if (!api) {
    console.error('[demo-host] Enterprise Copilot SDK 未加载：请先运行 npm run build:sdk');
    notify('Copilot SDK 未加载，请先构建 SDK');
    return;
  }

  let cached = null;
  let expiresAt = 0;

  api.init({
    appId: 'crm',
    gatewayUrl: GATEWAY,
    ui: {
      title: '企业 AI 助手',
      locale: 'zh-CN',
      position: 'bottom-right',
      primaryColor: '#0f4c81',
    },
    async getAccessToken() {
      // Refresh slightly before expiry so a long-lived page never sends a stale token.
      if (!cached || Date.now() > expiresAt) {
        const token = await fetchDemoToken();
        cached = token.accessToken;
        expiresAt = Date.now() + Math.max((token.expiresIn - 60) * 1000, 30_000);
      }
      return cached;
    },
    contextProvider() {
      return { ...order };
    },
    onError(error) {
      console.warn('[demo-host] copilot error', error);
    },
  });

  console.info('[demo-host] Enterprise Copilot initialized →', GATEWAY);
}

boot().catch((err) => {
  console.error(err);
  notify(err.message || 'Copilot 初始化失败');
});
