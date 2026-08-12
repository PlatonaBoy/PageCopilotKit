package com.enterprise.copilot.chat;

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
    name = "chat_threads",
    indexes = {
      @Index(name = "idx_thread_key", columnList = "threadId", unique = true),
      @Index(name = "idx_thread_owner", columnList = "tenantId,userSub")
    })
public class ChatThread {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64, unique = true)
  private String threadId;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String userSub;

  @Column(nullable = false, length = 64)
  private String appId;

  @Column(length = 200)
  private String title;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant lastActiveAt = Instant.now();

  public static ChatThread create(String threadId, String tenantId, String userSub, String appId) {
    ChatThread thread = new ChatThread();
    thread.threadId = threadId;
    thread.tenantId = tenantId;
    thread.userSub = userSub;
    thread.appId = appId;
    return thread;
  }

  /** A thread is only visible to the same tenant + user that created it. */
  public boolean ownedBy(String tenantId, String userSub) {
    return this.tenantId.equals(tenantId) && this.userSub.equals(userSub);
  }

  public Long getId() {
    return id;
  }

  public String getThreadId() {
    return threadId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getUserSub() {
    return userSub;
  }

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public void touch() {
    this.lastActiveAt = Instant.now();
  }
}
