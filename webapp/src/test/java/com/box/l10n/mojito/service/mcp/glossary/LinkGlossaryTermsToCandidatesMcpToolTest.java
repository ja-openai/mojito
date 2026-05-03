package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class LinkGlossaryTermsToCandidatesMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService =
      Mockito.mock(GlossaryTermIndexCurationService.class);
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final LinkGlossaryTermsToCandidatesMcpTool tool =
      new LinkGlossaryTermsToCandidatesMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(),
          glossaryMcpSupport,
          glossaryTermIndexCurationService);

  @Test
  public void executeLinksTermsToCandidatesByResolvedGlossary() {
    GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesResult expectedResult =
        new GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesResult(
            4L, "target", true, "search", 1, 1, 0, 0, 0, 0, 0, List.of());
    when(glossaryTermIndexCurationService.linkGlossaryTermsToCandidates(
            eq(4L),
            Mockito.any(
                GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesCommand.class)))
        .thenReturn(expectedResult);

    Object result =
        tool.execute(
            new LinkGlossaryTermsToCandidatesMcpTool.Input(
                null, "target", true, "search", 25, true, false));

    assertThat(result).isSameAs(expectedResult);
    ArgumentCaptor<GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesCommand>
        commandCaptor =
            ArgumentCaptor.forClass(
                GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesCommand.class);
    verify(glossaryTermIndexCurationService)
        .linkGlossaryTermsToCandidates(eq(4L), commandCaptor.capture());
    assertThat(commandCaptor.getValue().dryRun()).isTrue();
    assertThat(commandCaptor.getValue().searchQuery()).isEqualTo("search");
    assertThat(commandCaptor.getValue().limit()).isEqualTo(25);
    assertThat(commandCaptor.getValue().overwriteExistingLinks()).isTrue();
    assertThat(commandCaptor.getValue().allowAmbiguousMatches()).isFalse();
  }

  private static final class FakeGlossaryManagementService extends GlossaryManagementService {
    private FakeGlossaryManagementService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public SearchGlossariesView searchGlossaries(
        String searchQuery, Boolean enabled, Integer limit) {
      return new SearchGlossariesView(List.of(glossarySummary(4L, "target")), 1);
    }

    @Override
    public GlossaryDetail getGlossary(Long glossaryId) {
      return glossaryDetail(glossaryId, "target");
    }
  }

  private static GlossaryManagementService.GlossarySummary glossarySummary(Long id, String name) {
    return new GlossaryManagementService.GlossarySummary(
        id,
        null,
        null,
        name,
        null,
        true,
        0,
        "GLOBAL",
        "glossary",
        1,
        new GlossaryManagementService.RepositoryRef(id + 10, "glossary-" + name));
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
