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
 * A knowledge base article owned by one tenant.
 *
 * <p>The document id is unique per tenant rather than globally, so two tenants can independently
 * use natural keys of their own ("refund-policy") without colliding.
 */
@Entity
@Table(
    name = "knowledge_documents",
    indexes = {
      @Index(name = "idx_knowledge_doc_key", columnList = "tenantId,docId", unique = true),
      @Index(name = "idx_knowledge_doc_scope", columnList = "tenantId,appId")
    })
public class KnowledgeDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String docId;

  @Column(nullable = false, length = 64)
  private String tenantId;

  /** Application the document is visible to. {@code *} makes it visible to every application. */
  @Column(nullable = false, length = 64)
  private String appId;

  @Column(nullable = false, length = 300)
  private String title;

  /** Where the content came from — a URL or a human-readable locator shown with the citation. */
  @Column(length = 1000)
  private String source;

  /** Comma-separated labels, surfaced to the model so it can tell similar documents apart. */
  @Column(length = 500)
  private String tags;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(nullable = false)
  private int chunkCount;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  protected KnowledgeDocument() {}

  public static KnowledgeDocument create(
      String docId,
      String tenantId,
      String appId,
      String title,
      String source,
      String tags,
      String content) {
    KnowledgeDocument doc = new KnowledgeDocument();
    doc.docId = docId;
    doc.tenantId = tenantId;
    doc.appId = appId;
    doc.title = title;
    doc.source = source;
    doc.tags = tags;
    doc.content = content;
    return doc;
  }

  public void update(String appId, String title, String source, String tags, String content) {
    this.appId = appId;
    this.title = title;
    this.source = source;
    this.tags = tags;
    this.content = content;
    this.updatedAt = Instant.now();
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

  public String getTitle() {
    return title;
  }

  public String getSource() {
    return source;
  }

  public String getTags() {
    return tags;
  }

  public String getContent() {
    return content;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public void setChunkCount(int chunkCount) {
    this.chunkCount = chunkCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
