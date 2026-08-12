# Operations Guide

Deployment, configuration and go-live checklist for `copilot-gateway`.

## Profiles

| Profile | Database | Token minting | CORS | Use |
|---------|----------|---------------|------|-----|
| `dev` | H2 file (`./data/copilot`) | `POST /v1/demo/token` **enabled, unauthenticated** | any origin | Local development and offline demos only |
| `prod` | PostgreSQL | Not registered (404) | explicit allowlist | Production |
| `test` | H2 in-memory | n/a | `localhost:5173` | Automated tests |

The default profile is `dev`, so a bare `mvn spring-boot:run` is safe locally but **must never be
used in production**. Always set `SPRING_PROFILES_ACTIVE=prod` for real deployments.

## Required configuration (prod)

| Variable | Required | Notes |
|----------|----------|-------|
| `COPILOT_JWT_SECRET` | yes | HS256 key, **≥ 32 bytes**. Startup fails if shorter or missing. `openssl rand -base64 48` |
| `COPILOT_JWT_ISSUER` | recommended | Must match the `iss` claim your IdP emits. Verified by default. |
| `COPILOT_CORS_ORIGINS` | yes | Comma-separated browser origins. Empty means all browser calls are blocked. |
| `COPILOT_ALLOWED_APP_IDS` | yes | Applications permitted to call `/v1/chat`. |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | yes | PostgreSQL connection. |
| `COPILOT_MOCK_LLM` | yes | Set `false` in production. |
| `OPENAI_API_KEY` | when not mocking | Never exposed to browsers. |
| `OPENAI_BASE_URL` / `OPENAI_MODEL` | optional | Any OpenAI-compatible endpoint. |
| `COPILOT_RATE_USER` / `COPILOT_RATE_TENANT` | optional | Requests per minute. Defaults 20 / 200. |
| `COPILOT_LLM_TIMEOUT` | optional | Default `45s`. |
| `COPILOT_TOOLS_ENABLED` | optional | **`false` in prod by default.** Enables AI actions (tool calling / page operations). |

### Tool calling configuration

When enabling actions, configure the policy in the same breath — an enabled tool layer with an empty
allowlist offers every client-declared tool to the model (a startup warning is logged):

```yaml
copilot:
  tools:
    enabled: true
    max-steps-per-turn: 5          # tool round-trips per user turn
    allowed:                       # per-application tool allowlist
      crm: [approveOrder, exportOrder, page_click, page_fill, page_read]
    required-permission:           # JWT permission required per tool
      approveOrder: order:approve
```

Full model and security rationale: [actions.md](actions.md).

## Deploy with Docker

```bash
cp .env.example .env      # fill COPILOT_JWT_SECRET and DB_PASSWORD
docker compose up --build
```

The image runs as a non-root user, uses a JRE-only runtime layer, and exposes a healthcheck on
`/v1/health`.

## Database migrations

Schema is owned by Flyway (`src/main/resources/db/migration`). Hibernate runs with
`ddl-auto: validate`, so the application refuses to start if the schema drifts from the entities —
add a new `V<n>__*.sql` migration rather than letting Hibernate mutate tables.

## Observability

| Endpoint | Purpose |
|----------|---------|
| `GET /v1/health` | Simple public liveness probe used by the SDK and load balancers |
| `GET /actuator/health/liveness` | Kubernetes liveness |
| `GET /actuator/health/readiness` | Kubernetes readiness (includes DB) |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

Every request carries a trace id: incoming `X-Trace-Id` is honored, otherwise one is generated. It
is placed in MDC (so it appears in all log lines), echoed in the response header, and stored on the
audit row alongside the SSE `done` event's `traceId`.

## Audit

Every turn is persisted — including failures, with `status=FAILED` and an `errorCode`.

Read via `GET /v1/audits?limit=20`, which requires a valid token **and** the `audit:read`
permission, and is always filtered to the caller's tenant.

Retention is not automated; schedule a periodic delete against `audit_records.created_at` according
to your data policy.

## Reliability behavior

| Condition | Behavior |
|-----------|----------|
| Model timeout | Aborted at `COPILOT_LLM_TIMEOUT`, retried up to 2 times before any token is streamed |
| Repeated model failures | Circuit breaker opens for 30s after 5 consecutive failures; requests fail fast with `breaker_open` |
| Model failure with no output | Client receives a readable degraded message plus an `error` event |
| Model failure mid-stream | Partial answer is preserved; no silent restart (would duplicate text) |
| Client disconnects | Upstream streaming stops; the partial answer is persisted and audited |
| Rate limit exceeded | `429` with `rate_limited` and a retry hint |

## Go-live checklist

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `COPILOT_JWT_SECRET` is a fresh random value ≥ 32 bytes, injected from a secret manager
- [ ] `COPILOT_JWT_ISSUER` matches the real identity provider
- [ ] `COPILOT_CORS_ORIGINS` lists only your application origins (no wildcard)
- [ ] `COPILOT_MOCK_LLM=false` and the model key is configured
- [ ] `POST /v1/demo/token` returns **404**
- [ ] `GET /v1/audits` without `audit:read` returns **403**
- [ ] Cross-user access to another `threadId` returns **404**
- [ ] PostgreSQL reachable; Flyway migrations applied on boot
- [ ] Readiness/liveness probes wired to the actuator endpoints
- [ ] Log aggregation captures `traceId` and `tenantId`
- [ ] Audit retention job scheduled

Actions (only when `COPILOT_TOOLS_ENABLED=true`):

- [ ] `copilot.tools.allowed.<appId>` lists exactly the operations you intend to automate
- [ ] Every consequential tool has an entry in `copilot.tools.required-permission`
- [ ] Startup log shows **no** "enabled without an application allowlist" warning
- [ ] Full checklist in [actions.md](actions.md) reviewed

## Scaling notes

Rate limiting and the circuit breaker are **in-process**. A multi-instance deployment behind a load
balancer will therefore enforce limits per instance. Move both to a shared store (Redis) before
scaling horizontally; `RateLimiter` is intentionally a narrow interface to contain that change.

Conversation state is in PostgreSQL, so chat itself scales horizontally without sticky sessions.
