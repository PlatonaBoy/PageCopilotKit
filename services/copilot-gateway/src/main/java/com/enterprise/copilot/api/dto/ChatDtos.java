package com.enterprise.copilot.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ChatDtos {

  private ChatDtos() {}

  public record ActionableElement(String name, String role, String hint) {}

  public record PageContext(
      String url,
      String title,
      String summary,
      List<ActionableElement> actionableElements,
      /** Text the user has selected on the page, when any. */
      String selection) {}

  public record ChatRequest(
      @NotBlank @Size(max = 64) String appId,
      @Size(max = 64) String threadId,
      @NotBlank @Size(max = 8000) String message,
      PageContext pageContext,
      Map<String, Object> businessContext) {}

  public record TokenRequest(
      String sub, String name, String tenantId, List<String> roles, List<String> permissions) {}

  public record TokenResponse(String accessToken, long expiresIn) {}

  public record HealthResponse(String status) {}

  public record ThreadMessage(String role, String content, Instant createdAt) {}

  public record ThreadMessagesResponse(String threadId, List<ThreadMessage> messages) {}
}
