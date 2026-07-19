# Enterprise AI Copilot SDK — Architecture

## Positioning

Enterprise-facing embeddable AI assistant for web applications.

Any host app (Vue / React / Angular / JSP) loads one script:

```html
<script src="enterprise-copilot.js"></script>
```

and gets a bottom-right copilot that understands the current page, business context, and (in later phases) can call tools and act on the DOM.

## Design principles

1. **LLM API keys never enter the browser.** All model traffic goes through `copilot-gateway`.
2. **Identity comes from CAS/JWT**, not from client-supplied `userId`.
3. **Page DOM text is untrusted input** (prompt-injection surface). It is isolated from system/tool instructions.
4. **MVP is a monolith gateway.** Do not split microservices until RAG / MCP / admin require it.
5. **Wrap PageAgent; do not fork it.** Use it as the page perception/execution runtime.

## High-level architecture

```
Host App (Vue / React / JSP / Angular)
              |
    enterprise-copilot.js (SDK)
              |
  +-----------+-----------+-----------+
  |           |           |           |
Widget    PageEngine  ContextEngine  ToolRegistry
(Shadow)  (PageAgent) (business+)    (phase 2)
  |           |           |
  +-----+-----+-----------+
        |
   SSE / HTTP
        |
  copilot-gateway (Spring Boot + Spring AI)
        |
  OpenAI-compatible models
```

### Module responsibilities

| Module | Responsibility | MVP |
|--------|----------------|-----|
| Widget | Shadow DOM chat UI, streaming Markdown | Yes |
| PageEngine | DOM snapshot via PageAgent adapter | Yes (read/summary) |
| ContextEngine | Merge page + business + user (from JWT on server) | Yes |
| ToolRegistry | Client tools + HITL confirm | Phase 2 |
| Transport | `POST /v1/chat` SSE client | Yes |
| Gateway | Auth, prompt assembly, model proxy, audit | Yes |
| RAG / Memory / MCP | Knowledge, long-term memory, external tools | Phase 2–3 |

## Context model

```ts
interface CopilotInit {
  appId: string;
  gatewayUrl: string;
  getAccessToken: () => string | Promise<string>;
  contextProvider?: () => Record<string, unknown> | Promise<Record<string, unknown>>;
  tools?: CopilotTool[]; // phase 2
  ui?: { position?: 'bottom-right'; locale?: string };
}

interface PageContextSnapshot {
  url: string;
  title: string;
  summary: string;
  actionableElements: Array<{ name: string; role: string; hint?: string }>;
}
```

### Budgets (MVP defaults)

| Field | Limit |
|-------|-------|
| `businessContext` JSON | 4 KB |
| `pageContext.summary` | ~12 KB chars (~3–4k tokens) |
| `actionableElements` | top 40 interactive nodes |

Sensitive patterns (password inputs, `type=password`, obvious ID numbers) are stripped client-side before upload.

### Refresh strategy

- Capture snapshot on init and before each chat send.
- Throttle SPA updates: listen to `popstate` / `pushState` / `replaceState` and a debounced `MutationObserver` (1s) to invalidate cache only; re-snapshot on next send.

## Trust & threat model

| Threat | Mitigation |
|--------|------------|
| Stolen model API key | Keys only on gateway; SDK uses app JWT |
| Spoofed userId | Gateway trusts JWT claims only |
| Prompt injection via DOM | Label page context as untrusted data in prompt; never execute DOM text as instructions |
| Over-privileged tools | Phase 2: whitelist, schema validation, write+confirm, server re-auth |
| Cross-tenant leak | `tenantId` from JWT on every audit/session row |
| CSS breakage of host | Widget renders inside Shadow DOM |

### Audit fields

`traceId`, `userSub`, `tenantId`, `appId`, `question`, `contextHash`, `answer`, `model`, `latencyMs`, `createdAt`.

## Open-source landscape

| Project | Role vs this product |
|---------|----------------------|
| Alibaba PageAgent | Page runtime dependency |
| CopilotKit / AG-UI | Protocol & Context/Tool ideas |
| Pillar | Closest product shape; we differentiate on CAS/permission/audit |
| Dify / MaxKB | Optional RAG backend later |
| browser-use / Stagehand | External RPA — out of MVP scope |

**Differentiation:** Business / User / Permission context + CAS + server-side enforcement + audit + multi-tenant RAG — not another chat bubble.

## Phased roadmap

| Phase | Scope |
|-------|-------|
| **MVP** | Script embed, Shadow widget, page summary + business context, Spring AI gateway, JWT, audit, demo host |
| **Phase 2** | Tool calling, PageAgent execute, HITL confirm, permission denials |
| **Phase 3** | RAG (or Dify), real CAS, admin console, MCP |

## Runtime topology (local MVP)

- `apps/demo-host` — fake CRM page on Vite (~5173)
- `packages/sdk` — builds `enterprise-copilot.js`
- `services/copilot-gateway` — Spring Boot on `:8080`
- H2 (or PostgreSQL) for audit; in-memory session acceptable for MVP
