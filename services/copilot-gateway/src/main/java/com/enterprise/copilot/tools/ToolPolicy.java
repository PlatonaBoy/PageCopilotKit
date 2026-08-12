package com.enterprise.copilot.tools;

import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which tools the model may be offered and which calls may proceed.
 *
 * <p>The browser declares what it can execute, but a client declaration is not authorization. Two
 * server-side gates apply:
 *
 * <ol>
 *   <li>An optional allowlist per application (`copilot.tools.allowed.<appId>`). When configured,
 *       only those names are advertised to the model.
 *   <li>An optional required permission per tool (`copilot.tools.required-permission.<tool>`),
 *       checked against the verified JWT claims — not against anything the page or client asserts.
 * </ol>
 *
 * A model asking for a tool that was never advertised is refused outright: that is the shape a
 * prompt-injection attempt takes.
 */
@Component
public class ToolPolicy {

  private static final Logger log = LoggerFactory.getLogger(ToolPolicy.class);

  private final CopilotProperties properties;

  public ToolPolicy(CopilotProperties properties) {
    this.properties = properties;
  }

  public record Decision(boolean allowed, String code, String message) {
    static Decision allow() {
      return new Decision(true, null, null);
    }
  }

  /** Tools that may be advertised to the model for this turn. */
  public List<ClientTool> permitted(UserPrincipal user, String appId, List<ClientTool> declared) {
    if (declared == null || declared.isEmpty() || !properties.getTools().isEnabled()) {
      return List.of();
    }
    Map<String, ClientTool> unique = new LinkedHashMap<>();
    for (ClientTool tool : declared) {
      if (tool == null || tool.name() == null || tool.name().isBlank()) {
        continue;
      }
      if (check(user, appId, tool.name()).allowed()) {
        unique.putIfAbsent(tool.name(), tool);
      }
    }
    return List.copyOf(unique.values());
  }

  /**
   * Authorizes a specific call. {@code advertised} is the set the model was actually offered, so an
   * invented or smuggled tool name cannot slip through.
   */
  public Decision authorize(
      UserPrincipal user, String appId, String toolName, List<ClientTool> advertised) {
    boolean offered = advertised.stream().anyMatch(t -> t.name().equals(toolName));
    if (!offered) {
      log.warn(
          "Rejected tool call '{}' for app={} user={}: not advertised for this turn",
          toolName,
          appId,
          user.sub());
      return new Decision(false, "tool_not_available", "Tool is not available: " + toolName);
    }
    return check(user, appId, toolName);
  }

  private Decision check(UserPrincipal user, String appId, String toolName) {
    if (!properties.getTools().isEnabled()) {
      return new Decision(false, "tool_disabled", "Tool calling is disabled");
    }

    List<String> allowlist = properties.getTools().getAllowed().get(appId);
    if (allowlist != null && !allowlist.isEmpty() && !allowlist.contains(toolName)) {
      return new Decision(
          false, "tool_not_allowed", "Tool not allowed for this application: " + toolName);
    }

    String required = properties.getTools().getRequiredPermission().get(toolName);
    if (required != null && !required.isBlank() && !user.permissions().contains(required)) {
      log.warn(
          "Denied tool '{}' for user={} tenant={}: missing permission '{}'",
          toolName,
          user.sub(),
          user.tenantId(),
          required);
      return new Decision(false, "tool_forbidden", "Missing permission for " + toolName);
    }

    return Decision.allow();
  }

  public static boolean isWrite(ClientTool tool) {
    return tool == null || !"read".equals(normalizeRisk(tool.risk()));
  }

  private static String normalizeRisk(String risk) {
    return risk == null ? "write" : risk.toLowerCase(Locale.ROOT);
  }
}
