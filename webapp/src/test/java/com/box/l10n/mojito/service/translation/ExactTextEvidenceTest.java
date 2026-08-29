package com.box.l10n.mojito.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.Test;

public class ExactTextEvidenceTest {

  @Test
  public void redactsShortLabeledAndQuotedJsonCredentials() {
    List<String> credentials =
        List.of(
            "password=abc",
            "token=x",
            "Bearer x",
            "Authorization: a",
            "Cookie: a=b",
            "{\"password\":\"supersecret\"}",
            "{\"token\":\"x\"}",
            "{\"api_key\":\"abc\"}",
            "{\"connection_string\":\"x\"}");

    for (String credential : credentials) {
      ExactTextEvidence evidence = ExactTextEvidence.fromNullable(credential);
      assertThat(evidence.exactText()).as(credential).isNull();
      assertThat(evidence.preview()).as(credential).isNull();
      assertThat(evidence.redacted()).as(credential).isTrue();
      assertThat(evidence.requiresNativeReview()).as(credential).isTrue();
      assertThat(evidence.sha256()).as(credential).hasSize(64);
    }
  }

  @Test
  public void preservesOrdinaryExactText() {
    String ordinary = "  Reset your password\nusing the account page.  ";

    ExactTextEvidence evidence = ExactTextEvidence.fromNullable(ordinary);

    assertThat(evidence.exactText()).isEqualTo(ordinary);
    assertThat(evidence.preview()).isNull();
    assertThat(evidence.truncated()).isFalse();
    assertThat(evidence.redacted()).isFalse();
    assertThat(evidence.requiresNativeReview()).isFalse();
  }

  @Test
  public void returnsOnlyBoundedPreviewForLargePayload() {
    String large = "😀".repeat(10_000);

    ExactTextEvidence evidence = ExactTextEvidence.fromNullable(large);

    assertThat(evidence.exactText()).isNull();
    assertThat(evidence.preview().codePointCount(0, evidence.preview().length()))
        .isEqualTo(ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT);
    assertThat(evidence.codePointLength()).isEqualTo(10_000);
    assertThat(evidence.sha256()).hasSize(64);
    assertThat(evidence.truncated()).isTrue();
    assertThat(evidence.requiresNativeReview()).isTrue();
  }

  @Test
  public void rejectsAnUnboundedDatabasePreview() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    ExactTextEvidence.fromSummary(
                        "x".repeat(ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT + 1),
                        "a".repeat(64),
                        1000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("boundedPreview exceeds");
  }
}
