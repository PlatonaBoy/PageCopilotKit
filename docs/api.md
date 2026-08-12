# Enterprise AI Copilot — API Contract

Base URL: `{gatewayUrl}` (e.g. `http://localhost:8080`)

Authenticated endpoints require:

```
Authorization: Bearer <JWT>
Content-Type: application/json
```

Every response carries `X-Trace-Id`. Send your own to correlate with upstream systems.

## JWT claims

| Claim | Required | Description |
|-------|----------|-------------|
| `sub` | yes | User id. Rejected when blank. |
| `iss` | yes | Must equal `copilot.jwt-issuer` (verification is on by default). |
| `exp` | yes | Expiry. A 30s clock skew is tolerated. |
| `name` / `preferred_username` | no | Display name. |
| `tenantId` / `tenant_id` | no | Tenant isolation key. Defaults to `default`. |
| `roles` | no | String array. |
| `permissions` | no | String array. `audit:read` gates the audit API. |

Signing is HS256 with `COPILOT_JWT_SECRET` (≥ 32 bytes, enforced at startup). Swap in CAS/OIDC JWKS
by replacing the verification in `JwtService`.

---

## `POST /v1/chat`

Starts or continues a conversation. Responds with **SSE** (`text/event-stream`).

### Request

```json
{
  "appId": "crm",
  "threadId": "thr_0f1e…",
  "message": "这个订单金额是多少",
  "pageContext": {
    "url": "https://example.com/orders/123",
    "title": "订单详情",
    "summary": "…structured page extract…",
    "actionableElements": [
      { "name": "提交审批", "role": "button", "hint": "approve" }
    ],
    "selection": "年度框架合同首笔订单"
  },
  "businessContext": {
    "orderId": "ORD-123456",
    "status": "待审批",
    "amount": 50000
  },
  "clientTools": [
    {
      "name": "approveOrder",
      "description": "审批当前订单",
      "parameters": { "type": "object", "properties": { "orderId": { "type": "string" } } },
      "risk": "write"
    }
  ]
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `appId` | yes | Must be in `copilot.allowed-app-ids`. |
| `threadId` | no | Omit to start a new thread. Must belong to the caller. |
| `message` | yes | Max 8000 chars at the API, truncated to 4000 for the prompt. |
| `pageContext` | no | Produced by the SDK's PageEngine. |
| `pageContext.actionableElements[].ref` | no | Opaque handle a page action can target. |
| `pageContext.selection` | no | Text the user highlighted; prioritized in the answer. |
| `businessContext` | no | Authoritative app facts. **Max 4KB** or the request is rejected. |
| `clientTools` | no | Capabilities the browser can execute this turn. Max 40. |

Declaring a tool is not authorization: the gateway intersects `clientTools` with its own allowlist and
the caller's JWT permissions, and only the survivors reach the model. See [actions.md](actions.md).

Send `Accept: text/event-stream, application/json` — the stream is SSE but contract errors are JSON.

Contract errors (unknown `appId`, oversized context, rate limit, unknown thread) are returned as
**real HTTP status codes before the stream opens**, not as SSE events.

### SSE events

```
event: <type>
data: <json>

```

| Event | Data | Meaning |
|-------|------|---------|
| `thread` | `{ "threadId": "thr_…" }` | Thread assigned. Persist it to resume later. |
| `text.delta` | `{ "delta": "…" }` | Incremental answer text. |
| `text.done` | `{ "content": "…" }` | Complete answer. |
| `tool.call` | `{ "id", "name", "arguments" }` | The client must execute this and report back. |
| `error` | `{ "code": "...", "message": "..." }` | Generation failed or degraded. |
| `done` | `{ "traceId": "trc_…" }` | **End of turn.** Clients must stop reading here. |

A turn that ends with `tool.call` is not finished: the client executes the tool (asking the user first
when the risk requires it) and continues via the tool-result endpoint below.

`done` is authoritative: the HTTP connection may stay open afterwards, so a client that waits for
the socket to close will hang.

When generation fails, the stream emits a readable degraded message, then `error`, then `done` — the
HTTP status stays `200` because headers were already sent.

### Example

```
event: thread
data: {"threadId":"thr_9c2b1f"}

event: text.delta
data: {"delta":"根据业务上下文，"}

event: text.delta
data: {"delta":"订单金额为 50000。"}

event: text.done
data: {"content":"根据业务上下文，订单金额为 50000。"}

