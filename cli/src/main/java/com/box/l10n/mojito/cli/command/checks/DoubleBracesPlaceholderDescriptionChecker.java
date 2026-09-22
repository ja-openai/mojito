package com.box.l10n.mojito.cli.command.checks;

import java.util.Set;

public class DoubleBracesPlaceholderDescriptionChecker
    extends SingleBracesPlaceholderDescriptionChecker {

  @Override
  public Set<String> checkCommentForDescriptions(String source, String comment) {
    return super.checkCommentForDescriptions(replaceDoubleBracesWithSingle(source), comment);
  }

  private String replaceDoubleBracesWithSingle(String str) {
    return str.replace("{{", "{").replace("}}", "}");
  }
}
