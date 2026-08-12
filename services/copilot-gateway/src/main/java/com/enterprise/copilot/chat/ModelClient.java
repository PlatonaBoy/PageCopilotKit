package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.chat.PromptBuilder.BuiltPrompt;
import com.enterprise.copilot.config.CopilotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Wraps the LLM call with the guardrails an enterprise gateway needs: bounded wait, bounded
 * retries, and a circuit breaker so a failing provider degrades fast instead of pinning threads.
 *
 * <p>Retries only apply before the first token is emitted — once the client has seen partial output
 * we cannot silently restart the answer.
 *
 * <p>Tool calling runs with Spring AI's internal execution disabled: the tools live in the browser,
 * so the gateway only needs the model's intent, which it forwards to the client. Because streaming
 * and tool-call detection do not mix cleanly, a turn that offers tools uses a single blocking call
 * and then replays the text as deltas; turns without tools keep true streaming.
 */
@Component
public class ModelClient {

  private static final Logger log = LoggerFactory.getLogger(ModelClient.class);
  private static final int SIMULATED_CHUNK = 24;

  private final CopilotProperties properties;
  private final ObjectMapper objectMapper;
  private final ChatClient chatClient;
  private final boolean modelAvailable;

  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicReference<Instant> breakerOpenUntil = new AtomicReference<>(Instant.EPOCH);

