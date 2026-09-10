package com.enterprise.copilot.knowledge;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the tenant knowledge base: ingest, listing, deletion and retrieval.
 *
 * <p>Every operation takes the tenant from verified JWT claims, never from the request body — a
 * caller cannot read or write another tenant's documents even by guessing a document id.
 */
@Service
public class KnowledgeService {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

  /** Conservative id charset: safe in a URL path and readable in an audit trail. */
  private static final Pattern DOC_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");

  private static final int MAX_TITLE_CHARS = 300;
  private static final int MAX_SOURCE_CHARS = 1000;
  private static final int MAX_TAGS_CHARS = 500;

  private final KnowledgeDocumentRepository documents;
  private final KnowledgeChunkRepository chunks;
  private final KnowledgeIndex index;
  private final CopilotProperties properties;

  public KnowledgeService(
      KnowledgeDocumentRepository documents,
      KnowledgeChunkRepository chunks,
      KnowledgeIndex index,
      CopilotProperties properties) {
    this.documents = documents;
    this.chunks = chunks;
    this.index = index;
    this.properties = properties;
  }

  /** What the caller asked to store. Tenant is supplied separately, from the token. */
  public record DocumentInput(
      String docId, String appId, String title, String source, List<String> tags, String content) {}

  public boolean isEnabled() {
    return properties.getKnowledge().isEnabled();
  }

  /**
   * Creates or replaces a document and rebuilds its chunks.
   *
   * <p>Upsert rather than insert-only: knowledge bases are synced from an owning system, and a
   * re-sync must not accumulate duplicates that then compete with each other for citations.
   */
  @Transactional
  public KnowledgeDocument upsert(String tenantId, DocumentInput input) {
    requireEnabled();

    String docId = resolveDocId(input.docId());
    String appId = requireText(input.appId(), "appId", 64);
    String title = requireText(input.title(), "title", MAX_TITLE_CHARS);
    String content = requireText(input.content(), "content", properties.getKnowledge().getMaxDocumentChars());
    String source = optionalText(input.source(), "source", MAX_SOURCE_CHARS);
    String tags = joinTags(input.tags());

    Optional<KnowledgeDocument> existing = documents.findByTenantIdAndDocId(tenantId, docId);
    if (existing.isEmpty()
        && documents.countByTenantId(tenantId) >= properties.getKnowledge().getMaxDocumentsPerTenant()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "knowledge_quota_exceeded",
          "Tenant already stores the maximum of "
              + properties.getKnowledge().getMaxDocumentsPerTenant()
              + " documents");
    }

