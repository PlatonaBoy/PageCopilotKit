# Enterprise AI Copilot SDK

面向企业应用的可嵌入网页 AI 助手。业务系统引入一个 JS，即可获得右下角助手：**理解当前页面 + 业务上下文，多轮问答**。

```html
<script src="https://your-cdn/enterprise-copilot.js"></script>
<script>
  Copilot.init({
    appId: 'crm',
    gatewayUrl: 'https://copilot.example.com',
    getAccessToken: () => window.casAccessToken, // 你的 CAS/OIDC JWT
    contextProvider: () => ({ orderId: window.orderId, status: window.orderStatus }),
  });
</script>
```

## 文档

| 文档 | 内容 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 分层架构、上下文模型、威胁模型 |
| [docs/actions.md](docs/actions.md) | **页面操作与工具调用**：接入、risk 分级、安全模型 |
| [docs/api.md](docs/api.md) | 接口契约（SSE 事件、错误码、JWT 声明） |
| [docs/operations.md](docs/operations.md) | 部署、配置、上线检查清单 |
| [docs/design-v1-hardening.md](docs/design-v1-hardening.md) | V1 加固设计与取舍依据 |

## 能力

| 能力 | 状态 |
|------|------|
| 单 script 嵌入、Shadow DOM 隔离 | 已支持 |
| 页面感知（标题、字段、表格、控件、用户选区） | 已支持 |
| 业务上下文注入（`contextProvider`） | 已支持 |
| **多轮对话**（服务端会话持久化 + 刷新恢复） | 已支持 |
| 流式输出、停止生成、失败重试、复制、清空 | 已支持 |
| 中英文界面、主题色、位置、移动端全屏 | 已支持 |
| 键盘可达性（Escape、焦点陷阱、`aria-live`） | 已支持 |
| 敏感信息脱敏、`data-copilot-ignore` 忽略区域 | 已支持 |
| JWT 鉴权、租户隔离、限流、审计（成功与失败） | 已支持 |
| 模型超时/重试/熔断与降级 | 已支持 |
| **业务工具调用**（`registerTool`，服务端权限校验） | 已支持 |
| **页面操作**（点击/填表/下拉/读取，元素执行前重校验） | 已支持 |
| **risk 分级确认**（`read` 直接执行，`write` 需人工确认） | 已支持 |
| 企业知识库 / RAG / 引用来源 | 后续 |
| 跨页 / 跨 iframe / 深层 Shadow DOM 感知 | 后续 |

## 快速开始（本地）

### 前置

- Java 21、Maven 3.8+
- Node.js 20+

### 1. 启动 Gateway（dev profile，离线可用）

```bash
cd services/copilot-gateway
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

`dev` profile 会启用**免鉴权的演示令牌接口**与宽松 CORS，仅用于本地。健康检查：`GET http://localhost:8080/v1/health`。

接真实模型：

```bash
export SPRING_PROFILES_ACTIVE=dev
export COPILOT_MOCK_LLM=false
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com   # 或任意兼容端点
export OPENAI_MODEL=gpt-4o-mini
mvn spring-boot:run
```

### 2. 构建 SDK 并启动 Demo

```bash
npm run build:sdk
npm run dev --prefix apps/demo-host
```

打开 `http://localhost:5173`，点右下角 **AI**，试试：

**问答**

- 「这个订单金额是多少」
- 「客户是谁」（验证多轮上下文）
- 「当前页面有哪些按钮」

**操作**（详见 [docs/actions.md](docs/actions.md)）

- 「帮我审批这个订单」→ 弹出确认卡片，确认后页面状态变为「审批中」
- 「帮我填写审批人为张三」→ 直接填入（demo 中 `page_fill` 已配置免确认）
- 「把优先级改成加急」→ 直接选中下拉项
- 「帮我导出 Excel」→ 业务写操作，始终需要确认

> SDK 产物是 git-ignored 的，改动 SDK 源码后需重新执行 `npm run build:sdk`（demo 通过 `<script src>` 加载构建产物，不会热更新）。

### 3. 生产形态本地验证

```bash
cp .env.example .env    # 填入 COPILOT_JWT_SECRET 与 DB_PASSWORD
docker compose up --build
```

启动 PostgreSQL + `prod` profile 的 Gateway：无演示令牌接口、CORS 收紧、Flyway 迁移。

## 验证

```bash
npm run verify          # SDK lint + 单测 + 构建（含体积门禁）+ Gateway 测试
npm run test:e2e        # 浏览器端到端（需先起 gateway 与 demo）
```

| 套件 | 覆盖 |
|------|------|
| SDK 单测（Vitest，77 项） | SSE 解析边界、上下文预算、页面提取与脱敏、状态批处理、Widget 与确认卡片、工具注册与 risk 判定、页面动作与元素身份校验、i18n |
| Gateway 测试（JUnit，65 项） | Prompt 组装与预算、Mock 规划器、鉴权矩阵、多轮持久化、租户隔离、CORS、限额、工具白名单与权限、tool-result 绑定校验与应用隔离、启动配置校验 |
| E2E（Playwright，18 项） | 嵌入加载、多轮上下文、刷新恢复、清空、错误重试、忽略区域、确认/拒绝、页面填表与下拉、权限拒绝 |

若机器已装 Chrome，可用 `CHROME_PATH=/usr/bin/google-chrome npm run test:e2e` 复用它，省去浏览器下载。

## 仓库结构

```
packages/sdk/              TypeScript SDK（Vite 库构建 → IIFE + ESM + d.ts）
apps/demo-host/            假 CRM 演示页
services/copilot-gateway/  Spring Boot + Spring AI 网关
tests/e2e/                 Playwright 端到端
docs/                      架构、接口、运维、设计
```

## 安全要点

- **模型 API Key 永不进入浏览器**：所有模型流量经 Gateway。
- **身份来自 JWT**，不接受前端声明的 `userId`；`tenantId` 贯穿会话、审计与限流。
- **页面 DOM 文本视为不可信输入**，与系统指令隔离；**页面内容永远不能触发操作**。
- **会话按 (tenantId, userSub) 隔离**，访问他人会话返回 404。
- **工具调用需服务端授权**：前端声明不等于授权，按 `appId` 白名单 + JWT 权限双重校验。
- **写操作需人工确认**，且确认卡片展示实际参数；可能重放动作的请求在写入后禁止自动重试。
- **工具结果必须对应真实待处理调用**，无法伪造「已成功」；线程的应用身份不可中途切换。
- **页面动作执行前重新校验元素**（存在、可见、可用、标签未变），页面变化时失败而非误点。
- 生产部署前请对照 [docs/operations.md](docs/operations.md) 与 [docs/actions.md](docs/actions.md) 的上线清单。
