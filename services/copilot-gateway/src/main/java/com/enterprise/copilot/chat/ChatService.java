package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.api.dto.ChatDtos.ToolResultRequest;
import com.enterprise.copilot.audit.AuditRecord;
import com.enterprise.copilot.audit.AuditService;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.chat.PromptBuilder.BuiltPrompt;
import com.enterprise.copilot.config.CopilotProperties;
import com.enterprise.copilot.ratelimit.RateLimiter;
import com.enterprise.copilot.tools.ToolPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
  private static final String DEGRADED_MESSAGE = "AI 服务暂时不可用，请稍后重试。（已记录本次失败）";

  private final CopilotProperties properties;
  private final PromptBuilder promptBuilder;
  private final ThreadService threadService;
  private final ModelClient modelClient;
  private final MockAnswerService mockAnswerService;
  private final AuditService auditService;
  private final RateLimiter rateLimiter;
  private final ToolPolicy toolPolicy;
  private final ObjectMapper objectMapper;

  public ChatService(
      CopilotProperties properties,
      PromptBuilder promptBuilder,
      ThreadService threadService,
      ModelClient modelClient,
      MockAnswerService mockAnswerService,
      AuditService auditService,
      RateLimiter rateLimiter,
      ToolPolicy toolPolicy,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.promptBuilder = promptBuilder;
    this.threadService = threadService;
    this.modelClient = modelClient;
    this.mockAnswerService = mockAnswerService;
    this.auditService = auditService;
    this.rateLimiter = rateLimiter;
    this.toolPolicy = toolPolicy;
    this.objectMapper = objectMapper;
  }

  public SseEmitter streamChat(UserPrincipal user, ChatRequest request) {
    enforceRateLimit(user);

    // Validation and thread resolution happen synchronously so contract errors surface as
    // real HTTP status codes instead of an SSE error inside a 200 response.
    ChatThread thread = threadService.resolveOrCreate(user, request.threadId(), request.appId());
    List<ChatTurn> history = threadService.loadHistoryForPrompt(thread.getThreadId());
    BuiltPrompt prompt = promptBuilder.build(user, request, history);
    List<ClientTool> tools = toolPolicy.permitted(user, request.appId(), request.clientTools());

    threadService.appendUserTurn(thread, request.message());

    return run(
        user,
        thread,
        request.appId(),
        request.message(),
        prompt,
        tools,
        () -> mockAnswerService.plan(
            request.message(),
            request.pageContext(),
            request.businessContext(),
            tools,
            history));
  }

  /**
   * Continues a turn after the browser executed a tool.
   *
   * <p>The tool outcome and a fresh page observation are folded into history, then the model decides
   * whether to answer or act again. Each continuation is its own SSE response, which keeps the
   * protocol stateless and avoids holding a stream open across a user confirmation.
   */
  public SseEmitter streamToolResult(
      UserPrincipal user, String threadId, ToolResultRequest request) {
    enforceRateLimit(user);

    ChatThread thread = threadService.requireOwned(user, threadId);
    threadService.requireSameApp(thread, request.appId());
    // Policy is evaluated against the thread's own application, never the one the client claims.
    String appId = thread.getAppId();
    List<ClientTool> tools = toolPolicy.permitted(user, appId, request.clientTools());

    // A result is only meaningful for the call that is actually outstanding. Without this a caller
    // could fabricate a successful outcome for a write the user never approved.
    threadService.requirePendingToolCall(threadId, request.toolCallId(), request.name());

    if (threadService.countToolCallsInCurrentTurn(threadId)
        >= properties.getTools().getMaxStepsPerTurn()) {
      throw new ApiException(
          HttpStatus.CONFLICT, "tool_step_limit", "Too many tool steps for one turn");
    }

    String outcome =
        mockAnswerService.describeToolOutcome(request.name(), request.result(), request.error());
    threadService.appendToolResultTurn(thread, request.name(), outcome);

    List<ChatTurn> history = threadService.loadHistoryForPrompt(threadId);
    BuiltPrompt prompt =
        promptBuilder.buildContinuation(
            user, appId, request.pageContext(), request.businessContext(), history, outcome);

    return run(
        user,
        thread,
        appId,
        "[tool-result] " + request.name(),
        prompt,
        tools,
        // Offline continuation just reports the outcome; it never chains further actions on its own.
        () -> new MockAnswerService.MockPlan(null, outcome + "（离线模式）"));
  }

  private void enforceRateLimit(UserPrincipal user) {
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
  }

  private SseEmitter run(
      UserPrincipal user,
      ChatThread thread,
      String appId,
      String question,
      BuiltPrompt prompt,
      List<ClientTool> tools,
      java.util.function.Supplier<MockAnswerService.MockPlan> mockPlanner) {

    String traceId = "trc_" + UUID.randomUUID().toString().replace("-", "");
    SseEmitter emitter = new SseEmitter(properties.getLlm().getTimeout().plusSeconds(30).toMillis());
    AtomicBoolean clientGone = new AtomicBoolean(false);
    emitter.onTimeout(() -> clientGone.set(true));
    emitter.onError(ex -> clientGone.set(true));
    emitter.onCompletion(() -> clientGone.set(true));

    long started = System.currentTimeMillis();

    Thread.startVirtualThread(
        () -> {
          MDC.put("traceId", traceId);
          MDC.put("tenantId", user.tenantId());
          MDC.put("threadId", thread.getThreadId());
          StringBuilder full = new StringBuilder();
          try {
            emit(emitter, "thread", Map.of("threadId", thread.getThreadId()));

            ModelClient.ToolInvocation toolCall;
            if (modelClient.isMockMode()) {
              MockAnswerService.MockPlan plan = mockPlanner.get();
              toolCall = plan.toolCall();
              if (toolCall == null) {
                streamMock(emitter, plan.text(), full, clientGone);
              }
            } else {
              ModelClient.Completion completion =
                  modelClient.complete(
                      prompt,
                      tools,
                      chunk -> {
                        if (clientGone.get()) {
                          throw new ClientDisconnectedException();
                        }
                        full.append(chunk);
                        emitQuietly(emitter, "text.delta", Map.of("delta", chunk));
                      });
              toolCall = completion.toolCall();
            }

            if (toolCall != null) {
              dispatchToolCall(emitter, user, thread, appId, tools, toolCall, full);
            } else {
              String answer = full.toString();
              emit(emitter, "text.done", Map.of("content", answer));
              threadService.appendAssistantTurn(thread, answer);
            }

            emit(emitter, "done", Map.of("traceId", traceId));
            audit(
                user,
                appId,
                thread,
                traceId,
                question,
                prompt,
                full.toString(),
                AuditRecord.Status.SUCCESS,
                null,
                started);
            emitter.complete();
          } catch (ClientDisconnectedException ex) {
            log.info("Client disconnected mid-stream; aborting upstream");
            threadService.appendAssistantTurn(thread, full.toString());
            audit(
                user,
                appId,
                thread,
                traceId,
                question,
                prompt,
                full.toString(),
                AuditRecord.Status.FAILED,
                "client_disconnected",
                started);
            emitter.complete();
          } catch (Exception ex) {
            handleFailure(
                emitter, user, appId, thread, traceId, question, prompt, full, ex, started);
          } finally {
            MDC.clear();
          }
        });

    return emitter;
  }

  /**
   * Authorizes and forwards a tool request.
   *
   * <p>Authorization is deliberately re-checked here against verified JWT claims: the model naming a
   * tool is intent, not permission, and a tool the client never advertised is treated as an attack.
   */
  private void dispatchToolCall(
      SseEmitter emitter,
      UserPrincipal user,
      ChatThread thread,
      String appId,
      List<ClientTool> tools,
      ModelClient.ToolInvocation call,
      StringBuilder full)
      throws IOException {
    ToolPolicy.Decision decision = toolPolicy.authorize(user, appId, call.name(), tools);
    if (!decision.allowed()) {
      String refusal = "无法执行操作「" + call.name() + "」：" + decision.message();
      emit(emitter, "text.delta", Map.of("delta", refusal));
      emit(emitter, "text.done", Map.of("content", refusal));
      emit(emitter, "error", Map.of("code", decision.code(), "message", decision.message()));
      full.append(refusal);
      threadService.appendAssistantTurn(thread, refusal);
      return;
    }

    threadService.appendToolCallTurn(thread, call.id(), call.name(), serialize(call.arguments()));
    if (!full.isEmpty()) {
      // Any prose the model produced alongside the request is still a completed assistant turn.
      emit(emitter, "text.done", Map.of("content", full.toString()));
      threadService.appendAssistantTurn(thread, full.toString());
    }
    emit(
        emitter,
        "tool.call",
        Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments()));
  }

  private void handleFailure(
      SseEmitter emitter,
      UserPrincipal user,
      String appId,
      ChatThread thread,
      String traceId,
      String question,
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

    audit(
        user,
        appId,
        thread,
        traceId,
        question,
        prompt,
        answer,
        AuditRecord.Status.FAILED,
        code,
        started);
  }

  private void audit(
      UserPrincipal user,
      String appId,
      ChatThread thread,
      String traceId,
      String question,
      BuiltPrompt prompt,
      String answer,
      AuditRecord.Status status,
      String errorCode,
      long started) {
    auditService.record(
        user,
        appId,
        thread.getThreadId(),
        traceId,
        question,
        prompt.contextHash(),
        answer,
        modelClient.modelLabel(),
        status,
        errorCode,
        System.currentTimeMillis() - started);
  }

  private void streamMock(
      SseEmitter emitter, String answer, StringBuilder full, AtomicBoolean clientGone)
      throws IOException, InterruptedException {
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

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return String.valueOf(value);
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
