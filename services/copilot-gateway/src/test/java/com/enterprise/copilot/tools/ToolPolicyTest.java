package com.enterprise.copilot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.copilot.api.dto.ChatDtos.ClientTool;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPolicyTest {

  private final CopilotProperties properties = new CopilotProperties();
  private final ToolPolicy policy = new ToolPolicy(properties);

  private static ClientTool tool(String name, String risk) {
    return new ClientTool(name, name, Map.of(), risk);
  }

  private static UserPrincipal user(List<String> permissions) {
    return new UserPrincipal("u1", "u1", "t1", List.of("manager"), permissions);
  }

  @Test
  void advertisesEverythingTheClientDeclaresByDefault() {
    var permitted =
        policy.permitted(user(List.of()), "crm", List.of(tool("a", "read"), tool("b", "write")));

    assertEquals(2, permitted.size());
  }

  @Test
  void deduplicatesRepeatedToolNames() {
    var permitted =
        policy.permitted(user(List.of()), "crm", List.of(tool("a", "read"), tool("a", "write")));

    assertEquals(1, permitted.size());
  }

  @Test
  void filtersToolsOutsideTheApplicationAllowlist() {
    properties.getTools().getAllowed().put("crm", List.of("approveOrder"));

    var permitted =
        policy.permitted(
            user(List.of()), "crm", List.of(tool("approveOrder", "write"), tool("dropDatabase", "write")));

    assertEquals(1, permitted.size());
    assertEquals("approveOrder", permitted.get(0).name());
  }

  @Test
  void filtersToolsWhoseRequiredPermissionIsMissing() {
    properties.getTools().getRequiredPermission().put("approveOrder", "order:approve");

    assertTrue(policy.permitted(user(List.of()), "crm", List.of(tool("approveOrder", "write"))).isEmpty());
    assertEquals(
        1,
        policy.permitted(user(List.of("order:approve")), "crm", List.of(tool("approveOrder", "write")))
            .size());
  }

  @Test
  void advertisesNothingWhenToolCallingIsDisabled() {
    properties.getTools().setEnabled(false);

    assertTrue(policy.permitted(user(List.of()), "crm", List.of(tool("a", "read"))).isEmpty());
  }

  @Test
  void refusesAToolThatWasNeverAdvertised() {
    // This is the shape a prompt-injection attempt takes: a name the client never offered.
    var decision = policy.authorize(user(List.of()), "crm", "dropDatabase", List.of(tool("a", "read")));

    assertFalse(decision.allowed());
    assertEquals("tool_not_available", decision.code());
  }

  @Test
  void refusesAnAdvertisedToolWhenThePermissionWasRevokedMidTurn() {
    var advertised = List.of(tool("approveOrder", "write"));
    // Config or entitlements can change between advertisement and execution.
    properties.getTools().getRequiredPermission().put("approveOrder", "order:approve");

    var decision = policy.authorize(user(List.of()), "crm", "approveOrder", advertised);

    assertFalse(decision.allowed());
    assertEquals("tool_forbidden", decision.code());
  }

  @Test
  void allowsAnAdvertisedToolWhenPermissionsMatch() {
    properties.getTools().getRequiredPermission().put("approveOrder", "order:approve");
    var advertised = List.of(tool("approveOrder", "write"));

    assertTrue(
        policy.authorize(user(List.of("order:approve")), "crm", "approveOrder", advertised).allowed());
  }

  @Test
  void treatsMissingOrUnknownRiskAsWrite() {
    assertTrue(ToolPolicy.isWrite(new ClientTool("a", "a", Map.of(), null)));
    assertTrue(ToolPolicy.isWrite(new ClientTool("a", "a", Map.of(), "destructive")));
    assertFalse(ToolPolicy.isWrite(new ClientTool("a", "a", Map.of(), "read")));
  }
}
