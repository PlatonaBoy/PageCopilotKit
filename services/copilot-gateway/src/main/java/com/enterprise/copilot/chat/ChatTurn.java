package com.enterprise.copilot.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
    name = "chat_messages",
    indexes = {@Index(name = "idx_message_thread", columnList = "threadId,id")})
public class ChatTurn {

  public enum Role {
    USER,
    ASSISTANT
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String threadId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Role role;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  public static ChatTurn of(String threadId, Role role, String content) {
    ChatTurn turn = new ChatTurn();
    turn.threadId = threadId;
    turn.role = role;
    turn.content = content == null ? "" : content;
    return turn;
  }

  public Long getId() {
    return id;
  }

  public String getThreadId() {
    return threadId;
  }

  public Role getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
