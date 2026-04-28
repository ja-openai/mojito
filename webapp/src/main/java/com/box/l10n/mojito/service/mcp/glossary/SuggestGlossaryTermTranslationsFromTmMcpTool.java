package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SuggestGlossaryTermTranslationsFromTmMcpTool
    extends TypedMcpToolHandler<SuggestGlossaryTermTranslationsFromTmMcpTool.Input> {

  private static final int DEFAULT_TERM_LIMIT = 100;
  private static final int MAX_TERM_LIMIT = 500;
  private static final int DEFAULT_MATCH_LIMIT = 100;
  private static final int MAX_MATCH_LIMIT = 500;
  private static final int MAX_SAMPLE_TEXT_UNIT_IDS = 5;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term.suggest_translations_from_tm",
          "Suggest glossary term translations from TM",
          "Suggest target glossary translations by searching existing Mojito text-unit translations for exact source-term matches. Review suggestions before writing them with glossary.term.bulk_upsert.",
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
                  "localeTags",
                  "Locales to mine for target term suggestions.",
                  true,
                  stringArraySchema("Locales to mine for target term suggestions.")),
              new McpToolParameter(
                  "repositoryNames",
                  "Product repositories to search. Defaults to selected glossary repositories when available.",
                  false,
                  stringArraySchema(
                      "Product repositories to search. Defaults to selected glossary repositories when available.")),
              new McpToolParameter(
                  "tmTextUnitIds",
                  "Optional glossary source tmTextUnitIds to restrict suggestions to.",
                  false,
                  integerArraySchema(
                      "Optional glossary source tmTextUnitIds to restrict suggestions to.")),
              new McpToolParameter(
                  "sources",
                  "Optional source terms to restrict suggestions to.",
                  false,
                  stringArraySchema("Optional source terms to restrict suggestions to.")),
              new McpToolParameter(
                  "termLimit",
                  "Max glossary terms to inspect when tmTextUnitIds and sources are omitted. Defaults to 100, max 500.",
                  false,
                  Integer.class),
              new McpToolParameter(
                  "matchLimit",
                  "Max product text-unit matches per term and locale. Defaults to 100, max 500.",
                  false,
                  Integer.class)));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;
  private final TextUnitSearcher textUnitSearcher;

  public SuggestGlossaryTermTranslationsFromTmMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService,
      TextUnitSearcher textUnitSearcher) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
    this.textUnitSearcher = Objects.requireNonNull(textUnitSearcher);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      List<String> localeTags,
      List<String> repositoryNames,
      List<Long> tmTextUnitIds,
      List<String> sources,
      Integer termLimit,
      Integer matchLimit) {}

  public record SuggestTranslationsResult(
      GlossaryRef glossary,
      List<String> repositoryNames,
      List<String> localeTags,
      int inspectedTermCount,
      List<TermSuggestion> terms) {}

  public record GlossaryRef(Long id, String name) {}

  public record TermSuggestion(
      Long tmTextUnitId,
      String termKey,
      String source,
      boolean doNotTranslate,
      List<LocaleSuggestion> locales) {}

  public record LocaleSuggestion(String localeTag, List<TargetSuggestion> suggestions) {}

  public record TargetSuggestion(
      String target, int occurrenceCount, List<String> statuses, List<Long> sampleTmTextUnitIds) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.glossaryId(), validatedInput.glossaryName());
    List<String> repositoryNames =
        resolveRepositoryNames(glossary, validatedInput.repositoryNames());
    int termLimit = normalizeLimit(validatedInput.termLimit(), DEFAULT_TERM_LIMIT, MAX_TERM_LIMIT);
    int matchLimit =
        normalizeLimit(validatedInput.matchLimit(), DEFAULT_MATCH_LIMIT, MAX_MATCH_LIMIT);
    List<GlossaryTermService.TermView> terms =
        filterTerms(
            glossaryTermService.searchTerms(glossary.id(), null, List.of(), termLimit).terms(),
            validatedInput.tmTextUnitIds(),
            validatedInput.sources());

    List<TermSuggestion> termSuggestions =
        terms.stream()
            .map(
                term ->
                    toTermSuggestion(
                        term, validatedInput.localeTags(), repositoryNames, matchLimit))
            .filter(
                term -> term.locales().stream().anyMatch(locale -> !locale.suggestions().isEmpty()))
            .toList();

    return new SuggestTranslationsResult(
        new GlossaryRef(glossary.id(), glossary.name()),
        repositoryNames,
        validatedInput.localeTags(),
        terms.size(),
        termSuggestions);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    List<String> localeTags = normalizeStringList(input.localeTags());
    if (localeTags.isEmpty()) {
      throw new IllegalArgumentException("localeTags are required");
    }
    return new Input(
        input.glossaryId(),
        input.glossaryName(),
        localeTags,
        normalizeStringList(input.repositoryNames()),
        normalizeLongList(input.tmTextUnitIds()),
        normalizeStringList(input.sources()),
        input.termLimit(),
        input.matchLimit());
  }

  private List<String> resolveRepositoryNames(
      GlossaryManagementService.GlossaryDetail glossary, List<String> repositoryNames) {
    if (!repositoryNames.isEmpty()) {
      return repositoryNames;
    }
    List<String> glossaryRepositoryNames =
        glossary.repositories().stream()
            .map(GlossaryManagementService.RepositoryRef::name)
            .filter(Objects::nonNull)
            .toList();
    if (!glossaryRepositoryNames.isEmpty()) {
      return glossaryRepositoryNames;
    }
    throw new IllegalArgumentException(
        "repositoryNames are required when the glossary has no selected repositories");
  }

  private List<GlossaryTermService.TermView> filterTerms(
      List<GlossaryTermService.TermView> terms, List<Long> tmTextUnitIds, List<String> sources) {
    if (tmTextUnitIds.isEmpty() && sources.isEmpty()) {
      return terms;
    }
    Set<Long> tmTextUnitIdSet = new LinkedHashSet<>(tmTextUnitIds);
    Set<String> sourceKeys =
        sources.stream().map(this::normalizeKey).collect(java.util.stream.Collectors.toSet());
    return terms.stream()
        .filter(
            term ->
                tmTextUnitIdSet.contains(term.tmTextUnitId())
                    || sourceKeys.contains(normalizeKey(term.source())))
        .toList();
  }

  private TermSuggestion toTermSuggestion(
      GlossaryTermService.TermView term,
      List<String> localeTags,
      List<String> repositoryNames,
      int matchLimit) {
    List<LocaleSuggestion> locales =
        localeTags.stream()
            .map(
                localeTag ->
                    toLocaleSuggestion(term.source(), localeTag, repositoryNames, matchLimit))
            .toList();
    return new TermSuggestion(
        term.tmTextUnitId(), term.termKey(), term.source(), term.doNotTranslate(), locales);
  }

  private LocaleSuggestion toLocaleSuggestion(
      String source, String localeTag, List<String> repositoryNames, int matchLimit) {
    if (source == null || source.isBlank()) {
      return new LocaleSuggestion(localeTag, List.of());
    }
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setRepositoryNames(repositoryNames);
    parameters.setLocaleTags(List.of(localeTag));
    parameters.setPluralFormsFiltered(false);
    parameters.setLimit(matchLimit);
    parameters.setTextSearch(sourceTextSearch(source));

    Map<String, MutableTargetSuggestion> suggestionsByTarget = new LinkedHashMap<>();
    for (TextUnitDTO match : textUnitSearcher.search(parameters)) {
      String target = normalizeOptional(match.getTarget());
      if (target == null) {
        continue;
      }
      suggestionsByTarget.computeIfAbsent(target, MutableTargetSuggestion::new).add(match);
    }

    List<TargetSuggestion> suggestions =
        suggestionsByTarget.values().stream()
            .map(MutableTargetSuggestion::toSuggestion)
            .sorted(
                Comparator.comparingInt(TargetSuggestion::occurrenceCount)
                    .reversed()
                    .thenComparing(TargetSuggestion::target, String.CASE_INSENSITIVE_ORDER))
            .toList();
    return new LocaleSuggestion(localeTag, suggestions);
  }

  private TextUnitTextSearch sourceTextSearch(String source) {
    TextUnitTextSearchPredicate predicate = new TextUnitTextSearchPredicate();
    predicate.setField(TextUnitTextSearchField.SOURCE);
    predicate.setSearchType(SearchType.EXACT);
    predicate.setValue(source.trim());
    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setPredicates(List.of(predicate));
    return textSearch;
  }

  private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
    int normalized = limit == null ? defaultLimit : limit;
    if (normalized < 1 || normalized > maxLimit) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + maxLimit + ": " + normalized);
    }
    return normalized;
  }

  private List<String> normalizeStringList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .map(this::normalizeOptional)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private List<Long> normalizeLongList(List<Long> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream().filter(value -> value != null && value > 0).distinct().toList();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeKey(String value) {
    String normalized = normalizeOptional(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private static Map<String, Object> stringArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
  }

  private static Map<String, Object> integerArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "integer"));
  }

  private static final class MutableTargetSuggestion {
    private final String target;
    private int occurrenceCount;
    private final Set<String> statuses = new LinkedHashSet<>();
    private final List<Long> sampleTmTextUnitIds = new ArrayList<>();

    private MutableTargetSuggestion(String target) {
      this.target = target;
    }

    private void add(TextUnitDTO match) {
      occurrenceCount++;
      if (match.getStatus() != null) {
        statuses.add(match.getStatus().name());
      }
      if (match.getTmTextUnitId() != null
          && sampleTmTextUnitIds.size() < MAX_SAMPLE_TEXT_UNIT_IDS) {
        sampleTmTextUnitIds.add(match.getTmTextUnitId());
      }
    }

    private TargetSuggestion toSuggestion() {
      return new TargetSuggestion(
          target,
          occurrenceCount,
          statuses.stream().sorted().toList(),
          List.copyOf(sampleTmTextUnitIds));
    }
  }
}
