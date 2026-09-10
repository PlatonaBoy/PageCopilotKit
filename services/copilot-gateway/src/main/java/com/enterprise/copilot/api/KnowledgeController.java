package com.enterprise.copilot.api;

import com.enterprise.copilot.api.dto.KnowledgeDtos.DeleteResponse;
import com.enterprise.copilot.api.dto.KnowledgeDtos.DocumentListResponse;
import com.enterprise.copilot.api.dto.KnowledgeDtos.DocumentRequest;
import com.enterprise.copilot.api.dto.KnowledgeDtos.DocumentView;
import com.enterprise.copilot.api.dto.KnowledgeDtos.SearchHit;
import com.enterprise.copilot.api.dto.KnowledgeDtos.SearchRequest;
import com.enterprise.copilot.api.dto.KnowledgeDtos.SearchResponse;
import com.enterprise.copilot.auth.AuthSupport;
import com.enterprise.copilot.auth.UserPrincipal;
import com.enterprise.copilot.config.CopilotProperties;
import com.enterprise.copilot.knowledge.KnowledgeDocument;
import com.enterprise.copilot.knowledge.KnowledgeIndex;
import com.enterprise.copilot.knowledge.KnowledgeService;
import com.enterprise.copilot.knowledge.RetrievedSnippet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge base management.
 *
 * <p>Reads require {@code knowledge:read} and writes require {@code knowledge:write}; both are
 * always scoped to the caller's tenant. Chat retrieval itself needs no permission — answering from
 * the tenant's own knowledge base is the product, while curating it is an administrative act.
 */
@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeController {

  private static final String READ_PERMISSION = "knowledge:read";
  private static final String WRITE_PERMISSION = "knowledge:write";

  private final KnowledgeService knowledgeService;
  private final CopilotProperties properties;

  public KnowledgeController(KnowledgeService knowledgeService, CopilotProperties properties) {
    this.knowledgeService = knowledgeService;
    this.properties = properties;
  }

  @PostMapping("/documents")
  public DocumentView upsert(@Valid @RequestBody DocumentRequest body, HttpServletRequest request) {
    UserPrincipal user = authorize(request, WRITE_PERMISSION);
    validateAppId(body.appId());
    KnowledgeDocument saved =
        knowledgeService.upsert(
            user.tenantId(),
            new KnowledgeService.DocumentInput(
                body.docId(),
                body.appId(),
                body.title(),
                body.source(),
                body.tags(),
                body.content()));
    return toView(saved);
  }

  @GetMapping("/documents")
  public DocumentListResponse list(
      @RequestParam(required = false) String appId,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    UserPrincipal user = authorize(request, READ_PERMISSION);
    List<DocumentView> items =
        knowledgeService.list(user.tenantId(), appId, limit).stream()
            .map(KnowledgeController::toView)
            .toList();
    return new DocumentListResponse(user.tenantId(), items.size(), items);
  }

  @GetMapping("/documents/{docId}")
  public DocumentView get(@PathVariable String docId, HttpServletRequest request) {
    UserPrincipal user = authorize(request, READ_PERMISSION);
    return toView(knowledgeService.get(user.tenantId(), docId));
  }

  @DeleteMapping("/documents/{docId}")
  public DeleteResponse delete(@PathVariable String docId, HttpServletRequest request) {
    UserPrincipal user = authorize(request, WRITE_PERMISSION);
    knowledgeService.delete(user.tenantId(), docId);
    return new DeleteResponse(true, docId);
  }

  /**
   * Runs retrieval without generating an answer.
   *
   * <p>This is what makes a bad citation debuggable: an operator can see the exact ranking the
   * model was given, separately from what the model then did with it.
   */
  @PostMapping("/search")
  public SearchResponse search(@Valid @RequestBody SearchRequest body, HttpServletRequest request) {
    UserPrincipal user = authorize(request, READ_PERMISSION);
    String appId = body.appId() == null || body.appId().isBlank() ? null : body.appId();
    if (appId != null) {
      validateAppId(appId);
    }

    List<RetrievedSnippet> snippets = knowledgeService.retrieve(user.tenantId(), appId, body.query());
    int topK = body.topK() == null ? snippets.size() : Math.clamp(body.topK(), 0, snippets.size());
    List<SearchHit> hits =
        snippets.subList(0, topK).stream()
            .map(
                snippet ->
                    new SearchHit(
                        snippet.index(),
                        snippet.docId(),
                        snippet.title(),
                        snippet.source(),
                        snippet.snippet(),
                        snippet.score()))
            .toList();
    return new SearchResponse(user.tenantId(), appId == null ? "*" : appId, hits.size(), hits);
  }

  private UserPrincipal authorize(HttpServletRequest request, String permission) {
    UserPrincipal user = AuthSupport.requireUser(request);
    if (!user.permissions().contains(permission)) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "forbidden", "Missing permission: " + permission);
    }
    return user;
  }

  /**
   * A document scoped to an unknown application would be invisible forever, so the mistake is
   * rejected at write time rather than discovered when answers silently lack sources.
   */
  private void validateAppId(String appId) {
    if (KnowledgeIndex.ALL_APPS.equals(appId)) {
      return;
    }
    if (!properties.getAllowedAppIds().contains(appId)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "bad_request",
          "Unknown appId: " + appId + " (use \"*\" for all applications)");
    }
  }

  private static DocumentView toView(KnowledgeDocument document) {
    return new DocumentView(
        document.getDocId(),
        document.getAppId(),
        document.getTitle(),
        document.getSource(),
        splitTags(document.getTags()),
        document.getChunkCount(),
        document.getContent().length(),
        document.getCreatedAt(),
        document.getUpdatedAt());
  }

  private static List<String> splitTags(String tags) {
    if (tags == null || tags.isBlank()) {
      return List.of();
    }
    return Arrays.stream(tags.split(",")).filter(tag -> !tag.isBlank()).toList();
  }
}
