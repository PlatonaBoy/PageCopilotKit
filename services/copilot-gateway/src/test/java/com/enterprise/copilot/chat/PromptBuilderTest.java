package com.enterprise.copilot.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.PageContext;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

  @Test
  void buildsPromptWithUntrustedPageLabel() {
    CopilotProperties props = new CopilotProperties();
    PromptBuilder builder = new PromptBuilder(props, new ObjectMapper());
    UserPrincipal user =
        new UserPrincipal("u1", "张三", "demo", List.of("manager"), List.of("order:view"));
    ChatRequest request =
        new ChatRequest(
            "crm",
            null,
            "状态？",
            new PageContext("http://x", "订单详情", "summary", List.of()),
            Map.of("status", "待审批"),
            List.of());

    var built = builder.build(user, request);
    assertTrue(built.system().contains("untrusted") || built.user().contains("UNTRUSTED"));
    assertTrue(built.user().contains("待审批"));
  }

  @Test
  void rejectsUnknownApp() {
    CopilotProperties props = new CopilotProperties();
    PromptBuilder builder = new PromptBuilder(props, new ObjectMapper());
    UserPrincipal user = new UserPrincipal("u1", "张三", "demo", List.of(), List.of());
    ChatRequest request = new ChatRequest("unknown", null, "hi", null, Map.of(), List.of());
    assertThrows(ApiException.class, () -> builder.build(user, request));
  }
}
