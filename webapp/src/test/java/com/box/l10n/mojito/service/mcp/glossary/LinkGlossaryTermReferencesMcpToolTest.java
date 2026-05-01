package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import java.util.List;
import org.junit.Test;

public class LinkGlossaryTermReferencesMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final LinkGlossaryTermReferencesMcpTool tool =
      new LinkGlossaryTermReferencesMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), glossaryMcpSupport, glossaryTermService);

  @Test
  public void executeAppendsReferencesByTermKey() {
    Object result =
        tool.execute(
            new LinkGlossaryTermReferencesMcpTool.Input(
                4L,
                null,
                null,
                "actions",
                List.of(
                    new LinkGlossaryTermReferencesMcpTool.EvidenceInput(
                        "STRING_USAGE",
                        "Used in onboarding CTA",
                        null,
                        281663L,
                        null,
                        null,
                        null,
                        null))));

    assertThat(glossaryTermService.lastGlossaryId).isEqualTo(4L);
    assertThat(glossaryTermService.lastTmTextUnitId).isEqualTo(20L);
    assertThat(glossaryTermService.lastEvidence)
        .containsExactly(
            new GlossaryTermService.EvidenceInput(
                "STRING_USAGE", "Used in onboarding CTA", null, 281663L, null, null, null, null));
    assertThat(result)
        .isEqualTo(
            new LinkGlossaryTermReferencesMcpTool.LinkReferencesResult(
                new LinkGlossaryTermReferencesMcpTool.GlossaryRef(4L, "g4"),
                glossaryTermService.termView));
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
    private Long lastGlossaryId;
    private Long lastTmTextUnitId;
    private List<EvidenceInput> lastEvidence;
    private final TermView termView =
        new TermView(
            10L,
            null,
            null,
            20L,
            "actions",
            "Actions",
            "Source comment",
            "Source comment",
            null,
            "GENERAL",
            "SOFT",
            "CANDIDATE",
            "AUTOMATED",
            false,
            false,
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
      return new SearchTermsView(List.of(termView), 1, List.of());
    }

    @Override
    public TermView appendTermEvidence(
        Long glossaryId, Long tmTextUnitId, List<EvidenceInput> evidenceInputs) {
      lastGlossaryId = glossaryId;
      lastTmTextUnitId = tmTextUnitId;
      lastEvidence = evidenceInputs;
      return termView;
    }
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
