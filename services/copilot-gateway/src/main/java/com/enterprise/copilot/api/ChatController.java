package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.ChatRequest;
import com.enterprise.copilot.auth.AuthSupport;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.chat.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
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

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    return chatService.streamChat(user, body);
  }

  @PostMapping("/chat/{threadId}/tool-result")
  public Map<String, String> toolResult(
      @PathVariable String threadId, @RequestBody Map<String, Object> body) {
    chatService.rejectToolResult();
    return Map.of();
  }
}
