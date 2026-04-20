package com.box.l10n.mojito.service.mcp.textunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class SearchTextUnitsMcpToolTest {

  private final RepositoryRepository repositoryRepository =
      Mockito.mock(RepositoryRepository.class);
  private final LocaleService localeService = Mockito.mock(LocaleService.class);
  private final TextUnitSearcher textUnitSearcher = Mockito.mock(TextUnitSearcher.class);
  private final ServerConfig serverConfig = Mockito.mock(ServerConfig.class);
  private final SearchTextUnitsMcpTool tool =
      new SearchTextUnitsMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(),
          repositoryRepository,
          localeService,
          textUnitSearcher,
          serverConfig);

  @Test
  public void executeSearchesTextUnitsWithRegexAndReturnsCompactMatches() {
    Repository repository = Mockito.mock(Repository.class);
    when(repository.getId()).thenReturn(7L);
    when(repository.getName()).thenReturn("privacy-transcend");
    when(repositoryRepository.findByName("privacy-transcend")).thenReturn(repository);

    Locale locale = Mockito.mock(Locale.class);
    when(locale.getId()).thenReturn(950L);
    when(locale.getBcp47Tag()).thenReturn("fr");
    when(localeService.findByBcp47Tag("fr")).thenReturn(locale);

    when(serverConfig.getUrl()).thenReturn("https://mojito.example/");

    TextUnitDTO dto = new TextUnitDTO();
    dto.setRepositoryName("privacy-transcend");
    dto.setTmTextUnitId(281663L);
    dto.setTmTextUnitCurrentVariantId(981L);
    dto.setTmTextUnitVariantId(982L);
    dto.setName("ui.src.noticeAndDoNotSell.doNotSellHonoredDescription");
    dto.setSource("source");
    dto.setComment("comment");
    dto.setTarget("bad\u0000target");
    dto.setTargetLocale("fr");
    dto.setTargetComment("target comment");
    dto.setStatus(TMTextUnitVariant.Status.APPROVED);
    dto.setIncludedInLocalizedFile(true);
    dto.setAssetId(1588L);
    dto.setAssetTextUnitId(201L);
    dto.setAssetTextUnitUsages("line 10187");
    dto.setAssetPath("lang-mojito/en.json");
    dto.setBranchId(1987L);
    dto.setLastSuccessfulAssetExtractionId(33L);
    dto.setAssetExtractionId(33L);
    dto.setCreatedDate(ZonedDateTime.parse("2026-04-20T18:00:00Z"));

    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of(dto));

    Object result =
        tool.execute(
            new SearchTextUnitsMcpTool.Input(
                "privacy-transcend", "fr", "target", "\\u0000", "regex", 25, 5));

    ArgumentCaptor<TextUnitSearcherParameters> captor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(captor.capture());
    TextUnitSearcherParameters parameters = captor.getValue();

    assertThat(parameters.getRepositoryNames()).containsExactly("privacy-transcend");
    assertThat(parameters.getLocaleTags()).containsExactly("fr");
    assertThat(parameters.getLimit()).isEqualTo(25);
    assertThat(parameters.getOffset()).isEqualTo(5);
    assertThat(parameters.getTextSearch()).isNotNull();
    assertThat(parameters.getTextSearch().getPredicates()).hasSize(1);
    assertThat(parameters.getTextSearch().getPredicates().get(0).getField().getJsonValue())
        .isEqualTo("target");
    assertThat(parameters.getTextSearch().getPredicates().get(0).getSearchType().name())
        .isEqualTo("REGEX");
    assertThat(parameters.getTextSearch().getPredicates().get(0).getValue()).isEqualTo("\\u0000");

    assertThat(result)
        .isEqualTo(
            new SearchTextUnitsMcpTool.SearchResult(
                new SearchTextUnitsMcpTool.RepositoryRef(7L, "privacy-transcend"),
                new SearchTextUnitsMcpTool.LocaleRef(950L, "fr"),
                new SearchTextUnitsMcpTool.SearchQuery("target", "regex", "\\u0000", 25, 5),
                1,
                List.of(
                    new SearchTextUnitsMcpTool.TextUnitMatch(
                        "privacy-transcend",
                        281663L,
                        "https://mojito.example/text-units/281663?locale=fr",
                        981L,
                        982L,
                        "ui.src.noticeAndDoNotSell.doNotSellHonoredDescription",
                        "source",
                        "comment",
                        "bad\u0000target",
                        "fr",
                        "target comment",
                        "APPROVED",
                        true,
                        true,
                        "lang-mojito/en.json",
                        1588L,
                        201L,
                        "line 10187",
                        1987L,
                        ZonedDateTime.parse("2026-04-20T18:00:00Z")))));
  }

  @Test
  public void executeRejectsUnknownSearchType() {
    assertThatThrownBy(
            () ->
                tool.execute(
                    new SearchTextUnitsMcpTool.Input(
                        "privacy-transcend", "fr", "target", "x", "wildcard", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported searchType: wildcard");
  }

  @Test
  public void executeRequiresRepository() {
    assertThatThrownBy(
            () ->
                tool.execute(
                    new SearchTextUnitsMcpTool.Input(null, "fr", "target", "x", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("repository is required");
  }
}
