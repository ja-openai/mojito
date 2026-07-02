package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermInflectionProfileService;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ReviewGlossaryTermInflectionProfilesMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final GlossaryMcpSupport glossaryMcpSupport =
      new GlossaryMcpSupport(glossaryManagementService);
  private final FakeInflectionProfileService inflectionProfileService =
      new FakeInflectionProfileService();
  private final ReviewGlossaryTermInflectionProfilesMcpTool tool =
      new ReviewGlossaryTermInflectionProfilesMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(),
          glossaryMcpSupport,
          inflectionProfileService);

  @Test
  public void descriptorPinsCheckedV0CompiledExportBoundary() {
    assertThat(tool.descriptor().description()).contains("before checked V0 compiled export");
    assertThat(tool.descriptor().description()).doesNotContain("all languages");
    assertThat(tool.descriptor().description()).doesNotContain("all inflection");
  }

  @Test
  public void executeListsProfilesNeedingReviewAndDiagnostics() {
    inflectionProfileService.profiles =
        List.of(
            profile(
                20L, "item.approved", TermInflectionProfilePackJsonLoader.STATUS_APPROVED, "[]"),
            profile(
                21L,
                "ar.explicit.mother",
                TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED,
                """
                [
                  {
                    "reason": "missing-form-cell",
                    "message": "Missing construct.accusative.dual",
                    "formKey": "construct.accusative.dual"
                  },
                  {
                    "code": "missing-form-cell",
                    "message": "Missing construct.genitive.dual",
                    "formKey": "construct.genitive.dual"
                  },
                  {
                    "code": "ambiguous",
                    "message": "Source row has multiple candidates"
                  },
                  {
                    "messageId": "checkout.pay",
                    "argument": "owner",
                    "relatedArgument": "item",
                    "missing": ["agreeWith.gender"]
                  }
                ]
                """),
            profile(
                22L, "item.disabled", TermInflectionProfilePackJsonLoader.STATUS_DISABLED, "[]"));

    ReviewGlossaryTermInflectionProfilesMcpTool.Result result =
        (ReviewGlossaryTermInflectionProfilesMcpTool.Result)
            tool.execute(
                new ReviewGlossaryTermInflectionProfilesMcpTool.Input(
                    null, "target", "ar", null, null, null, null, null, null, false, 10));

    assertThat(result.glossary().id()).isEqualTo(4L);
    assertThat(result.localeTag()).isEqualTo("ar");
    assertThat(result.action()).isEqualTo("LIST");
    assertThat(result.totalProfileCount()).isEqualTo(3);
    assertThat(result.returnedProfileCount()).isEqualTo(2);
    assertThat(result.profiles())
        .extracting("termId")
        .containsExactly("ar.explicit.mother", "item.disabled");
    assertThat(result.profiles().getFirst().diagnosticCount()).isEqualTo(4);
    assertThat(result.profiles().getFirst().morphology().get("partOfSpeech").asText())
        .isEqualTo("noun");
    assertThat(result.profiles().getFirst().forms().get("bare.singular").asText())
        .isEqualTo("term");
    assertThat(result.profiles().getFirst().missingFormKeys())
        .containsExactly("construct.accusative.dual", "construct.genitive.dual");
    assertThat(result.profiles().getFirst().diagnostics().get(1).get("formKey").asText())
        .isEqualTo("construct.genitive.dual");
    assertThat(result.profiles().getFirst().diagnosticSummaries().get(3).relatedArgument())
        .isEqualTo("item");
    assertThat(result.profiles().getFirst().diagnosticSummaries().get(3).missing())
        .containsExactly("agreeWith.gender");
  }

  @Test
  public void executeAppliesApproveActionThroughServiceBoundary() {
    inflectionProfileService.profiles =
        new ArrayList<>(
            List.of(
                profile(
                    21L,
                    "ar.explicit.mother",
                    TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED,
                    """
                    [{"code": "missing-form-cell", "message": "Needs review"}]
                    """)));

    ReviewGlossaryTermInflectionProfilesMcpTool.Result result =
        (ReviewGlossaryTermInflectionProfilesMcpTool.Result)
            tool.execute(
                new ReviewGlossaryTermInflectionProfilesMcpTool.Input(
                    null,
                    "target",
                    "ar",
                    21L,
                    "APPROVE",
                    "[]",
                    "{\"partOfSpeech\":\"noun\"}",
                    "{\"bare.singular\":\"أم\",\"construct.genitive.dual\":\"أُمَّي\"}",
                    "{\"reviewedBy\":\"mcp\"}",
                    true,
                    10));

    assertThat(inflectionProfileService.lastGlossaryId).isEqualTo(4L);
    assertThat(inflectionProfileService.lastTmTextUnitId).isEqualTo(21L);
    assertThat(inflectionProfileService.lastInput.localeTag()).isEqualTo("ar");
    assertThat(inflectionProfileService.lastInput.status())
        .isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(inflectionProfileService.lastInput.morphologyJson())
        .isEqualTo("{\"partOfSpeech\":\"noun\"}");
    assertThat(inflectionProfileService.lastInput.formsJson())
        .isEqualTo("{\"bare.singular\":\"أم\",\"construct.genitive.dual\":\"أُمَّي\"}");
    assertThat(inflectionProfileService.lastInput.diagnosticsJson()).isEqualTo("[]");
    assertThat(inflectionProfileService.lastInput.provenanceJson())
        .isEqualTo("{\"reviewedBy\":\"mcp\"}");
    assertThat(result.reviewedProfile().status())
        .isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(result.reviewedProfile().diagnosticCount()).isZero();
    assertThat(result.reviewedProfile().forms().get("construct.genitive.dual").asText())
        .isEqualTo("أُمَّي");
  }

  @Test
  public void executeListsTurkishExplicitReviewDiagnostics() {
    inflectionProfileService.profiles =
        List.of(
            profile(
                23L,
                "tr.explicit.cakmak",
                TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED,
                """
                [
                  {
                    "reason": "requires-explicit-review",
                    "code": "turkish-explicit-template-review",
                    "message": "Turkish supplemental exception requires explicit template forms",
                    "termId": "tr.explicit.cakmak"
                  }
                ]
                """));

    ReviewGlossaryTermInflectionProfilesMcpTool.Result result =
        (ReviewGlossaryTermInflectionProfilesMcpTool.Result)
            tool.execute(
                new ReviewGlossaryTermInflectionProfilesMcpTool.Input(
                    null, "target", "tr", null, "LIST", null, null, null, null, false, 10));

    assertThat(result.localeTag()).isEqualTo("tr");
    assertThat(result.returnedProfileCount()).isEqualTo(1);
    ReviewGlossaryTermInflectionProfilesMcpTool.InflectionProfileSummary profile =
        result.profiles().getFirst();
    assertThat(profile.termId()).isEqualTo("tr.explicit.cakmak");
    assertThat(profile.missingFormKeys()).isEmpty();
    assertThat(profile.diagnosticSummaries()).hasSize(1);
    assertThat(profile.diagnosticSummaries().getFirst().reason())
        .isEqualTo("requires-explicit-review");
    assertThat(profile.diagnosticSummaries().getFirst().code())
        .isEqualTo("turkish-explicit-template-review");
    assertThat(profile.diagnosticSummaries().getFirst().message())
        .isEqualTo("Turkish supplemental exception requires explicit template forms");
    assertThat(profile.diagnosticSummaries().getFirst().termId()).isEqualTo("tr.explicit.cakmak");
  }

  @Test
  public void executeCanIncludeApprovedMalayalamProductRows() {
    inflectionProfileService.profiles =
        List.of(
            profile(
                24L,
                "ml.case.father",
                "ml",
                TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
                "{\"partOfSpeech\":\"noun\",\"gender\":\"masculine\"}",
                """
                {
                  "nominative.singular": "പിതാവ്",
                  "sociative.plural": "പിതാക്കന്മാരോട്",
                  "vocative.singular": "പിതാവേ"
                }
                """,
                "[]"));

    ReviewGlossaryTermInflectionProfilesMcpTool.Result result =
        (ReviewGlossaryTermInflectionProfilesMcpTool.Result)
            tool.execute(
                new ReviewGlossaryTermInflectionProfilesMcpTool.Input(
                    null, "target", "ml", null, "LIST", null, null, null, null, true, 10));

    assertThat(result.localeTag()).isEqualTo("ml");
    assertThat(result.totalProfileCount()).isEqualTo(1);
    assertThat(result.returnedProfileCount()).isEqualTo(1);
    ReviewGlossaryTermInflectionProfilesMcpTool.InflectionProfileSummary profile =
        result.profiles().getFirst();
    assertThat(profile.termId()).isEqualTo("ml.case.father");
    assertThat(profile.status()).isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(profile.formCount()).isEqualTo(3);
    assertThat(profile.diagnosticCount()).isZero();
    assertThat(profile.missingFormKeys()).isEmpty();
    assertThat(profile.morphology().get("gender").asText()).isEqualTo("masculine");
    assertThat(profile.forms().get("vocative.singular").asText()).isEqualTo("പിതാവേ");
    assertThat(profile.forms().get("sociative.plural").asText()).isEqualTo("പിതാക്കന്മാരോട്");
  }

  @Test
  public void executeRejectsReviewActionWithoutTermId() {
    assertThatThrownBy(
            () ->
                tool.execute(
                    new ReviewGlossaryTermInflectionProfilesMcpTool.Input(
                        null, "target", "ar", null, "DISABLE", null, null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tmTextUnitId is required");
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

  private static final class FakeInflectionProfileService
      extends GlossaryTermInflectionProfileService {

    private List<InflectionProfileView> profiles = List.of();
    private Long lastGlossaryId;
    private Long lastTmTextUnitId;
    private InflectionProfileReviewInput lastInput;

    private FakeInflectionProfileService() {
      super(null, null, null, new ObjectMapper());
    }

    @Override
    public List<InflectionProfileView> getProfiles(Long glossaryId, String localeTag) {
      lastGlossaryId = glossaryId;
      return profiles;
    }

    @Override
    public InflectionProfileView reviewProfile(
        Long glossaryId, Long tmTextUnitId, InflectionProfileReviewInput input) {
      lastGlossaryId = glossaryId;
      lastTmTextUnitId = tmTextUnitId;
      lastInput = input;
      InflectionProfileView existing =
          profiles.stream()
              .filter(profile -> profile.tmTextUnitId().equals(tmTextUnitId))
              .findFirst()
              .orElseThrow();
      InflectionProfileView reviewed =
          new InflectionProfileView(
              existing.id(),
              existing.createdDate(),
              existing.lastModifiedDate(),
              existing.glossaryTermMetadataId(),
              existing.tmTextUnitId(),
              existing.termId(),
              existing.source(),
              existing.localeTag(),
              existing.schema(),
              input.status(),
              input.morphologyJson() == null ? existing.morphologyJson() : input.morphologyJson(),
              input.formsJson() == null ? existing.formsJson() : input.formsJson(),
              input.diagnosticsJson() == null
                  ? existing.diagnosticsJson()
                  : input.diagnosticsJson(),
              input.provenanceJson() == null ? existing.provenanceJson() : input.provenanceJson());
      profiles =
          profiles.stream()
              .map(profile -> profile.tmTextUnitId().equals(tmTextUnitId) ? reviewed : profile)
              .toList();
      return reviewed;
    }
  }

  private static GlossaryTermInflectionProfileService.InflectionProfileView profile(
      Long tmTextUnitId, String termId, String status, String diagnosticsJson) {
    return profile(
        tmTextUnitId,
        termId,
        "ar",
        status,
        "{\"partOfSpeech\":\"noun\"}",
        "{\"bare.singular\":\"term\"}",
        diagnosticsJson);
  }

  private static GlossaryTermInflectionProfileService.InflectionProfileView profile(
      Long tmTextUnitId,
      String termId,
      String localeTag,
      String status,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson) {
    return new GlossaryTermInflectionProfileService.InflectionProfileView(
        tmTextUnitId + 100,
        null,
        null,
        tmTextUnitId + 200,
        tmTextUnitId,
        termId,
        termId.replace('.', ' '),
        localeTag,
        TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA,
        status,
        morphologyJson,
        formsJson,
        diagnosticsJson,
        "{\"source\":\"unit-test\"}");
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
        List.of("ar"),
        List.of(),
        List.of());
  }
}