event: done
data: {"traceId":"trc_5f7a"}
```

---

## `POST /v1/chat/{threadId}/tool-result`

Reports the outcome of a tool the browser executed. The response is the **SSE continuation** of the
same turn, so no stream is held open across a user confirmation.

```json
{
  "appId": "crm",
  "toolCallId": "call_abc",
  "name": "approveOrder",
  "result": { "orderId": "ORD-123456", "status": "审批中" },
  "pageContext": { "url": "…", "title": "…", "summary": "…", "actionableElements": [] },
  "businessContext": { "status": "审批中" },
  "clientTools": []
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `toolCallId` | yes | The `id` from the `tool.call` event. |
| `name` | yes | Tool that ran. |
| `result` | no | Whatever the tool returned. Omit when it failed. |
| `error` | no | Failure reason, or `user_declined: …` when the user refused. |
| `pageContext` | no | **Fresh** snapshot — an action usually changed the DOM. |
| `businessContext` | no | Re-read after the action. |

Returns `404 thread_not_found` for another user's thread, and `409 tool_step_limit` when the turn has
already used its tool-step budget.

## `GET /v1/threads/{threadId}/messages`

Restores a conversation. Returns 404 when the thread does not exist **or belongs to someone else**.

```json
{
  "threadId": "thr_9c2b1f",
  "messages": [
    { "role": "user", "content": "这个订单金额是多少", "createdAt": "2026-08-12T09:00:00Z" },
    { "role": "assistant", "content": "订单金额为 50000。", "createdAt": "2026-08-12T09:00:01Z" }
  ]
}
```

## `DELETE /v1/threads/{threadId}`

Deletes the thread and its messages.

```json
{ "deleted": true, "threadId": "thr_9c2b1f" }
```

## `GET /v1/audits?limit=20`

Requires the `audit:read` permission. Always scoped to the caller's tenant.

```json
{
  "tenantId": "demo",
  "count": 1,
  "items": [
    {
      "traceId": "trc_5f7a",
      "appId": "crm",
      "threadId": "thr_9c2b1f",
      "userSub": "zhangsan",
      "question": "这个订单金额是多少",
      "answer": "订单金额为 50000。",
      "status": "SUCCESS",
      "errorCode": "",
      "model": "mock",
      "latencyMs": 94,
      "createdAt": "2026-08-12T09:00:01Z"
    }
  ]
}
```

## `GET /v1/health`

Public, no auth.

```json
{ "status": "UP" }
```

## `POST /v1/demo/token` — dev profile only

Mints a short-lived JWT for local demos. The controller is annotated `@Profile("dev")`, so under any
other profile the route is **not registered** and returns 404. It cannot be re-enabled by
configuration alone.

```json
{ "accessToken": "eyJ…", "expiresIn": 3600 }
```

---

## Error envelope

```json
{ "code": "rate_limited", "message": "Rate limit exceeded for user (20/min). Retry in 42s" }
```

| HTTP | `code` | When |
|------|--------|------|
| 400 | `bad_request` | Validation failure, unknown `appId`, malformed body |
| 401 | `unauthorized` | Missing/invalid token. Adds `reason`: `token_missing`, `token_expired`, `token_signature_invalid`, `token_malformed` |
| 403 | `forbidden` | Authenticated but missing a required permission |
| 404 | `not_found` | Route not registered (e.g. dev-only endpoint in prod) |
| 404 | `thread_not_found` | Thread missing **or owned by another user** |
| 405 | `method_not_allowed` | Wrong HTTP method |
| 409 | `tool_step_limit` | Turn exceeded `copilot.tools.max-steps-per-turn` |
| 413 | `context_too_large` | `businessContext` over 4KB |
| 429 | `rate_limited` | Per-user or per-tenant limit exceeded |
| 500 | `internal_error` | Unexpected failure (no stack details exposed) |

SSE-only codes (delivered as an `error` event on a `200` response): `model_error`, `breaker_open`,
`model_stream_interrupted`, `client_disconnected`, `tool_forbidden`, `tool_not_available`,
`tool_not_allowed`, `tool_disabled`.

Error bodies are always JSON with an explicit content type, even when the request's `Accept` only
lists `text/event-stream`.

---

## CORS

Allowed origins come from `copilot.cors-allowed-origins`. Outside the `dev` profile, wildcard
origins are rejected at startup. Allowed methods: `GET`, `POST`, `DELETE`, `OPTIONS`.
