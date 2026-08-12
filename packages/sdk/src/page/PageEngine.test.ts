import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PageEngine } from './PageEngine';

function setBody(html: string) {
  document.body.innerHTML = html;
}

let engine: PageEngine;

beforeEach(() => {
  engine = new PageEngine();
  document.title = '订单详情';
});

afterEach(() => {
  engine.stop();
  document.body.innerHTML = '';
});

describe('PageEngine snapshot', () => {
  it('collects visible interactive controls with accessible names', async () => {
    setBody(`
      <button id="approve" data-action="approve">提交审批</button>
      <a href="#x">查看历史</a>
      <input aria-label="搜索关键字" />
    `);

    const snap = await engine.snapshot();
    const names = snap.actionableElements.map((e) => e.name);

    expect(names).toContain('提交审批');
    expect(names).toContain('查看历史');
    expect(names).toContain('搜索关键字');
  });

  it('never reports password or secret inputs', async () => {
    setBody(`
      <input type="password" aria-label="登录密码" />
      <input name="apiKey" aria-label="API Key" />
      <button>正常按钮</button>
    `);

    const snap = await engine.snapshot();
    const serialized = JSON.stringify(snap);

    expect(serialized).not.toContain('登录密码');
    expect(serialized).not.toContain('API Key');
    expect(serialized).toContain('正常按钮');
  });

  it('skips controls inside regions marked data-copilot-ignore', async () => {
    setBody(`
      <div data-copilot-ignore><button>内部调试</button><p>机密备注</p></div>
      <button>公开操作</button>
    `);

    const snap = await engine.snapshot();

    expect(snap.actionableElements.map((e) => e.name)).not.toContain('内部调试');
    expect(snap.actionableElements.map((e) => e.name)).toContain('公开操作');
  });

  it('excludes ignored regions from the page text, not just from controls', async () => {
    setBody(`
      <main>
        <p>公开说明</p>
        <section data-copilot-ignore><p>机密备注不应上传</p></section>
      </main>
    `);

    const snap = await engine.snapshot();

    expect(snap.summary).toContain('公开说明');
    expect(snap.summary).not.toContain('机密备注不应上传');
  });

  it('excludes script and style content from the page text', async () => {
    setBody(`
      <main>
        <p>正文</p>
        <script>const secret = 'do-not-upload';</script>
        <style>.x { color: red }</style>
      </main>
    `);

    const snap = await engine.snapshot();

    expect(snap.summary).toContain('正文');
    expect(snap.summary).not.toContain('do-not-upload');
  });

  it('redacts emails, phone numbers and key-like strings from page text', async () => {
    setBody(`
      <main>
        <p>联系 zhangsan@example.com 或 13800138000</p>
        <p>令牌 sk-abcdefghijklmnopqrstuvwxyz</p>
      </main>
    `);

    const snap = await engine.snapshot();

    expect(snap.summary).not.toContain('zhangsan@example.com');
    expect(snap.summary).not.toContain('13800138000');
    expect(snap.summary).not.toContain('sk-abcdefghijklmnopqrstuvwxyz');
    expect(snap.summary).toContain('[redacted-email]');
  });

  it('extracts label/value pairs from definition lists', async () => {
    setBody(`
      <main>
        <dl>
          <dt>金额</dt><dd>¥ 50,000.00</dd>
          <dt>状态</dt><dd>待审批</dd>
        </dl>
      </main>
    `);

    const snap = await engine.snapshot();

    expect(snap.summary).toContain('FIELDS:');
    expect(snap.summary).toContain('金额: ¥ 50,000.00');
    expect(snap.summary).toContain('状态: 待审批');
  });

  it('renders tables as markdown so rows stay associated', async () => {
    setBody(`
      <main>
        <table>
          <tr><th>商品</th><th>数量</th></tr>
          <tr><td>钢板</td><td>20</td></tr>
        </table>
      </main>
    `);

    const snap = await engine.snapshot();

    expect(snap.summary).toContain('TABLES:');
    expect(snap.summary).toContain('| 商品 | 数量 |');
    expect(snap.summary).toContain('| 钢板 | 20 |');
  });

  it('includes the page title and url', async () => {
    setBody('<main><h1>订单</h1></main>');

    const snap = await engine.snapshot();

    expect(snap.title).toBe('订单详情');
    expect(snap.url).toBe(location.href);
    expect(snap.summary).toContain('HEADINGS:');
  });

  it('serves a cached snapshot until the DOM is invalidated', async () => {
    setBody('<main><h1>第一版</h1></main>');
    const first = await engine.snapshot();

    setBody('<main><h1>第二版</h1></main>');
    const cached = await engine.snapshot();
    expect(cached.summary).toBe(first.summary);

    engine.invalidate();
    const fresh = await engine.snapshot();
    expect(fresh.summary).toContain('第二版');
  });
});
