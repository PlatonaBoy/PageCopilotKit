package com.enterprise.copilot.auth;

import com.enterprise.copilot.config.CopilotProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
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
    if (secret.length < 32) {
      byte[] padded = new byte[32];
      System.arraycopy(secret, 0, padded, 0, secret.length);
      secret = padded;
    }
    this.key = Keys.hmacShaKeyFor(secret);
  }

  public UserPrincipal parse(String token) {
    Claims claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    String name = firstNonBlank(claims.get("name", String.class), claims.get("preferred_username", String.class), claims.getSubject());
    String tenant =
        firstNonBlank(claims.get("tenantId", String.class), claims.get("tenant_id", String.class), "default");
    return new UserPrincipal(
        claims.getSubject(),
        name,
        tenant,
        asStringList(claims.get("roles")),
        asStringList(claims.get("permissions")));
  }

  public String issueDemoToken(
      String sub, String name, String tenantId, List<String> roles, List<String> permissions, long ttlSeconds) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(properties.getJwtIssuer())
        .subject(sub)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .claims(
            Map.of(
                "name", name == null ? sub : name,
                "tenantId", tenantId == null ? "default" : tenantId,
                "roles", roles == null ? List.of() : roles,
                "permissions", permissions == null ? List.of() : permissions))
        .signWith(key)
        .compact();
  }

  @SuppressWarnings("unchecked")
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
      return out;
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
