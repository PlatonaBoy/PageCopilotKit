package com.enterprise.copilot.chat;

import com.enterprise.copilot.api.ApiException;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns conversation state. Threads are scoped to (tenantId, userSub): a caller can never read or
 * continue somebody else's thread — mismatches surface as 404 rather than 403 so thread ids are not
 * enumerable.
 */
@Service
public class ThreadService {

  private final ChatThreadRepository threads;
  private final ChatTurnRepository turns;
  private final CopilotProperties properties;

  public ThreadService(
      ChatThreadRepository threads, ChatTurnRepository turns, CopilotProperties properties) {
    this.threads = threads;
    this.turns = turns;
    this.properties = properties;
  }

  /**
   * Resolve an existing thread or create a new one. Never returns another user's thread.
   *
   * <p>A thread's {@code appId} is fixed at creation. Rebinding it later would let a caller carry a
   * conversation into an application with a laxer tool policy, so a mismatch is rejected.
   */
  @Transactional
  public ChatThread resolveOrCreate(UserPrincipal user, String requestedThreadId, String appId) {
    if (requestedThreadId != null && !requestedThreadId.isBlank()) {
      ChatThread existing = requireOwned(user, requestedThreadId);
      requireSameApp(existing, appId);
      existing.touch();
      return threads.save(existing);
    }
    String threadId = "thr_" + UUID.randomUUID().toString().replace("-", "");
    return threads.save(ChatThread.create(threadId, user.tenantId(), user.sub(), appId));
  }

  /** Rejects a request that claims a different application than the thread was created for. */
  public void requireSameApp(ChatThread thread, String appId) {
    if (appId != null && !appId.isBlank() && !thread.getAppId().equals(appId)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "app_mismatch",
          "Thread belongs to application " + thread.getAppId());
    }
  }

  @Transactional(readOnly = true)
  public ChatThread requireOwned(UserPrincipal user, String threadId) {
    Optional<ChatThread> found = threads.findByThreadId(threadId);
    if (found.isEmpty() || !found.get().ownedBy(user.tenantId(), user.sub())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "thread_not_found", "Thread not found");
    }
    return found.get();
  }

  /**
   * Load recent turns for prompt assembly, oldest first, trimmed to the configured turn and
   * character budget. Trimming drops the oldest turns first so the most relevant context survives.
   */
  @Transactional(readOnly = true)
  public List<ChatTurn> loadHistoryForPrompt(String threadId) {
    int maxMessages = Math.max(properties.getHistory().getMaxTurns(), 1) * 2;
    List<ChatTurn> newestFirst =
        turns.findByThreadIdOrderByIdDesc(threadId, PageRequest.of(0, maxMessages));

    List<ChatTurn> selected = new ArrayList<>();
    int budget = properties.getHistory().getMaxChars();
    int used = 0;
    for (ChatTurn turn : newestFirst) {
      int cost = turn.getContent().length();
      if (!selected.isEmpty() && used + cost > budget) {
        break;
      }
      used += cost;
      selected.add(turn);
    }
    Collections.reverse(selected);
    return selected;
  }

  /**
   * Turns for UI restore. Tool call/result turns are internal reasoning steps, so only the visible
   * conversation is returned — the client renders chat bubbles, not a trace.
   */
  @Transactional(readOnly = true)
  public List<ChatTurn> loadAll(UserPrincipal user, String threadId) {
    requireOwned(user, threadId);
    return turns.findByThreadIdOrderByIdAsc(threadId).stream()
        .filter(t -> t.getRole() == ChatTurn.Role.USER || t.getRole() == ChatTurn.Role.ASSISTANT)
        .toList();
  }

  @Transactional
  public void appendUserTurn(ChatThread thread, String content) {
    if (thread.getTitle() == null || thread.getTitle().isBlank()) {
      thread.setTitle(content.length() > 60 ? content.substring(0, 60) : content);
      threads.save(thread);
    }
    turns.save(ChatTurn.of(thread.getThreadId(), ChatTurn.Role.USER, content));
  }

  @Transactional
  public void appendAssistantTurn(ChatThread thread, String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    turns.save(ChatTurn.of(thread.getThreadId(), ChatTurn.Role.ASSISTANT, content));
    thread.touch();
    threads.save(thread);
    pruneIfNeeded(thread.getThreadId());
  }

  /**
   * Records an outstanding tool request.
   *
   * <p>The call id is stored alongside the name so the matching result can be verified later; the
   * arguments follow so the prompt still reads naturally.
   */
  @Transactional
  public void appendToolCallTurn(
      ChatThread thread, String callId, String toolName, String argumentsJson) {
    turns.save(
        ChatTurn.of(
            thread.getThreadId(),
            ChatTurn.Role.TOOL_CALL,
            "%s [%s] %s".formatted(toolName, callId, argumentsJson == null ? "{}" : argumentsJson)));
    thread.touch();
    threads.save(thread);
  }

  /**
   * Verifies that the reported result answers the tool call the server is actually waiting on.
   *
   * <p>Guards two things: that a call is outstanding at all, and that the id and name match it. A
   * client cannot otherwise inject an invented outcome — for instance claiming a write succeeded
   * when the user declined it.
   */
  @Transactional(readOnly = true)
  public void requirePendingToolCall(String threadId, String callId, String toolName) {
    ChatTurn pending = null;
    for (ChatTurn turn : turns.findByThreadIdOrderByIdAsc(threadId)) {
      switch (turn.getRole()) {
        case TOOL_CALL -> pending = turn;
        // A recorded result or a new user message closes the outstanding call.
        case TOOL_RESULT, USER -> pending = null;
        default -> {}
      }
    }
    if (pending == null) {
      throw new ApiException(
          HttpStatus.CONFLICT, "no_pending_tool_call", "No tool call is awaiting a result");
    }
    String expectedPrefix = "%s [%s]".formatted(toolName, callId);
    if (!pending.getContent().startsWith(expectedPrefix)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "tool_call_mismatch",
          "Result does not match the outstanding tool call");
    }
  }

  @Transactional
  public void appendToolResultTurn(ChatThread thread, String toolName, String outcome) {
    turns.save(
        ChatTurn.of(thread.getThreadId(), ChatTurn.Role.TOOL_RESULT, toolName + ": " + outcome));
    thread.touch();
    threads.save(thread);
    pruneIfNeeded(thread.getThreadId());
  }

  /**
   * Number of tool calls since the last user message — the server-side guard against a model that
   * keeps acting instead of answering.
   */
  @Transactional(readOnly = true)
  public int countToolCallsInCurrentTurn(String threadId) {
    List<ChatTurn> all = turns.findByThreadIdOrderByIdAsc(threadId);
    int count = 0;
    for (int i = all.size() - 1; i >= 0; i -= 1) {
      ChatTurn turn = all.get(i);
      if (turn.getRole() == ChatTurn.Role.USER) {
        break;
      }
      if (turn.getRole() == ChatTurn.Role.TOOL_CALL) {
        count += 1;
      }
    }
    return count;
  }

  @Transactional
  public void delete(UserPrincipal user, String threadId) {
    ChatThread thread = requireOwned(user, threadId);
    turns.deleteByThreadId(thread.getThreadId());
    threads.delete(thread);
  }

  /** Keep threads bounded so a long-lived conversation cannot grow without limit. */
  private void pruneIfNeeded(String threadId) {
    int retain = properties.getHistory().getRetainMessages();
    List<ChatTurn> all = turns.findByThreadIdOrderByIdAsc(threadId);
    if (all.size() <= retain) {
      return;
    }
    turns.deleteAll(all.subList(0, all.size() - retain));
  }
}
