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
    registry
        .addMapping("/v1/**")
        .allowedOrigins(properties.getCorsAllowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true)
        .maxAge(3600);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(jwtAuthInterceptor)
        .addPathPatterns("/v1/**")
        .excludePathPatterns("/v1/health", "/v1/demo/token");
  }
}
