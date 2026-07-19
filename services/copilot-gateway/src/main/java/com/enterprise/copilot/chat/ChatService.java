package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.audit.AuditRecord;
import com.enterprise.copilot.audit.AuditRepository;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.chat.PromptBuilder.BuiltPrompt;
import com.enterprise.copilot.config.CopilotProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final CopilotProperties properties;
  private final PromptBuilder promptBuilder;
  private final AuditRepository auditRepository;
  private final ChatClient chatClient;
  private final boolean hasRealModel;

  public ChatService(
      CopilotProperties properties,
      PromptBuilder promptBuilder,
      AuditRepository auditRepository,
      ObjectProvider<ChatModel> chatModelProvider) {
    this.properties = properties;
    this.promptBuilder = promptBuilder;
    this.auditRepository = auditRepository;
    ChatModel model = chatModelProvider.getIfAvailable();
    this.hasRealModel = model != null;
    this.chatClient = model == null ? null : ChatClient.create(model);
  }

  public SseEmitter streamChat(UserPrincipal user, ChatRequest request) {
    BuiltPrompt prompt = promptBuilder.build(user, request);
    String threadId =
        request.threadId() == null || request.threadId().isBlank()
            ? "thr_" + UUID.randomUUID().toString().replace("-", "")
            : request.threadId();
    String traceId = "trc_" + UUID.randomUUID().toString().replace("-", "");

    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
    long started = System.currentTimeMillis();

    Thread.startVirtualThread(
        () -> {
          AtomicReference<StringBuilder> full = new AtomicReference<>(new StringBuilder());
          try {
            emit(emitter, "thread", Map.of("threadId", threadId));

            boolean useMock = properties.isMockLlm() || !hasRealModel;
            if (useMock) {
              streamMock(emitter, prompt, request, full);
            } else {
              streamModel(emitter, prompt, full);
            }

            String answer = full.get().toString();
            emit(emitter, "text.done", Map.of("content", answer));
            emit(emitter, "done", Map.of("traceId", traceId));
            persistAudit(user, request, threadId, traceId, prompt.contextHash(), answer, started);
            emitter.complete();
          } catch (Exception ex) {
            log.error("Chat stream failed traceId={}", traceId, ex);
            try {
              emit(
                  emitter,
                  "error",
                  Map.of(
                      "code",
                      "model_error",
                      "message",
                      ex.getMessage() == null ? "model error" : ex.getMessage()));
              emit(emitter, "done", Map.of("traceId", traceId));
            } catch (Exception ignored) {
              // ignore secondary failures
            }
            emitter.completeWithError(ex);
          }
        });

    return emitter;
  }

  private void streamModel(SseEmitter emitter, BuiltPrompt prompt, AtomicReference<StringBuilder> full)
      throws IOException {
    Flux<String> flux =
        chatClient.prompt().system(prompt.system()).user(prompt.user()).stream().content();

    flux.toStream()
        .forEach(
            chunk -> {
              if (chunk == null || chunk.isEmpty()) {
                return;
              }
              full.get().append(chunk);
              try {
                emit(emitter, "text.delta", Map.of("delta", chunk));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  private void streamMock(
      SseEmitter emitter,
      BuiltPrompt prompt,
      ChatRequest request,
      AtomicReference<StringBuilder> full)
      throws IOException, InterruptedException {
    String answer = buildMockAnswer(request);
    // stream in small chunks for demo UX
    int i = 0;
    while (i < answer.length()) {
      int end = Math.min(answer.length(), i + 12);
      String chunk = answer.substring(i, end);
      full.get().append(chunk);
      emit(emitter, "text.delta", Map.of("delta", chunk));
      Thread.sleep(18);
      i = end;
    }
  }

  private String buildMockAnswer(ChatRequest request) {
    String status = null;
    String orderId = null;
    if (request.businessContext() != null) {
      Object s = request.businessContext().get("status");
      Object o = request.businessContext().get("orderId");
      status = s == null ? null : String.valueOf(s);
      orderId = o == null ? null : String.valueOf(o);
    }
    String title =
        request.pageContext() == null || request.pageContext().title() == null
            ? "当前页面"
            : request.pageContext().title();
    String buttons = "(无)";
    if (request.pageContext() != null
        && request.pageContext().actionableElements() != null
        && !request.pageContext().actionableElements().isEmpty()) {
      buttons =
          request.pageContext().actionableElements().stream()
              .map(el -> el.name() == null ? el.role() : el.name())
              .reduce((a, b) -> a + "、" + b)
              .orElse("(无)");
    }

    String q = request.message() == null ? "" : request.message();
    if (q.contains("按钮") || q.toLowerCase().contains("button")) {
      return "当前页面「" + title + "」上我识别到的可操作按钮/控件包括：" + buttons + "。";
    }
    if (status != null && (q.contains("状态") || q.toLowerCase().contains("status"))) {
      return "根据业务上下文，订单 "
          + (orderId == null ? "" : orderId + " ")
          + "当前状态为「"
          + status
          + "」。页面标题为「"
          + title
          + "」。";
    }
    return "我已结合页面「"
        + title
        + "」与业务上下文回答（Mock LLM）。"
        + (status == null ? "" : " 订单状态：" + status + "。")
        + " 可操作控件："
        + buttons
        + "。你问的是："
        + q;
  }

  private void persistAudit(
      UserPrincipal user,
      ChatRequest request,
      String threadId,
      String traceId,
      String contextHash,
      String answer,
      long started) {
    AuditRecord record = new AuditRecord();
    record.setTraceId(traceId);
    record.setUserSub(user.sub());
    record.setTenantId(user.tenantId());
    record.setAppId(request.appId());
    record.setThreadId(threadId);
    record.setQuestion(request.message());
    record.setContextHash(contextHash);
    record.setAnswer(answer);
    record.setModel(properties.isMockLlm() || !hasRealModel ? "mock" : "openai-compatible");
    record.setLatencyMs(System.currentTimeMillis() - started);
    auditRepository.save(record);
  }

  private static void emit(SseEmitter emitter, String event, Object data) throws IOException {
    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
  }

  public void rejectToolResult() {
    throw new ApiException(
        HttpStatus.NOT_IMPLEMENTED, "not_implemented", "Tool result endpoint is phase 2");
  }
}
