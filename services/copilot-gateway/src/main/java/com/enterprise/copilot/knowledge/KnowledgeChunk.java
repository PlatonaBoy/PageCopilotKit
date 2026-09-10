package com.enterprise.copilot.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A retrievable slice of a document.
 *
 * <p>Chunks — not whole documents — are what the retriever ranks and what a citation points at, so
 * an answer can name the exact passage it used instead of a 40-page handbook.
 */
@Entity
@Table(
    name = "knowledge_chunks",
    indexes = {
      @Index(name = "idx_knowledge_chunk_scope", columnList = "tenantId,appId"),
      @Index(name = "idx_knowledge_chunk_doc", columnList = "tenantId,docId")
    })
public class KnowledgeChunk {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String docId;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String appId;

  @Column(nullable = false)
  private int chunkIndex;

  /** Document title, denormalized so the retriever can weigh it without a second read. */
  @Column(nullable = false, length = 300)
  private String heading;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  protected KnowledgeChunk() {}

  public static KnowledgeChunk of(
      String tenantId, String appId, String docId, int chunkIndex, String heading, String content) {
    KnowledgeChunk chunk = new KnowledgeChunk();
    chunk.tenantId = tenantId;
    chunk.appId = appId;
    chunk.docId = docId;
    chunk.chunkIndex = chunkIndex;
    chunk.heading = heading;
    chunk.content = content;
    return chunk;
  }

  public Long getId() {
    return id;
  }

  public String getDocId() {
    return docId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getAppId() {
    return appId;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public String getHeading() {
    return heading;
  }

  public String getContent() {
    return content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
