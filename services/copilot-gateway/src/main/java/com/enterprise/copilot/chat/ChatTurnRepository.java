package com.enterprise.copilot.chat;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, Long> {

  /** Newest first — caller reverses to chronological order after budget trimming. */
  List<ChatTurn> findByThreadIdOrderByIdDesc(String threadId, Pageable pageable);

  List<ChatTurn> findByThreadIdOrderByIdAsc(String threadId);

  @Transactional
  void deleteByThreadId(String threadId);
}
