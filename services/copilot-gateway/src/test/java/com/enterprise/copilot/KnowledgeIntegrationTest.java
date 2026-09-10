package com.enterprise.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.copilot.auth.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Knowledge base at the HTTP layer: ingest, retrieval, tenant isolation, permission gates, and the
 * chat path that emits a {@code citations} SSE event and restores it with the thread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @Autowired private ObjectMapper objectMapper;

  private String token(String sub, String tenant, List<String> permissions) {
    return jwtService.issueToken(sub, sub, tenant, List.of("manager"), permissions, 600);
  }

  private String writer(String tenant) {
    return token("kb-writer", tenant, List.of("knowledge:read", "knowledge:write"));
  }

  private String reader(String tenant) {
    return token("kb-reader", tenant, List.of("knowledge:read"));
  }

  private String chatter(String tenant) {
    return token("kb-chatter", tenant, List.of());
  }

  private String document(String docId, String title, String content) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("docId", docId);
    body.put("appId", "crm");
    body.put("title", title);
    body.put("source", "https://intranet.example.com/kb/" + docId);
    body.put("tags", List.of("政策"));
    body.put("content", content);
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void ingestRequiresWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + reader("t-kb-perm"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(document("refund-policy", "退货政策", "签收后 30 日内可退货")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));
  }

  @Test
  void listingRequiresReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + chatter("t-kb-perm")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));
  }

  @Test
  void upsertIsIdempotentAndSearchRanksTheMatchingDocument() throws Exception {
    String jwt = writer("t-kb-search");

    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document("refund-policy", "退货与退款政策", "客户可在签收后 30 个自然日内申请退货。财务 5 个工作日内退款。")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.docId").value("refund-policy"))
        .andExpect(jsonPath("$.chunkCount").value(1));

    // A second ingest with the same id must replace, not accumulate.
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document("refund-policy", "退货与退款政策", "客户可在签收后 30 个自然日内申请退货。财务 5 个工作日内退款。")))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/v1/knowledge/documents").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.tenantId").value("t-kb-search"));

    mockMvc
        .perform(
            post("/v1/knowledge/search")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("query", "退货时限是多久", "appId", "crm"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.hits[0].docId").value("refund-policy"))
        .andExpect(jsonPath("$.hits[0].index").value(1))
        .andExpect(jsonPath("$.hits[0].snippet").value(org.hamcrest.Matchers.containsString("30")));
  }

  @Test
  void documentsAreIsolatedAcrossTenants() throws Exception {
    String owner = writer("t-kb-owner");
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document("secret-policy", "机密政策", "内部折扣码是 SECRET-42。")))
        .andExpect(status().isOk());

    String stranger = writer("t-kb-stranger");
    mockMvc
        .perform(
            get("/v1/knowledge/documents/secret-policy")
                .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("knowledge_document_not_found"));

    mockMvc
        .perform(
            post("/v1/knowledge/search")
                .header("Authorization", "Bearer " + stranger)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", "折扣码 SECRET-42"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void chatEmitsCitationsAndRestoresThemWithTheThread() throws Exception {
    String admin = writer("t-kb-chat");
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    document(
                        "refund-policy",
                        "退货与退款政策",
                        "客户可在签收后 30 个自然日内申请退货。超过 30 天不再受理无理由退货。")))
        .andExpect(status().isOk());

    // Chat itself needs no knowledge permission: answering from the tenant's own documents is the
    // product. The writer token is reused only so the same tenant owns both ingest and the turn.
    String jwt = token("kb-chatter", "t-kb-chat", List.of());
    Map<String, Object> chat =
        Map.of(
            "appId",
            "crm",
            "message",
            "退货时限是多久",
            "pageContext",
            Map.of("url", "http://x", "title", "订单详情", "summary", "订单详情页", "actionableElements", List.of()),
            "businessContext",
            Map.of("orderId", "ORD-1"));

    MvcResult result =
        mockMvc
            .perform(
                post("/v1/chat")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(chat)))
            .andExpect(status().isOk())
            .andReturn();
    result.getAsyncResult(10_000);
    String stream = result.getResponse().getContentAsString();

    assertThat(stream).contains("event:citations");
    assertThat(stream).contains("refund-policy");
    assertThat(stream).contains("30");

    String threadId = extractThreadId(stream);
    assertThat(threadId).startsWith("thr_");

    mockMvc
        .perform(get("/v1/threads/" + threadId + "/messages").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(2))
        .andExpect(jsonPath("$.messages[1].role").value("assistant"))
        .andExpect(jsonPath("$.messages[1].citations[0].docId").value("refund-policy"))
        .andExpect(jsonPath("$.messages[1].citations[0].index").value(1));
  }

  @Test
  void deletingADocumentStopsItFromBeingCited() throws Exception {
    String jwt = writer("t-kb-del");
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document("invoice-guide", "发票开具说明", "订单发货后 3 个工作日内开具电子发票。")))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            delete("/v1/knowledge/documents/invoice-guide")
                .header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));

    mockMvc
        .perform(
            post("/v1/knowledge/search")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", "电子发票什么时候开"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void unknownAppIdIsRejectedAtIngest() throws Exception {
    mockMvc
        .perform(
            post("/v1/knowledge/documents")
                .header("Authorization", "Bearer " + writer("t-kb-app"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "docId", "x",
                            "appId", "nope",
                            "title", "t",
                            "content", "c"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));
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
