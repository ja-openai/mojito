package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class SeedTermIndexCandidatesFromGlossaryMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService =
      Mockito.mock(GlossaryTermIndexCurationService.class);
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final SeedTermIndexCandidatesFromGlossaryMcpTool tool =
      new SeedTermIndexCandidatesFromGlossaryMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(),
          glossaryMcpSupport,
          glossaryTermService,
          glossaryTermIndexCurationService);

  @Test
  public void executeSeedsTargetCandidatesFromSourceGlossaryTerms() {
    when(glossaryTermIndexCurationService.seedTermsForGlossary(
            eq(8L), any(GlossaryTermIndexCurationService.SeedCommand.class)))
        .thenReturn(
            new GlossaryTermIndexCurationService.SeedResult(
                1,
                1,
                0,
                List.of(
                    new GlossaryTermIndexCurationService.SeededTermView(
                        91L,
                        45L,
                        "Actions",
                        "actions",
                        null,
                        "Things a user can do.",
                        "Imported from Mojito glossary 'source' for review.",
                        "GENERAL",
                        null,
                        "SOFT",
                        false,
                        85))));

    SeedTermIndexCandidatesFromGlossaryMcpTool.SeedFromGlossaryResult result =
        (SeedTermIndexCandidatesFromGlossaryMcpTool.SeedFromGlossaryResult)
            tool.execute(
                new SeedTermIndexCandidatesFromGlossaryMcpTool.Input(
                    4L, null, 8L, null, "act", List.of("fr"), null, null, null, 50));

    assertThat(glossaryTermService.lastGlossaryId).isEqualTo(4L);
    assertThat(glossaryTermService.lastSearchQuery).isEqualTo("act");
    assertThat(glossaryTermService.lastLocaleTags).containsExactly("fr");
    assertThat(glossaryTermService.lastLimit).isEqualTo(50);
    assertThat(result.sourceGlossary())
        .isEqualTo(new SeedTermIndexCandidatesFromGlossaryMcpTool.GlossaryRef(4L, "source"));
    assertThat(result.targetGlossary())
        .isEqualTo(new SeedTermIndexCandidatesFromGlossaryMcpTool.GlossaryRef(8L, "target"));
    assertThat(result.selectedTermCount()).isEqualTo(1);
    assertThat(result.seedResult().createdCandidateCount()).isEqualTo(1);

    ArgumentCaptor<GlossaryTermIndexCurationService.SeedCommand> commandCaptor =
        ArgumentCaptor.forClass(GlossaryTermIndexCurationService.SeedCommand.class);
    verify(glossaryTermIndexCurationService).seedTermsForGlossary(eq(8L), commandCaptor.capture());
    GlossaryTermIndexCurationService.SeedTermInput seedTerm =
        commandCaptor.getValue().terms().get(0);
    assertThat(seedTerm.term()).isEqualTo("Actions");
    assertThat(seedTerm.sourceType()).isEqualTo("EXTERNAL");
    assertThat(seedTerm.sourceName()).isEqualTo("glossary:4");
    assertThat(seedTerm.sourceExternalId()).isEqualTo("glossary-term:20");
    assertThat(seedTerm.confidence()).isEqualTo(85);
    assertThat(seedTerm.definition()).isEqualTo("Things a user can do.");
    assertThat(seedTerm.reviewStatus()).isNull();
    assertThat(seedTerm.metadata())
        .containsEntry("sourceGlossaryId", 4L)
        .containsEntry("sourceGlossaryTermTmTextUnitId", 20L);
  }

  @Test
  public void executeFiltersSourceStatusByDefault() {
    when(glossaryTermIndexCurationService.seedTermsForGlossary(
            eq(8L), any(GlossaryTermIndexCurationService.SeedCommand.class)))
        .thenReturn(new GlossaryTermIndexCurationService.SeedResult(1, 1, 0, List.of()));

    tool.execute(
        new SeedTermIndexCandidatesFromGlossaryMcpTool.Input(
            4L, null, 8L, null, null, null, null, null, null, null));

    ArgumentCaptor<GlossaryTermIndexCurationService.SeedCommand> commandCaptor =
        ArgumentCaptor.forClass(GlossaryTermIndexCurationService.SeedCommand.class);
    verify(glossaryTermIndexCurationService).seedTermsForGlossary(eq(8L), commandCaptor.capture());
    assertThat(commandCaptor.getValue().terms()).hasSize(1);
    assertThat(commandCaptor.getValue().terms().get(0).term()).isEqualTo("Actions");
  }

  @Test
  public void executeRejectsInvalidLimit() {
    assertThatThrownBy(
            () ->
                tool.execute(
                    new SeedTermIndexCandidatesFromGlossaryMcpTool.Input(
                        4L, null, 8L, null, null, null, null, null, null, 1001)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be between 1 and 1000");
  }

  private static final class FakeGlossaryManagementService extends GlossaryManagementService {
    private FakeGlossaryManagementService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public GlossaryDetail getGlossary(Long glossaryId) {
      return glossaryId == 4L ? glossaryDetail(4L, "source") : glossaryDetail(8L, "target");
    }
  }

  private static final class FakeGlossaryTermService extends GlossaryTermService {
    private Long lastGlossaryId;
    private String lastSearchQuery;
    private List<String> lastLocaleTags;
    private Integer lastLimit;
    private final TermView approvedTerm =
        new TermView(
            10L,
            null,
            null,
            20L,
            "actions",
            "Actions",
            null,
            "Things a user can do.",
            null,
            "GENERAL",
            "SOFT",
            "APPROVED",
            "MANUAL",
            false,
            false,
            null,
            45L,
            null,
            null,
            List.of(new TermTranslationView("fr", "Actions", null, "APPROVED")),
            List.of());
    private final TermView rejectedTerm =
        new TermView(
            11L,
            null,
            null,
            21L,
            "skip",
            "Skip",
            null,
            null,
            null,
            "GENERAL",
            "SOFT",
            "REJECTED",
            "MANUAL",
            false,
            false,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    private FakeGlossaryTermService() {
      super(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null);
    }

    @Override
    public SearchTermsView searchTerms(
        Long glossaryId, String searchQuery, List<String> localeTags, Integer limit) {
      lastGlossaryId = glossaryId;
      lastSearchQuery = searchQuery;
      lastLocaleTags = localeTags;
      lastLimit = limit;
      return new SearchTermsView(List.of(approvedTerm, rejectedTerm), 2, List.of("fr"));
    }
  }

  private static GlossaryManagementService.GlossaryDetail glossaryDetail(Long id, String name) {
    return new GlossaryManagementService.GlossaryDetail(
        id,
        null,
        null,
        name,
        null,
        true,
        0,
        "GLOBAL",
        new GlossaryManagementService.RepositoryRef(id + 10, "glossary-" + name),
        "glossary",
        List.of("fr"),
        List.of(),
        List.of());
  }
}
