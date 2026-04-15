package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

public class BadTranslationReviewProjectServiceTest {

  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository =
      Mockito.mock(ReviewProjectTextUnitRepository.class);
  private final ServerConfig serverConfig = Mockito.mock(ServerConfig.class);

  private final BadTranslationReviewProjectService service =
      new BadTranslationReviewProjectService(reviewProjectTextUnitRepository, serverConfig);

  @Test
  public void findReviewProjectCandidatesRanksSameReviewedVariantHighest() {
    BadTranslationLookupService.TranslationCandidate candidate =
        new BadTranslationLookupService.TranslationCandidate(
            new BadTranslationLookupService.RepositoryRef(11L, "chatgpt-web"),
            41L,
            52L,
            63L,
            "string.id",
            "/src/a.ts",
            74L,
            "source",
            "{bad}",
            "comment",
            "APPROVED",
            true,
            null,
            true);

    when(serverConfig.getUrl()).thenReturn("https://mojito.example/");
    when(reviewProjectTextUnitRepository.findBadTranslationReviewProjectMatches(41L, 7L))
        .thenReturn(
            List.of(
                new BadTranslationReviewProjectMatchRow(
                    88L,
                    ReviewProjectStatus.CLOSED,
                    ZonedDateTime.parse("2026-04-15T12:00:00Z"),
                    ZonedDateTime.parse("2026-04-16T12:00:00Z"),
                    501L,
                    "Request A",
                    901L,
                    "requestor",
                    601L,
                    "Team A",
                    902L,
                    "pm-a",
                    903L,
                    "translator-a",
                    904L,
                    "creator-a",
                    70L,
                    "{bad}",
                    71L,
                    "{bad}",
                    72L,
                    "{bad}",
                    63L,
                    ZonedDateTime.parse("2026-04-15T12:30:00Z"),
                    905L,
                    "reviewer-a"),
                new BadTranslationReviewProjectMatchRow(
                    89L,
                    ReviewProjectStatus.OPEN,
                    ZonedDateTime.parse("2026-04-14T12:00:00Z"),
                    null,
                    502L,
                    "Request B",
                    911L,
                    "requestor-b",
                    602L,
                    "Team B",
                    912L,
                    "pm-b",
                    913L,
                    "translator-b",
                    914L,
                    "creator-b",
                    80L,
                    "different",
                    81L,
                    "different",
                    82L,
                    "different",
                    null,
                    null,
                    null,
                    null)));

    List<BadTranslationReviewProjectCandidate> result =
        service.findReviewProjectCandidates(
            candidate, new BadTranslationLookupService.LocaleRef(7L, "hr"));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().reviewProjectId()).isEqualTo(88L);
    assertThat(result.getFirst().confidence()).isEqualTo("HIGH");
    assertThat(result.getFirst().reviewProjectLink())
        .isEqualTo("https://mojito.example/review-projects/88");
    assertThat(result.getFirst().confidenceReasons())
        .contains("Decision history reviewed the same translation variant");
  }
}
