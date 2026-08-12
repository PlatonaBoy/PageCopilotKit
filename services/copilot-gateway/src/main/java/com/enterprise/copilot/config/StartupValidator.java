package com.enterprise.copilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails fast on unsafe production configuration instead of quietly starting an exploitable gateway.
 */
@Component
public class StartupValidator implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

  private final CopilotProperties properties;
  private final Environment environment;

  public StartupValidator(CopilotProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    boolean dev = environment.matchesProfiles("dev");
    if (dev) {
      log.warn(
          "Running with the 'dev' profile: token minting endpoint and permissive CORS are enabled.");
      return;
    }

    if (properties.isCorsAllowAllPatterns()) {
      throw new IllegalStateException(
          "copilot.cors-allow-all-patterns must be false outside the dev profile");
    }
    if (properties.getCorsAllowedOrigins().isEmpty()) {
      log.warn(
          "copilot.cors-allowed-origins is empty — browser clients will be blocked by CORS."
              + " Set COPILOT_CORS_ORIGINS to your application origins.");
    }
    if (environment.matchesProfiles("prod") && properties.getLlm().isMock()) {
      log.warn("Production profile is running with the mock LLM — set COPILOT_MOCK_LLM=false");
    }
    log.info(
        "Gateway configuration validated (apps={}, rateLimit={}/min per user, mockLlm={})",
        properties.getAllowedAppIds(),
        properties.getRateLimit().getPerUserPerMinute(),
        properties.getLlm().isMock());
  }
}
