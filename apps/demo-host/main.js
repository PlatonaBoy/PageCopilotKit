const GATEWAY = import.meta.env.VITE_GATEWAY_URL || 'http://localhost:8080';

const order = {
  orderId: 'ORD-123456',
  status: '待审批',
  amount: 50000,
  customerId: '10001',
  customerName: '华东制造有限公司',
  level: 'VIP',
};

async function fetchDemoToken() {
  const res = await fetch(`${GATEWAY}/v1/demo/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sub: 'zhangsan',
      name: '张三',
      tenantId: 'demo',
      roles: ['manager'],
      permissions: ['order:view', 'order:approve'],
    }),
  });
  if (!res.ok) {
    throw new Error(`demo token failed: ${res.status}`);
  }
  const data = await res.json();
  return data.accessToken;
}

function bindActions() {
  document.getElementById('btn-approve')?.addEventListener('click', () => {
    order.status = '审批中';
    document.getElementById('order-status').textContent = order.status;
    alert('已提交审批（demo）');
  });
  document.getElementById('btn-reject')?.addEventListener('click', () => {
    order.status = '已拒绝';
    document.getElementById('order-status').textContent = order.status;
  });
  document.getElementById('btn-export')?.addEventListener('click', () => {
    alert('导出 Excel（demo）');
  });
}

async function boot() {
  bindActions();

  if (!window.EnterpriseCopilot && !window.Copilot) {
    console.error('EnterpriseCopilot SDK failed to load');
    return;
  }

  const api = window.EnterpriseCopilot || window.Copilot;
  let cachedToken = '';
  let tokenExpires = 0;

  api.init({
    appId: 'crm',
    gatewayUrl: GATEWAY,
    ui: { title: '企业 AI Copilot', locale: 'zh-CN' },
    async getAccessToken() {
      const now = Date.now();
      if (!cachedToken || now > tokenExpires) {
        cachedToken = await fetchDemoToken();
        tokenExpires = now + 50 * 60 * 1000;
      }
      return cachedToken;
    },
    contextProvider() {
      return {
        orderId: order.orderId,
        status: order.status,
        amount: order.amount,
        customerId: order.customerId,
        customerName: order.customerName,
        level: order.level,
      };
    },
  });

  console.info('[demo-host] Enterprise Copilot initialized →', GATEWAY);
}

boot().catch((err) => {
  console.error(err);
  alert(`Copilot 初始化失败：${err.message}`);
});
