package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;

public class ReviewGlossaryTermPlanMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final ReviewGlossaryTermPlanMcpTool tool =
      new ReviewGlossaryTermPlanMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), glossaryMcpSupport, glossaryTermService);

  @Test
  public void executeDetectsExistingSourceDuplicate() {
    Object result =
        tool.execute(
            new ReviewGlossaryTermPlanMcpTool.Input(
                4L,
                null,
                List.of(new ReviewGlossaryTermPlanMcpTool.TermInput(null, null, "actions"))));

    assertThat(result)
        .isEqualTo(
            new ReviewGlossaryTermPlanMcpTool.ReviewPlanResult(
                new ReviewGlossaryTermPlanMcpTool.GlossaryRef(4L, "g4"),
                1,
                false,
                List.of(
                    new ReviewGlossaryTermPlanMcpTool.TermPlan(
                        0,
                        "REVIEW_DUPLICATE",
                        null,
                        null,
                        "actions",
                        List.of("Existing glossary term match found. Review before writing."),
                        List.of(
                            new ReviewGlossaryTermPlanMcpTool.ExistingTermMatch(
                                "SOURCE", 20L, "actions", "Actions", "CANDIDATE"))))));
  }

  @Test
  public void executeAllowsLargeSingleRequest() {
    List<ReviewGlossaryTermPlanMcpTool.TermInput> terms =
        IntStream.range(0, 201).mapToObj(i -> termInput("term " + i)).toList();

    ReviewGlossaryTermPlanMcpTool.ReviewPlanResult result =
        (ReviewGlossaryTermPlanMcpTool.ReviewPlanResult)
            tool.execute(new ReviewGlossaryTermPlanMcpTool.Input(4L, null, terms));

    assertThat(result.proposedTermCount()).isEqualTo(201);
    assertThat(result.plan()).hasSize(201);
  }

  @Test
  public void executeRejectsOverLargeRequestLimit() {
    List<ReviewGlossaryTermPlanMcpTool.TermInput> terms =
        IntStream.range(0, 1001).mapToObj(i -> termInput("term " + i)).toList();

    assertThatThrownBy(() -> tool.execute(new ReviewGlossaryTermPlanMcpTool.Input(4L, null, terms)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("terms must contain at most 1000 entries");
  }

  private ReviewGlossaryTermPlanMcpTool.TermInput termInput(String source) {
    return new ReviewGlossaryTermPlanMcpTool.TermInput(null, null, source);
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
    private final TermView termView =
        new TermView(
            10L,
            null,
            null,
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
