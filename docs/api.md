# Enterprise AI Copilot — API Contract (MVP)

Base URL: `{gatewayUrl}` (e.g. `http://localhost:8080`)

All chat endpoints require:

```
Authorization: Bearer <JWT>
Content-Type: application/json
```

## JWT claims (CAS-shaped)

| Claim | Required | Description |
|-------|----------|-------------|
| `sub` | yes | User id |
| `name` / `preferred_username` | no | Display name |
| `tenantId` / `tenant_id` | no | Tenant isolation key (default `default`) |
| `roles` | no | String array |
| `permissions` | no | String array |
| `exp` | yes | Expiry |

MVP gateway validates HS256 with shared secret `COPILOT_JWT_SECRET`. Production replaces this with CAS JWKS.

---

## `POST /v1/chat`

Start (or continue) a chat turn. Response is **SSE** (`text/event-stream`).

### Request body

```json
{
  "appId": "crm",
  "threadId": "optional-existing-thread",
  "message": "当前订单状态是什么？",
  "pageContext": {
    "url": "https://example.com/orders/123",
    "title": "订单详情",
    "summary": "…dehydrated DOM text…",
    "actionableElements": [
      { "name": "提交审批", "role": "button", "hint": "approve" }
    ]
  },
  "businessContext": {
    "orderId": "123456",
    "status": "待审批",
    "amount": 50000
  },
  "clientTools": []
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `appId` | yes | Registered application id |
| `threadId` | no | Omit to create a new thread |
| `message` | yes | User utterance |
| `pageContext` | no | From PageEngine |
| `businessContext` | no | From `contextProvider()`, max 4KB |
| `clientTools` | no | Phase 2 tool schemas |

### SSE events

Each event is:

```
event: <type>
data: <json>

```

| Event | Data shape | Meaning |
|-------|------------|---------|
| `thread` | `{ "threadId": "…" }` | Thread assigned |
| `text.delta` | `{ "delta": "…" }` | Streaming assistant token/chunk |
| `text.done` | `{ "content": "…" }` | Full assistant message |
| `tool.call` | `{ "id", "name", "arguments" }` | Phase 2 — client must execute |
| `error` | `{ "code", "message" }` | Failure |
| `done` | `{ "traceId": "…" }` | Stream finished |

### Example stream

```
event: thread
data: {"threadId":" thr_01H…"}

event: text.delta
data: {"delta":"当前"}

event: text.delta
data: {"delta":"订单状态为「待审批」。"}

event: text.done
data: {"content":"当前订单状态为「待审批」。"}

event: done
data: {"traceId":"trc_…"}
```

---

## `POST /v1/chat/{threadId}/tool-result` (Phase 2)

```json
{
  "toolCallId": "call_…",
  "name": "approveOrder",
  "result": { "ok": true }
}
```

MVP returns `501 Not Implemented`.

---

## `GET /v1/health`

```json
{ "status": "UP" }
```

No auth.

---

## `POST /v1/demo/token` (local / demo only)

Issues a short-lived HS256 JWT for the demo host. **Disabled when `copilot.demo-token-enabled=false`.**

Request:

```json
{
  "sub": "zhangsan",
  "name": "张三",
  "tenantId": "demo",
  "roles": ["manager"],
  "permissions": ["order:view", "order:approve"]
}
```

Response:

```json
{ "accessToken": "eyJ…", "expiresIn": 3600 }
```

---

## Error codes

| HTTP | `code` | When |
|------|--------|------|
| 401 | `unauthorized` | Missing/invalid JWT |
| 400 | `bad_request` | Validation failure |
| 413 | `context_too_large` | Context over budget |
| 502 | `model_error` | Upstream LLM failure |
| 501 | `not_implemented` | Phase 2+ endpoints |

---

## CORS

Gateway allows origins configured in `copilot.cors-allowed-origins` (demo: `http://localhost:5173`).  
`appId` must be in `copilot.allowed-app-ids`.
