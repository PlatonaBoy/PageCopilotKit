# Enterprise AI Copilot SDK

面向企业应用的一站式网页 AI 助手嵌入平台（MVP）。

任意业务页引入一个 JS，即可获得右下角助手：理解当前页面摘要 + 业务上下文，并通过 Gateway 流式回答。

## 架构

详见 [docs/architecture.md](docs/architecture.md) 与 [docs/api.md](docs/api.md)。

```
demo-host / 业务系统
        │
 enterprise-copilot.js  (packages/sdk)
        │  SSE + JWT
 copilot-gateway        (Spring Boot + Spring AI)
        │
 OpenAI-compatible LLM 或 Mock LLM
```

## 快速开始

### 前置

- Java 21、Maven 3.8+
- Node.js 20+

### 1. 启动 Gateway

```bash
cd services/copilot-gateway
COPILOT_MOCK_LLM=true mvn spring-boot:run
```

默认 `http://localhost:8080`。健康检查：`GET /v1/health`。

对接真实模型时：

```bash
export COPILOT_MOCK_LLM=false
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com   # 或兼容端点
export OPENAI_MODEL=gpt-4o-mini
mvn spring-boot:run
```

### 2. 构建 SDK 并启动 Demo

```bash
# 仓库根目录
npm run build:sdk
npm run dev --prefix apps/demo-host
```

打开 `http://localhost:5173`，点击右下角 **AI**，尝试：

- 「当前页面有哪些按钮？」
- 「当前订单状态是什么？」

Demo 会调用 `POST /v1/demo/token` 获取 JWT（仅本地演示）。

### 业务系统接入

```html
<script src="https://your-cdn/enterprise-copilot.js"></script>
<script>
  Copilot.init({
    appId: 'crm',
    gatewayUrl: 'https://copilot.example.com',
    getAccessToken: () => window.casAccessToken, // CAS JWT，不要传裸 userId
    contextProvider: () => ({
      orderId: window.currentOrderId,
      status: window.currentOrderStatus,
    }),
    ui: { title: 'AI 助手' },
  });
</script>
```

产物路径：`packages/sdk/dist/enterprise-copilot.js`（IIFE）与 `enterprise-copilot.esm.js`（ESM）。

## MVP 范围

**已包含**

- Shadow DOM 聊天 Widget + SSE 流式输出
- PageEngine 页面摘要 / 可操作元素（同文档 DOM）
- Business Context Provider
- Gateway：JWT、Prompt 组装、Mock/真实 LLM、审计落库（H2）

**二期**

- `registerTool` / 页面操作（封装 PageAgent execute）
- HITL 确认、服务端权限二次校验
- RAG / 真实 CAS JWKS

## 验收清单

- [ ] `GET /v1/health` 返回 UP
- [ ] Demo 页右下角出现 AI 按钮
- [ ] 提问「订单状态」能命中 businessContext 中的状态
- [ ] 提问「按钮」能列出页面可操作控件
- [ ] 浏览器 Network 中无模型 API Key
- [ ] H2 / `audit_records` 有审计行（或查看 gateway 日志）

## 仓库结构

```
packages/sdk/              TypeScript SDK
apps/demo-host/            假 CRM 演示页
services/copilot-gateway/  Spring Boot Gateway
docs/                      架构与 API
```
