package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import org.junit.Test;

public class SuggestGlossaryTermTranslationsFromTmMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final FakeTextUnitSearcher textUnitSearcher = new FakeTextUnitSearcher();
  private final SuggestGlossaryTermTranslationsFromTmMcpTool tool =
      new SuggestGlossaryTermTranslationsFromTmMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(),
          glossaryMcpSupport,
          glossaryTermService,
          textUnitSearcher);

  @Test
  public void executeSuggestsTargetsFromExactSourceMatches() {
    Object result =
        tool.execute(
            new SuggestGlossaryTermTranslationsFromTmMcpTool.Input(
                6L, null, List.of("fr"), List.of("chatgpt-web"), List.of(), List.of(), null, null));

    assertThat(textUnitSearcher.lastRepositoryNames).containsExactly("chatgpt-web");
    assertThat(textUnitSearcher.lastLocaleTags).containsExactly("fr");
    assertThat(textUnitSearcher.lastLimit).isEqualTo(100);
    assertThat(result)
        .isEqualTo(
            new SuggestGlossaryTermTranslationsFromTmMcpTool.SuggestTranslationsResult(
                new SuggestGlossaryTermTranslationsFromTmMcpTool.GlossaryRef(6L, "ghanna"),
                List.of("chatgpt-web"),
                List.of("fr"),
                1,
                List.of(
                    new SuggestGlossaryTermTranslationsFromTmMcpTool.TermSuggestion(
                        20L,
                        "sora_478958c8",
                        "Sora",
                        true,
                        List.of(
                            new SuggestGlossaryTermTranslationsFromTmMcpTool.LocaleSuggestion(
                                "fr",
                                List.of(
                                    new SuggestGlossaryTermTranslationsFromTmMcpTool
                                        .TargetSuggestion(
                                        "Sora",
                                        2,
                                        List.of("APPROVED", "REVIEW_NEEDED"),
                                        List.of(100L, 101L)))))))));
  }

  private static final class FakeGlossaryManagementService extends GlossaryManagementService {
    private FakeGlossaryManagementService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public GlossaryDetail getGlossary(Long glossaryId) {
      return glossaryDetail();
    }
  }

  private static final class FakeGlossaryTermService extends GlossaryTermService {
    private FakeGlossaryTermService() {
      super(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null);
    }

    @Override
    public SearchTermsView searchTerms(
        Long glossaryId, String searchQuery, List<String> localeTags, Integer limit) {
      return new SearchTermsView(
          List.of(
              new TermView(
                  10L,
                  null,
                  null,
                  20L,
                  "sora_478958c8",
                  "Sora",
                  "Named OpenAI product",
                  "Named OpenAI product",
                  "proper noun",
                  "PRODUCT",
                  "SOFT",
                  "CANDIDATE",
                  "AI_EXTRACTED",
                  true,
                  true,
                  null,
                  null,
                  List.of(),
                  List.of())),
          1,
          List.of("fr"));
    }
  }

  private static final class FakeTextUnitSearcher extends TextUnitSearcher {
    private List<String> lastRepositoryNames;
    private List<String> lastLocaleTags;
    private Integer lastLimit;

    @Override
    public List<TextUnitDTO> search(TextUnitSearcherParameters searchParameters) {
      lastRepositoryNames = searchParameters.getRepositoryNames();
      lastLocaleTags = searchParameters.getLocaleTags();
      lastLimit = searchParameters.getLimit();
      return List.of(
          textUnit(100L, "Sora", TMTextUnitVariant.Status.APPROVED),
          textUnit(101L, "Sora", TMTextUnitVariant.Status.REVIEW_NEEDED));
    }
  }

  private static TextUnitDTO textUnit(
      Long tmTextUnitId, String target, TMTextUnitVariant.Status status) {
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setTmTextUnitId(tmTextUnitId);
    textUnitDTO.setTarget(target);
    textUnitDTO.setStatus(status);
    return textUnitDTO;
  }

  private static GlossaryManagementService.GlossaryDetail glossaryDetail() {
    return new GlossaryManagementService.GlossaryDetail(
        6L,
        null,
        null,
        "ghanna",
        "ChatGPT web glossary",
        true,
        10,
        "SELECTED_REPOSITORIES",
        new GlossaryManagementService.RepositoryRef(14L, "glossary-ghanna"),
        "glossary",
        List.of("fr"),
        List.of(new GlossaryManagementService.RepositoryRef(2L, "chatgpt-web")),
        List.of());
  }
}
