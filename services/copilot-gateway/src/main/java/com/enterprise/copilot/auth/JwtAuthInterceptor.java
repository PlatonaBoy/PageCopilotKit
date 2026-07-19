package com.enterprise.copilot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

  public static final String ATTR_USER = "copilot.user";

  private final JwtService jwtService;
  private final ObjectMapper objectMapper;

  public JwtAuthInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      writeUnauthorized(response, "Missing Bearer token");
      return false;
    }
    try {
      UserPrincipal user = jwtService.parse(header.substring(7).trim());
      request.setAttribute(ATTR_USER, user);
      return true;
    } catch (Exception ex) {
      writeUnauthorized(response, "Invalid token");
      return false;
    }
  }

  private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), Map.of("code", "unauthorized", "message", message));
  }
}
