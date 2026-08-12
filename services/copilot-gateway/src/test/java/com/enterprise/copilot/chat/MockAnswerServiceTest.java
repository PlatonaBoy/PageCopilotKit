package com.enterprise.copilot.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.copilot.api.dto.ChatDtos.ActionableElement;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.PageContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockAnswerServiceTest {

  private final MockAnswerService service = new MockAnswerService();

  private static ChatRequest ask(String message) {
    return new ChatRequest(
        "crm",
        null,
        message,
        new PageContext(
            "http://x",
            "订单详情",
            "订单详情页 客户 华东制造 金额 50,000.00 状态 待审批",
            List.of(
                new ActionableElement("提交审批", "button", null),
                new ActionableElement("导出 Excel", "button", null)),
            null),
        Map.of("orderId", "ORD-123456", "status", "待审批", "amount", 50000, "customerName", "华东制造"));
  }

  @Test
  void answersAmountFromBusinessContext() {
    String answer = service.answer(ask("这个订单金额是多少"), List.of());
    assertTrue(answer.contains("50000"), () -> "expected amount in answer but got: " + answer);
  }

  @Test
  void answersCustomerFromBusinessContext() {
    String answer = service.answer(ask("客户是谁"), List.of());
    assertTrue(answer.contains("华东制造"), () -> answer);
  }

  @Test
  void answersStatus() {
    String answer = service.answer(ask("当前状态是什么"), List.of());
    assertTrue(answer.contains("待审批"), () -> answer);
  }

  @Test
  void listsControls() {
    String answer = service.answer(ask("当前页面有哪些按钮"), List.of());
    assertTrue(answer.contains("提交审批") && answer.contains("导出 Excel"), () -> answer);
  }

  @Test
  void refusesInsteadOfEchoingWhenNothingMatches() {
    ChatRequest request =
        new ChatRequest("crm", null, "帮我预测下季度营收", null, Map.of("status", "待审批"));
    String answer = service.answer(request, List.of());
    assertTrue(answer.contains("没有"), () -> "expected a refusal but got: " + answer);
    assertTrue(!answer.contains("你问的是"), "must not echo the question back");
  }
}
