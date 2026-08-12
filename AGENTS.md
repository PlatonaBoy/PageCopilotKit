# AGENTS.md

## Cursor Cloud specific instructions

### Product / services
Monorepo for an embeddable "Enterprise AI Copilot" (see `README.md` and `docs/`). Four parts:

- `packages/sdk` — TypeScript SDK (Vite lib build). Produces `packages/sdk/dist/enterprise-copilot.js` (IIFE), `enterprise-copilot.esm.js` (ESM) and `index.d.ts`.
- `apps/demo-host` — fake CRM demo page on Vite dev server (port `5173`).
- `services/copilot-gateway` — Spring Boot + Spring AI gateway (Java 21, Maven), port `8080`.
- `tests/e2e` — Playwright browser tests against the running demo + gateway.

### Toolchain
Node 20+ and Java 21 are available. Maven (`mvn`) is required for the gateway; if missing, install with `sudo apt-get install -y maven`.

### Running (dev)

- **Gateway**: `SPRING_PROFILES_ACTIVE=dev mvn -f services/copilot-gateway/pom.xml spring-boot:run`.
  - The `dev` profile is required locally: it supplies a dev JWT secret, uses an H2 file DB, enables permissive CORS, and registers the unauthenticated `POST /v1/demo/token` endpoint the demo needs. Under any other profile that endpoint is not registered (404) and startup fails without `COPILOT_JWT_SECRET` (≥ 32 bytes).
  - Mock LLM is the default (`COPILOT_MOCK_LLM=true`), so it runs fully offline. The mock resolves questions against `businessContext` field names (amount/customer/status), and also **plans actions**: phrasing like 「帮我审批这个订单」/「帮我填写审批人为张三」 emits a `tool.call` for a permitted tool. Set `COPILOT_MOCK_LLM=false` + `OPENAI_API_KEY` for a real model.
  - Tool calling is governed by `copilot.tools.*` (dev profile ships an allowlist for `crm` and requires `order:approve` for `approveOrder`). In the `prod` profile actions are **off** unless `COPILOT_TOOLS_ENABLED=true`.
  - Health: `GET http://localhost:8080/v1/health` → `{"status":"UP"}`. Actuator probes at `/actuator/health/{liveness,readiness}`.
- **Demo host**: from repo root run `npm run build:sdk` FIRST (builds the SDK and copies the IIFE bundle to `apps/demo-host/public/vendor/`), then `npm run dev --prefix apps/demo-host`. `npm run dev:demo` does both. The vendor bundle is git-ignored and must be rebuilt after SDK changes — the demo loads the built file via `<script src>`, so SDK edits are NOT hot-reloaded.

### Lint / test / build

- Everything at once: `npm run verify` (SDK lint + unit tests + build with size budget + gateway tests).
- SDK: `npm run lint`, `npm run typecheck`, `npm test` (Vitest, jsdom), `npm run build` — all under `packages/sdk`. The build fails if the IIFE bundle exceeds its gzip budget (`scripts/check-size.mjs`).
- Gateway: `mvn -f services/copilot-gateway/pom.xml test`. Integration tests use the `test` profile with in-memory H2 and Flyway.
- E2E: start gateway + demo first, then `npm run test:e2e`. Set `CHROME_PATH` to reuse an installed Chrome instead of downloading one, e.g. `CHROME_PATH=/usr/local/bin/google-chrome npm run test:e2e`.

### Database
Schema is owned by Flyway (`services/copilot-gateway/src/main/resources/db/migration`) and Hibernate runs with `ddl-auto: validate`. Adding or changing an entity field requires a new `V<n>__*.sql` migration, otherwise startup fails with a schema-validation error. Note that `String` columns backed by `TEXT` must use `columnDefinition = "text"` rather than `@Lob`, or validation will expect a CLOB and fail.

### End-to-end smoke test (no browser needed)
With the gateway running under `dev`: `POST /v1/demo/token` to mint a JWT, then `POST /v1/chat` (SSE) with `Authorization: Bearer <token>`, `appId: crm`, a `message`, and a `businessContext`. Reuse the returned `threadId` on a follow-up request to exercise multi-turn history, and `GET /v1/threads/{threadId}/messages` to inspect stored turns. To exercise the action loop, include `clientTools` and an action-phrased message (「帮我审批这个订单」) — the stream ends with `event:tool.call`; report the outcome via `POST /v1/chat/{threadId}/tool-result` (the `toolCallId` and `name` must match the outstanding call).

Note the per-user rate limit defaults to 20/min; set `COPILOT_RATE_USER` higher when scripting many requests as one user (CI does this for the E2E suite).

### Production shape
`docker compose up --build` runs PostgreSQL plus the gateway on the `prod` profile. Copy `.env.example` to `.env` first — `COPILOT_JWT_SECRET` and `DB_PASSWORD` are required and compose fails fast without them. See `docs/operations.md` for the go-live checklist.
