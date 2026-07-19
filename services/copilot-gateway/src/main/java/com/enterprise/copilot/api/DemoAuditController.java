package com.enterprise.copilot.api;

import com.enterprise.copilot.audit.AuditRecord;
import com.enterprise.copilot.audit.AuditRepository;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/demo")
public class DemoAuditController {

  private final CopilotProperties properties;
  private final AuditRepository auditRepository;

  public DemoAuditController(CopilotProperties properties, AuditRepository auditRepository) {
    this.properties = properties;
    this.auditRepository = auditRepository;
  }

  @GetMapping("/audits")
  public Map<String, Object> recent(@RequestParam(defaultValue = "10") int limit) {
    if (!properties.isDemoTokenEnabled()) {
      throw new ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "not_found", "Demo endpoint disabled");
    }
    int size = Math.min(Math.max(limit, 1), 50);
    List<AuditRecord> rows =
        auditRepository
            .findAll(PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "id")))
            .getContent();
    Map<String, Object> body = new HashMap<>();
    body.put("count", rows.size());
    body.put(
        "items",
        rows.stream()
            .map(
                r ->
                    Map.of(
                        "traceId", r.getTraceId(),
                        "appId", r.getAppId(),
                        "userSub", r.getUserSub(),
                        "question", r.getQuestion(),
                        "answer", r.getAnswer() == null ? "" : r.getAnswer(),
                        "latencyMs", r.getLatencyMs() == null ? 0 : r.getLatencyMs(),
                        "model", r.getModel() == null ? "" : r.getModel()))
            .toList());
    return body;
  }
}
