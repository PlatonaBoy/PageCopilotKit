package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.dto.ChatDtos.ActionableElement;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic offline answerer used when no model is configured.
 *
 * <p>It is a retrieval stub, not a language model: it resolves the question against business
 * context keys (with Chinese/English aliases) and falls back to keyword recall over the page text.
 * When nothing matches it refuses instead of echoing the question, mirroring the grounding policy
 * the real model is instructed to follow.
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
      List.of("按钮", "控件", "操作", "能做什么", "button", "control", "action");

  private static final List<String> SUMMARY_KEYWORDS =
      List.of("这个页面", "当前页面", "什么页面", "页面是", "摘要", "summar", "what page", "this page");

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
