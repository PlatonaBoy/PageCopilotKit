package com.enterprise.copilot.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lexical (BM25) retrieval over knowledge chunks.
 *
 * <p>BM25 rather than embeddings, deliberately: it needs no model call, so the gateway keeps its
 * "runs fully offline" property, results are deterministic and explainable, and an operator can see
 * exactly why a passage was cited. The trade-off is no synonym matching — {@code
 * docs/knowledge.md} describes how to swap in a vector store when that matters.
 *
 * <p>The index is per tenant and in-process, rebuilt lazily from the database and invalidated on
 * every write. That keeps tenant isolation structural (one tenant's postings are never in another
 * tenant's index) at the cost of a rebuild after each ingest — acceptable up to {@code
 * copilot.knowledge.max-indexed-chunks}, beyond which retrieval belongs in a real search engine.
 */
@Component
public class KnowledgeIndex {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);

  /** Standard BM25 saturation and length-normalization constants. */
  private static final double K1 = 1.2;

  private static final double B = 0.75;

  /** Application scope that makes a document visible to every application in the tenant. */
  public static final String ALL_APPS = "*";

  private final KnowledgeChunkRepository chunks;
  private final Map<String, TenantIndex> cache = new ConcurrentHashMap<>();

  public KnowledgeIndex(KnowledgeChunkRepository chunks) {
    this.chunks = chunks;
  }

  /** One scored chunk. {@code score} is comparable only within a single query. */
  public record Hit(String docId, int chunkIndex, String content, double score) {}

  private record Entry(
      String docId, int chunkIndex, String appId, String content, Map<String, Integer> termFreq) {
    int length() {
      return termFreq.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  private record TenantIndex(List<Entry> entries, Map<String, Integer> docFreq, double avgLength) {}

  /** Drops the cached index so the next query rebuilds it from the database. */
  public void invalidate(String tenantId) {
    cache.remove(tenantId);
  }

  public void invalidateAll() {
    cache.clear();
  }

  /**
   * Ranks chunks visible to {@code appId} against the query.
   *
   * <p>Documents scoped to {@link #ALL_APPS} participate alongside the application's own; anything
   * belonging to another application is never scored, so a shared gateway cannot leak one app's
   * handbook into another app's answers. A null or {@code *} {@code appId} is an administrative
   * search across the tenant.
   */
  public List<Hit> search(String tenantId, String appId, String query, int topK, double minScore) {
    if (topK <= 0) {
      return List.of();
    }
    List<String> queryTerms = KnowledgeTokenizer.tokenize(query);
    if (queryTerms.isEmpty()) {
      return List.of();
    }
    TenantIndex index = cache.computeIfAbsent(tenantId, this::build);
    if (index.entries().isEmpty()) {
      return List.of();
    }

    Set<String> distinct = new HashSet<>(queryTerms);
    List<Hit> scored = new ArrayList<>();
    for (Entry entry : index.entries()) {
      if (!isVisible(entry, appId)) {
        continue;
      }
      double score = score(entry, distinct, index);
      if (score >= minScore) {
        scored.add(new Hit(entry.docId(), entry.chunkIndex(), entry.content(), score));
      }
    }

    scored.sort(
        Comparator.comparingDouble(Hit::score)
            .reversed()
            // Stable ordering for equal scores keeps answers reproducible across restarts.
            .thenComparing(Hit::docId)
            .thenComparingInt(Hit::chunkIndex));
    return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
  }

  private static boolean isVisible(Entry entry, String appId) {
    // A null / "*" appId is an administrative search across the tenant. Chat always passes the
    // thread's own application, so one app's handbook still cannot leak into another's answers.
    if (appId == null || appId.isBlank() || ALL_APPS.equals(appId)) {
      return true;
    }
    return ALL_APPS.equals(entry.appId()) || entry.appId().equals(appId);
  }

  private double score(Entry entry, Set<String> queryTerms, TenantIndex index) {
    double total = 0;
    int length = entry.length();
    for (String term : queryTerms) {
      Integer frequency = entry.termFreq().get(term);
      if (frequency == null) {
        continue;
      }
      int documentFrequency = index.docFreq().getOrDefault(term, 0);
      if (documentFrequency == 0) {
        continue;
      }
      int corpusSize = index.entries().size();
      double idf =
          Math.log(1 + (corpusSize - documentFrequency + 0.5) / (documentFrequency + 0.5));
      double normalized = length / Math.max(index.avgLength(), 1.0);
      total += idf * (frequency * (K1 + 1)) / (frequency + K1 * (1 - B + B * normalized));
    }
    return total;
  }

  private TenantIndex build(String tenantId) {
    List<KnowledgeChunk> rows = chunks.findByTenantIdOrderByIdAsc(tenantId);
    List<Entry> entries = new ArrayList<>(rows.size());
    Map<String, Integer> docFreq = new HashMap<>();
    long totalLength = 0;

    for (KnowledgeChunk row : rows) {
      Map<String, Integer> termFreq = new HashMap<>();
      // The heading is indexed alongside the body so a question phrased like the document title
      // ranks its passages, even when the body never repeats the title verbatim.
      for (String term : KnowledgeTokenizer.tokenize(row.getHeading())) {
        termFreq.merge(term, 1, Integer::sum);
      }
      for (String term : KnowledgeTokenizer.tokenize(row.getContent())) {
        termFreq.merge(term, 1, Integer::sum);
      }
      for (String term : termFreq.keySet()) {
        docFreq.merge(term, 1, Integer::sum);
      }
      Entry entry =
          new Entry(row.getDocId(), row.getChunkIndex(), row.getAppId(), row.getContent(), termFreq);
      totalLength += entry.length();
      entries.add(entry);
    }

    double avgLength = entries.isEmpty() ? 1.0 : (double) totalLength / entries.size();
    log.debug("Built knowledge index tenant={} chunks={}", tenantId, entries.size());
    return new TenantIndex(List.copyOf(entries), Map.copyOf(docFreq), avgLength);
  }
}
