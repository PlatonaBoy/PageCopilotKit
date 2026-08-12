package com.enterprise.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.copilot.auth.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @Autowired private ObjectMapper objectMapper;

  private String token(String sub, String tenant, List<String> permissions) {
    return jwtService.issueToken(sub, sub, tenant, List.of("manager"), permissions, 600);
  }

  private String chatBody(String message, String threadId) throws Exception {
    Map<String, Object> page =
        Map.of(
            "url", "http://localhost/orders/1",
            "title", "订单详情",
            "summary", "订单详情页",
            "actionableElements", List.of(Map.of("name", "提交审批", "role", "button")));
    Map<String, Object> body =
        threadId == null
            ? Map.of(
                "appId", "crm",
                "message", message,
                "pageContext", page,
                "businessContext", Map.of("orderId", "ORD-1", "status", "待审批", "amount", 50000))
            : Map.of(
                "appId", "crm",
                "threadId", threadId,
                "message", message,
                "pageContext", page,
                "businessContext", Map.of("orderId", "ORD-1", "status", "待审批", "amount", 50000));
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void healthIsPublic() throws Exception {
    mockMvc
        .perform(get("/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void chatRequiresBearerToken() throws Exception {
    mockMvc
        .perform(post("/v1/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody("hi", null)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  @Test
  void malformedTokenIsRejectedWithReason() throws Exception {
    mockMvc
        .perform(
            post("/v1/chat")
                .header("Authorization", "Bearer not-a-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody("hi", null)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.reason").value("token_malformed"));
  }

  @Test
  void tokenMintingEndpointIsAbsentOutsideDevProfile() throws Exception {
    mockMvc
        .perform(post("/v1/demo/token").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unknownAppIdIsRejected() throws Exception {
    String body =
        objectMapper.writeValueAsString(Map.of("appId", "nope", "message", "hi"));
    mockMvc
        .perform(
            post("/v1/chat")
                .header("Authorization", "Bearer " + token("u1", "t1", List.of()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));
  }

  @Test
  void oversizedBusinessContextIsRejected() throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of("appId", "crm", "message", "hi", "businessContext", Map.of("b", "x".repeat(6000))));
    mockMvc
        .perform(
            post("/v1/chat")
                .header("Authorization", "Bearer " + token("u1", "t1", List.of()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("context_too_large"));
  }

  @Test
  void chatStreamsSseAndPersistsMultiTurnHistory() throws Exception {
    String jwt = token("multi", "t-multi", List.of());

    MvcResult first =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(chatBody("这个订单金额是多少", null)))
            .andExpect(status().isOk())
            .andReturn();

    first.getAsyncResult(10_000);
    String stream = first.getResponse().getContentAsString();

    assertThat(stream).contains("event:thread").contains("event:text.delta").contains("event:done");
    assertThat(stream).contains("50000");

    String threadId = extractThreadId(stream);
    assertThat(threadId).startsWith("thr_");

    // Follow-up on the same thread must find the earlier turns persisted.
    MvcResult second =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(chatBody("客户是谁", threadId)))
            .andExpect(status().isOk())
            .andReturn();
    second.getAsyncResult(10_000);

    mockMvc
        .perform(get("/v1/threads/" + threadId + "/messages").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(4))
        .andExpect(jsonPath("$.messages[0].role").value("user"))
        .andExpect(jsonPath("$.messages[0].content").value("这个订单金额是多少"))
        .andExpect(jsonPath("$.messages[1].role").value("assistant"));
  }

  @Test
  void threadsAreIsolatedAcrossUsers() throws Exception {
    String owner = token("owner", "t-iso", List.of());
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(chatBody("状态", null)))
            .andExpect(status().isOk())
            .andReturn();
    result.getAsyncResult(10_000);
    String threadId = extractThreadId(result.getResponse().getContentAsString());

    String intruder = token("intruder", "t-iso", List.of());
    mockMvc
        .perform(
            get("/v1/threads/" + threadId + "/messages").header("Authorization", "Bearer " + intruder))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("thread_not_found"));
  }

  @Test
  void threadCanBeDeletedByOwner() throws Exception {
    String jwt = token("deleter", "t-del", List.of());
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(chatBody("状态", null)))
            .andReturn();
    result.getAsyncResult(10_000);
    String threadId = extractThreadId(result.getResponse().getContentAsString());

    mockMvc
        .perform(delete("/v1/threads/" + threadId).header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));

    mockMvc
        .perform(get("/v1/threads/" + threadId + "/messages").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditRequiresPermissionAndIsTenantScoped() throws Exception {
    mockMvc
        .perform(get("/v1/audits").header("Authorization", "Bearer " + token("u", "t-a", List.of())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));

    mockMvc
        .perform(
            get("/v1/audits")
                .header("Authorization", "Bearer " + token("u", "t-a", List.of("audit:read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value("t-a"));
  }

  @Test
  void corsPreflightIsAllowedForConfiguredOrigin() throws Exception {
    mockMvc
        .perform(
            options("/v1/chat")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
        .andExpect(status().isOk());
  }

  @Test
  void corsPreflightIsRejectedForUnknownOrigin() throws Exception {
    mockMvc
        .perform(
            options("/v1/chat")
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  private static String extractThreadId(String sse) {
    int idx = sse.indexOf("\"threadId\":\"");
    if (idx < 0) {
      return "";
    }
    int start = idx + "\"threadId\":\"".length();
    return sse.substring(start, sse.indexOf('"', start));
  }
}
