package com.enterprise.copilot.auth;

import com.enterprise.copilot.config.CopilotProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final CopilotProperties properties;
  private final SecretKey key;

  public JwtService(CopilotProperties properties) {
    this.properties = properties;
    byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    if (secret.length < CopilotProperties.MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "copilot.jwt-secret must be at least "
              + CopilotProperties.MIN_SECRET_BYTES
              + " bytes (got "
              + secret.length
              + "). Set COPILOT_JWT_SECRET to a strong random value.");
    }
    this.key = Keys.hmacShaKeyFor(secret);
  }

  @PostConstruct
  void logConfiguration() {
    // Never log the secret itself; only confirm it was accepted.
    org.slf4j.LoggerFactory.getLogger(JwtService.class)
        .info(
            "JWT verification ready (issuer={}, requireIssuer={}, clockSkew={}s)",
            properties.getJwtIssuer(),
            properties.isJwtRequireIssuer(),
            properties.getJwtClockSkew().toSeconds());
  }

  public UserPrincipal parse(String token) {
    JwtParserBuilder builder =
        Jwts.parser().verifyWith(key).clockSkewSeconds(properties.getJwtClockSkew().toSeconds());
    if (properties.isJwtRequireIssuer()) {
      builder.requireIssuer(properties.getJwtIssuer());
    }
    Claims claims = builder.build().parseSignedClaims(token).getPayload();

    String sub = claims.getSubject();
    if (sub == null || sub.isBlank()) {
      throw new IllegalArgumentException("token has no subject");
    }
    String name =
        firstNonBlank(
            claims.get("name", String.class),
            claims.get("preferred_username", String.class),
            sub);
    String tenant =
        firstNonBlank(
            claims.get("tenantId", String.class), claims.get("tenant_id", String.class), "default");
    return new UserPrincipal(
        sub, name, tenant, asStringList(claims.get("roles")), asStringList(claims.get("permissions")));
  }

  public String issueToken(
      String sub,
      String name,
      String tenantId,
      List<String> roles,
      List<String> permissions,
      long ttlSeconds) {
    Instant now = Instant.now();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("name", name == null ? sub : name);
    claims.put("tenantId", tenantId == null ? "default" : tenantId);
    claims.put("roles", roles == null ? List.of() : roles);
    claims.put("permissions", permissions == null ? List.of() : permissions);
    return Jwts.builder()
        .issuer(properties.getJwtIssuer())
        .subject(sub)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .claims(claims)
        .signWith(key)
        .compact();
  }

  private static List<String> asStringList(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
      return List.copyOf(out);
    }
    return List.of(String.valueOf(value));
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return "";
  }
}
