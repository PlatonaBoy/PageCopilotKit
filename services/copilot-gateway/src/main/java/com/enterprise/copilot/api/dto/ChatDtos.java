package com.enterprise.copilot.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public final class ChatDtos {

  private ChatDtos() {}

  public record ActionableElement(String name, String role, String hint) {}

  public record PageContext(
      String url, String title, String summary, List<ActionableElement> actionableElements) {}

  public record ClientToolSchema(
      String name, String description, Map<String, Object> parameters) {}

  public record ChatRequest(
      @NotBlank String appId,
      String threadId,
      @NotBlank String message,
      PageContext pageContext,
      Map<String, Object> businessContext,
      List<ClientToolSchema> clientTools) {}

  public record DemoTokenRequest(
      String sub, String name, String tenantId, List<String> roles, List<String> permissions) {}

  public record DemoTokenResponse(String accessToken, long expiresIn) {}

  public record HealthResponse(String status) {}
}
