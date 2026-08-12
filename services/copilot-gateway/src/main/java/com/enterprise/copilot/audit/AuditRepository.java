package com.enterprise.copilot.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditRecord, Long> {

  /** Tenant-scoped read — audit queries must never cross tenants. */
  List<AuditRecord> findByTenantIdOrderByIdDesc(String tenantId, Pageable pageable);
}
