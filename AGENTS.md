# AGENTS.md

## Cursor Cloud specific instructions

### Product / services
Monorepo for an embeddable "Enterprise AI Copilot" (see `README.md` and `docs/`). Three parts:

- `packages/sdk` — TypeScript SDK (Vite lib build). Produces `packages/sdk/dist/enterprise-copilot.js` (IIFE) consumed by the demo.
- `apps/demo-host` — fake CRM demo page on Vite dev server (port `5173`).
- `services/copilot-gateway` — Spring Boot + Spring AI gateway (Java 21, Maven), port `8080`, H2 audit DB (file at `services/copilot-gateway/data/`).

### Toolchain
Node 20+ and Java 21 are available. Maven (`mvn`, installed system-wide) is required for the gateway and is not provided by the update script — it is baked into the VM image. If `mvn` is ever missing, install with `sudo apt-get install -y maven`.

### Running (dev)
Standard commands are in `README.md` / root `package.json`. Key points:

- Gateway (mock LLM, no API key needed): `COPILOT_MOCK_LLM=true mvn -f services/copilot-gateway/pom.xml spring-boot:run`. Health: `GET http://localhost:8080/v1/health` → `{"status":"UP"}`. Mock mode is the default (`copilot.mock-llm` defaults true), so it runs fully offline; set `COPILOT_MOCK_LLM=false` + `OPENAI_API_KEY` for a real model.
- Demo host: from repo root run `npm run build:sdk` FIRST (this builds the SDK and copies the IIFE bundle to `apps/demo-host/public/vendor/enterprise-copilot.js`), then `npm run dev --prefix apps/demo-host`. `npm run dev:demo` does both. The vendor bundle is git-ignored and must be (re)built after SDK changes — the demo loads the built file via `<script src>`, not the SDK source, so SDK edits are NOT hot-reloaded into the demo until you rebuild.

### Lint / test / build
- SDK typecheck + build: `npm run build:sdk` (runs `tsc --noEmit` then `vite build`). There is no separate ESLint config.
- Gateway tests: `mvn -f services/copilot-gateway/pom.xml test` (JUnit via spring-boot-starter-test).

### End-to-end smoke test (no browser needed)
The full backend flow can be exercised with curl against a running gateway: `POST /v1/demo/token` to mint a demo JWT, then `POST /v1/chat` (SSE) with `Authorization: Bearer <token>`, `appId: crm`, a `message`, and a `businessContext`. The mock LLM echoes the order status / page buttons from the supplied context.

### Known product bug (not an environment issue)
The browser demo currently throws `Copilot 初始化失败：api.init is not a function` on load. Root cause: `packages/sdk/src/index.ts` sets `window.EnterpriseCopilot = api` (which has `.init`), but the SDK Vite build (`packages/sdk/vite.config.ts`) uses `name: 'EnterpriseCopilot'` + `exports: 'named'`, so Rollup's IIFE wrapper overwrites `window.EnterpriseCopilot` with the module namespace object (`{ default: api, Copilot, ... }`) whose `.init` is undefined (it lives at `.default.init`). `window.Copilot` is unaffected. Minimal fixes: have `apps/demo-host/main.js` prefer `window.Copilot` (or unwrap `.default`), or drop `exports: 'named'` / rename the IIFE global in the SDK build. This is a product bug, intentionally left unfixed by environment setup.
