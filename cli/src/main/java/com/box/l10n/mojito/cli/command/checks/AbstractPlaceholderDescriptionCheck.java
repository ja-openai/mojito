package com.box.l10n.mojito.cli.command.checks;

import static com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSender.QUOTE_MARKER;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public abstract class AbstractPlaceholderDescriptionCheck {

  public abstract Set<String> checkCommentForDescriptions(String source, String comment);

  public Optional<String> getFailureText(String placeholder) {
    String placeholderKind;
    if (StringUtils.isNumeric(placeholder)) {
      placeholderKind = "number";
    } else if (!placeholder.trim().isEmpty()) {
      placeholderKind = "with name";
    } else {
      return Optional.empty();
    }
    return Optional.of(
        "Missing description for placeholder "
            + placeholderKind
            + " "
            + QUOTE_MARKER
            + placeholder
            + QUOTE_MARKER
            + " in comment. Please add a description in the string comment in the form "
            + placeholder
            + ":<description>");
  }

  protected boolean isPlaceholderDescriptionMissingInComment(String comment, String placeholder) {
    return StringUtils.isBlank(comment)
        || !Pattern.compile(Pattern.quote(placeholder) + ":.+").matcher(comment).find();
  }
}
