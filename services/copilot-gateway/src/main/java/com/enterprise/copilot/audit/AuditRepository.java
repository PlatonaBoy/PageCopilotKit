package com.enterprise.copilot.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditRecord, Long> {}
