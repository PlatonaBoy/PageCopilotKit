package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.api.dto.ChatDtos.ThreadMessage;
import com.enterprise.copilot.api.dto.ChatDtos.ThreadMessagesResponse;
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

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    return chatService.streamChat(user, body);
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