  public ModelClient(
      CopilotProperties properties,
      ObjectMapper objectMapper,
      ObjectProvider<ChatModel> chatModelProvider) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    ChatModel model = chatModelProvider.getIfAvailable();
    this.modelAvailable = model != null;
    this.chatClient = model == null ? null : ChatClient.create(model);
  }

  /** A model turn either produced text or asked for a tool. */
  public record Completion(String text, ToolInvocation toolCall) {
    public boolean hasToolCall() {
      return toolCall != null;
    }
  }

  public record ToolInvocation(String id, String name, Map<String, Object> arguments) {}

  public boolean isMockMode() {
    return properties.getLlm().isMock() || !modelAvailable;
  }

  public String modelLabel() {
    return isMockMode() ? "mock" : "openai-compatible";
  }

  public boolean isBreakerOpen() {
    return Instant.now().isBefore(breakerOpenUntil.get());
  }

  /**
   * Runs one model turn. Text is delivered through {@code onDelta}; a tool request is returned
   * instead. Throws {@link ModelUnavailableException} when the breaker is open or all attempts fail.
   */
  public Completion complete(BuiltPrompt prompt, List<ClientTool> tools, Consumer<String> onDelta) {
    if (isBreakerOpen()) {
      throw new ModelUnavailableException("breaker_open", "Model temporarily unavailable");
    }

    int attempts = Math.max(properties.getLlm().getMaxRetries(), 0) + 1;
    RuntimeException lastFailure = null;

    for (int attempt = 1; attempt <= attempts; attempt++) {
      boolean emitted = false;
      try {
        Completion completion =
            tools == null || tools.isEmpty()
                ? new Completion(streamText(prompt, onDelta), null)
                : callWithTools(prompt, tools, onDelta);
        emitted = true;
        onSuccess();
        return completion;
      } catch (RuntimeException ex) {
        lastFailure = ex;
        if (emitted) {
          onFailure();
          throw new ModelUnavailableException("model_stream_interrupted", describe(ex));
        }
        log.warn("Model attempt {}/{} failed: {}", attempt, attempts, describe(ex));
        if (attempt < attempts) {
          sleepBackoff(attempt);
        }
      }
    }

    onFailure();
    throw new ModelUnavailableException(
        "model_error", lastFailure == null ? "model error" : describe(lastFailure));
  }

  private String streamText(BuiltPrompt prompt, Consumer<String> onDelta) {
    Flux<String> flux =
        chatClient
            .prompt()
            .system(prompt.system())
            .user(prompt.user())
            .stream()
            .content()
            .timeout(properties.getLlm().getTimeout());

    StringBuilder full = new StringBuilder();
    var iterator = flux.toIterable().iterator();
    while (iterator.hasNext()) {
      String chunk = iterator.next();
      if (chunk == null || chunk.isEmpty()) {
        continue;
      }
      full.append(chunk);
      onDelta.accept(chunk);
    }
    return full.toString();
  }

  private Completion callWithTools(
      BuiltPrompt prompt, List<ClientTool> tools, Consumer<String> onDelta) {
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(tools.stream().map(ClientToolCallback::new).map(cb -> (ToolCallback) cb).toList())
            // The tools execute in the browser; Spring must hand us the intent, not run it.
            .internalToolExecutionEnabled(false)
            .build();

    ChatResponse response =
        chatClient
            .prompt()
            .system(prompt.system())
            .user(prompt.user())
            .options(options)
            .call()
            .chatResponse();

    if (response == null || response.getResult() == null) {
      throw new IllegalStateException("empty model response");
    }
    AssistantMessage message = response.getResult().getOutput();

    if (message.hasToolCalls()) {
      AssistantMessage.ToolCall call = message.getToolCalls().get(0);
      return new Completion(nullToEmpty(message.getText()), toInvocation(call));
    }

    String text = nullToEmpty(message.getText());
    // Replay as deltas so the client's streaming UI behaves identically on both paths.
    for (int i = 0; i < text.length(); i += SIMULATED_CHUNK) {
      onDelta.accept(text.substring(i, Math.min(text.length(), i + SIMULATED_CHUNK)));
    }
    return new Completion(text, null);
  }

  private ToolInvocation toInvocation(AssistantMessage.ToolCall call) {
    Map<String, Object> args = Map.of();
    String raw = call.arguments();
    if (raw != null && !raw.isBlank()) {
      try {
        args = objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      } catch (Exception ex) {
        log.warn("Could not parse tool arguments for '{}': {}", call.name(), describe(ex));
      }
    }
    return new ToolInvocation(
        call.id() == null || call.id().isBlank() ? "call_" + System.nanoTime() : call.id(),
        call.name(),
        args);
  }

  private void sleepBackoff(int attempt) {
    long millis = properties.getLlm().getRetryBackoff().toMillis() * (long) Math.pow(2, attempt - 1);
    try {
      Thread.sleep(Math.min(millis, Duration.ofSeconds(5).toMillis()));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("interrupted", "Request interrupted");
    }
  }

  private void onSuccess() {
    consecutiveFailures.set(0);
  }

  private void onFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= properties.getLlm().getBreakerFailureThreshold()) {
      breakerOpenUntil.set(Instant.now().plus(properties.getLlm().getBreakerOpenDuration()));
      consecutiveFailures.set(0);
      log.error(
          "Model circuit breaker opened for {}s after {} consecutive failures",
          properties.getLlm().getBreakerOpenDuration().toSeconds(),
          failures);
    }
  }

  private static String describe(Throwable ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Carries a browser tool's schema to the model. {@code call} is never invoked because internal
   * tool execution is disabled; it fails loudly if that assumption is ever broken.
   */
  private final class ClientToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    private ClientToolCallback(ClientTool tool) {
      this.definition =
          DefaultToolDefinition.builder()
              .name(tool.name())
              .description(
                  tool.description() == null || tool.description().isBlank()
                      ? tool.name()
                      : tool.description())
              .inputSchema(schemaJson(tool))
              .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String toolInput) {
      throw new IllegalStateException(
          "Browser tools must not execute on the gateway: " + definition.name());
    }

    private String schemaJson(ClientTool tool) {
      Map<String, Object> schema =
          tool.parameters() == null || tool.parameters().isEmpty()
              ? Map.of("type", "object", "properties", Map.of())
              : tool.parameters();
      try {
        return objectMapper.writeValueAsString(schema);
      } catch (Exception ex) {
        return "{\"type\":\"object\",\"properties\":{}}";
      }
    }
  }

  /** Signals that no answer could be produced; carries a stable code for the SSE error event. */
  public static class ModelUnavailableException extends RuntimeException {
    private final String code;

    public ModelUnavailableException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String getCode() {
      return code;
    }
  }

  /** Kept for symmetry with tests that build empty tool lists. */
  static List<ClientTool> noTools() {
    return new ArrayList<>();
  }
}
