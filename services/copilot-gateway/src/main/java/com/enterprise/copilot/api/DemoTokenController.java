package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.DemoTokenRequest;
import com.enterprise.copilot.api.dto.ChatDtos.DemoTokenResponse;
import com.enterprise.copilot.auth.JwtService;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/demo")
public class DemoTokenController {

  private static final long TTL = 3600;

  private final CopilotProperties properties;
  private final JwtService jwtService;

  public DemoTokenController(CopilotProperties properties, JwtService jwtService) {
    this.properties = properties;
    this.jwtService = jwtService;
  }

  @PostMapping("/token")
  public DemoTokenResponse token(@RequestBody(required = false) DemoTokenRequest body) {
    if (!properties.isDemoTokenEnabled()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Demo token endpoint disabled");
    }
    DemoTokenRequest req =
        body == null
            ? new DemoTokenRequest("zhangsan", "张三", "demo", List.of("manager"), List.of("order:view"))
            : body;
    String token =
        jwtService.issueDemoToken(
            blankTo(req.sub(), "zhangsan"),
            blankTo(req.name(), "张三"),
            blankTo(req.tenantId(), "demo"),
            req.roles() == null ? List.of("manager") : req.roles(),
            req.permissions() == null ? List.of("order:view", "order:approve") : req.permissions(),
            TTL);
    return new DemoTokenResponse(token, TTL);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
