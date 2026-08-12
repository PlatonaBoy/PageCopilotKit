package com.enterprise.copilot.api;

import com.enterprise.copilot.audit.AuditRecord;
import com.enterprise.copilot.audit.AuditService;
import com.enterprise.copilot.auth.AuthSupport;
import com.enterprise.copilot.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit read API. Requires a valid token, requires the {@code audit:read} permission, and is always
 * scoped to the caller's tenant.
 */
@RestController
@RequestMapping("/v1/audits")
public class AuditController {

  private static final String REQUIRED_PERMISSION = "audit:read";

  private final AuditService auditService;

  public AuditController(AuditService auditService) {
    this.auditService = auditService;
  }

  @GetMapping
  public Map<String, Object> recent(
      @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
    UserPrincipal user = AuthSupport.requireUser(request);
    if (!user.permissions().contains(REQUIRED_PERMISSION)) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "forbidden", "Missing permission: " + REQUIRED_PERMISSION);
    }

    List<AuditRecord> rows = auditService.recentForTenant(user.tenantId(), limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", user.tenantId());
    body.put("count", rows.size());
    body.put("items", rows.stream().map(AuditController::toView).toList());
    return body;
  }

  private static Map<String, Object> toView(AuditRecord record) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("traceId", record.getTraceId());
    view.put("appId", record.getAppId());
    view.put("threadId", record.getThreadId());
    view.put("userSub", record.getUserSub());
    view.put("question", record.getQuestion());
    view.put("answer", record.getAnswer() == null ? "" : record.getAnswer());
    view.put("status", record.getStatus());
    view.put("errorCode", record.getErrorCode() == null ? "" : record.getErrorCode());
    view.put("model", record.getModel() == null ? "" : record.getModel());
    view.put("latencyMs", record.getLatencyMs() == null ? 0 : record.getLatencyMs());
    view.put("createdAt", record.getCreatedAt());
    return view;
  }
}
