package com.enterprise.copilot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts a request-scoped trace id into MDC and echoes it back so logs and clients can correlate. */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Trace-Id";
  private static final String MDC_KEY = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String traceId =
        incoming == null || incoming.isBlank()
            ? "req_" + UUID.randomUUID().toString().replace("-", "")
            : incoming.substring(0, Math.min(incoming.length(), 64));
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
