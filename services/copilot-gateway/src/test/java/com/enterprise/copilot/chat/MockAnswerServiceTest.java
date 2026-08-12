package com.enterprise.copilot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.copilot.api.dto.ChatDtos.ActionableElement;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.api.dto.ChatDtos.PageContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockAnswerServiceTest {

  private final MockAnswerService service = new MockAnswerService();

  private static final Map<String, Object> BUSINESS =
      Map.of("orderId", "ORD-123456", "status", "待审批", "amount", 50000, "customerName", "华东制造");

  private static PageContext page() {
    return new PageContext(
        "http://x",
        "订单详情",
        "订单详情页 客户 华东制造 金额 50,000.00 状态 待审批",
        List.of(
            new ActionableElement("e1_1", "提交审批", "button", "approve", "button", null, null),
            new ActionableElement("e1_2", "导出 Excel", "button", "export", "button", null, null)),
        null);
  }

  private static ChatRequest ask(String message) {
    return new ChatRequest("crm", null, message, page(), BUSINESS, List.of());
  }

  private static ClientTool tool(String name, String description, String risk) {
    return new ClientTool(name, description, Map.of(), risk);
  }

  @Test
  void answersAmountFromBusinessContext() {
    String answer = service.answer(ask("这个订单金额是多少"), List.of());
    assertTrue(answer.contains("50000"), () -> "expected amount in answer but got: " + answer);
  }

  @Test
  void answersCustomerFromBusinessContext() {
    assertTrue(service.answer(ask("客户是谁"), List.of()).contains("华东制造"));
  }

  @Test
  void answersStatus() {
    assertTrue(service.answer(ask("当前状态是什么"), List.of()).contains("待审批"));
  }

  @Test
  void listsControls() {
    String answer = service.answer(ask("当前页面有哪些按钮"), List.of());
    assertTrue(answer.contains("提交审批") && answer.contains("导出 Excel"), () -> answer);
  }

  @Test
  void refusesInsteadOfEchoingWhenNothingMatches() {
    ChatRequest request =
        new ChatRequest("crm", null, "帮我预测下季度营收", null, Map.of("status", "待审批"), List.of());
    String answer = service.answer(request, List.of());
    assertTrue(answer.contains("没有"), () -> "expected a refusal but got: " + answer);
    assertTrue(!answer.contains("你问的是"), "must not echo the question back");
  }

  @Test
  void planPicksABusinessToolWhenTheUserAsksForAction() {
    var plan =
        service.plan(
            "帮我审批这个订单",
            page(),
            BUSINESS,
            List.of(tool("approveOrder", "审批当前订单", "write")),
            List.of());

    assertNotNull(plan.toolCall(), "an action request must produce a tool call");
    assertEquals("approveOrder", plan.toolCall().name());
    assertEquals("ORD-123456", plan.toolCall().arguments().get("orderId"));
  }

  @Test
  void planFallsBackToClickingTheNamedControl() {
    var plan =
        service.plan(
            "帮我点提交审批", page(), BUSINESS, List.of(tool("page_click", "点击控件", "write")), List.of());

    assertNotNull(plan.toolCall());
    assertEquals("page_click", plan.toolCall().name());
    assertEquals("e1_1", plan.toolCall().arguments().get("ref"));
  }

  @Test
  void planFillsAFieldRatherThanMatchingAToolThatSharesAWord() {
    // "填写审批人" contains 审批, which must not be mistaken for the approveOrder tool.
    PageContext form =
        new PageContext(
            "http://x",
            "订单详情",
            "表单",
            List.of(new ActionableElement("e1_5", "审批人", "input", null, "text", null, null)),
            null);

    var plan =
        service.plan(
            "帮我填写审批人为张三",
            form,
            BUSINESS,
            List.of(tool("approveOrder", "审批当前订单", "write"), tool("page_fill", "填写字段", "write")),
            List.of());

    assertNotNull(plan.toolCall());
    assertEquals("page_fill", plan.toolCall().name());
    assertEquals("e1_5", plan.toolCall().arguments().get("ref"));
    assertEquals("张三", plan.toolCall().arguments().get("value"));
  }

  @Test
  void planSelectsAnOptionForADropdown() {
    PageContext form =
        new PageContext(
            "http://x",
            "订单详情",
            "表单",
            List.of(new ActionableElement("e1_6", "优先级", "select", null, "select", null, null)),
            null);

    var plan =
        service.plan(
            "把优先级改成加急", form, BUSINESS, List.of(tool("page_select", "选择选项", "write")), List.of());

    assertNotNull(plan.toolCall());
    assertEquals("page_select", plan.toolCall().name());
    assertEquals("加急", plan.toolCall().arguments().get("option"));
  }

  @Test
  void planIgnoresAFillRequestWithoutAValue() {
    PageContext form =
        new PageContext(
            "http://x",
            "订单详情",
            "表单",
            List.of(new ActionableElement("e1_5", "审批人", "input", null, "text", null, null)),
            null);

    var plan =
        service.plan("帮我填写审批人", form, BUSINESS, List.of(tool("page_fill", "填写字段", "write")), List.of());

    assertNull(plan.toolCall(), "without a value there is nothing to type");
  }

  @Test
  void planAnswersInsteadOfActingForInformationalQuestions() {
    var plan =
        service.plan(
            "这个订单金额是多少",
            page(),
            BUSINESS,
            List.of(tool("approveOrder", "审批当前订单", "write")),
            List.of());

    assertNull(plan.toolCall(), "a question must not trigger an action");
    assertTrue(plan.text().contains("50000"));
  }

  @Test
  void planDoesNotActWhenNoToolsAreAvailable() {
    var plan = service.plan("帮我点提交审批", page(), BUSINESS, List.of(), List.of());
    assertNull(plan.toolCall());
  }

  @Test
  void describesDeclinedActionsWithoutClaimingSuccess() {
    String outcome =
        service.describeToolOutcome("approveOrder", null, "user_declined: the user did not approve");
    assertTrue(outcome.contains("未执行"), () -> outcome);
  }

  @Test
  void describesFailedActionsWithTheReason() {
    String outcome = service.describeToolOutcome("page_click", null, "element is disabled");
    assertTrue(outcome.contains("失败") && outcome.contains("element is disabled"));
  }
}
