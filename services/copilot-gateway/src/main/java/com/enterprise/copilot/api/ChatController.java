package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.ThreadMessage;
import com.enterprise.copilot.api.dto.ChatDtos.ThreadMessagesResponse;
import com.enterprise.copilot.api.dto.ChatDtos.ToolResultRequest;
import com.enterprise.copilot.auth.AuthSupport;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.chat.ChatService;
import com.enterprise.copilot.chat.ChatTurn;
import com.enterprise.copilot.chat.ThreadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1")
public class ChatController {

  private final ChatService chatService;
  private final ThreadService threadService;

  public ChatController(ChatService chatService, ThreadService threadService) {
    this.chatService = chatService;
    this.threadService = threadService;
  }

  /** Charset is declared explicitly so intermediaries never guess at non-ASCII answers. */
  private static final String SSE_UTF8 = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8";

  @PostMapping(value = "/chat", produces = SSE_UTF8)
  public SseEmitter chat(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    return chatService.streamChat(user, body);
  }

  /**
   * Reports the outcome of a tool the browser executed. The response is the SSE continuation of the
   * same turn, so the protocol stays stateless across a user confirmation.
   */
  @PostMapping(value = "/chat/{threadId}/tool-result", produces = SSE_UTF8)
  public SseEmitter toolResult(
      @PathVariable String threadId,
      @Valid @RequestBody ToolResultRequest body,
      HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    return chatService.streamToolResult(user, threadId, body);
  }

  @GetMapping("/threads/{threadId}/messages")
  public ThreadMessagesResponse messages(
      @PathVariable String threadId, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    List<ChatTurn> turns = threadService.loadAll(user, threadId);
    return new ThreadMessagesResponse(
        threadId,
        turns.stream()
            .map(
                turn ->
                    new ThreadMessage(
                        turn.getRole().name().toLowerCase(), turn.getContent(), turn.getCreatedAt()))
            .toList());
  }

  @DeleteMapping("/threads/{threadId}")
  public Map<String, Object> deleteThread(
      @PathVariable String threadId, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    threadService.delete(user, threadId);
    return Map.of("deleted", true, "threadId", threadId);
  }
}
