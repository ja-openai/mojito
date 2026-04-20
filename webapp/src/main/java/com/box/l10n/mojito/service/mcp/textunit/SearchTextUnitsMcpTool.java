package com.box.l10n.mojito.service.mcp.textunit;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import com.box.l10n.mojito.utils.ServerConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SearchTextUnitsMcpTool extends TypedMcpToolHandler<SearchTextUnitsMcpTool.Input> {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "text_unit.search",
          "Search Mojito text units",
          "Search Mojito text units by repository, locale, field, and search mode. Supports regex matching to find malformed translations or control characters in source or target content.",
          true,
          true,
          List.of(
              new McpToolParameter("repository", "Mojito repository name to search within.", true),
              new McpToolParameter(
                  "locale", "Locale to search, for example fr, fr-FR, or hr_HR.", true),
              new McpToolParameter(
                  "field",
                  "Field to search: stringId, source, target, comment, asset, location, pluralFormOther, or tmTextUnitIds.",
                  true),
              new McpToolParameter("value", "Search value or regex pattern.", true),
              new McpToolParameter(
                  "searchType",
                  "Search mode: exact, contains, ilike, or regex. Defaults to exact.",
                  false),
              new McpToolParameter(
                  "limit", "Optional max results to return. Defaults to 50, max 500.", false),
              new McpToolParameter(
                  "offset", "Optional result offset for pagination. Defaults to 0.", false)));

  private final RepositoryRepository repositoryRepository;
  private final LocaleService localeService;
  private final TextUnitSearcher textUnitSearcher;
  private final ServerConfig serverConfig;

  public SearchTextUnitsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      RepositoryRepository repositoryRepository,
      LocaleService localeService,
      TextUnitSearcher textUnitSearcher,
      ServerConfig serverConfig) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.localeService = Objects.requireNonNull(localeService);
    this.textUnitSearcher = Objects.requireNonNull(textUnitSearcher);
    this.serverConfig = Objects.requireNonNull(serverConfig);
  }

  public record Input(
      String repository,
      String locale,
      String field,
      String value,
      String searchType,
      Integer limit,
      Integer offset) {}

  public record RepositoryRef(Long id, String name) {}

  public record LocaleRef(Long id, String bcp47Tag) {}

  public record SearchQuery(String field, String searchType, String value, int limit, int offset) {}

  public record TextUnitMatch(
      String repositoryName,
      Long tmTextUnitId,
      String textUnitLink,
      Long tmTextUnitCurrentVariantId,
      Long tmTextUnitVariantId,
      String stringId,
      String source,
      String comment,
      String target,
      String targetLocale,
      String targetComment,
      String status,
      boolean includedInLocalizedFile,
      boolean used,
      String assetPath,
      Long assetId,
      Long assetTextUnitId,
      String assetTextUnitUsages,
      Long branchId,
      ZonedDateTime createdDate) {}

  public record SearchResult(
      RepositoryRef repository,
      LocaleRef locale,
      SearchQuery query,
      int matchCount,
      List<TextUnitMatch> matches) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    TextUnitTextSearchField field = parseField(validatedInput.field());
    SearchType searchType = parseSearchType(validatedInput.searchType());
    int limit = normalizeLimit(validatedInput.limit());
    int offset = normalizeOffset(validatedInput.offset());
    Repository repository = resolveRepository(validatedInput.repository());
    Locale locale = resolveLocale(validatedInput.locale());

    TextUnitSearcherParameters searchParameters = new TextUnitSearcherParameters();
    searchParameters.setRepositoryNames(List.of(repository.getName()));
    searchParameters.setLocaleTags(List.of(locale.getBcp47Tag()));
    searchParameters.setPluralFormsFiltered(false);
    searchParameters.setLimit(limit);
    searchParameters.setOffset(offset);
    searchParameters.setTextSearch(buildTextSearch(field, searchType, validatedInput.value()));

    List<TextUnitMatch> matches =
        textUnitSearcher.search(searchParameters).stream().map(this::toMatch).toList();

    return new SearchResult(
        new RepositoryRef(repository.getId(), repository.getName()),
        new LocaleRef(locale.getId(), locale.getBcp47Tag()),
        new SearchQuery(
            field.getJsonValue(),
            searchType.name().toLowerCase(java.util.Locale.ROOT),
            validatedInput.value().trim(),
            limit,
            offset),
        matches.size(),
        matches);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }

    return new Input(
        requireNonBlank(input.repository(), "repository"),
        requireNonBlank(input.locale(), "locale"),
        requireNonBlank(input.field(), "field"),
        requireNonBlank(input.value(), "value"),
        normalizeOptional(input.searchType()),
        input.limit(),
        input.offset());
  }

  private Repository resolveRepository(String repositoryName) {
    Repository repository = repositoryRepository.findByName(repositoryName);
    if (repository == null) {
      throw new IllegalArgumentException("Unknown repository: " + repositoryName);
    }
    return repository;
  }

  private Locale resolveLocale(String localeTag) {
    String normalizedLocaleTag = localeTag.trim().replace('_', '-');
    Locale locale = localeService.findByBcp47Tag(normalizedLocaleTag);
    if (locale == null) {
      throw new IllegalArgumentException("Unknown locale: " + localeTag);
    }
    return locale;
  }

  private TextUnitTextSearch buildTextSearch(
      TextUnitTextSearchField field, SearchType searchType, String value) {
    TextUnitTextSearchPredicate predicate = new TextUnitTextSearchPredicate();
    predicate.setField(field);
    predicate.setSearchType(searchType);
    predicate.setValue(value.trim());

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setPredicates(List.of(predicate));
    return textSearch;
  }

  private TextUnitTextSearchField parseField(String field) {
    try {
      return TextUnitTextSearchField.fromJsonValue(field.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported field: " + field, e);
    }
  }

  private SearchType parseSearchType(String searchType) {
    if (searchType == null) {
      return SearchType.EXACT;
    }

    try {
      return SearchType.valueOf(searchType.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported searchType: " + searchType, e);
    }
  }

  private int normalizeLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_LIMIT : limit;
    if (normalized < 1 || normalized > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_LIMIT + ": " + normalized);
    }
    return normalized;
  }

  private int normalizeOffset(Integer offset) {
    int normalized = offset == null ? 0 : offset;
    if (normalized < 0) {
      throw new IllegalArgumentException("offset must be >= 0: " + normalized);
    }
    return normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private TextUnitMatch toMatch(TextUnitDTO textUnitDTO) {
    return new TextUnitMatch(
        textUnitDTO.getRepositoryName(),
        textUnitDTO.getTmTextUnitId(),
        buildTextUnitLink(textUnitDTO.getTmTextUnitId(), textUnitDTO.getTargetLocale()),
        textUnitDTO.getTmTextUnitCurrentVariantId(),
        textUnitDTO.getTmTextUnitVariantId(),
        textUnitDTO.getName(),
        textUnitDTO.getSource(),
        textUnitDTO.getComment(),
        textUnitDTO.getTarget(),
        textUnitDTO.getTargetLocale(),
        textUnitDTO.getTargetComment(),
        textUnitDTO.getStatus() == null ? null : textUnitDTO.getStatus().name(),
        textUnitDTO.isIncludedInLocalizedFile(),
        textUnitDTO.isUsed(),
        textUnitDTO.getAssetPath(),
        textUnitDTO.getAssetId(),
        textUnitDTO.getAssetTextUnitId(),
        textUnitDTO.getAssetTextUnitUsages(),
        textUnitDTO.getBranchId(),
        textUnitDTO.getCreatedDate());
  }

  private String buildTextUnitLink(Long tmTextUnitId, String localeTag) {
    String baseUrl = normalizeServerBaseUrl();
    if (tmTextUnitId == null || baseUrl == null) {
      return null;
    }

    String textUnitLink = baseUrl + "/text-units/" + tmTextUnitId;
    if (localeTag == null || localeTag.isBlank()) {
      return textUnitLink;
    }
    return textUnitLink + "?locale=" + URLEncoder.encode(localeTag.trim(), StandardCharsets.UTF_8);
  }

  private String normalizeServerBaseUrl() {
    String configured = serverConfig.getUrl();
    if (configured == null || configured.isBlank()) {
      return null;
    }

    String trimmed = configured.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isBlank() ? null : trimmed;
  }
}
