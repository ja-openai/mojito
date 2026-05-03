package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SeedTermIndexCandidatesFromGlossaryMcpTool
    extends TypedMcpToolHandler<SeedTermIndexCandidatesFromGlossaryMcpTool.Input> {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1_000;
  private static final List<String> DEFAULT_SOURCE_STATUSES =
      List.of(GlossaryTermMetadata.STATUS_APPROVED, GlossaryTermMetadata.STATUS_CANDIDATE);

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term_index.seed_candidates_from_glossary",
          "Seed candidates from glossary",
          "Copy terms from an existing Mojito glossary into a target glossary's term-index candidate review layer. This does not create accepted glossary terms; curators review and accept the seeded candidates in the glossary builder.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "sourceGlossaryId",
                  "Source glossary id. Preferred when known.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "sourceGlossaryName",
                  "Exact source glossary name. Used only when sourceGlossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "targetGlossaryId",
                  "Target glossary id whose builder should receive the candidates.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "targetGlossaryName",
                  "Exact target glossary name. Used only when targetGlossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "searchQuery",
                  "Optional source glossary search query before seeding candidates.",
                  false),
              new McpToolParameter(
                  "localeTags",
                  "Optional locale tags to include from the source glossary response.",
                  false,
                  stringArraySchema(
                      "Optional locale tags to include from the source glossary response.")),
              new McpToolParameter(
                  "sourceTmTextUnitIds",
                  "Optional source glossary term tmTextUnitIds to seed.",
                  false,
                  integerArraySchema("Optional source glossary term tmTextUnitIds to seed.")),
              new McpToolParameter(
                  "termKeys",
                  "Optional source glossary term keys to seed.",
                  false,
                  stringArraySchema("Optional source glossary term keys to seed.")),
              new McpToolParameter(
                  "sourceStatuses",
                  "Optional source glossary statuses to include. Defaults to APPROVED and CANDIDATE.",
                  false,
                  stringArraySchema(
                      "Optional source glossary statuses to include. Defaults to APPROVED and CANDIDATE.")),
              new McpToolParameter(
                  "limit",
                  "Optional max source terms to scan. Defaults to 200, max 1000.",
                  false,
                  Integer.class)));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  public SeedTermIndexCandidatesFromGlossaryMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
    this.glossaryTermIndexCurationService =
        Objects.requireNonNull(glossaryTermIndexCurationService);
  }

  public record Input(
      Long sourceGlossaryId,
      String sourceGlossaryName,
      Long targetGlossaryId,
      String targetGlossaryName,
      String searchQuery,
      List<String> localeTags,
      List<Long> sourceTmTextUnitIds,
      List<String> termKeys,
      List<String> sourceStatuses,
      Integer limit) {}

  public record SeedFromGlossaryResult(
      GlossaryRef sourceGlossary,
      GlossaryRef targetGlossary,
      String searchQuery,
      List<String> localeTags,
      int sourceTermCount,
      long sourceTotalCount,
      int selectedTermCount,
      GlossaryTermIndexCurationService.SeedResult seedResult,
      List<SourceTermSelection> selectedTerms) {}

  public record GlossaryRef(Long id, String name) {}

  public record SourceTermSelection(
      Long tmTextUnitId, String termKey, String source, String status, String provenance) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail sourceGlossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.sourceGlossaryId(), validatedInput.sourceGlossaryName());
    GlossaryManagementService.GlossaryDetail targetGlossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.targetGlossaryId(), validatedInput.targetGlossaryName());
    int limit = normalizeLimit(validatedInput.limit());
    List<String> localeTags =
        validatedInput.localeTags() == null ? List.of() : validatedInput.localeTags();
    GlossaryTermService.SearchTermsView sourceView =
        glossaryTermService.searchTerms(
            sourceGlossary.id(), validatedInput.searchQuery(), localeTags, limit);
    Set<Long> sourceTmTextUnitIds = normalizeLongSet(validatedInput.sourceTmTextUnitIds());
    Set<String> termKeys = normalizeStringSet(validatedInput.termKeys());
    Set<String> sourceStatuses = normalizeStatuses(validatedInput.sourceStatuses());

    List<GlossaryTermService.TermView> selectedTerms =
        sourceView.terms().stream()
            .filter(term -> matchesSourceTermIds(term, sourceTmTextUnitIds))
            .filter(term -> matchesTermKeys(term, termKeys))
            .filter(term -> matchesSourceStatus(term, sourceStatuses))
            .toList();

    List<GlossaryTermIndexCurationService.SeedTermInput> seedInputs =
        selectedTerms.stream().map(term -> toSeedTermInput(sourceGlossary, term)).toList();
    GlossaryTermIndexCurationService.SeedResult seedResult =
        seedInputs.isEmpty()
            ? new GlossaryTermIndexCurationService.SeedResult(0, 0, 0, List.of())
            : glossaryTermIndexCurationService.seedTermsForGlossary(
                targetGlossary.id(), new GlossaryTermIndexCurationService.SeedCommand(seedInputs));

    return new SeedFromGlossaryResult(
        new GlossaryRef(sourceGlossary.id(), sourceGlossary.name()),
        new GlossaryRef(targetGlossary.id(), targetGlossary.name()),
        normalizeOptional(validatedInput.searchQuery()),
        sourceView.localeTags(),
        sourceView.terms().size(),
        sourceView.totalCount(),
        selectedTerms.size(),
        seedResult,
        selectedTerms.stream().map(this::toSelection).toList());
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (input.sourceGlossaryId() == null && normalizeOptional(input.sourceGlossaryName()) == null) {
      throw new IllegalArgumentException("sourceGlossaryId or sourceGlossaryName is required");
    }
    if (input.targetGlossaryId() == null && normalizeOptional(input.targetGlossaryName()) == null) {
      throw new IllegalArgumentException("targetGlossaryId or targetGlossaryName is required");
    }
    normalizeLimit(input.limit());
    normalizeStatuses(input.sourceStatuses());
    return input;
  }

  private GlossaryTermIndexCurationService.SeedTermInput toSeedTermInput(
      GlossaryManagementService.GlossaryDetail sourceGlossary, GlossaryTermService.TermView term) {
    return new GlossaryTermIndexCurationService.SeedTermInput(
        term.source(),
        null,
        TermIndexCandidate.SOURCE_TYPE_EXTERNAL,
        "glossary:" + sourceGlossary.id(),
        sourceExternalId(term),
        confidenceForStatus(term.status()),
        null,
        firstNonBlank(term.definition(), term.sourceComment()),
        "Imported from Mojito glossary '" + sourceGlossary.name() + "' for review.",
        term.termType(),
        term.partOfSpeech(),
        term.enforcement(),
        term.doNotTranslate(),
        null,
        null,
        null,
        null,
        null,
        sourceMetadata(sourceGlossary, term));
  }

  private Map<String, Object> sourceMetadata(
      GlossaryManagementService.GlossaryDetail sourceGlossary, GlossaryTermService.TermView term) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("sourceGlossaryId", sourceGlossary.id());
    metadata.put("sourceGlossaryName", sourceGlossary.name());
    metadata.put("sourceGlossaryTermTmTextUnitId", term.tmTextUnitId());
    metadata.put("sourceGlossaryTermKey", term.termKey());
    metadata.put("sourceGlossaryTermStatus", term.status());
    metadata.put("sourceGlossaryTermProvenance", term.provenance());
    metadata.put("sourceGlossaryTermIndexCandidateId", term.termIndexCandidateId());
    metadata.put("sourceGlossaryTermIndexExtractedTermId", term.termIndexExtractedTermId());
    return metadata;
  }

  private SourceTermSelection toSelection(GlossaryTermService.TermView term) {
    return new SourceTermSelection(
        term.tmTextUnitId(), term.termKey(), term.source(), term.status(), term.provenance());
  }

  private String sourceExternalId(GlossaryTermService.TermView term) {
    if (term.tmTextUnitId() != null) {
      return "glossary-term:" + term.tmTextUnitId();
    }
    String termKey = normalizeOptional(term.termKey());
    if (termKey != null) {
      return "glossary-term-key:" + termKey;
    }
    return null;
  }

  private boolean matchesSourceTermIds(
      GlossaryTermService.TermView term, Set<Long> sourceTmTextUnitIds) {
    if (sourceTmTextUnitIds.isEmpty()) {
      return true;
    }
    return term.tmTextUnitId() != null && sourceTmTextUnitIds.contains(term.tmTextUnitId());
  }

  private boolean matchesTermKeys(GlossaryTermService.TermView term, Set<String> termKeys) {
    if (termKeys.isEmpty()) {
      return true;
    }
    String termKey = normalizeOptional(term.termKey());
    return termKey != null && termKeys.contains(termKey.toLowerCase(Locale.ROOT));
  }

  private boolean matchesSourceStatus(
      GlossaryTermService.TermView term, Set<String> sourceStatuses) {
    String status = normalizeStatus(term.status());
    return status != null && sourceStatuses.contains(status);
  }

  private Integer confidenceForStatus(String status) {
    String normalizedStatus = normalizeStatus(status);
    if (normalizedStatus == null) {
      return 50;
    }
    return switch (normalizedStatus) {
      case GlossaryTermMetadata.STATUS_APPROVED -> 85;
      case GlossaryTermMetadata.STATUS_CANDIDATE -> 65;
      case GlossaryTermMetadata.STATUS_DEPRECATED -> 35;
      case GlossaryTermMetadata.STATUS_REJECTED -> 10;
      default -> 50;
    };
  }

  private int normalizeLimit(Integer limit) {
    int normalizedLimit = limit == null ? DEFAULT_LIMIT : limit;
    if (normalizedLimit < 1 || normalizedLimit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    return normalizedLimit;
  }

  private Set<String> normalizeStatuses(List<String> statuses) {
    List<String> effectiveStatuses =
        statuses == null || statuses.isEmpty() ? DEFAULT_SOURCE_STATUSES : statuses;
    LinkedHashSet<String> normalizedStatuses = new LinkedHashSet<>();
    for (String status : effectiveStatuses) {
      String normalizedStatus = normalizeStatus(status);
      if (normalizedStatus == null || !GlossaryTermMetadata.STATUSES.contains(normalizedStatus)) {
        throw new IllegalArgumentException("Unsupported source status: " + status);
      }
      normalizedStatuses.add(normalizedStatus);
    }
    return normalizedStatuses;
  }

  private String normalizeStatus(String status) {
    String normalized = normalizeOptional(status);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private Set<String> normalizeStringSet(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .map(this::normalizeOptional)
        .filter(Objects::nonNull)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<Long> normalizeLongSet(List<Long> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String firstNonBlank(String left, String right) {
    String normalizedLeft = normalizeOptional(left);
    return normalizedLeft == null ? normalizeOptional(right) : normalizedLeft;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static Map<String, Object> stringArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
  }

  private static Map<String, Object> integerArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "integer"));
  }
}
