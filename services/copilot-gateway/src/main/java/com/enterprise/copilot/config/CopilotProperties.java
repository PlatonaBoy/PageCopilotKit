package com.enterprise.copilot.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copilot")
public class CopilotProperties {

  /** Minimum HS256 key length. Shorter secrets are rejected at startup. */
  public static final int MIN_SECRET_BYTES = 32;

  private String jwtSecret = "";
  private String jwtIssuer = "enterprise-copilot";
  private boolean jwtRequireIssuer = true;
  private Duration jwtClockSkew = Duration.ofSeconds(30);
  private List<String> allowedAppIds = new ArrayList<>(List.of("crm", "demo"));
  private List<String> corsAllowedOrigins = new ArrayList<>();
  private boolean corsAllowAllPatterns = false;
  private Context context = new Context();
  private History history = new History();
  private Knowledge knowledge = new Knowledge();
  private Llm llm = new Llm();
  private RateLimit rateLimit = new RateLimit();
  private Tools tools = new Tools();

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

  public boolean isJwtRequireIssuer() {
    return jwtRequireIssuer;
  }

  public void setJwtRequireIssuer(boolean jwtRequireIssuer) {
    this.jwtRequireIssuer = jwtRequireIssuer;
  }

  public Duration getJwtClockSkew() {
    return jwtClockSkew;
  }

