package com.box.l10n.mojito.service.badtranslation;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class BadTranslationSlackMessageComposer {

  public String compose(
      String reason,
      String buildkiteUrl,
      BadTranslationLookupService.LocaleRef locale,
      BadTranslationLookupService.TranslationCandidate candidate,
      BadTranslationPersonRef translationAuthor,
      BadTranslationReviewProjectCandidate reviewProjectCandidate,
      BadTranslationSlackService.SlackContext slackContext,
      boolean translationRejected) {
    Objects.requireNonNull(locale);
    Objects.requireNonNull(candidate);

    StringBuilder builder = new StringBuilder();
    builder
        .append(":warning: *Invalid ICU translation in ")
        .append(candidate.repository().name())
        .append("*\n")
        .append("*String*: `")
        .append(candidate.stringId())
        .append("`\n")
        .append("*Locale*: `")
        .append(locale.bcp47Tag())
        .append("`");

    if (reason != null && !reason.isBlank()) {
      builder.append("\n*Reason*: ").append(reason.trim());
    }

    if (candidate.target() != null && !candidate.target().isBlank()) {
      builder
          .append("\n*Current translation*")
          .append("\n```")
          .append(candidate.target().trim())
          .append("```");
    }

    if (reviewProjectCandidate != null && reviewProjectCandidate.reviewProjectId() != null) {
      builder
          .append("\n*Review project*: ")
          .append(
              formatSlackLink(
                  reviewProjectCandidate.reviewProjectLink(),
                  reviewProjectCandidate.reviewProjectRequestName(),
                  String.valueOf(reviewProjectCandidate.reviewProjectId())));
    }

    appendPerson(builder, "*Author*", translationAuthor);
    appendPerson(builder, "*Reviewer*", slackContext == null ? null : slackContext.reviewer());
    appendPerson(builder, "*Owner*", slackContext == null ? null : slackContext.owner());

    if (translationRejected) {
      builder.append("\n*Action*: Translation already rejected in Mojito.");
    } else {
      builder.append("\n*Action*: Review only. No Mojito rejection has been sent yet.");
    }

    if (buildkiteUrl != null && !buildkiteUrl.isBlank()) {
      builder
          .append("\n*Source*: ")
          .append(formatSlackLink(buildkiteUrl.trim(), "Buildkite report", buildkiteUrl.trim()));
    }

    builder.append("\nPlease re-review without bypassing integrity checks.");

    return builder.toString();
  }

  private void appendPerson(StringBuilder builder, String label, BadTranslationPersonRef person) {
    String rendered = renderPerson(person);
    if (rendered == null) {
      return;
    }
    builder.append("\n").append(label).append(": ").append(rendered);
  }

  private String renderPerson(BadTranslationPersonRef person) {
    if (person == null) {
      return null;
    }
    if (person.slackMention() != null && !person.slackMention().isBlank()) {
      return person.slackMention();
    }
    if (person.username() != null && !person.username().isBlank()) {
      return person.username();
    }
    return null;
  }

  private String formatSlackLink(String url, String label, String fallback) {
    if (url == null || url.isBlank()) {
      return fallback;
    }
    if (label == null || label.isBlank()) {
      return "<" + url.trim() + ">";
    }
    return "<" + url.trim() + "|" + label.trim() + ">";
  }
}
