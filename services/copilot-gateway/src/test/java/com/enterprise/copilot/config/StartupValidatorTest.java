package com.enterprise.copilot.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The validator is the last line of defense against booting an unsafe production configuration, so
 * its rules are pinned here rather than left to a manual boot.
 */
class StartupValidatorTest {

  private static MockEnvironment profile(String name) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(name);
    return env;
  }

  private static StartupValidator validator(CopilotProperties props, String profile) {
    return new StartupValidator(props, profile(profile));
  }

  @Test
  void devProfileSkipsProductionChecks() {
    CopilotProperties props = new CopilotProperties();
    props.setCorsAllowAllPatterns(true); // would fail outside dev

    assertDoesNotThrow(() -> validator(props, "dev").onApplicationEvent(null));
  }

  @Test
  void rejectsWildcardCorsOutsideDev() {
    CopilotProperties props = new CopilotProperties();
    props.setCorsAllowAllPatterns(true);
    props.setCorsAllowedOrigins(List.of("https://app.example.com"));

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> validator(props, "prod").onApplicationEvent(null));
    org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("cors-allow-all-patterns"));
  }

  @Test
  void acceptsALockedDownProductionConfiguration() {
    CopilotProperties props = new CopilotProperties();
    props.setCorsAllowAllPatterns(false);
    props.setCorsAllowedOrigins(List.of("https://app.example.com"));
    props.getLlm().setMock(false);
    props.getTools().setEnabled(false);

    assertDoesNotThrow(() -> validator(props, "prod").onApplicationEvent(null));
  }

  @Test
  void toolsEnabledWithAnAllowlistIsAccepted() {
    CopilotProperties props = new CopilotProperties();
    props.setCorsAllowAllPatterns(false);
    props.setCorsAllowedOrigins(List.of("https://app.example.com"));
    props.getTools().setEnabled(true);
    props.getTools().getAllowed().put("crm", List.of("approveOrder"));

    // The empty-allowlist case only logs a warning, so the meaningful assertion is that a properly
    // configured tool policy still boots cleanly.
    assertDoesNotThrow(() -> validator(props, "prod").onApplicationEvent(null));
  }
}
