import { expect, test, type Page } from '@playwright/test';

const ROOT = '#enterprise-copilot-root';

function shadow(page: Page) {
  return page.locator(ROOT);
}

async function openPanel(page: Page) {
  await page.waitForFunction(
    (root) => Boolean(document.querySelector(root)?.shadowRoot?.querySelector('button.launcher')),
    ROOT,
  );
  await shadow(page).locator('button.launcher').click();
  await expect(shadow(page).locator('.panel')).toBeVisible();
}

async function ask(page: Page, question: string) {
  const textarea = shadow(page).locator('textarea');
  await textarea.click();
  await textarea.fill(question);
  await shadow(page).locator('.composer button').click();
}

async function settled(page: Page) {
  await expect(shadow(page).locator('.panel')).toHaveAttribute('data-streaming', 'false', {
    timeout: 30_000,
  });
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await openPanel(page);
});

test('asks for confirmation before running a write action', async ({ page }) => {
  await ask(page, '帮我审批这个订单');

  const card = shadow(page).locator('.confirm');
  await expect(card).toBeVisible();
  await expect(card).toContainText('approveOrder');
  // The arguments the action will run with must be visible before approving.
  await expect(card.locator('.confirm-args')).toContainText('ORD-123456');

  // Nothing has happened on the page yet.
  await expect(page.locator('#order-status')).toHaveText('待审批');
});

test('executes the action on the page after approval', async ({ page }) => {
  await ask(page, '帮我审批这个订单');
  await shadow(page).locator('.confirm .approve').click();

  await expect(page.locator('#order-status')).toHaveText('审批中');
  await expect(shadow(page).locator('.activity.done')).toContainText('approveOrder');
  await settled(page);
});

test('leaves the page untouched when the user declines', async ({ page }) => {
  await ask(page, '帮我审批这个订单');
  await shadow(page).locator('.confirm .decline').click();

  await expect(page.locator('#order-status')).toHaveText('待审批');
  await expect(shadow(page).locator('.activity.rejected')).toBeVisible();
  await settled(page);
});

test('blocks the composer while a confirmation is pending', async ({ page }) => {
  await ask(page, '帮我审批这个订单');

  await expect(shadow(page).locator('.confirm')).toBeVisible();
  await expect(shadow(page).locator('textarea')).toBeDisabled();

  await shadow(page).locator('.confirm .decline').click();
  await expect(shadow(page).locator('textarea')).toBeEnabled();
});

test('fills a form field without asking, because fill is auto-approved here', async ({ page }) => {
  await ask(page, '帮我填写审批人为张三');
  await settled(page);

  await expect(page.locator('#reviewer')).toHaveValue('张三');
  // Auto-approved actions must still be logged, and must not show a confirmation card.
  await expect(shadow(page).locator('.activity.done')).toContainText('page_fill');
  await expect(shadow(page).locator('.confirm')).toHaveCount(0);
});

test('selects a dropdown option without asking', async ({ page }) => {
  await ask(page, '把优先级改成加急');
  await settled(page);

  await expect(page.locator('#priority')).toHaveValue('high');
  await expect(shadow(page).locator('.confirm')).toHaveCount(0);
});

test('still confirms an export action even though fill is auto-approved', async ({ page }) => {
  await ask(page, '帮我导出 Excel');

  // Auto-approval is scoped to page_fill / page_select; business writes always ask.
  const card = shadow(page).locator('.confirm');
  await expect(card).toBeVisible();
  await expect(card).toContainText('exportOrder');

  await card.locator('.decline').click();
  await expect(shadow(page).locator('.activity.rejected')).toBeVisible();
  await settled(page);
});

test('answers questions without proposing any action', async ({ page }) => {
  await ask(page, '这个订单金额是多少');
  await settled(page);

  await expect(shadow(page).locator('.confirm')).toHaveCount(0);
  await expect(shadow(page).locator('.row.assistant .bubble').last()).toContainText('50000');
});

test('never offers a tool the account lacks permission for', async ({ page }) => {
  // Re-init with a token that has no order:approve permission.
  await page.evaluate(async () => {
    const res = await fetch('http://localhost:8080/v1/demo/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sub: 'noperm', tenantId: 'demo', permissions: [] }),
    });
    const { accessToken } = await res.json();
      window.EnterpriseCopilot.init({
      appId: 'crm',
      gatewayUrl: 'http://localhost:8080',
      ui: { locale: 'zh-CN' },
      getAccessToken: () => accessToken,
      contextProvider: () => ({ orderId: 'ORD-123456', status: '待审批', amount: 50000 }),
      tools: [
        {
          name: 'approveOrder',
          description: '审批当前订单',
          parameters: { type: 'object', properties: {} },
          risk: 'write',
          execute: () => {
            (window as unknown as { __approved?: boolean }).__approved = true;
            return { ok: true };
          },
        },
      ],
    });
  });

  await openPanel(page);
  await ask(page, '帮我审批这个订单');
  await settled(page);

  await expect(shadow(page).locator('.confirm')).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __approved?: boolean }).__approved)).toBeUndefined();
  await expect(page.locator('#order-status')).toHaveText('待审批');
});
