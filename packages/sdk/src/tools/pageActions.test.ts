import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PageEngine } from '../page/PageEngine';
import { createPageActionTools } from './pageActions';
import type { CopilotTool } from '../types';

let page: PageEngine;
let tools: Map<string, CopilotTool>;

async function mount(html: string, autoApprove: Array<'page_fill' | 'page_select'> = []) {
  document.body.innerHTML = html;
  page = new PageEngine();
  tools = new Map(
    createPageActionTools(page, { enabled: true, autoApprove }).map((t) => [t.name, t]),
  );
  const snapshot = await page.snapshot();
  return snapshot;
}

function refFor(snapshot: Awaited<ReturnType<PageEngine['snapshot']>>, name: string): string {
  const found = snapshot.actionableElements.find((el) => el.name === name);
  if (!found) throw new Error(`no control named ${name}`);
  return found.ref;
}

beforeEach(() => {
  document.title = '订单详情';
});

afterEach(() => {
  page?.stop();
  document.body.innerHTML = '';
});

describe('page action tools', () => {
  it('are absent unless the host enables them', () => {
    expect(createPageActionTools(new PageEngine(), undefined)).toEqual([]);
    expect(createPageActionTools(new PageEngine(), { enabled: false })).toEqual([]);
  });

  it('classify reads as read and mutations as write', async () => {
    await mount('<button>提交</button>');

    expect(tools.get('page_read')!.risk).toBe('read');
    expect(tools.get('page_scroll')!.risk).toBe('read');
    expect(tools.get('page_click')!.risk).toBe('write');
    expect(tools.get('page_fill')!.risk).toBe('write');
    expect(tools.get('page_select')!.risk).toBe('write');
  });

  it('never auto-approves clicking, even when fill is auto-approved', async () => {
    await mount('<button>提交</button>', ['page_fill']);

    expect(tools.get('page_fill')!.confirm).toBe(false);
    expect(tools.get('page_click')!.confirm).toBeUndefined();
  });

  it('clicks the referenced control', async () => {
    const snapshot = await mount('<button id="go">提交审批</button>');
    let clicked = false;
    document.getElementById('go')!.addEventListener('click', () => {
      clicked = true;
    });

    const result = await tools.get('page_click')!.execute({ ref: refFor(snapshot, '提交审批') });

    expect(clicked).toBe(true);
    expect(result).toEqual({ clicked: '提交审批' });
  });

  it('fills a text input and fires the events frameworks listen to', async () => {
    const snapshot = await mount('<label for="who">审批人</label><input id="who" name="who" />');
    const events: string[] = [];
    const input = document.getElementById('who') as HTMLInputElement;
    input.addEventListener('input', () => events.push('input'));
    input.addEventListener('change', () => events.push('change'));

    await tools.get('page_fill')!.execute({ ref: refFor(snapshot, '审批人'), value: '张三' });

    expect(input.value).toBe('张三');
    expect(events).toEqual(['input', 'change']);
  });

  it('selects a dropdown option by its visible label', async () => {
    const snapshot = await mount(
      '<label for="p">优先级</label><select id="p" name="p"><option value="normal">普通</option><option value="high">加急</option></select>',
    );

    const result = await tools
      .get('page_select')!
      .execute({ ref: refFor(snapshot, '优先级'), option: '加急' });

    expect((document.getElementById('p') as HTMLSelectElement).value).toBe('high');
    expect(result).toEqual({ selected: '加急' });
  });

  it('lists the available options when the requested one does not exist', async () => {
    const snapshot = await mount(
      '<label for="p">优先级</label><select id="p" name="p"><option>普通</option></select>',
    );

    await expect(
      tools.get('page_select')!.execute({ ref: refFor(snapshot, '优先级'), option: '不存在' }),
    ).rejects.toThrow(/Available: 普通/);
  });

  it('reads the current value of a control', async () => {
    const snapshot = await mount(
      '<label for="who">审批人</label><input id="who" name="who" value="李四" />',
    );

    const result = await tools.get('page_read')!.execute({ ref: refFor(snapshot, '审批人') });

    expect(result).toMatchObject({ name: '审批人', value: '李四' });
  });

  it('refuses an unknown ref instead of guessing a target', async () => {
    await mount('<button>提交</button>');

    await expect(tools.get('page_click')!.execute({ ref: 'e99_99' })).rejects.toThrow(
      /unknown element ref/,
    );
  });

  it('refuses when the referenced element left the page', async () => {
    const snapshot = await mount('<button id="go">提交审批</button>');
    const ref = refFor(snapshot, '提交审批');
    document.getElementById('go')!.remove();

    await expect(tools.get('page_click')!.execute({ ref })).rejects.toThrow(/no longer on the page/);
  });

  it('refuses when the control was relabelled after the snapshot', async () => {
    const snapshot = await mount('<button id="go">提交审批</button>');
    const ref = refFor(snapshot, '提交审批');
    // A re-render swapped the button's meaning; clicking it would be the wrong action.
    document.getElementById('go')!.textContent = '删除订单';

    await expect(tools.get('page_click')!.execute({ ref })).rejects.toThrow(/the page changed/);
  });

  it('refuses to fill a disabled field', async () => {
    const snapshot = await mount(
      '<label for="who">审批人</label><input id="who" name="who" disabled />',
    );

    // A disabled control is filtered from the snapshot entirely, so there is nothing to target.
    expect(snapshot.actionableElements.some((el) => el.name === '审批人')).toBe(true);
    await expect(
      tools.get('page_fill')!.execute({ ref: refFor(snapshot, '审批人'), value: 'x' }),
    ).rejects.toThrow(/disabled/);
  });

  it('refuses to fill a control that is not a text field', async () => {
    const snapshot = await mount('<button id="go">提交审批</button>');

    await expect(
      tools.get('page_fill')!.execute({ ref: refFor(snapshot, '提交审批'), value: 'x' }),
    ).rejects.toThrow(/not a text field/);
  });

  it('never exposes a password field as a target', async () => {
    const snapshot = await mount(
      '<label for="pw">登录密码</label><input id="pw" name="password" type="password" /><button>提交</button>',
    );

    expect(snapshot.actionableElements.some((el) => el.name === '登录密码')).toBe(false);
  });
});
