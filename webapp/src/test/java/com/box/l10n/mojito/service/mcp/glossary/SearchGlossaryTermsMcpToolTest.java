package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import java.util.List;
import org.junit.Test;

public class SearchGlossaryTermsMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final SearchGlossaryTermsMcpTool tool =
      new SearchGlossaryTermsMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), glossaryMcpSupport, glossaryTermService);

  @Test
  public void executeSearchesResolvedGlossaryTerms() {
    Object result =
        tool.execute(
            new SearchGlossaryTermsMcpTool.Input(null, "g4", "actions", List.of("fr"), 25));

    assertThat(glossaryTermService.lastGlossaryId).isEqualTo(4L);
    assertThat(glossaryTermService.lastSearchQuery).isEqualTo("actions");
    assertThat(glossaryTermService.lastLocaleTags).containsExactly("fr");
    assertThat(glossaryTermService.lastLimit).isEqualTo(25);
    assertThat(result)
        .isEqualTo(
            new SearchGlossaryTermsMcpTool.SearchResult(
                new SearchGlossaryTermsMcpTool.GlossaryRef(4L, "g4"),
                "actions",
                List.of("fr"),
                1,
                List.of(glossaryTermService.term)));
  }

  private static final class FakeGlossaryManagementService extends GlossaryManagementService {
    private FakeGlossaryManagementService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public SearchGlossariesView searchGlossaries(
        String searchQuery, Boolean enabled, Integer limit) {
      return new SearchGlossariesView(List.of(glossarySummary()), 1);
    }

    @Override
    public GlossaryDetail getGlossary(Long glossaryId) {
      return glossaryDetail();
    }
  }

  private static final class FakeGlossaryTermService extends GlossaryTermService {
    private Long lastGlossaryId;
    private String lastSearchQuery;
    private List<String> lastLocaleTags;
    private Integer lastLimit;
    private final TermView term =
        new TermView(
            10L,
            20L,
            "actions",
            "Actions",
            null,
            null,
            null,
            "GENERAL",
            "SOFT",
            "CANDIDATE",
            "AUTOMATED",
            false,
            false,
            List.of(new TermTranslationView("fr", "Actions", null, "APPROVED")),
            List.of());

    private FakeGlossaryTermService() {
      super(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public SearchTermsView searchTerms(
        Long glossaryId, String searchQuery, List<String> localeTags, Integer limit) {
      lastGlossaryId = glossaryId;
      lastSearchQuery = searchQuery;
      lastLocaleTags = localeTags;
      lastLimit = limit;
      return new SearchTermsView(List.of(term), 1, List.of("fr"));
    }
  }

  private static GlossaryManagementService.GlossarySummary glossarySummary() {
    return new GlossaryManagementService.GlossarySummary(
        4L,
        null,
        null,
        "g4",
        null,
        true,
        0,
        "GLOBAL",
        "glossary",
        0,
        new GlossaryManagementService.RepositoryRef(14L, "glossary-g4"));
  }

  private static GlossaryManagementService.GlossaryDetail glossaryDetail() {
    return new GlossaryManagementService.GlossaryDetail(
        4L,
        null,
        null,
        "g4",
        null,
        true,
        0,
        "GLOBAL",
        new GlossaryManagementService.RepositoryRef(14L, "glossary-g4"),
        "glossary",
        List.of("fr"),
        List.of(),
        List.of());
  }
}
