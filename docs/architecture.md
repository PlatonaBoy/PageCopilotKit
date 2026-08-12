# Enterprise AI Copilot SDK — Architecture

Embeddable AI assistant for business web applications. A host page loads one script:

```html
<script src="enterprise-copilot.js"></script>
```

and gets a bottom-right copilot that understands the current page and the app's business context,
holds a multi-turn conversation about them, and — where the host allows it — **acts**: calling
registered business tools and operating page controls, with risk-graded human confirmation.

Current scope: page-grounded Q&A plus the action layer described in [actions.md](actions.md).
Enterprise knowledge retrieval (RAG) remains a later phase.

## Design principles

1. **LLM API keys never enter the browser.** All model traffic goes through `copilot-gateway`.
2. **Identity comes from a verifiable JWT**, never a client-supplied `userId`.
3. **Conversation state lives on the server**, so token budget, auditing and ownership checks cannot
   be bypassed by a client.
4. **Page DOM text is untrusted input** (prompt-injection surface), isolated from system instructions.
5. **Defaults are safe.** Every debug affordance is gated behind the `dev` profile.
6. **Failures are visible and recoverable**, and always audited.
7. **Zero host pollution.** Shadow DOM styling, no global namespace theft, no layout interference.

## Component view

```mermaid
flowchart TB
  Host[Host_App_Vue_React_JSP]
  SDK[enterprise_copilot_js]
  Widget[Widget_ShadowDOM_confirm_card]
  Store[CopilotStore_batched_deltas]
  PageEng[PageEngine_structured_snapshot]
  Registry[ElementRegistry_refs_reverify]
  Tools[ToolRegistry_risk_grading]
  Ctx[ContextEngine_budget]
  Trans[Transport_SSE_timeout_401retry]

  GW[Gateway_SpringBoot]
  Guard[Guard_JWT_ratelimit]
  Policy[ToolPolicy_allowlist_permissions]
  Threads[ThreadService_history_pending_calls]
  Prompt[PromptBuilder_budget]
  Model[ModelClient_timeout_retry_breaker]
  Mock[MockAnswerService_offline_planner]
  Audit[AuditService_success_and_failure]
  DB[(PostgreSQL)]
  LLM[OpenAI_compatible_model]

  Host --> SDK
  SDK --> Widget
  Widget --> Store
  SDK --> PageEng
  PageEng --> Registry
  SDK --> Tools
  Tools --> Registry
  SDK --> Ctx
  Ctx --> Trans
  Trans -->|SSE_and_tool_result| GW
  GW --> Guard
  Guard --> Policy
  Policy --> Threads
  Threads --> Prompt
  Prompt --> Model
  Prompt --> Mock
  Model --> LLM
  GW --> Audit
  Threads --> DB
  Audit --> DB
```

### Module responsibilities

| Module | Responsibility |
|--------|----------------|
| `widget/` | Shadow DOM chat UI: streaming, stop, retry, copy, clear, confirmation card, i18n, a11y |
| `store.ts` | External store; batches streaming deltas so long answers do not thrash React |
| `page/PageEngine` | Structured page extract: headings, fields, tables, controls with refs, selection; redaction |
| `page/elementRegistry` | Maps refs back to live elements; re-verifies identity before any action |
| `tools/ToolRegistry` | Business tool registration, risk grading, confirmation decisions, execution |
| `tools/pageActions` | Built-in DOM tools: click, fill, select, read, scroll |
| `context/ContextEngine` | Merges business context, enforces the 4KB budget, tolerates host bugs |
| `transport/` | SSE client with idle timeout, error taxonomy, tool-result continuation, thread APIs |
| `auth/` | JWT verification, principal extraction |
| `tools/ToolPolicy` (gateway) | Per-app allowlist + per-tool JWT permission, at advertisement and dispatch |
| `chat/ThreadService` | Conversation persistence, ownership enforcement, pending tool-call tracking |
| `chat/PromptBuilder` | Grounded prompt assembly under a combined character budget |
| `chat/ModelClient` | Model call with timeout, bounded retry, circuit breaker; forwards tool intent |
| `chat/MockAnswerService` | Offline answering and deterministic action planning (no API key needed) |
| `ratelimit/` | Per-user and per-tenant fixed-window limits |
| `audit/` | Audit of every turn, successful or failed |

The action loop (tool.call → confirmation → execution → tool-result continuation) and its security
model are specified in [actions.md](actions.md).

## Conversation model

A turn is not self-contained; the server owns history.