    List<String> pieces =
        KnowledgeChunker.chunk(
            content,
            properties.getKnowledge().getChunkChars(),
            properties.getKnowledge().getChunkOverlapChars());
    if (pieces.isEmpty()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "bad_request", "content is empty");
    }

    long existingChunks = chunks.countByTenantId(tenantId);
    long replacedChunks = existing.map(KnowledgeDocument::getChunkCount).orElse(0);
    if (existingChunks - replacedChunks + pieces.size() > properties.getKnowledge().getMaxIndexedChunks()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "knowledge_quota_exceeded",
          "Tenant index would exceed "
              + properties.getKnowledge().getMaxIndexedChunks()
              + " chunks");
    }

    KnowledgeDocument document =
        existing
            .map(
                doc -> {
                  doc.update(appId, title, source, tags, content);
                  return doc;
                })
            .orElseGet(
                () ->
                    KnowledgeDocument.create(
                        docId, tenantId, appId, title, source, tags, content));
    document.setChunkCount(pieces.size());
    documents.save(document);

    chunks.deleteByTenantIdAndDocId(tenantId, docId);
    List<KnowledgeChunk> rows = new ArrayList<>(pieces.size());
    for (int i = 0; i < pieces.size(); i += 1) {
      rows.add(KnowledgeChunk.of(tenantId, appId, docId, i, title, pieces.get(i)));
    }
    chunks.saveAll(rows);

    index.invalidate(tenantId);
    log.info(
        "Knowledge document upserted tenant={} doc={} app={} chunks={}",
        tenantId,
        docId,
        appId,
        pieces.size());
    return document;
  }

  @Transactional(readOnly = true)
  public List<KnowledgeDocument> list(String tenantId, String appId, int limit) {
    int capped = Math.clamp(limit, 1, 200);
    if (appId == null || appId.isBlank()) {
      return documents.findByTenantIdOrderByIdDesc(tenantId, PageRequest.of(0, capped));
    }
    return documents.findByTenantIdAndAppIdInOrderByIdDesc(
        tenantId, List.of(appId, KnowledgeIndex.ALL_APPS), PageRequest.of(0, capped));
  }

  @Transactional(readOnly = true)
  public KnowledgeDocument get(String tenantId, String docId) {
    return documents
        .findByTenantIdAndDocId(tenantId, docId)
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.NOT_FOUND,
                    "knowledge_document_not_found",
                    "Document not found"));
  }

  @Transactional
  public void delete(String tenantId, String docId) {
    KnowledgeDocument document = get(tenantId, docId);
    chunks.deleteByTenantIdAndDocId(tenantId, docId);
    documents.delete(document);
    index.invalidate(tenantId);
    log.info("Knowledge document deleted tenant={} doc={}", tenantId, docId);
  }

  /**
   * Retrieves the passages that should ground an answer to {@code question}.
   *
   * <p>Returns an empty list — never a partial or fabricated match — when nothing clears the score
   * floor, so "the knowledge base has nothing on this" stays distinguishable from "here is a weak
   * guess".
   */
  @Transactional(readOnly = true)
  public List<RetrievedSnippet> retrieve(String tenantId, String appId, String question) {
    CopilotProperties.Knowledge config = properties.getKnowledge();
    if (!config.isEnabled() || question == null || question.isBlank()) {
      return List.of();
    }

    List<KnowledgeIndex.Hit> hits =
        index.search(tenantId, appId, question, config.getTopK(), config.getMinScore());
    if (hits.isEmpty()) {
      return List.of();
    }

    // Look up the cited documents by the ids the retriever actually returned. Loading a page of
    // the tenant's catalogue would drop a hit whose document sits past the page window.
    Map<String, KnowledgeDocument> byDocId = new java.util.LinkedHashMap<>();
    for (KnowledgeIndex.Hit hit : hits) {
      if (byDocId.containsKey(hit.docId())) {
        continue;
      }
      documents.findByTenantIdAndDocId(tenantId, hit.docId()).ifPresent(doc -> byDocId.put(hit.docId(), doc));
    }

    Set<String> queryTerms = new LinkedHashSet<>(KnowledgeTokenizer.tokenize(question));
    List<RetrievedSnippet> snippets = new ArrayList<>(hits.size());
    int budget = config.getMaxContextChars();
    int position = 1;

    for (KnowledgeIndex.Hit hit : hits) {
      KnowledgeDocument document = byDocId.get(hit.docId());
      if (document == null) {
        // The index is rebuilt on write, so this only happens in a race with a concurrent delete.
        continue;
      }
      String snippet = excerpt(hit.content(), queryTerms, config.getMaxSnippetChars());
      if (snippet.length() > budget) {
        snippet = snippet.substring(0, Math.max(budget, 0)).strip();
      }
      if (snippet.isBlank()) {
        break;
      }
      budget -= snippet.length();
      snippets.add(
          new RetrievedSnippet(
              position,
              document.getDocId(),
              document.getTitle(),
              document.getSource(),
              snippet,
              hit.chunkIndex(),
              round(hit.score())));
      position += 1;
      if (budget <= 0) {
        break;
      }
    }
    return List.copyOf(snippets);
  }

  /**
   * A window of the chunk centred on the first query-term match.
   *
   * <p>Showing the head of the chunk would often miss the sentence that actually answered the
   * question, which makes a citation look wrong even when the retrieval was right.
   */
  public static String excerpt(String content, Set<String> queryTerms, int maxChars) {
    String text = content.strip();
    if (text.length() <= maxChars) {
      return text;
    }
    String haystack = text.toLowerCase(Locale.ROOT);
    int best = -1;
    for (String term : queryTerms) {
      int found = haystack.indexOf(term);
      if (found >= 0 && (best < 0 || found < best)) {
        best = found;
      }
    }
    if (best < 0) {
      return text.substring(0, maxChars).strip() + "…";
    }

    int start = Math.max(0, best - maxChars / 3);
    int end = Math.min(text.length(), start + maxChars);
    start = Math.max(0, Math.min(start, end - maxChars));
    String window = text.substring(start, end).strip();
    return (start > 0 ? "…" : "") + window + (end < text.length() ? "…" : "");
  }

  private void requireEnabled() {
    if (!properties.getKnowledge().isEnabled()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "knowledge_disabled",
          "Knowledge base is disabled (copilot.knowledge.enabled=false)");
    }
  }

  private static String resolveDocId(String requested) {
    if (requested == null || requested.isBlank()) {
      return "kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
    String trimmed = requested.strip();
    if (!DOC_ID.matcher(trimmed).matches()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "bad_request",
          "docId must match [A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    }
    return trimmed;
  }

  private static String requireText(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "bad_request", field + " is required");
    }
    String trimmed = value.strip();
    if (trimmed.length() > max) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "knowledge_document_too_large",
          field + " exceeds " + max + " characters");
    }
    return trimmed;
  }

  private static String optionalText(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return requireText(value, field, max);
  }

  private static String joinTags(List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return null;
    }
    String joined =
        tags.stream()
            .filter(tag -> tag != null && !tag.isBlank())
            .map(String::strip)
            .distinct()
            .collect(Collectors.joining(","));
    if (joined.isBlank()) {
      return null;
    }
    if (joined.length() > MAX_TAGS_CHARS) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "knowledge_document_too_large",
          "tags exceed " + MAX_TAGS_CHARS + " characters");
    }
    return joined;
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }
}
