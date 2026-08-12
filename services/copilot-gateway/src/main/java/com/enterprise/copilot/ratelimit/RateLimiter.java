package com.enterprise.copilot.ratelimit;

import com.enterprise.copilot.config.CopilotProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter keyed by user and tenant.
 *
 * <p>In-process only: adequate for a single gateway instance. A multi-instance deployment should
 * move these counters to Redis — the interface is intentionally narrow so that swap is contained.
 */
@Component
public class RateLimiter {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final CopilotProperties properties;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public RateLimiter(CopilotProperties properties) {
    this.properties = properties;
  }

  public record Decision(boolean allowed, String scope, int limit, long retryAfterSeconds) {
    public static Decision allow() {
      return new Decision(true, null, 0, 0);
    }
  }

  public Decision check(String tenantId, String userSub) {
    if (!properties.getRateLimit().isEnabled()) {
      return Decision.allow();
    }
    Decision user =
        consume(
            "u:" + tenantId + ":" + userSub,
            properties.getRateLimit().getPerUserPerMinute(),
            "user");
    if (!user.allowed()) {
      return user;
    }
    return consume("t:" + tenantId, properties.getRateLimit().getPerTenantPerMinute(), "tenant");
  }

  private Decision consume(String key, int limit, String scope) {
    if (limit <= 0) {
      return Decision.allow();
    }
    Instant now = Instant.now();
    Window window =
        windows.compute(
            key,
            (k, existing) -> {
              if (existing == null || now.isAfter(existing.resetAt)) {
                return new Window(now.plus(WINDOW));
              }
              return existing;
            });

    int used = window.count.incrementAndGet();
    if (used > limit) {
      long retryAfter = Math.max(Duration.between(now, window.resetAt).getSeconds(), 1);
      return new Decision(false, scope, limit, retryAfter);
    }
    return Decision.allow();
  }

  /** Drops expired windows so the map cannot grow unbounded across many tenants. */
  public void evictExpired() {
    Instant now = Instant.now();
    windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().resetAt));
  }

  private static final class Window {
    private final Instant resetAt;
    private final AtomicInteger count = new AtomicInteger();

    private Window(Instant resetAt) {
      this.resetAt = resetAt;
    }
  }
}
