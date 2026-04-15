package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class BadTranslationSlackMessageComposerTest {

  private final BadTranslationSlackMessageComposer composer =
      new BadTranslationSlackMessageComposer();

  @Test
  public void composeIncludesOwnerProjectAndActionState() {
    String message =
        composer.compose(
            "formatjs compile failed: MISSING_OTHER_CLAUSE",
            "https://buildkite.example/build/1",
            new BadTranslationLookupService.LocaleRef(7L, "hr"),
            new BadTranslationLookupService.TranslationCandidate(
                new BadTranslationLookupService.RepositoryRef(11L, "chatgpt-web"),
                41L,
                52L,
                63L,
                "Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel",
                "/src/hr.json",
                74L,
                "source",
                "{bad}",
                "comment",
                "APPROVED",
                true,
                null,
                true),
            new BadTranslationPersonRef(901L, "author-one", "U901", "<@U901>"),
            new BadTranslationReviewProjectCandidate(
                88L,
                "COMPLETE",
                null,
                null,
                null,
                "https://mojito.example/review-projects/88",
                501L,
                "April web review",
                "https://mojito.example/review-projects?requestId=501",
                601L,
                "Team A",
                null,
                new BadTranslationPersonRef(903L, "pm-one", "U903", "<@U903>"),
                new BadTranslationPersonRef(902L, "translator-one", "U902", "<@U902>"),
                new BadTranslationPersonRef(904L, "reviewer-one", "U904", "<@U904>"),
                "HIGH",
                95,
                java.util.List.of("Decision history reviewed the same translation variant")),
            new BadTranslationSlackService.SlackContext(
                new BadTranslationSlackDestination(
                    "TEAM_CHANNEL",
                    "slack-client",
                    "C123",
                    null,
                    true,
                    "Will post into the team's configured Slack channel"),
                new BadTranslationPersonRef(901L, "author-one", "U901", "<@U901>"),
                new BadTranslationPersonRef(904L, "reviewer-one", "U904", "<@U904>"),
                new BadTranslationPersonRef(903L, "pm-one", "U903", "<@U903>"),
                501L),
            true);

    assertThat(message)
        .contains(":warning:")
        .contains("*Invalid ICU translation in chatgpt-web*")
        .contains("`hr`")
        .contains("`Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel`")
        .contains("```{bad}```")
        .contains("<https://mojito.example/review-projects/88|April web review>")
        .contains("<@U901>")
        .contains("<@U904>")
        .contains("<@U903>")
        .contains("Translation already rejected in Mojito")
        .contains("<https://buildkite.example/build/1|Buildkite report>");
  }
}
