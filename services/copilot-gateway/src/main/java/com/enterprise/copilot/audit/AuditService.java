package com.enterprise.copilot.audit;

import com.enterprise.copilot.auth.UserPrincipal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditRepository repository;

  public AuditService(AuditRepository repository) {
    this.repository = repository;
  }

  /**
   * Records the turn. Auditing must never break a user-facing request, so failures here are logged
   * rather than propagated.
   */
  @Transactional
  public void record(
      UserPrincipal user,
      String appId,
      String threadId,
      String traceId,
      String question,
      String contextHash,
      String answer,
      String model,
      AuditRecord.Status status,
      String errorCode,
      long latencyMs) {
    try {
      AuditRecord record = new AuditRecord();
      record.setTraceId(traceId);
      record.setUserSub(user.sub());
      record.setTenantId(user.tenantId());
      record.setAppId(appId);
      record.setThreadId(threadId);
      record.setQuestion(question);
      record.setContextHash(contextHash);
      record.setAnswer(answer);
      record.setModel(model);
      record.setStatus(status);
      record.setErrorCode(errorCode);
      record.setLatencyMs(latencyMs);
      repository.save(record);
    } catch (Exception ex) {
      log.error("Failed to persist audit record traceId={}", traceId, ex);
    }
  }

  @Transactional(readOnly = true)
  public List<AuditRecord> recentForTenant(String tenantId, int limit) {
    int size = Math.min(Math.max(limit, 1), 100);
    return repository.findByTenantIdOrderByIdDesc(tenantId, PageRequest.of(0, size));
  }
}
