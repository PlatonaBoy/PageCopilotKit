package com.enterprise.copilot.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthSupport {

  private AuthSupport() {}

  public static UserPrincipal requireUser(HttpServletRequest request) {
    Object value = request.getAttribute(JwtAuthInterceptor.ATTR_USER);
    if (!(value instanceof UserPrincipal user)) {
      throw new IllegalStateException("Authenticated user missing");
    }
    return user;
  }
}
