package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermInflectionDiagnostics;
import com.box.l10n.mojito.mf2.inflection.TermInflectionDiagnostics.DiagnosticSummary;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermInflectionProfileService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ReviewGlossaryTermInflectionProfilesMcpTool
    extends TypedMcpToolHandler<ReviewGlossaryTermInflectionProfilesMcpTool.Input> {

  private static final String ACTION_LIST = "LIST";
  private static final String ACTION_APPROVE = "APPROVE";
  private static final String ACTION_DISABLE = "DISABLE";
  private static final String ACTION_MARK_REVIEW_NEEDED = "MARK_REVIEW_NEEDED";
  private static final Set<String> ACTIONS =
      Set.of(ACTION_LIST, ACTION_APPROVE, ACTION_DISABLE, ACTION_MARK_REVIEW_NEEDED);
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.inflection.review_profiles",
          "Review glossary inflection profiles",
          "List glossary term inflection profiles that need review and optionally approve, disable, or mark one profile as review-needed. Use this before checked V0 compiled export to inspect diagnostics such as missing Arabic form cells or Turkish explicit-template review rows.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "glossaryId", "Glossary id. Preferred when known.", false, Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "localeTag", "Inflection profile locale tag, for example fr or ar.", true),
              new McpToolParameter(
                  "tmTextUnitId",
                  "Glossary source tmTextUnitId to review. Required when action is not LIST.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "action",
                  "Review action. Defaults to LIST.",
                  false,
                  enumSchema("Review action. Defaults to LIST.", ACTIONS)),
              new McpToolParameter(
                  "diagnosticsJson",
                  "Optional replacement diagnostics JSON array. Pass [] when approving after resolving diagnostics.",
                  false),
              new McpToolParameter(
                  "morphologyJson",
                  "Optional replacement morphology JSON object. Use with formsJson when review changes grammar metadata.",
                  false),
              new McpToolParameter(
                  "formsJson",
                  "Optional replacement forms JSON object. Use this to fill missing form cells before approving.",
                  false),
              new McpToolParameter(
                  "provenanceJson", "Optional replacement provenance JSON object.", false),
              new McpToolParameter(
                  "includeApproved",
                  "When true, include approved profiles in the returned list. Defaults to false.",
                  false,
                  Boolean.class),
              new McpToolParameter(
                  "limit",
                  "Max profiles to return. Defaults to 100, max 500.",
                  false,
                  Integer.class)));

  private final ObjectMapper objectMapper;
  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermInflectionProfileService inflectionProfileService;

  public ReviewGlossaryTermInflectionProfilesMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermInflectionProfileService inflectionProfileService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.inflectionProfileService = Objects.requireNonNull(inflectionProfileService);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      String localeTag,
      Long tmTextUnitId,
      String action,
      String diagnosticsJson,
      String morphologyJson,
      String formsJson,
      String provenanceJson,
      Boolean includeApproved,
      Integer limit) {}

  public record Result(
      GlossaryRef glossary,
      String localeTag,
      String action,
      InflectionProfileSummary reviewedProfile,
      int totalProfileCount,
      int returnedProfileCount,
      List<InflectionProfileSummary> profiles) {}

  public record GlossaryRef(Long id, String name) {}

  public record InflectionProfileSummary(
      Long glossaryTermMetadataId,
      Long tmTextUnitId,
      String termId,
      String source,
      String status,
      int formCount,
      int diagnosticCount,
      List<DiagnosticSummary> diagnosticSummaries,
      List<String> missingFormKeys,
      JsonNode morphology,
      JsonNode forms,
      JsonNode diagnostics,
      JsonNode provenance) {}

  @Override
  protected Object execute(Input input) {
    EffectiveInput effectiveInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(
            effectiveInput.glossaryId(), effectiveInput.glossaryName());

    InflectionProfileSummary reviewedProfile = null;
    if (!ACTION_LIST.equals(effectiveInput.action())) {
      GlossaryTermInflectionProfileService.InflectionProfileView view =
          inflectionProfileService.reviewProfile(
              glossary.id(),
              effectiveInput.tmTextUnitId(),
              new GlossaryTermInflectionProfileService.InflectionProfileReviewInput(
                  effectiveInput.localeTag(),
                  reviewStatus(effectiveInput.action()),
                  effectiveInput.morphologyJson(),
                  effectiveInput.formsJson(),
                  effectiveInput.diagnosticsJson(),
                  effectiveInput.provenanceJson()));
      reviewedProfile = toSummary(view);
    }

    List<GlossaryTermInflectionProfileService.InflectionProfileView> profileViews =
        inflectionProfileService.getProfiles(glossary.id(), effectiveInput.localeTag());
    List<InflectionProfileSummary> profiles =
        profileViews.stream()
            .map(this::toSummary)
            .filter(profile -> effectiveInput.includeApproved() || needsReview(profile))
            .limit(effectiveInput.limit())
            .toList();
    return new Result(
        new GlossaryRef(glossary.id(), glossary.name()),
        effectiveInput.localeTag(),
        effectiveInput.action(),
        reviewedProfile,
        profileViews.size(),
        profiles.size(),
        profiles);
  }

  private EffectiveInput validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    String localeTag = requireNonBlank(input.localeTag(), "localeTag");
    String action = normalizeAction(input.action());
    if (!ACTION_LIST.equals(action) && input.tmTextUnitId() == null) {
      throw new IllegalArgumentException("tmTextUnitId is required when action is " + action);
    }
    int limit = input.limit() == null ? DEFAULT_LIMIT : input.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    return new EffectiveInput(
        input.glossaryId(),
        input.glossaryName(),
        localeTag,
        input.tmTextUnitId(),
        action,
        input.diagnosticsJson(),
        input.morphologyJson(),
        input.formsJson(),
        input.provenanceJson(),
        input.includeApproved() != null && input.includeApproved(),
        limit);
  }

  private InflectionProfileSummary toSummary(
      GlossaryTermInflectionProfileService.InflectionProfileView view) {
    JsonNode forms = objectMapper.readTreeUnchecked(view.formsJson());
    JsonNode morphology = objectMapper.readTreeUnchecked(view.morphologyJson());
    JsonNode diagnostics = objectMapper.readTreeUnchecked(view.diagnosticsJson());
    JsonNode provenance = objectMapper.readTreeUnchecked(view.provenanceJson());
    return new InflectionProfileSummary(
        view.glossaryTermMetadataId(),
        view.tmTextUnitId(),
        view.termId(),
        view.source(),
        view.status(),
        forms.isObject() ? forms.size() : 0,
        diagnostics.isArray() ? diagnostics.size() : 0,
        TermInflectionDiagnostics.diagnosticSummaries(diagnostics),
        TermInflectionDiagnostics.missingFormKeys(diagnostics),
        morphology,
        forms,
        diagnostics,
        provenance);
  }

  private boolean needsReview(InflectionProfileSummary profile) {
    return !TermInflectionProfilePackJsonLoader.STATUS_APPROVED.equals(profile.status())
        || profile.diagnosticCount() > 0;
  }

  private String reviewStatus(String action) {
    return switch (action) {
      case ACTION_APPROVE -> TermInflectionProfilePackJsonLoader.STATUS_APPROVED;
      case ACTION_DISABLE -> TermInflectionProfilePackJsonLoader.STATUS_DISABLED;
      case ACTION_MARK_REVIEW_NEEDED -> TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED;
      default -> throw new IllegalArgumentException("Unsupported review action: " + action);
    };
  }

  private String normalizeAction(String action) {
    String normalized = action == null || action.isBlank() ? ACTION_LIST : action.trim();
    normalized = normalized.toUpperCase(Locale.ROOT);
    if (!ACTIONS.contains(normalized)) {
      throw new IllegalArgumentException("Unsupported action: " + action);
    }
    return normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  private static Map<String, Object> enumSchema(String description, Set<String> values) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("description", description);
    schema.put("enum", values.stream().sorted().toList());
    return schema;
  }

  private record EffectiveInput(
      Long glossaryId,
      String glossaryName,
      String localeTag,
      Long tmTextUnitId,
      String action,
      String diagnosticsJson,
      String morphologyJson,
      String formsJson,
      String provenanceJson,
      boolean includeApproved,
      int limit) {}
}