```mermaid
sequenceDiagram
  participant B as Browser SDK
  participant G as Gateway
  participant D as PostgreSQL
  participant M as Model

  B->>G: POST /v1/chat (message, threadId?, pageContext, businessContext, clientTools)
  G->>G: verify JWT, rate limit, filter tools by policy
  G->>D: resolve or create thread (tenantId + userSub scoped)
  D-->>G: recent turns
  G->>G: assemble prompt (history + page + business, budgeted)
  G->>D: persist user turn
  G->>M: completion (permitted tools offered)
  alt model answers
    M-->>G: deltas
    G-->>B: SSE text.delta, text.done, done
    G->>D: persist assistant turn + audit row
  else model requests a tool
    M-->>G: tool call
    G->>G: re-authorize the named tool
    G->>D: persist TOOL_CALL turn
    G-->>B: SSE tool.call, done
    B->>B: confirm (write risk), execute, re-observe page
    B->>G: POST /v1/chat/threadId/tool-result
    G->>G: verify pending call matches (id + name)
    G->>D: persist TOOL_RESULT turn
    G->>M: continue with outcome + fresh page
    M-->>G: deltas
    G-->>B: SSE text.delta, text.done, done
  end
```

Ownership: a thread belongs to `(tenantId, userSub)`. A mismatch returns **404**, not 403, so thread
ids are not enumerable.

The SDK stores only the `threadId` (in `sessionStorage`) and refetches messages on reload — message
content never needs to be trusted from the client.

## Context model

```ts
interface PageContextSnapshot {
  url: string;
  title: string;
  summary: string;            // structured: HEADINGS / FIELDS / TABLES / CONTROLS / PAGE_TEXT
  actionableElements: Array<{
    ref: string;              // opaque handle actions target; re-verified before use
    name: string;
    role: string;
    kind: string;             // button | link | text | select | checkbox | radio | other
    hint?: string;
    value?: string;
    disabled?: boolean;
  }>;
  selection?: string;         // what the user highlighted
}
```

### Budgets

| Field | Limit | Enforced |
|-------|-------|----------|
| `businessContext` | 4 KB | Client trims, server rejects with 413 |
| `pageContext.summary` | 12 000 chars | Client caps, server re-caps |
| `actionableElements` | 40 (viewport-first) | Client and server |
| `message` | 8 000 API / 4 000 prompt | Server |
| Conversation history | 8 turns / 6 000 chars | Server |
| **Total prompt** | 24 000 chars | Server |

Under budget pressure the server sheds context in a deliberate order: **page text first, then
history. Business facts are never dropped** — they are the authoritative answer source.

### Redaction

Removed client-side before upload: password and secret-named inputs, email addresses, phone numbers,
long digit sequences (card-like), `sk-` style keys. Hosts can exclude whole regions with
`data-copilot-ignore`.

## Trust & threat model

| Threat | Mitigation |
|--------|------------|
| Stolen model API key | Keys only on the gateway; the browser holds an app JWT |
| Spoofed identity | Only verified JWT claims are trusted; issuer and expiry checked |
| Weak signing key | Startup fails when the secret is under 32 bytes |
| Prompt injection via DOM | Page context is labeled untrusted data; system prompt forbids following it |
| Cross-tenant / cross-user leakage | Threads scoped to `(tenantId, userSub)`; audit reads filtered by tenant |
| Audit exposure | `/v1/audits` requires a token plus `audit:read` |
| Unauthenticated token minting | Demo endpoint exists only under the `dev` profile |
| Abuse / cost blowout | Per-user and per-tenant rate limits; bounded context and retries |
| Host CSS breakage | Widget renders inside a Shadow DOM |
| Sensitive page content | Client-side redaction plus `data-copilot-ignore` |

Audit fields: `traceId`, `userSub`, `tenantId`, `appId`, `threadId`, `question`, `contextHash`,
`answer`, `model`, `status`, `errorCode`, `latencyMs`, `createdAt`.

## Reliability

| Failure | Behavior |
|---------|----------|
| Model timeout | Aborted at `copilot.llm.timeout`; up to 2 retries **before** the first token |
| Model failure mid-stream | Partial answer kept; no restart (would duplicate text) |
| Repeated model failures | Circuit breaker opens 30s after 5 consecutive failures |
| Any model failure | Degraded message to the user, `error` event, `FAILED` audit row |
| Client disconnect | Upstream streaming stops; partial answer persisted |
| Gateway silence | Client-side idle timeout aborts and offers retry |
| Expired token | Client refetches the token once and replays — unless the turn already performed a write, since replaying could re-run the action (reporting a tool outcome is always retried: it re-executes nothing) |
| Rate limit | `429 rate_limited` with a retry hint |

## Open-source landscape

| Project | Role |
|---------|------|
| Alibaba PageAgent | Reference for in-page perception; our page actions follow the same "live in the page" model with a policy layer on top |
| CopilotKit / AG-UI | Protocol and Context/Tool design influence |
| Pillar | Closest product shape; we differentiate on identity, tenancy, audit |
| Dify / MaxKB | Candidate RAG backends for a later phase |

**Differentiation:** business/user context binding, verifiable identity, tenant isolation,
server-side enforcement and audit — not another chat bubble.

## Runtime topology

| Environment | Gateway | Database | Token minting |
|-------------|---------|----------|---------------|
| Local (`dev`) | `mvn spring-boot:run` on `:8080` | H2 file | Enabled (unauthenticated) |
| Production (`prod`) | Container, non-root JRE | PostgreSQL + Flyway | Not registered |

See [operations.md](operations.md) for configuration and the go-live checklist.
