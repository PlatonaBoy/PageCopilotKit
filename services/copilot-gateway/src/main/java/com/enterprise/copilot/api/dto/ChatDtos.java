package com.enterprise.copilot.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ChatDtos {

  private ChatDtos() {}

  public record ActionableElement(
      String ref, String name, String role, String hint, String kind, Boolean disabled, String value) {}

  public record PageContext(
      String url,
      String title,
      String summary,
      List<ActionableElement> actionableElements,
      /** Text the user has selected on the page, when any. */
      String selection) {}

  /** A capability the browser is willing to execute for this turn. */
  public record ClientTool(
      @NotBlank @Size(max = 64) String name,
      @Size(max = 500) String description,
      Map<String, Object> parameters,
      /** `read` or `write`; anything else is treated as `write`. */
      String risk) {}

  public record ChatRequest(
      @NotBlank @Size(max = 64) String appId,
      @Size(max = 64) String threadId,
      @NotBlank @Size(max = 8000) String message,
      PageContext pageContext,
      Map<String, Object> businessContext,
      @Size(max = 40) List<ClientTool> clientTools) {}

  /** Outcome of a tool the browser executed, plus a fresh observation of the page. */
  public record ToolResultRequest(
      @NotBlank @Size(max = 64) String appId,
      @NotBlank @Size(max = 128) String toolCallId,
      @NotBlank @Size(max = 64) String name,
      Object result,
      @Size(max = 2000) String error,
      PageContext pageContext,
      Map<String, Object> businessContext,
      @Size(max = 40) List<ClientTool> clientTools) {}

  public record TokenRequest(
      String sub, String name, String tenantId, List<String> roles, List<String> permissions) {}

  public record TokenResponse(String accessToken, long expiresIn) {}

  public record HealthResponse(String status) {}

  public record ThreadMessage(String role, String content, Instant createdAt) {}

  public record ThreadMessagesResponse(String threadId, List<ThreadMessage> messages) {}
}
