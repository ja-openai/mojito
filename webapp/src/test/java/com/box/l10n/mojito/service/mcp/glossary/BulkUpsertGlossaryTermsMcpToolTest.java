package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;

public class BulkUpsertGlossaryTermsMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final FakeGlossaryTermService glossaryTermService = new FakeGlossaryTermService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final BulkUpsertGlossaryTermsMcpTool tool =
      new BulkUpsertGlossaryTermsMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), glossaryMcpSupport, glossaryTermService);

  @Test
  public void executeDefaultsToDryRunAndDoesNotUpsert() {
    Object result =
        tool.execute(
            new BulkUpsertGlossaryTermsMcpTool.Input(
                4L, null, null, List.of(termInput(null, "Actions"))));

    assertThat(result)
        .isEqualTo(
            new BulkUpsertGlossaryTermsMcpTool.BulkUpsertResult(
                new BulkUpsertGlossaryTermsMcpTool.GlossaryRef(4L, "g4"),
                true,
                false,
                1,
                List.of(
                    new BulkUpsertGlossaryTermsMcpTool.OperationPreview(
                        0, null, "actions", "Actions", "CANDIDATE", "AUTOMATED", 1, 1, 1)),
                List.of()));
    assertThat(glossaryTermService.upsertCallCount).isZero();
  }

  @Test
  public void executeAppliesTermsWithTranslationsAndLinkedTextUnitEvidence() {
    Object result =
        tool.execute(
            new BulkUpsertGlossaryTermsMcpTool.Input(
                4L, null, false, List.of(termInput(20L, "Actions"))));

    assertThat(glossaryTermService.upsertCallCount).isEqualTo(1);
    assertThat(glossaryTermService.lastGlossaryId).isEqualTo(4L);
    assertThat(glossaryTermService.lastTmTextUnitId).isEqualTo(20L);
    assertThat(glossaryTermService.lastCommand.source()).isEqualTo("Actions");
    assertThat(glossaryTermService.lastCommand.translations())
        .containsExactly(new GlossaryTermService.TranslationInput("fr", "Actions", null));
    assertThat(glossaryTermService.lastCommand.evidence())
        .containsExactly(
            new GlossaryTermService.EvidenceInput(
                "STRING_USAGE", "Used in onboarding CTA", null, 281663L, null, null, null, null));
    assertThat(result)
        .isEqualTo(
            new BulkUpsertGlossaryTermsMcpTool.BulkUpsertResult(
                new BulkUpsertGlossaryTermsMcpTool.GlossaryRef(4L, "g4"),
                false,
                true,
                1,
                List.of(
                    new BulkUpsertGlossaryTermsMcpTool.OperationPreview(
                        0, 20L, "actions", "Actions", "CANDIDATE", "AUTOMATED", 1, 1, 1)),
                List.of(glossaryTermService.termView)));
  }

  @Test
  public void executeAllowsLargeSingleRequest() {
    List<BulkUpsertGlossaryTermsMcpTool.TermInput> terms =
        IntStream.range(0, 201).mapToObj(i -> termInput(null, "Action " + i)).toList();

    BulkUpsertGlossaryTermsMcpTool.BulkUpsertResult result =
        (BulkUpsertGlossaryTermsMcpTool.BulkUpsertResult)
            tool.execute(new BulkUpsertGlossaryTermsMcpTool.Input(4L, null, true, terms));

    assertThat(result.termCount()).isEqualTo(201);
    assertThat(result.operations()).hasSize(201);
    assertThat(glossaryTermService.upsertCallCount).isZero();
  }

  @Test
  public void executeRejectsOverLargeRequestLimit() {
    List<BulkUpsertGlossaryTermsMcpTool.TermInput> terms =
        IntStream.range(0, 1001).mapToObj(i -> termInput(null, "Action " + i)).toList();

    assertThatThrownBy(
            () -> tool.execute(new BulkUpsertGlossaryTermsMcpTool.Input(4L, null, true, terms)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("terms must contain at most 1000 entries");
  }

  @Test
  public void executeRequiresTerms() {
    assertThatThrownBy(
            () -> tool.execute(new BulkUpsertGlossaryTermsMcpTool.Input(4L, null, true, List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("terms are required");
  }

  private BulkUpsertGlossaryTermsMcpTool.TermInput termInput(Long tmTextUnitId, String source) {
    return new BulkUpsertGlossaryTermsMcpTool.TermInput(
        tmTextUnitId,
        "actions",
        source,
        "Source comment",
        "A product action.",
        null,
        "GENERAL",
        "SOFT",
        "CANDIDATE",
        "AUTOMATED",
        false,
        false,
        List.of(new BulkUpsertGlossaryTermsMcpTool.TranslationInput("fr", "Actions", null)),
        List.of(
            new BulkUpsertGlossaryTermsMcpTool.EvidenceInput(
                "STRING_USAGE", "Used in onboarding CTA", null, 281663L, null, null, null, null)));
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
    private int upsertCallCount;
    private Long lastGlossaryId;
    private Long lastTmTextUnitId;
    private TermUpsertCommand lastCommand;
    private final TermView termView =
        new TermView(
            10L,
            null,
            null,
            20L,
            "actions",
            "Actions",
            "Source comment",
            "A product action.",
            null,
            "GENERAL",
            "SOFT",
            "CANDIDATE",
            "AUTOMATED",
            false,
            false,
            List.of(),
            List.of());

    private FakeGlossaryTermService() {
      super(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public TermView upsertTerm(Long glossaryId, Long tmTextUnitId, TermUpsertCommand command) {
      upsertCallCount++;
      lastGlossaryId = glossaryId;
      lastTmTextUnitId = tmTextUnitId;
      lastCommand = command;
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
