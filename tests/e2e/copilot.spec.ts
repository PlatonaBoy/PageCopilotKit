import { expect, test, type Page } from '@playwright/test';

const GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080';
const ROOT = '#enterprise-copilot-root';

/** The widget lives in a shadow root, so all queries go through its host. */
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

async function waitForAnswer(page: Page) {
  await expect(shadow(page).locator('.panel')).toHaveAttribute('data-streaming', 'false', {
    timeout: 30_000,
  });
  return shadow(page).locator('.row.assistant .bubble').last();
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
});

test('mounts the widget and exposes a callable init API', async ({ page }) => {
  const api = await page.evaluate(() => ({
    hasInit: typeof window.EnterpriseCopilot?.init === 'function',
    hasAlias: typeof window.Copilot?.init === 'function',
    mounted: Boolean(document.querySelector('#enterprise-copilot-root')),
  }));

  expect(api).toEqual({ hasInit: true, hasAlias: true, mounted: true });
});

test('answers a question grounded in business context', async ({ page }) => {
  await openPanel(page);
  await ask(page, '这个订单金额是多少');

  const answer = await waitForAnswer(page);
  await expect(answer).toContainText('50000');
});

test('answers a question about page controls', async ({ page }) => {
  await openPanel(page);
  await ask(page, '当前页面有哪些按钮');

  const answer = await waitForAnswer(page);
  await expect(answer).toContainText('提交审批');
});

test('keeps context across turns in the same thread', async ({ page }) => {
  await openPanel(page);

  await ask(page, '这个订单金额是多少');
  await waitForAnswer(page);

  await ask(page, '客户是谁');
  const second = await waitForAnswer(page);

  await expect(second).toContainText('华东制造');
  // Two questions and two answers.
  await expect(shadow(page).locator('.bubble')).toHaveCount(4);
});

test('restores the conversation after a page reload', async ({ page }) => {
  await openPanel(page);
  await ask(page, '这个订单金额是多少');
  await waitForAnswer(page);

  await page.reload();
  await openPanel(page);

  await expect(shadow(page).locator('.row.user .bubble').first()).toContainText('这个订单金额是多少');
});

test('clears the conversation on demand', async ({ page }) => {
  await openPanel(page);
  await ask(page, '状态是什么');
  await waitForAnswer(page);

  page.once('dialog', (dialog) => dialog.accept());
  await shadow(page).locator('.header .icon-button').first().click();

  await expect(shadow(page).locator('.empty')).toBeVisible();
});

test('surfaces a retryable error when the gateway fails', async ({ page }) => {
  await page.route(`${GATEWAY}/v1/chat`, (route) =>
    route.fulfill({ status: 503, body: JSON.stringify({ code: 'server_error', message: 'down' }) }),
  );

  await openPanel(page);
  await ask(page, '任意问题');

  const answer = await waitForAnswer(page);
  await expect(answer).toContainText('重试');
  await expect(shadow(page).getByRole('button', { name: '重新生成' })).toBeVisible();
});

test('never uploads data-copilot-ignore regions in the page context', async ({ page }) => {
  const payloads: string[] = [];
  page.on('request', (req) => {
    if (req.url().includes('/v1/chat') && req.method() === 'POST') {
      payloads.push(req.postData() ?? '');
    }
  });

  await openPanel(page);
  await ask(page, '页面说了什么');
  await waitForAnswer(page);

  expect(payloads.length).toBeGreaterThan(0);
  const sent = payloads.join('\n');
  // The demo page marks its debug block with data-copilot-ignore.
  expect(sent).not.toContain('内部调试信息');
  // Sanity check that real page content still made it through.
  expect(sent).toContain('订单详情');
});

test('closes the panel with Escape', async ({ page }) => {
  await openPanel(page);
  await shadow(page).locator('textarea').press('Escape');
  await expect(shadow(page).locator('.panel')).not.toBeVisible();
});