  public void setJwtClockSkew(Duration jwtClockSkew) {
    this.jwtClockSkew = jwtClockSkew;
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

  public History getHistory() {
    return history;
  }

  public void setHistory(History history) {
    this.history = history;
  }

  public Knowledge getKnowledge() {
    return knowledge;
  }

  public void setKnowledge(Knowledge knowledge) {
    this.knowledge = knowledge;
  }

  public Llm getLlm() {
    return llm;
  }

  public void setLlm(Llm llm) {
    this.llm = llm;
  }

  public RateLimit getRateLimit() {
    return rateLimit;
  }

  public void setRateLimit(RateLimit rateLimit) {
    this.rateLimit = rateLimit;
  }

  public Tools getTools() {
    return tools;
  }

  public void setTools(Tools tools) {
    this.tools = tools;
  }

  public static class Context {
    private int maxBusinessBytes = 4096;
    private int maxSummaryChars = 12000;
    private int maxActionableElements = 40;
    private int maxMessageChars = 4000;
    private int maxTitleChars = 300;
    private int maxUrlChars = 2000;
    private int maxSelectionChars = 2000;
    /** Total prompt character budget across history + page + business. */
    private int maxPromptChars = 24000;

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

    public int getMaxMessageChars() {
      return maxMessageChars;
    }

    public void setMaxMessageChars(int maxMessageChars) {
      this.maxMessageChars = maxMessageChars;
    }

    public int getMaxTitleChars() {
      return maxTitleChars;
    }

    public void setMaxTitleChars(int maxTitleChars) {
      this.maxTitleChars = maxTitleChars;
    }

    public int getMaxUrlChars() {
      return maxUrlChars;
    }

    public void setMaxUrlChars(int maxUrlChars) {
      this.maxUrlChars = maxUrlChars;
    }

    public int getMaxSelectionChars() {
      return maxSelectionChars;
    }

    public void setMaxSelectionChars(int maxSelectionChars) {
      this.maxSelectionChars = maxSelectionChars;
    }

    public int getMaxPromptChars() {
      return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
      this.maxPromptChars = maxPromptChars;
    }
  }

  public static class History {
    private int maxTurns = 8;
    private int maxChars = 6000;
    private int retainMessages = 200;

    public int getMaxTurns() {
      return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
      this.maxTurns = maxTurns;
    }

    public int getMaxChars() {
      return maxChars;
    }

    public void setMaxChars(int maxChars) {
      this.maxChars = maxChars;
    }

    public int getRetainMessages() {
      return retainMessages;
    }

    public void setRetainMessages(int retainMessages) {
      this.retainMessages = retainMessages;
    }
  }

  public static class Knowledge {
    private boolean enabled = true;
    /** Snippets handed to the model for one question. */
    private int topK = 3;
    /** Per-snippet character cap, applied before the block budget. */
    private int maxSnippetChars = 400;
    /** Combined budget for the retrieved knowledge block. */
    private int maxContextChars = 3000;
    /** Target chunk size at ingest. Chunks are the unit of retrieval and of citation. */
    private int chunkChars = 700;
    /** Overlap between adjacent chunks so a fact split across a boundary is still retrievable. */
    private int chunkOverlapChars = 120;
    /** Largest single document accepted by the ingest API. */
    private int maxDocumentChars = 200_000;
    /** Documents one tenant may store. */
    private int maxDocumentsPerTenant = 2000;
    /** Chunks below this BM25 score are treated as noise rather than a weak match. */
    private double minScore = 0.35;
    /** Safety valve for the in-process index; ingest is refused beyond this. */
    private int maxIndexedChunks = 50_000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getTopK() {
      return topK;
    }

    public void setTopK(int topK) {
      this.topK = topK;
    }

    public int getMaxSnippetChars() {
      return maxSnippetChars;
    }

    public void setMaxSnippetChars(int maxSnippetChars) {
      this.maxSnippetChars = maxSnippetChars;
    }

    public int getMaxContextChars() {
      return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
      this.maxContextChars = maxContextChars;
    }

    public int getChunkChars() {
      return chunkChars;
    }

    public void setChunkChars(int chunkChars) {
      this.chunkChars = chunkChars;
    }

    public int getChunkOverlapChars() {
      return chunkOverlapChars;
    }

    public void setChunkOverlapChars(int chunkOverlapChars) {
      this.chunkOverlapChars = chunkOverlapChars;
    }

    public int getMaxDocumentChars() {
      return maxDocumentChars;
    }

    public void setMaxDocumentChars(int maxDocumentChars) {
      this.maxDocumentChars = maxDocumentChars;
    }

    public int getMaxDocumentsPerTenant() {
      return maxDocumentsPerTenant;
    }

    public void setMaxDocumentsPerTenant(int maxDocumentsPerTenant) {
      this.maxDocumentsPerTenant = maxDocumentsPerTenant;
    }

    public double getMinScore() {
      return minScore;
    }

    public void setMinScore(double minScore) {
      this.minScore = minScore;
    }

    public int getMaxIndexedChunks() {
      return maxIndexedChunks;
    }

    public void setMaxIndexedChunks(int maxIndexedChunks) {
      this.maxIndexedChunks = maxIndexedChunks;
    }
  }

  public static class Llm {
    private boolean mock = true;
    private Duration timeout = Duration.ofSeconds(45);
    private int maxRetries = 2;
    private Duration retryBackoff = Duration.ofMillis(400);
    /** Consecutive failures before the breaker opens. */
    private int breakerFailureThreshold = 5;

    private Duration breakerOpenDuration = Duration.ofSeconds(30);

    public boolean isMock() {
      return mock;
    }

    public void setMock(boolean mock) {
      this.mock = mock;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public int getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public Duration getRetryBackoff() {
      return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
      this.retryBackoff = retryBackoff;
    }

    public int getBreakerFailureThreshold() {
      return breakerFailureThreshold;
    }

    public void setBreakerFailureThreshold(int breakerFailureThreshold) {
      this.breakerFailureThreshold = breakerFailureThreshold;
    }

    public Duration getBreakerOpenDuration() {
      return breakerOpenDuration;
    }

    public void setBreakerOpenDuration(Duration breakerOpenDuration) {
      this.breakerOpenDuration = breakerOpenDuration;
    }
  }

  public static class Tools {
    private boolean enabled = true;
    /** Maximum tool round-trips the server will serve for one user turn. */
    private int maxStepsPerTurn = 5;
    /** appId -> allowed tool names. Empty means "any tool the client declares". */
    private java.util.Map<String, List<String>> allowed = new java.util.LinkedHashMap<>();
    /** tool name -> permission the caller's JWT must carry. */
    private java.util.Map<String, String> requiredPermission = new java.util.LinkedHashMap<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxStepsPerTurn() {
      return maxStepsPerTurn;
    }

    public void setMaxStepsPerTurn(int maxStepsPerTurn) {
      this.maxStepsPerTurn = maxStepsPerTurn;
    }

    public java.util.Map<String, List<String>> getAllowed() {
      return allowed;
    }

    public void setAllowed(java.util.Map<String, List<String>> allowed) {
      this.allowed = allowed;
    }

    public java.util.Map<String, String> getRequiredPermission() {
      return requiredPermission;
    }

    public void setRequiredPermission(java.util.Map<String, String> requiredPermission) {
      this.requiredPermission = requiredPermission;
    }
  }

  public static class RateLimit {
    private boolean enabled = true;
    private int perUserPerMinute = 20;
    private int perTenantPerMinute = 200;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getPerUserPerMinute() {
      return perUserPerMinute;
    }

    public void setPerUserPerMinute(int perUserPerMinute) {
      this.perUserPerMinute = perUserPerMinute;
    }

    public int getPerTenantPerMinute() {
      return perTenantPerMinute;
    }

    public void setPerTenantPerMinute(int perTenantPerMinute) {
      this.perTenantPerMinute = perTenantPerMinute;
    }
  }
}
