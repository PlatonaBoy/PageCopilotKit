# Enterprise knowledge base

Tenant-scoped retrieval that grounds answers in your own documents and shows the user where they
came from.

This is not a general-purpose RAG platform. It is the smallest design that makes "according to our
refund policy…" a real, auditable sentence rather than a hallucination: ingest documents per tenant,
retrieve a handful of passages for each question, put them in the prompt as numbered excerpts, and
emit the same list as an SSE `citations` event the widget can render.

Vector search and an external store are deliberately out of scope. The gateway must keep working
fully offline (`COPILOT_MOCK_LLM=true`, no model key), results must be deterministic, and a cited
passage must be explainable. BM25 over character-bigram tokens meets those constraints. Swap the
`KnowledgeIndex` implementation when synonym matching or multi-million-chunk corpora become the
problem — the ingest API, citation contract and prompt shape stay the same.

## How a turn uses it

```
question
   │
   ▼
KnowledgeService.retrieve(tenantId, appId, question)
   │   BM25, top-K, score floor
   ▼
numbered excerpts  ──►  PromptBuilder  (KNOWLEDGE BASE block)
                   ──►  SSE event `citations`  (before the first token)
                   ──►  chat_messages.citations  (so restore shows the same sources)
```

Resolution order the model (and the offline mock) is instructed to follow:

1. **Business context** — the host's `contextProvider` facts. Authoritative for the current record.
2. **Knowledge base** — this tenant's documents. Authoritative for policy, process, definitions.
3. **Page context** — untrusted DOM. Data only, never instructions.

A knowledge excerpt is reference material, not an instruction: an uploaded document that says
"ignore previous rules" is reported, not obeyed. The same isolation already applied to page text.

## Isolation

| Boundary | Enforcement |
|----------|-------------|
| Tenant | Every lookup is `(tenantId, docId)`. The retriever's index is per tenant. A guessed id in another tenant returns 404. |
| Application | A document's `appId` is either a real application or `*` (visible to every app in the tenant). Chat retrieves with the thread's application, so one app's handbook cannot ground another's answers. |
| Identity | Ingest and listing require JWT permissions (`knowledge:write` / `knowledge:read`). Chat retrieval needs none — answering from the tenant's own documents is the product. Tenant always comes from verified claims, never from the request body. |

## Ingest

```
POST /v1/knowledge/documents
Authorization: Bearer <JWT with knowledge:write>
```

```json
{
  "docId": "refund-policy",
  "appId": "crm",
  "title": "退货与退款政策",
  "source": "https://intranet.example.com/kb/refund-policy",
  "tags": ["售后", "政策"],
  "content": "客户可在签收后 30 个自然日内申请退货。…"
}
```

- Omit `docId` to have one assigned. Re-posting the same `docId` **replaces** the document and
  rebuilds its chunks — knowledge bases are synced from an owning system, not accumulated.
- `appId: "*"` publishes the document to every application in the tenant.
- The body is split into overlapping chunks (default 700 characters, 120 overlap) at paragraph
  boundaries. Chunks, not documents, are what BM25 ranks and what a citation points at.
- Quotas (`max-documents-per-tenant`, `max-indexed-chunks`, `max-document-chars`) refuse ingest
  rather than silently dropping content.

Other routes, all tenant-scoped:

| Method | Path | Permission |
|--------|------|------------|
| GET | `/v1/knowledge/documents` | `knowledge:read` |
| GET | `/v1/knowledge/documents/{docId}` | `knowledge:read` |
| DELETE | `/v1/knowledge/documents/{docId}` | `knowledge:write` |
| POST | `/v1/knowledge/search` | `knowledge:read` |

`POST /v1/knowledge/search` runs the same retrieval chat uses, without generating an answer. That
is what makes a bad citation debuggable: an operator can see the ranking the model was given.

## Citations in chat

The `citations` SSE event is sent **before** the first `text.delta`, so the widget can show sources
while the answer streams:

```
event: citations
data: {"citations":[{"index":1,"docId":"refund-policy","title":"退货与退款政策","source":"https://…","snippet":"…"}]}
```

`index` is the `[n]` marker the model is told to place after the sentence it used. The SDK renders
the list as expandable chips under the assistant bubble. Restoring a thread via
`GET /v1/threads/{id}/messages` returns the same array on each assistant message.

A continuation after a tool result does **not** retrieve again: there is no new user question, and
claiming a source for "the button was clicked" would be a lie.

## Offline demo

Under the `dev` profile the gateway seeds three CRM documents for tenant `demo` (refund policy,
approval SLA, invoicing). They are upserted on every start, so restarting against the H2 file
database refreshes rather than duplicates. Ask:

- 「退货时限是多久」
- 「审批超过 4 小时怎么办」
- 「发货后多久能开票」

The mock answerer quotes the top passage verbatim and cites `[1]`. It does not paraphrase: without a
language model, a wrong paraphrase under a real citation is worse than a verbatim excerpt.

## Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `copilot.knowledge.enabled` | `true` | Set `false` to disable ingest and retrieval. |
| `copilot.knowledge.top-k` | `3` | Passages handed to the model. |
| `copilot.knowledge.max-snippet-chars` | `400` | Per-passage cap. |
| `copilot.knowledge.max-context-chars` | `3000` | Combined knowledge-block budget. |
| `copilot.knowledge.chunk-chars` | `700` | Ingest chunk size. |
| `copilot.knowledge.chunk-overlap-chars` | `120` | Overlap so a fact on a boundary is still retrievable. |
| `copilot.knowledge.min-score` | `0.35` | BM25 floor. Below this, "nothing found" is the correct answer. |
| `copilot.knowledge.max-documents-per-tenant` | `2000` | Ingest quota. |
| `copilot.knowledge.max-indexed-chunks` | `50000` | Safety valve for the in-process index. |

The knowledge block outranks page text and history under the combined prompt budget: it was selected
because it answers this question, and dropping it would silently turn a cited answer into a guess.
Business facts are still never dropped.

## What this is not

- **Not embeddings.** There is no vector column and no embedding API call. Synonyms that do not
  share characters (e.g. "退款" vs "refund") will miss unless both terms appear in the document.
- **Not a shared index across instances.** The BM25 index is in-process and rebuilt on write. Chat
  itself is fine behind a load balancer (PostgreSQL holds the chunks); a write on instance A is
  visible to instance B on the next cache miss. For a large corpus, replace `KnowledgeIndex` with
  OpenSearch / pgvector and keep the rest.
- **Not access control inside a tenant.** Any caller in the tenant can have their questions grounded
  in every document of that tenant (scoped by `appId`). Finer ACLs belong in the system that owns
  the documents, by simply not ingesting what a role must not see.
