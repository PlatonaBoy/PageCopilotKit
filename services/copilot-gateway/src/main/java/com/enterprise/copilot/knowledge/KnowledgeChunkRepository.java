package com.enterprise.copilot.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

  List<KnowledgeChunk> findByTenantIdOrderByIdAsc(String tenantId);

  @Transactional
  void deleteByTenantIdAndDocId(String tenantId, String docId);

  long countByTenantId(String tenantId);
}
