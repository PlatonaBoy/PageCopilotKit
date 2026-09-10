package com.enterprise.copilot.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

  /** Every lookup is tenant-scoped: a document id alone is never enough to reach a row. */
  Optional<KnowledgeDocument> findByTenantIdAndDocId(String tenantId, String docId);

  List<KnowledgeDocument> findByTenantIdOrderByIdDesc(String tenantId, Pageable pageable);

  List<KnowledgeDocument> findByTenantIdAndAppIdInOrderByIdDesc(
      String tenantId, List<String> appIds, Pageable pageable);

  long countByTenantId(String tenantId);
}
