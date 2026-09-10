package com.enterprise.copilot.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {

  private KnowledgeDtos() {}

  /**
   * Ingest payload. {@code tenantId} is deliberately absent: it comes from the caller's verified
   * token, so a client cannot write into another tenant's knowledge base.
   */
  public record DocumentRequest(
      @Size(max = 64) String docId,
      @NotBlank @Size(max = 64) String appId,
      @NotBlank @Size(max = 300) String title,
      @Size(max = 1000) String source,
      @Size(max = 20) List<@Size(max = 40) String> tags,
      @NotBlank @Size(max = 200_000) String content) {}

  public record DocumentView(
      String docId,
      String appId,
      String title,
      String source,
      List<String> tags,
      int chunkCount,
      int contentChars,
      Instant createdAt,
      Instant updatedAt) {}

  public record DocumentListResponse(String tenantId, int count, List<DocumentView> items) {}

  public record SearchRequest(
      @NotBlank @Size(max = 1000) String query, @Size(max = 64) String appId, Integer topK) {}

  public record SearchHit(
      int index, String docId, String title, String source, String snippet, double score) {}

  public record SearchResponse(String tenantId, String appId, int count, List<SearchHit> hits) {}

  public record DeleteResponse(boolean deleted, String docId) {}
}
