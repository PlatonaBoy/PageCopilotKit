package com.enterprise.copilot.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

  Optional<ChatThread> findByThreadId(String threadId);
}
