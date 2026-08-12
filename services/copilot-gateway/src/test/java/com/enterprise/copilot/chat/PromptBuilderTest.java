package com.enterprise.copilot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.api.dto.ChatDtos.ActionableElement;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.PageContext;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PromptBuilderTest {

  private final CopilotProperties properties = new CopilotProperties();
  private final PromptBuilder builder = new PromptBuilder(properties, new ObjectMapper());

  private static final UserPrincipal USER =
      new UserPrincipal("u1", "张三", "demo", List.of("manager"), List.of("order:view"));

  private static ChatRequest request(String message, Map<String, Object> business, PageContext page) {
    return new ChatRequest("crm", null, message, page, business);
  }

  @Test
  void marksPageContextUntrustedAndIncludesBusinessFacts() {
    var built =
        builder.build(
            USER,
            request(
                "状态？",
                Map.of("status", "待审批"),
                new PageContext("http://x", "订单详情", "summary", List.of(), null)),
            List.of());

    assertTrue(built.system().contains("untrusted"), "system prompt must label page data untrusted");
    assertTrue(built.user().contains("UNTRUSTED"), "user block must mark the page section");
    assertTrue(built.user().contains("待审批"));
  }

  @Test
  void includesConversationHistoryOldestFirst() {
    List<ChatTurn> history =
        List.of(
            ChatTurn.of("thr_1", ChatTurn.Role.USER, "订单金额是多少"),
            ChatTurn.of("thr_1", ChatTurn.Role.ASSISTANT, "金额为 50000"));

    var built = builder.build(USER, request("那客户是谁", Map.of(), null), history);

    int userTurn = built.user().indexOf("订单金额是多少");
    int assistantTurn = built.user().indexOf("金额为 50000");
    assertTrue(userTurn >= 0 && assistantTurn > userTurn, "history must appear chronologically");
  }

  @Test
  void rejectsUnknownApp() {
    ChatRequest req = new ChatRequest("unknown", null, "hi", null, Map.of());
    ApiException ex = assertThrows(ApiException.class, () -> builder.build(USER, req, List.of()));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
  }

  @Test
  void rejectsOversizedBusinessContext() {
    Map<String, Object> big = Map.of("blob", "x".repeat(5000));
    ApiException ex =
        assertThrows(
            ApiException.class, () -> builder.build(USER, request("hi", big, null), List.of()));
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
    assertEquals("context_too_large", ex.getCode());
  }

  @Test
  void capsActionableElementsAndSummary() {
    properties.getContext().setMaxActionableElements(2);
    properties.getContext().setMaxSummaryChars(20);

    List<ActionableElement> elements =
        List.of(
            new ActionableElement("A", "button", null),
            new ActionableElement("B", "button", null),
            new ActionableElement("C", "button", null));

    var built =
        builder.build(
            USER,
            request("有哪些按钮", Map.of(), new PageContext("u", "t", "y".repeat(200), elements, null)),
            List.of());

    assertFalse(built.user().contains("[button] C"), "element cap must drop extras");
    assertTrue(built.user().contains("[truncated]"), "summary cap must truncate");
  }

  @Test
  void keepsBusinessContextWhenTotalBudgetIsTight() {
    properties.getContext().setMaxPromptChars(300);

    var built =
        builder.build(
            USER,
            request(
                "金额",
                Map.of("amount", 50000),
                new PageContext("u", "t", "z".repeat(5000), List.of(), null)),
            List.of());

    assertTrue(built.user().contains("50000"), "business facts must survive budget pressure");
  }

  @Test
  void includesUserSelectionWhenPresent() {
    var built =
        builder.build(
            USER,
            request("这段什么意思", Map.of(), new PageContext("u", "t", "body", List.of(), "关键条款")),
            List.of());

    assertTrue(built.user().contains("关键条款"));
  }
}
