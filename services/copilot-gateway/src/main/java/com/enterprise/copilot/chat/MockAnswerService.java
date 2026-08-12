package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.dto.ChatDtos.ActionableElement;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.api.dto.ChatDtos.PageContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic offline answerer and action planner used when no model is configured.
 *
 * <p>It is a heuristic stub, not a language model. Questions are resolved against business context
 * keys (with Chinese/English aliases) with keyword recall over the page text as fallback; requests
 * phrased as actions ("帮我审批", "填写X为Y") are mapped onto the permitted tools — field edits first,
 * then business tools by description overlap, then a click on the named control. When nothing
 * matches it refuses instead of echoing the question, mirroring the grounding policy the real model
 * is instructed to follow. It never chains actions on its own.
 */
@Component
public class MockAnswerService {

  /** Question keyword -> candidate business-context field names. */
  private static final Map<String, List<String>> FIELD_ALIASES = new LinkedHashMap<>();

  static {
    FIELD_ALIASES.put("金额", List.of("amount", "totalAmount", "price", "total"));
    FIELD_ALIASES.put("价格", List.of("price", "amount", "unitPrice"));
    FIELD_ALIASES.put("amount", List.of("amount", "totalAmount", "price", "total"));
    FIELD_ALIASES.put("状态", List.of("status", "state", "orderStatus"));
    FIELD_ALIASES.put("status", List.of("status", "state", "orderStatus"));
    FIELD_ALIASES.put("客户", List.of("customerName", "customer", "customerId", "clientName"));
    FIELD_ALIASES.put("customer", List.of("customerName", "customer", "customerId"));
    FIELD_ALIASES.put("订单号", List.of("orderId", "orderNo", "orderNumber", "id"));
    FIELD_ALIASES.put("订单", List.of("orderId", "orderNo", "orderNumber", "id"));
    FIELD_ALIASES.put("order", List.of("orderId", "orderNo", "orderNumber", "id"));
    FIELD_ALIASES.put("等级", List.of("level", "tier", "grade"));
    FIELD_ALIASES.put("level", List.of("level", "tier", "grade"));
    FIELD_ALIASES.put("数量", List.of("quantity", "count", "qty"));
    FIELD_ALIASES.put("日期", List.of("date", "createdAt", "orderDate", "dueDate"));
    FIELD_ALIASES.put("时间", List.of("date", "createdAt", "updatedAt", "time"));
    FIELD_ALIASES.put("负责人", List.of("owner", "assignee", "applicant", "manager"));
    FIELD_ALIASES.put("申请人", List.of("applicant", "requester", "owner"));
    FIELD_ALIASES.put("备注", List.of("remark", "note", "comment", "description"));
  }

  private static final List<String> CONTROL_KEYWORDS =
      List.of("按钮", "控件", "能做什么", "button", "control");

  /** Verbs that mean "do it for me" rather than "tell me about it". */
  private static final List<String> ACTION_VERBS =
      List.of(
          "帮我点",
          "帮我提交",
          "帮我审批",
          "帮我填",
          "帮我导出",
          "帮我选",
          "点一下",
          "点击",
          "提交审批",
          "去审批",
          "填写",
          "填上",
          "选择",
          "click",
          "submit",
          "approve",
          "fill in",
          "select ");

  /** Verbs that specifically mean "type this into a field". */
  private static final List<String> FILL_VERBS = List.of("填写", "填上", "填入", "填", "输入", "fill", "type");

  /** Verbs that specifically mean "choose an option". */
  private static final List<String> SELECT_VERBS = List.of("选择", "选成", "改成", "设为", "select", "choose");

  /** Separators between a field name and the value the user wants in it. */
  private static final List<String> VALUE_SEPARATORS =
      List.of("为", "是", "填", "改成", "设为", "=", ":", "：", " to ");

  private static final List<String> SUMMARY_KEYWORDS =
      List.of("这个页面", "当前页面", "什么页面", "页面是", "摘要", "summar", "what page", "this page");

  /** A deterministic offline plan: either a tool to invoke or a textual answer. */
  public record MockPlan(ModelClient.ToolInvocation toolCall, String text) {}

  /**
   * Offline planner used when no model is configured.
   *
   * <p>Recognises "do it for me" phrasing and maps it onto an available tool: a named business tool
   * when the wording matches, otherwise a page action against the control whose label the user
   * mentioned. Anything it cannot ground becomes a refusal rather than a guess.
   */
  public MockPlan plan(
      String question,
      PageContext page,
      Map<String, Object> businessContext,
      List<ClientTool> tools,
      List<ChatTurn> history) {
    String text = question == null ? "" : question.trim();
    String lower = text.toLowerCase(Locale.ROOT);

    if (!tools.isEmpty() && isActionRequest(lower)) {
      // Intent first: "填写审批人" is a fill, even though it contains the word 审批. Matching a
      // business tool by keyword before checking the verb would pick the wrong action.
      ModelClient.ToolInvocation call = planFieldAction(text, lower, page, tools);
      if (call == null) {
        call = planBusinessTool(text, lower, businessContext, tools);
      }
      if (call == null) {
        call = planPageAction(text, page, tools);
      }
      if (call != null) {
        return new MockPlan(call, "");
      }
    }
    return new MockPlan(null, answer(new ChatRequest("mock", null, text, page, businessContext, tools), history));
  }

