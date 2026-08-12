package com.enterprise.copilot.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
    name = "audit_records",
    indexes = {
      @Index(name = "idx_audit_tenant_created", columnList = "tenantId,createdAt"),
      @Index(name = "idx_audit_trace", columnList = "traceId"),
      @Index(name = "idx_audit_thread", columnList = "threadId")
    })
public class AuditRecord {

  public enum Status {
    SUCCESS,
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String traceId;

  @Column(nullable = false, length = 128)
  private String userSub;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String appId;

  @Column(length = 64)
  private String threadId;

  // `text` maps natively on PostgreSQL and to CHARACTER VARYING on H2 in PG mode,
  // which keeps Hibernate schema validation aligned with the Flyway baseline.
  @Column(nullable = false, columnDefinition = "text")
  private String question;

  @Column(nullable = false, length = 64)
  private String contextHash;

  @Column(columnDefinition = "text")
  private String answer;

  @Column(length = 128)
  private String model;

  @Column(nullable = false, length = 16)
  private String status = Status.SUCCESS.name();

  @Column(length = 64)
  private String errorCode;

  private Long latencyMs;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  public Long getId() {
    return id;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getUserSub() {
    return userSub;
  }

  public void setUserSub(String userSub) {
    this.userSub = userSub;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public String getThreadId() {
    return threadId;
  }

  public void setThreadId(String threadId) {
    this.threadId = threadId;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getContextHash() {
    return contextHash;
  }

  public void setContextHash(String contextHash) {
    this.contextHash = contextHash;
  }

  public String getAnswer() {
    return answer;
  }

  public void setAnswer(String answer) {
    this.answer = answer;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status.name();
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public Long getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(Long latencyMs) {
    this.latencyMs = latencyMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
