package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ReviewGlossaryTermPlanMcpTool
    extends TypedMcpToolHandler<ReviewGlossaryTermPlanMcpTool.Input> {

  private static final int MAX_TERMS = 1_000;
  private static final int EXISTING_SCAN_LIMIT = 500;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term.review_plan",
          "Review glossary term plan",
          "Compare proposed glossary terms with existing terms and return create/update/duplicate guidance. This tool never writes; call glossary.term.bulk_upsert with dryRun=true after resolving warnings.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "glossaryId", "Glossary id. Preferred when known.", false, Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "terms", "Proposed terms to review before upserting.", true, termsSchema())));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;

  public ReviewGlossaryTermPlanMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
  }

  public record Input(Long glossaryId, String glossaryName, List<TermInput> terms) {}

  public record TermInput(Long tmTextUnitId, String termKey, String source) {}

  public record ReviewPlanResult(
      GlossaryRef glossary,
      int proposedTermCount,
      boolean existingScanTruncated,
      List<TermPlan> plan) {}

  public record GlossaryRef(Long id, String name) {}

  public record TermPlan(
      int index,
      String action,
      Long tmTextUnitId,
      String termKey,
      String source,
      List<String> warnings,
      List<ExistingTermMatch> existingMatches) {}

  public record ExistingTermMatch(
      String matchType, Long tmTextUnitId, String termKey, String source, String status) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.glossaryId(), validatedInput.glossaryName());
    GlossaryTermService.SearchTermsView existingView =
        glossaryTermService.searchTerms(glossary.id(), null, List.of(), EXISTING_SCAN_LIMIT);
    List<GlossaryTermService.TermView> existingTerms = existingView.terms();

    List<TermPlan> plan = new ArrayList<>();
    for (int i = 0; i < validatedInput.terms().size(); i++) {
      plan.add(toPlan(i, validatedInput.terms().get(i), existingTerms));
    }
    return new ReviewPlanResult(
        new GlossaryRef(glossary.id(), glossary.name()),
        validatedInput.terms().size(),
        existingView.totalCount() > existingTerms.size(),
        plan);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (input.terms() == null || input.terms().isEmpty()) {
      throw new IllegalArgumentException("terms are required");
    }
    if (input.terms().size() > MAX_TERMS) {
      throw new IllegalArgumentException("terms must contain at most " + MAX_TERMS + " entries");
    }
    for (int i = 0; i < input.terms().size(); i++) {
      TermInput term = input.terms().get(i);
      if (term == null) {
        throw new IllegalArgumentException("terms[" + i + "] is required");
      }
      if (term.tmTextUnitId() == null
          && normalizeOptional(term.termKey()) == null
          && normalizeOptional(term.source()) == null) {
        throw new IllegalArgumentException(
            "terms[" + i + "] needs tmTextUnitId, termKey, or source");
      }
    }
    return input;
  }

  private TermPlan toPlan(
      int index, TermInput proposedTerm, List<GlossaryTermService.TermView> existingTerms) {
    List<ExistingTermMatch> matches =
        existingTerms.stream()
            .map(existing -> toMatch(proposedTerm, existing))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ExistingTermMatch::matchType))
            .toList();
    List<String> warnings = new ArrayList<>();
    String action = proposedTerm.tmTextUnitId() == null ? "CREATE" : "UPDATE";

    if (!matches.isEmpty()) {
      boolean hasExactIdentity =
          matches.stream()
              .anyMatch(
                  match ->
                      "TM_TEXT_UNIT_ID".equals(match.matchType())
                          || "TERM_KEY".equals(match.matchType()));
      if (proposedTerm.tmTextUnitId() == null && !hasExactIdentity) {
        action = "REVIEW_DUPLICATE";
      }
      if (hasExactIdentity) {
        action = "UPDATE";
      }
      warnings.add("Existing glossary term match found. Review before writing.");
    }
    if (matches.size() > 1) {
      warnings.add("Multiple existing matches found. Consider merge or manual cleanup.");
    }

    return new TermPlan(
        index,
        action,
        proposedTerm.tmTextUnitId(),
        normalizeOptional(proposedTerm.termKey()),
        normalizeOptional(proposedTerm.source()),
        warnings,
        matches);
  }

  private ExistingTermMatch toMatch(
      TermInput proposedTerm, GlossaryTermService.TermView existingTerm) {
    if (proposedTerm.tmTextUnitId() != null
        && proposedTerm.tmTextUnitId().equals(existingTerm.tmTextUnitId())) {
      return toExistingTermMatch("TM_TEXT_UNIT_ID", existingTerm);
    }
    String proposedTermKey = normalizeOptional(proposedTerm.termKey());
    if (proposedTermKey != null && proposedTermKey.equals(existingTerm.termKey())) {
      return toExistingTermMatch("TERM_KEY", existingTerm);
    }
    String proposedSourceKey = normalizeKey(proposedTerm.source());
    String existingSourceKey = normalizeKey(existingTerm.source());
    if (proposedSourceKey != null && proposedSourceKey.equals(existingSourceKey)) {
      return toExistingTermMatch("SOURCE", existingTerm);
    }
    return null;
  }

  private ExistingTermMatch toExistingTermMatch(
      String matchType, GlossaryTermService.TermView existingTerm) {
    return new ExistingTermMatch(
        matchType,
        existingTerm.tmTextUnitId(),
        existingTerm.termKey(),
        existingTerm.source(),
        existingTerm.status());
  }

  private String normalizeKey(String value) {
    String normalized = normalizeOptional(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static Map<String, Object> termsSchema() {
    return Map.of(
        "type",
        "array",
        "minItems",
        1,
        "maxItems",
        MAX_TERMS,
        "items",
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "tmTextUnitId", integerSchema("Existing glossary source tmTextUnitId to update."),
                "termKey", stringSchema("Stable glossary term key."),
                "source", stringSchema("Canonical source term text."))));
  }

  private static Map<String, Object> stringSchema(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> integerSchema(String description) {
    return Map.of("type", "integer", "description", description);
  }
}
