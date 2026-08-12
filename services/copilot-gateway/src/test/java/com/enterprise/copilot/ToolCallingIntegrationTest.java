package com.enterprise.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.copilot.auth.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the action pipeline end to end at the HTTP layer, with the offline planner standing in for
 * the model so the assertions stay deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tools")
class ToolCallingIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @Autowired private ObjectMapper objectMapper;

  private String token(List<String> permissions) {
    return jwtService.issueToken("actor", "actor", "t-tools", List.of("manager"), permissions, 600);
  }

  private static Map<String, Object> page() {
    return Map.of(
        "url", "http://localhost/orders/1",
        "title", "订单详情",
        "summary", "订单详情页",
        "actionableElements",
            List.of(
                Map.of("ref", "e1_1", "name", "提交审批", "role", "button", "kind", "button")));
  }

  private static List<Map<String, Object>> tools(String... names) {
    return List.of(names).stream()
        .map(
            name ->
                Map.<String, Object>of(
                    "name",
                    name,
                    "description",
                    switch (name) {
                      case "approveOrder" -> "审批当前订单";
                      case "page_click" -> "点击页面控件";
                      default -> name;
                    },
                    "parameters",
                    Map.of("type", "object", "properties", Map.of()),
                    "risk",
                    "write"))
        .toList();
  }

  private String resultBody(String callId, String name, Object result) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "crm");
    body.put("toolCallId", callId);
    body.put("name", name);
    body.put("result", result);
    body.put("pageContext", page());
    body.put("businessContext", Map.of("orderId", "ORD-1", "status", "审批中"));
    body.put("clientTools", tools("approveOrder"));
    return objectMapper.writeValueAsString(body);
  }

  private String chatBody(String message, List<Map<String, Object>> clientTools) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "crm");
    body.put("message", message);
    body.put("pageContext", page());
    body.put("businessContext", Map.of("orderId", "ORD-1", "status", "待审批"));
    body.put("clientTools", clientTools);
    return objectMapper.writeValueAsString(body);
  }

  private String stream(String jwt, String body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    result.getAsyncResult(10_000);
    return utf8(result);
  }

  /** MockMvc would otherwise decode the SSE body with the servlet default charset. */
  private static String utf8(MvcResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  void emitsAToolCallWhenTheUserAsksForAnAction() throws Exception {
    String sse = stream(token(List.of("order:approve")), chatBody("帮我审批这个订单", tools("approveOrder")));

    assertThat(sse).contains("event:tool.call");
    assertThat(sse).contains("approveOrder");
    // A tool request is not an answer: no text.done should close the turn with prose.
    assertThat(sse).contains("event:done");
  }

  @Test
  void answersWithoutActingForAnInformationalQuestion() throws Exception {
    String sse = stream(token(List.of("order:approve")), chatBody("订单状态是什么", tools("approveOrder")));

    assertThat(sse).doesNotContain("event:tool.call");
    assertThat(sse).contains("待审批");
  }

  @Test
  void neverOffersAToolTheUserLacksPermissionFor() throws Exception {
    // `approveOrder` requires `order:approve` in the tools profile; this caller has none, so the
    // tool is filtered before the model sees it — it cannot request what it was never offered.
    String sse = stream(token(List.of()), chatBody("帮我审批这个订单", tools("approveOrder")));

    assertThat(sse).doesNotContain("event:tool.call");
    assertThat(sse).doesNotContain("已执行");
  }

  @Test
  void neverOffersAToolOutsideTheApplicationAllowlist() throws Exception {
    String sse =
        stream(
            token(List.of("order:approve")),
            chatBody("帮我 deleteEverything 删除全部", tools("deleteEverything")));

    assertThat(sse).doesNotContain("event:tool.call");
    assertThat(sse).doesNotContain("deleteEverything 已执行");
  }

  @Test
  void toolResultContinuesTheSameThread() throws Exception {
    String jwt = token(List.of("order:approve"));
    String first = stream(jwt, chatBody("帮我审批这个订单", tools("approveOrder")));
    String threadId = extract(first, "threadId");
    String callId = extract(first, "id");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "crm");
    body.put("toolCallId", callId);
    body.put("name", "approveOrder");
    body.put("result", Map.of("ok", true));
    body.put("pageContext", page());
    body.put("businessContext", Map.of("orderId", "ORD-1", "status", "审批中"));
    body.put("clientTools", tools("approveOrder"));

    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat/" + threadId + "/tool-result")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    result.getAsyncResult(10_000);
    String sse = utf8(result);

    assertThat(sse).contains("event:text.done");
    assertThat(sse).contains("已执行");
  }

  @Test
  void declinedActionIsReportedWithoutClaimingSuccess() throws Exception {
    String jwt = token(List.of("order:approve"));
    String first = stream(jwt, chatBody("帮我审批这个订单", tools("approveOrder")));
    String threadId = extract(first, "threadId");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "crm");
    body.put("toolCallId", extract(first, "id"));
    body.put("name", "approveOrder");
    body.put("error", "user_declined: the user did not approve this action");
    body.put("pageContext", page());
    body.put("businessContext", Map.of("orderId", "ORD-1", "status", "待审批"));
    body.put("clientTools", tools("approveOrder"));

    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat/" + threadId + "/tool-result")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    result.getAsyncResult(10_000);

    assertThat(utf8(result)).contains("未执行");
  }

  @Test
  void rejectsAFabricatedResultWhenNoToolCallIsOutstanding() throws Exception {
    String jwt = token(List.of("order:approve"));
    // A plain question leaves no tool call pending.
    String threadId = extract(stream(jwt, chatBody("订单状态是什么", tools("approveOrder"))), "threadId");

    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultBody("call_invented", "approveOrder", Map.of("ok", true))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("no_pending_tool_call"));
  }

  @Test
  void rejectsAResultThatDoesNotMatchTheOutstandingCall() throws Exception {
    String jwt = token(List.of("order:approve"));
    String first = stream(jwt, chatBody("帮我审批这个订单", tools("approveOrder")));
    String threadId = extract(first, "threadId");

    // Right thread, outstanding call — but a different tool name and id.
    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultBody("call_other", "page_click", Map.of("ok", true))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("tool_call_mismatch"));
  }

  @Test
  void rejectsReplayingAResultForAnAlreadyAnsweredCall() throws Exception {
    String jwt = token(List.of("order:approve"));
    String first = stream(jwt, chatBody("帮我审批这个订单", tools("approveOrder")));
    String threadId = extract(first, "threadId");
    String callId = extract(first, "id");

    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultBody(callId, "approveOrder", Map.of("ok", true))))
        .andExpect(status().isOk())
        .andReturn()
        .getAsyncResult(10_000);

    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultBody(callId, "approveOrder", Map.of("ok", true))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("no_pending_tool_call"));
  }

  @Test
  void cannotContinueAThreadUnderADifferentApplication() throws Exception {
    String jwt = token(List.of("order:approve"));
    String threadId = extract(stream(jwt, chatBody("帮我审批这个订单", tools("approveOrder"))), "threadId");

    Map<String, Object> body = new LinkedHashMap<>();
    // `demo` has no tools allowlist, so accepting this appId would widen the policy.
    body.put("appId", "demo");
    body.put("toolCallId", "call_x");
    body.put("name", "approveOrder");
    body.put("result", Map.of("ok", true));
    body.put("pageContext", page());
    body.put("businessContext", Map.of());

    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("app_mismatch"));
  }

  @Test
  void cannotRebindAThreadToAnotherApplicationViaChat() throws Exception {
    String jwt = token(List.of("order:approve"));
    String threadId = extract(stream(jwt, chatBody("订单状态是什么", tools())), "threadId");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "demo");
    body.put("threadId", threadId);
    body.put("message", "继续");
    body.put("pageContext", page());
    body.put("businessContext", Map.of());

    mockMvc
        .perform(
            post("/v1/chat")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("app_mismatch"));
  }

  @Test
  void toolResultOnAnotherUsersThreadIsNotFound() throws Exception {
    String owner = token(List.of("order:approve"));
    String threadId = extract(stream(owner, chatBody("帮我审批这个订单", tools("approveOrder"))), "threadId");

    String intruder =
        jwtService.issueToken("intruder", "intruder", "t-tools", List.of(), List.of(), 600);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appId", "crm");
    body.put("toolCallId", "call_x");
    body.put("name", "approveOrder");
    body.put("result", Map.of("ok", true));
    body.put("pageContext", page());
    body.put("businessContext", Map.of());

    mockMvc
        .perform(
            post("/v1/chat/" + threadId + "/tool-result")
                .header("Authorization", "Bearer " + intruder)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("thread_not_found"));
  }

  private static String extract(String sse, String field) {
    String needle = "\"" + field + "\":\"";
    int idx = sse.indexOf(needle);
    if (idx < 0) {
      return "";
    }
    int start = idx + needle.length();
    return sse.substring(start, sse.indexOf('"', start));
  }
}
