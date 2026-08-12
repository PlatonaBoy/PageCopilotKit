export type MessageKey =
  | 'title'
  | 'launcher'
  | 'openLabel'
  | 'closeLabel'
  | 'placeholder'
  | 'send'
  | 'stop'
  | 'stopped'
  | 'retry'
  | 'copy'
  | 'copied'
  | 'clear'
  | 'clearConfirm'
  | 'emptyState'
  | 'thinking'
  | 'restoring'
  | 'errorPrefix'
  | 'errorNetwork'
  | 'errorTimeout'
  | 'errorUnauthorized'
  | 'errorRateLimited'
  | 'errorTooLarge'
  | 'errorModel'
  | 'errorUnknown'
  | 'confirmTitle'
  | 'confirmHint'
  | 'approve'
  | 'decline'
  | 'toolRunning'
  | 'toolDone'
  | 'toolRejected'
  | 'toolFailed'
  | 'toolUnknown'
  | 'toolStepLimit'
  | 'errorToolForbidden';

type Catalog = Record<MessageKey, string>;

const ZH: Catalog = {
  title: 'AI 助手',
  launcher: 'AI',
  openLabel: '打开 AI 助手',
  closeLabel: '关闭',
  placeholder: '输入问题，回车发送…',
  send: '发送',
  stop: '停止',
  stopped: '（已停止生成）',
  retry: '重新生成',
  copy: '复制',
  copied: '已复制',
  clear: '清空会话',
  clearConfirm: '确定清空当前会话吗？',
  emptyState: '你可以问我当前页面的内容，例如页面上有哪些操作、某个字段的值是多少。',
  thinking: '正在思考…',
  restoring: '正在恢复会话…',
  errorPrefix: '出错了：',
  errorNetwork: '网络连接失败，请检查网络后重试。',
  errorTimeout: '请求超时，请重试。',
  errorUnauthorized: '登录状态已失效，请刷新页面重新登录。',
  errorRateLimited: '请求过于频繁，请稍后再试。',
  errorTooLarge: '页面内容过大，无法处理。',
  errorModel: 'AI 服务暂时不可用，请稍后重试。',
  errorUnknown: '请求失败，请重试。',
  confirmTitle: '需要你确认这个操作',
  confirmHint: '该操作会修改数据或提交表单，确认后才会执行。',
  approve: '确认执行',
  decline: '取消',
  toolRunning: '正在执行',
  toolDone: '已执行',
  toolRejected: '已取消（用户未确认）',
  toolFailed: '执行失败',
  toolUnknown: '该操作未在本页面注册，已拒绝',
  toolStepLimit: '连续操作步数过多，已中止。请拆分成更小的请求。',
  errorToolForbidden: '当前账号没有执行该操作的权限。',
};

const EN: Catalog = {
  title: 'AI Copilot',
  launcher: 'AI',
  openLabel: 'Open AI Copilot',
  closeLabel: 'Close',
  placeholder: 'Ask about this page, press Enter to send…',
  send: 'Send',
  stop: 'Stop',
  stopped: '(generation stopped)',
  retry: 'Regenerate',
  copy: 'Copy',
  copied: 'Copied',
  clear: 'Clear conversation',
  clearConfirm: 'Clear the current conversation?',
  emptyState:
    'Ask me about this page — which actions are available, or the value of a specific field.',
  thinking: 'Thinking…',
  restoring: 'Restoring conversation…',
  errorPrefix: 'Error: ',
  errorNetwork: 'Network request failed. Check your connection and retry.',
  errorTimeout: 'The request timed out. Please retry.',
  errorUnauthorized: 'Your session expired. Reload the page to sign in again.',
  errorRateLimited: 'Too many requests. Please wait a moment.',
  errorTooLarge: 'This page is too large to process.',
  errorModel: 'The AI service is temporarily unavailable. Please retry.',
  errorUnknown: 'Request failed. Please retry.',
  confirmTitle: 'Confirm this action',
  confirmHint: 'This changes data or submits a form. It runs only after you approve.',
  approve: 'Approve',
  decline: 'Cancel',
  toolRunning: 'Running',
  toolDone: 'Done',
  toolRejected: 'Cancelled (not approved)',
  toolFailed: 'Failed',
  toolUnknown: 'This action is not registered on the page and was rejected',
  toolStepLimit: 'Too many consecutive actions; stopped. Please split the request.',
  errorToolForbidden: 'Your account is not allowed to perform this action.',
};

const CATALOGS: Record<string, Catalog> = {
  'zh-CN': ZH,
  zh: ZH,
  'en-US': EN,
  en: EN,
};

export type Translator = (key: MessageKey) => string;

export function createTranslator(locale?: string): Translator {
  const catalog = resolveCatalog(locale);
  return (key) => catalog[key] ?? ZH[key];
}

function resolveCatalog(locale?: string): Catalog {
  if (!locale) return ZH;
  const exact = CATALOGS[locale];
  if (exact) return exact;
  const base = CATALOGS[locale.split('-')[0]!];
  return base ?? ZH;
}

/** Maps a transport/gateway error code onto a user-facing message key. */
export function errorMessageKey(code: string): MessageKey {
  switch (code) {
    case 'network':
      return 'errorNetwork';
    case 'timeout':
      return 'errorTimeout';
    case 'unauthorized':
      return 'errorUnauthorized';
    case 'rate_limited':
      return 'errorRateLimited';
    case 'context_too_large':
      return 'errorTooLarge';
    case 'model_error':
    case 'breaker_open':
    case 'model_stream_interrupted':
      return 'errorModel';
    case 'tool_forbidden':
      return 'errorToolForbidden';
    case 'tool_step_limit':
      return 'toolStepLimit';
    default:
      return 'errorUnknown';
  }
}
