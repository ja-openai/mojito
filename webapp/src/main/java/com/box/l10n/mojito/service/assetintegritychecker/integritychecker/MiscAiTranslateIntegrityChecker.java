package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class MiscAiTranslateIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  private static final Pattern HASHTAG_PATTERN =
      Pattern.compile("^#\\S+$", Pattern.UNICODE_CHARACTER_CLASS);

  private static final List<String> AI_ARTIFACT_TOKENS = List.of("output_json_schema");

  ForbidsControlCharIntegrityChecker forbidsControlCharIntegrityChecker =
      new ForbidsControlCharIntegrityChecker();

  @Override
  public void check(String sourceContent, String targetContent) throws IntegrityCheckException {
    checkSingleHashtag(sourceContent, targetContent);
    checkUnexpectedAiArtifacts(sourceContent, targetContent);
    forbidsControlCharIntegrityChecker.check(sourceContent, targetContent);
  }

  /** Checks that if the source is a single hashtag, the target is also a valid hashtag. */
  void checkSingleHashtag(String source, String target) throws IntegrityCheckException {
    String src = source == null ? "" : source.trim();
    String tgt = target == null ? "" : target.trim();

    if (HASHTAG_PATTERN.matcher(src).matches()) {
      if (!HASHTAG_PATTERN.matcher(tgt).matches()) {
        throw new IntegrityCheckException(
            "Source is a single hashtag, but the target is not a valid hashtag: "
                + tgt
                + ". Target should start with '#' and not contain spaces or be empty.");
      }
    }
  }

  void checkUnexpectedAiArtifacts(String source, String target) throws IntegrityCheckException {
    String src = source == null ? "" : source.toLowerCase(Locale.ROOT);
    String tgt = target == null ? "" : target.toLowerCase(Locale.ROOT);

    for (String artifactToken : AI_ARTIFACT_TOKENS) {
      if (tgt.contains(artifactToken) && !src.contains(artifactToken)) {
        throw new IntegrityCheckException(
            "Target contains unexpected AI translation artifact '"
                + artifactToken
                + "' that is not present in the source.");
      }
    }
  }
}