  /**
   * Any action-shaped verb counts as intent to act. A broad verb like 填 on its own is harmless:
   * every planner below still requires a concrete target (and a value, for field edits), so a
   * question such as "这个字段填什么" falls through to a plain answer.
   */
  private static boolean isActionRequest(String lower) {
    return matchesAny(lower, ACTION_VERBS)
        || matchesAny(lower, FILL_VERBS)
        || matchesAny(lower, SELECT_VERBS);
  }

  /**
   * Handles "fill X with Y" and "set X to Y" by locating the control whose label the user named and
   * extracting the value that follows it.
   */
  private ModelClient.ToolInvocation planFieldAction(
      String question, String lower, PageContext page, List<ClientTool> tools) {
    boolean wantsFill = matchesAny(lower, FILL_VERBS);
    boolean wantsSelect = matchesAny(lower, SELECT_VERBS);
    if (!wantsFill && !wantsSelect) {
      return null;
    }
    if (page == null || page.actionableElements() == null) {
      return null;
    }

    for (ActionableElement el : page.actionableElements()) {
      String name = el.name();
      if (name == null || name.isBlank() || el.ref() == null || !question.contains(name)) {
        continue;
      }
      String value = extractValue(question, name);
      if (value.isBlank()) {
        continue;
      }
      boolean isSelect = "select".equals(el.kind());
      String toolName = isSelect ? "page_select" : "page_fill";
      if (isSelect ? !wantsSelect && !wantsFill : !wantsFill) {
        continue;
      }
      if (tools.stream().noneMatch(t -> toolName.equals(t.name()))) {
        continue;
      }
      return new ModelClient.ToolInvocation(
          "mock_" + toolName + "_" + el.ref(),
          toolName,
          isSelect
              ? Map.of("ref", el.ref(), "option", value)
              : Map.of("ref", el.ref(), "value", value));
    }
    return null;
  }

  /** Text after the field name and a connector: "审批人为张三" -> "张三". */
  private static String extractValue(String question, String fieldName) {
    int idx = question.indexOf(fieldName);
    if (idx < 0) {
      return "";
    }
    String tail = question.substring(idx + fieldName.length()).trim();
    for (String separator : VALUE_SEPARATORS) {
      if (tail.startsWith(separator)) {
        return trimTrailingPunctuation(tail.substring(separator.length()).trim());
      }
    }
    return "";
  }

  private static String trimTrailingPunctuation(String value) {
    return value.replaceAll("[。，,.!！?？\\s]+$", "");
  }

  /** Matches a registered business tool by name or description overlap with the request. */
  private ModelClient.ToolInvocation planBusinessTool(
      String question, String lower, Map<String, Object> businessContext, List<ClientTool> tools) {
    for (ClientTool tool : tools) {
      if (tool.name().startsWith("page_")) {
        continue;
      }
      boolean nameHit = lower.contains(tool.name().toLowerCase(Locale.ROOT));
      boolean descHit =
          tool.description() != null
              && !tool.description().isBlank()
              && sharesKeyword(question, tool.description());
      if (nameHit || descHit) {
        Map<String, Object> args = new LinkedHashMap<>();
        // Pass through business identifiers the tool most likely needs.
        if (businessContext != null) {
          for (String key : List.of("orderId", "id", "customerId")) {
            Object value = lookup(businessContext, key);
            if (value != null) {
              args.put(key, value);
              break;
            }
          }
        }
        return new ModelClient.ToolInvocation("mock_" + tool.name(), tool.name(), args);
      }
    }
    return null;
  }

  /** Falls back to clicking the control whose visible label appears in the request. */
  private ModelClient.ToolInvocation planPageAction(
      String question, PageContext page, List<ClientTool> tools) {
    if (page == null || page.actionableElements() == null) {
      return null;
    }
    boolean canClick = tools.stream().anyMatch(t -> "page_click".equals(t.name()));
    if (!canClick) {
      return null;
    }
    for (ActionableElement el : page.actionableElements()) {
      String name = el.name();
      if (name == null || name.isBlank() || el.ref() == null) {
        continue;
      }
      if (question.contains(name)) {
        return new ModelClient.ToolInvocation(
            "mock_click_" + el.ref(), "page_click", Map.of("ref", el.ref()));
      }
    }
    return null;
  }

  /** Summarizes a tool outcome for the offline continuation turn. */
  public String describeToolOutcome(String toolName, Object result, String error) {
    if (error != null && !error.isBlank()) {
      if (error.startsWith("user_declined")) {
        return "操作「" + toolName + "」未执行：你取消了确认。";
      }
      return "操作「" + toolName + "」执行失败：" + error;
    }
    if (result == null) {
      return "操作「" + toolName + "」已执行。";
    }
    return "操作「" + toolName + "」已执行，结果：" + result + "。";
  }

