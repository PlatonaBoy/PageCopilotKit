package com.enterprise.copilot.config;

import com.enterprise.copilot.auth.JwtAuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

  private final CopilotProperties properties;
  private final JwtAuthInterceptor jwtAuthInterceptor;

  public WebConfig(CopilotProperties properties, JwtAuthInterceptor jwtAuthInterceptor) {
    this.properties = properties;
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    CorsRegistration registration =
        registry
            .addMapping("/v1/**")
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Accept")
            .exposedHeaders("X-Trace-Id")
            .allowCredentials(true)
            .maxAge(3600);

    if (properties.isCorsAllowAllPatterns()) {
      log.warn("CORS is configured to allow ALL origins — never enable this in production");
      registration.allowedOriginPatterns("*");
      return;
    }

    String[] origins = properties.getCorsAllowedOrigins().toArray(String[]::new);
    if (origins.length == 0) {
      log.warn("copilot.cors-allowed-origins is empty — browser requests will be rejected");
      return;
    }
    registration.allowedOriginPatterns(origins);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(jwtAuthInterceptor)
        .addPathPatterns("/v1/**")
        // Health and demo-token minting are the only unauthenticated surfaces.
        // Demo endpoints are additionally gated behind the `dev` profile.
        .excludePathPatterns("/v1/health", "/v1/demo/token");
  }
}
