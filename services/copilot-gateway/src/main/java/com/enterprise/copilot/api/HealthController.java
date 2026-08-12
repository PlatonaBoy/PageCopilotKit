package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.ChatDtos.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class HealthController {

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP");
  }
}