  /**
   * Overlap test that works for Chinese as well as space-delimited text.
   *
   * <p>Whitespace tokenization alone fails on Chinese ("审批当前订单" is a single token), so CJK runs are
   * additionally compared as character bigrams: "审批当前订单" yields 审批/批当/当前/前订/订单, and "帮我审批这个订单"
   * matches on 审批.
   */
  private static boolean sharesKeyword(String question, String description) {
    for (String token : description.split("[\\s,，。.;；:：]+")) {
      if (token.isBlank()) {
        continue;
      }
      if (token.length() >= 3 && question.contains(token)) {
        return true;
      }
      for (int i = 0; i + 2 <= token.length(); i += 1) {
        String bigram = token.substring(i, i + 2);
        if (isCjk(bigram) && question.contains(bigram)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isCjk(String text) {
    return text.codePoints().allMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
  }

  public String answer(ChatRequest request, List<ChatTurn> history) {
    String question = request.message() == null ? "" : request.message().trim();
    String lower = question.toLowerCase(Locale.ROOT);
    String title = pageTitle(request);

    if (matchesAny(lower, CONTROL_KEYWORDS)) {
      String controls = controls(request);
      return controls.isEmpty()
          ? "当前页面上没有识别到可操作的按钮或控件。"
          : "当前页面「" + title + "」上可操作的控件有：" + controls + "。";
    }

    // Direct business-field retrieval — this is what makes offline demos答得出金额/客户等字段。
    Map<String, Object> business = request.businessContext();
    if (business != null && !business.isEmpty()) {
      List<String> hits = new ArrayList<>();
      for (Map.Entry<String, List<String>> alias : FIELD_ALIASES.entrySet()) {
        if (!lower.contains(alias.getKey().toLowerCase(Locale.ROOT))) {
          continue;
        }
        for (String field : alias.getValue()) {
          Object value = lookup(business, field);
          if (value != null) {
            hits.add(field + "：" + value);
            break;
          }
        }
      }
      if (!hits.isEmpty()) {
        return "根据业务上下文，" + String.join("；", dedupe(hits)) + "。（离线模式）";
      }
    }

    if (matchesAny(lower, SUMMARY_KEYWORDS)) {
      return "当前页面是「"
          + title
          + "」。"
          + (business == null || business.isEmpty()
              ? ""
              : "业务上下文包含字段：" + String.join("、", business.keySet()) + "。")
          + "你可以问其中任意字段的具体值。";
    }

    // Keyword recall over page text so free-form questions still get grounded snippets.
    String snippet = recallFromPage(request, question);
    if (snippet != null) {
      return "页面中与你的问题相关的内容是：" + snippet + "（离线模式，未使用大模型）";
    }

    if (!history.isEmpty()) {
      return "当前页面与业务上下文中没有可回答该问题的信息。已理解的上下文包含："
          + describeAvailable(request)
          + "。（离线模式）";
    }
    return "当前页面与业务上下文中没有相关信息。可询问：" + describeAvailable(request) + "。（离线模式）";
  }

  private static String describeAvailable(ChatRequest request) {
    List<String> parts = new ArrayList<>();
    if (request.businessContext() != null && !request.businessContext().isEmpty()) {
      parts.add("业务字段（" + String.join("、", request.businessContext().keySet()) + "）");
    }
    String controls = controls(request);
    if (!controls.isEmpty()) {
      parts.add("页面控件");
    }
    return parts.isEmpty() ? "页面文本" : String.join("、", parts);
  }

  private static Object lookup(Map<String, Object> business, String field) {
    for (Map.Entry<String, Object> entry : business.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(field)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String recallFromPage(ChatRequest request, String question) {
    if (request.pageContext() == null || request.pageContext().summary() == null) {
      return null;
    }
    String summary = request.pageContext().summary();
    for (String token : question.split("[\\s，,。？?！!的是有多少]+")) {
      if (token.length() < 2) {
        continue;
      }
      int idx = summary.indexOf(token);
      if (idx >= 0) {
        int start = Math.max(0, idx - 40);
        int end = Math.min(summary.length(), idx + 80);
        return "…" + summary.substring(start, end).replace("\n", " ").trim() + "…";
      }
    }
    return null;
  }

  private static String controls(ChatRequest request) {
    if (request.pageContext() == null || request.pageContext().actionableElements() == null) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (ActionableElement el : request.pageContext().actionableElements()) {
      String name = el.name() == null || el.name().isBlank() ? el.role() : el.name();
      if (name != null && !name.isBlank() && !names.contains(name)) {
        names.add(name);
      }
    }
    return String.join("、", names);
  }

  private static String pageTitle(ChatRequest request) {
    if (request.pageContext() == null
        || request.pageContext().title() == null
        || request.pageContext().title().isBlank()) {
      return "当前页面";
    }
    return request.pageContext().title();
  }

  private static boolean matchesAny(String haystack, List<String> needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> dedupe(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String value : values) {
      if (!out.contains(value)) {
        out.add(value);
      }
    }
    return out;
  }
}
