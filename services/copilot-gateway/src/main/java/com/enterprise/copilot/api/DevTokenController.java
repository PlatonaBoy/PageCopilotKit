package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.TokenRequest;
import com.enterprise.copilot.api.dto.ChatDtos.TokenResponse;
import com.enterprise.copilot.auth.JwtService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints tokens for local development and demos only.
 *
 * <p>Registered exclusively under the {@code dev} profile — in any other profile the bean does not
 * exist, so the route returns 404 and cannot be re-enabled by configuration alone.
 */
@RestController
@RequestMapping("/v1/demo")
@Profile("dev")
public class DevTokenController {

  private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);
  private static final long TTL_SECONDS = 3600;

  private final JwtService jwtService;

  public DevTokenController(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @PostConstruct
  void warn() {
    log.warn(
        "DEV PROFILE: /v1/demo/token is enabled and unauthenticated. Never run this profile in production.");
  }

  @PostMapping("/token")
  public TokenResponse token(@RequestBody(required = false) TokenRequest body) {
    TokenRequest req = body == null ? new TokenRequest(null, null, null, null, null) : body;
    String token =
        jwtService.issueToken(
            blankTo(req.sub(), "zhangsan"),
            blankTo(req.name(), "张三"),
            blankTo(req.tenantId(), "demo"),
            req.roles() == null ? List.of("manager") : req.roles(),
            req.permissions() == null
                ? List.of("order:view", "order:approve", "audit:read")
                : req.permissions(),
            TTL_SECONDS);
    return new TokenResponse(token, TTL_SECONDS);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
