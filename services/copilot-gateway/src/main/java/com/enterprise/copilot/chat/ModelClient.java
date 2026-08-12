package com.enterprise.copilot.chat;

import com.enterprise.copilot.chat.PromptBuilder.BuiltPrompt;
import com.enterprise.copilot.config.CopilotProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Wraps the LLM call with the guardrails an enterprise gateway needs: bounded wait, bounded
 * retries, and a circuit breaker so a failing provider degrades fast instead of pinning threads.
 *
 * <p>Retries only apply before the first token is emitted — once the client has seen partial output
 * we cannot silently restart the answer.
 */
@Component
public class ModelClient {

  private static final Logger log = LoggerFactory.getLogger(ModelClient.class);

  private final CopilotProperties properties;
  private final ChatClient chatClient;
  private final boolean modelAvailable;

  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicReference<Instant> breakerOpenUntil = new AtomicReference<>(Instant.EPOCH);

  public ModelClient(CopilotProperties properties, ObjectProvider<ChatModel> chatModelProvider) {
    this.properties = properties;
    ChatModel model = chatModelProvider.getIfAvailable();
    this.modelAvailable = model != null;
    this.chatClient = model == null ? null : ChatClient.create(model);
  }

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
   * Streams the answer to {@code onDelta}. Throws {@link ModelUnavailableException} when the
   * breaker is open or all attempts fail, so the caller can emit a graceful SSE error.
   */
  public void stream(BuiltPrompt prompt, Consumer<String> onDelta) {
    if (isBreakerOpen()) {
      throw new ModelUnavailableException("breaker_open", "Model temporarily unavailable");
    }

    int attempts = Math.max(properties.getLlm().getMaxRetries(), 0) + 1;
    RuntimeException lastFailure = null;

    for (int attempt = 1; attempt <= attempts; attempt++) {
      boolean emitted = false;
      try {
        Flux<String> flux =
            chatClient
                .prompt()
                .system(prompt.system())
                .user(prompt.user())
                .stream()
                .content()
                .timeout(properties.getLlm().getTimeout());

        var iterator = flux.toIterable().iterator();
        while (iterator.hasNext()) {
          String chunk = iterator.next();
          if (chunk == null || chunk.isEmpty()) {
            continue;
          }
          emitted = true;
          onDelta.accept(chunk);
        }
        onSuccess();
        return;
      } catch (RuntimeException ex) {
        lastFailure = ex;
        if (emitted) {
          // Partial answer already delivered — retrying would duplicate content.
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
}
