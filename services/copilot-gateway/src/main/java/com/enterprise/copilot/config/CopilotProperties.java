package com.enterprise.copilot.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copilot")
public class CopilotProperties {

  private String jwtSecret = "dev-copilot-jwt-secret-change-me-32b";
  private String jwtIssuer = "enterprise-copilot";
  private List<String> allowedAppIds = new ArrayList<>(List.of("crm", "demo"));
  private List<String> corsAllowedOrigins =
      new ArrayList<>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
  private boolean demoTokenEnabled = true;
  private boolean mockLlm = true;
  /** When true, allow any Origin via allowedOriginPatterns (MVP / local demo). */
  private boolean corsAllowAllPatterns = true;
  private Context context = new Context();

  public String getJwtSecret() {
    return jwtSecret;
  }

  public void setJwtSecret(String jwtSecret) {
    this.jwtSecret = jwtSecret;
  }

  public String getJwtIssuer() {
    return jwtIssuer;
  }

  public void setJwtIssuer(String jwtIssuer) {
    this.jwtIssuer = jwtIssuer;
  }

  public List<String> getAllowedAppIds() {
    return allowedAppIds;
  }

  public void setAllowedAppIds(List<String> allowedAppIds) {
    this.allowedAppIds = allowedAppIds;
  }

  public List<String> getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
    this.corsAllowedOrigins = corsAllowedOrigins;
  }

  public boolean isDemoTokenEnabled() {
    return demoTokenEnabled;
  }

  public void setDemoTokenEnabled(boolean demoTokenEnabled) {
    this.demoTokenEnabled = demoTokenEnabled;
  }

  public boolean isMockLlm() {
    return mockLlm;
  }

  public void setMockLlm(boolean mockLlm) {
    this.mockLlm = mockLlm;
  }

  public boolean isCorsAllowAllPatterns() {
    return corsAllowAllPatterns;
  }

  public void setCorsAllowAllPatterns(boolean corsAllowAllPatterns) {
    this.corsAllowAllPatterns = corsAllowAllPatterns;
  }

  public Context getContext() {
    return context;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public static class Context {
    private int maxBusinessBytes = 4096;
    private int maxSummaryChars = 12000;
    private int maxActionableElements = 40;

    public int getMaxBusinessBytes() {
      return maxBusinessBytes;
    }

    public void setMaxBusinessBytes(int maxBusinessBytes) {
      this.maxBusinessBytes = maxBusinessBytes;
    }

    public int getMaxSummaryChars() {
      return maxSummaryChars;
    }

    public void setMaxSummaryChars(int maxSummaryChars) {
      this.maxSummaryChars = maxSummaryChars;
    }

    public int getMaxActionableElements() {
      return maxActionableElements;
    }

    public void setMaxActionableElements(int maxActionableElements) {
      this.maxActionableElements = maxActionableElements;
    }
  }
}
