package com.enterprise.copilot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

  public static final String ATTR_USER = "copilot.user";

  private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

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
      writeUnauthorized(response, "token_missing", "Missing Bearer token");
      return false;
    }
    String token = header.substring(7).trim();
    try {
      UserPrincipal user = jwtService.parse(token);
      request.setAttribute(ATTR_USER, user);
      return true;
    } catch (ExpiredJwtException ex) {
      writeUnauthorized(response, "token_expired", "Token expired");
    } catch (SignatureException ex) {
      writeUnauthorized(response, "token_signature_invalid", "Token signature invalid");
    } catch (MalformedJwtException | IllegalArgumentException ex) {
      writeUnauthorized(response, "token_malformed", "Token malformed");
    } catch (Exception ex) {
      log.debug("Token rejected", ex);
      writeUnauthorized(response, "token_invalid", "Token invalid");
    }
    return false;
  }

  private void writeUnauthorized(HttpServletResponse response, String reason, String message)
      throws Exception {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getWriter(),
        Map.of("code", "unauthorized", "reason", reason, "message", message));
  }
}
