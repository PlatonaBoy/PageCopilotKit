package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.audit.AuditRecord;
import com.enterprise.copilot.audit.AuditService;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.chat.PromptBuilder.BuiltPrompt;
import com.enterprise.copilot.config.CopilotProperties;
import com.enterprise.copilot.ratelimit.RateLimiter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final String DEGRADED_MESSAGE =
      "AI 服务暂时不可用，请稍后重试。（已记录本次失败）";

  private final CopilotProperties properties;
  private final PromptBuilder promptBuilder;
  private final ThreadService threadService;
  private final ModelClient modelClient;
  private final MockAnswerService mockAnswerService;
  private final AuditService auditService;
  private final RateLimiter rateLimiter;

  public ChatService(
      CopilotProperties properties,
      PromptBuilder promptBuilder,
      ThreadService threadService,
      ModelClient modelClient,
      MockAnswerService mockAnswerService,
      AuditService auditService,
      RateLimiter rateLimiter) {
    this.properties = properties;
    this.promptBuilder = promptBuilder;
    this.threadService = threadService;
    this.modelClient = modelClient;
    this.mockAnswerService = mockAnswerService;
    this.auditService = auditService;
    this.rateLimiter = rateLimiter;
  }

  public SseEmitter streamChat(UserPrincipal user, ChatRequest request) {
    RateLimiter.Decision decision = rateLimiter.check(user.tenantId(), user.sub());
    if (!decision.allowed()) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limited",
          "Rate limit exceeded for "
              + decision.scope()
              + " ("
              + decision.limit()
              + "/min). Retry in "
              + decision.retryAfterSeconds()
              + "s");
    }

    // Validation and thread resolution happen synchronously so contract errors surface as
    // real HTTP status codes instead of an SSE error inside a 200 response.
    ChatThread thread = threadService.resolveOrCreate(user, request.threadId(), request.appId());
    List<ChatTurn> history = threadService.loadHistoryForPrompt(thread.getThreadId());
    BuiltPrompt prompt = promptBuilder.build(user, request, history);

    String traceId = "trc_" + UUID.randomUUID().toString().replace("-", "");
    SseEmitter emitter = new SseEmitter(properties.getLlm().getTimeout().plusSeconds(30).toMillis());
    AtomicBoolean clientGone = new AtomicBoolean(false);
    emitter.onTimeout(() -> clientGone.set(true));
    emitter.onError(ex -> clientGone.set(true));
    emitter.onCompletion(() -> clientGone.set(true));

    long started = System.currentTimeMillis();
    threadService.appendUserTurn(thread, request.message());

    Thread.startVirtualThread(
        () -> {
          MDC.put("traceId", traceId);
          MDC.put("tenantId", user.tenantId());
          MDC.put("threadId", thread.getThreadId());
          StringBuilder full = new StringBuilder();
          try {
            emit(emitter, "thread", Map.of("threadId", thread.getThreadId()));

            if (modelClient.isMockMode()) {
              streamMock(emitter, request, history, full, clientGone);
            } else {
              modelClient.stream(
                  prompt,
                  chunk -> {
                    if (clientGone.get()) {
                      throw new ClientDisconnectedException();
                    }
                    full.append(chunk);
                    emitQuietly(emitter, "text.delta", Map.of("delta", chunk));
                  });
            }

            String answer = full.toString();
            emit(emitter, "text.done", Map.of("content", answer));
            emit(emitter, "done", Map.of("traceId", traceId));
            threadService.appendAssistantTurn(thread, answer);
            audit(
                user,
                request,
                thread,
                traceId,
                prompt,
                answer,
                AuditRecord.Status.SUCCESS,
                null,
                started);
            emitter.complete();
          } catch (ClientDisconnectedException ex) {
            log.info("Client disconnected mid-stream; aborting upstream");
            threadService.appendAssistantTurn(thread, full.toString());
            audit(
                user,
                request,
                thread,
                traceId,
                prompt,
                full.toString(),
                AuditRecord.Status.FAILED,
                "client_disconnected",
                started);
            emitter.complete();
          } catch (Exception ex) {
            handleFailure(emitter, user, request, thread, traceId, prompt, full, ex, started);
          } finally {
            MDC.clear();
          }
        });

    return emitter;
  }

  private void handleFailure(
      SseEmitter emitter,
      UserPrincipal user,
      ChatRequest request,
      ChatThread thread,
      String traceId,
      BuiltPrompt prompt,
      StringBuilder full,
      Exception ex,
      long started) {
    String code =
        ex instanceof ModelClient.ModelUnavailableException mue ? mue.getCode() : "internal_error";
    log.error("Chat stream failed code={}", code, ex);

    String answer = full.length() > 0 ? full.toString() : DEGRADED_MESSAGE;
    try {
      if (full.length() == 0) {
        // Nothing streamed yet — give the user a readable fallback instead of an empty bubble.
        emit(emitter, "text.delta", Map.of("delta", DEGRADED_MESSAGE));
      }
      emit(emitter, "error", Map.of("code", code, "message", safeMessage(ex)));
      emit(emitter, "done", Map.of("traceId", traceId));
      emitter.complete();
    } catch (Exception ignored) {
      emitter.completeWithError(ex);
    }

    audit(user, request, thread, traceId, prompt, answer, AuditRecord.Status.FAILED, code, started);
  }

  private void audit(
      UserPrincipal user,
      ChatRequest request,
      ChatThread thread,
      String traceId,
      BuiltPrompt prompt,
      String answer,
      AuditRecord.Status status,
      String errorCode,
      long started) {
    auditService.record(
        user,
        request.appId(),
        thread.getThreadId(),
        traceId,
        request.message(),
        prompt.contextHash(),
        answer,
        modelClient.modelLabel(),
        status,
        errorCode,
        System.currentTimeMillis() - started);
  }

  private void streamMock(
      SseEmitter emitter,
      ChatRequest request,
      List<ChatTurn> history,
      StringBuilder full,
      AtomicBoolean clientGone)
      throws IOException, InterruptedException {
    String answer = mockAnswerService.answer(request, history);
    int i = 0;
    while (i < answer.length()) {
      if (clientGone.get()) {
        throw new ClientDisconnectedException();
      }
      int end = Math.min(answer.length(), i + 12);
      String chunk = answer.substring(i, end);
      full.append(chunk);
      emit(emitter, "text.delta", Map.of("delta", chunk));
      Thread.sleep(15);
      i = end;
    }
  }

  private static String safeMessage(Exception ex) {
    if (ex instanceof ModelClient.ModelUnavailableException) {
      return ex.getMessage();
    }
    // Never surface internal stack details to the browser.
    return "Upstream model error";
  }

  private static void emit(SseEmitter emitter, String event, Object data) throws IOException {
    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
  }

  private static void emitQuietly(SseEmitter emitter, String event, Object data) {
    try {
      emit(emitter, event, data);
    } catch (IOException ex) {
      throw new ClientDisconnectedException();
    }
  }

  /** Raised when the browser hangs up so we can stop pulling from the model. */
  static class ClientDisconnectedException extends RuntimeException {
    ClientDisconnectedException() {
      super("client disconnected", null, false, false);
    }
  }
}
