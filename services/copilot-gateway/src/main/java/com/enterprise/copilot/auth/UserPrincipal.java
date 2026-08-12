package com.enterprise.copilot.auth;

import java.util.List;

public record UserPrincipal(
    String sub, String name, String tenantId, List<String> roles, List<String> permissions) {}
