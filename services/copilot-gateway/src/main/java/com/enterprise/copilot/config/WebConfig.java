package com.enterprise.copilot.config;

import com.enterprise.copilot.auth.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final CopilotProperties properties;
  private final JwtAuthInterceptor jwtAuthInterceptor;

  public WebConfig(CopilotProperties properties, JwtAuthInterceptor jwtAuthInterceptor) {
    this.properties = properties;
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String[] origins = properties.getCorsAllowedOrigins().toArray(String[]::new);
    var registration =
        registry
            .addMapping("/v1/**")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);

    // Prefer patterns so LAN IPs / Cursor port-forward hosts work in demo.
    // Explicit list still honored when it does not contain the wildcard pattern.
    if (properties.isCorsAllowAllPatterns()
        || (origins.length == 1 && "*".equals(origins[0]))) {
      registration.allowedOriginPatterns("*");
    } else {
      registration.allowedOriginPatterns(origins);
    }
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(jwtAuthInterceptor)
        .addPathPatterns("/v1/**")
        .excludePathPatterns("/v1/health", "/v1/demo/token", "/v1/demo/audits");
  }
}
