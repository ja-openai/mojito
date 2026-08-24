package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.fileformat.LocalizationCatalog;
import com.box.l10n.mojito.fileformat.LocalizationFileConverters;
import com.box.l10n.mojito.fileformat.LocalizationFileFormat;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.fileformat.LocalizationShadowComparator;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MarkdownLinkIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PrintfLikeIntegrityChecker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Validates the generated Android document, not only the stored translation fragments. */
@Component
public class AndroidLocalizedAssetIntegrityValidator {

  static final String ANDROID_RESOURCE_SYNTAX = "android-resource-syntax";
  static final String MARKDOWN_LINK_CONTRACT = "markdown-link-contract";
  static final String PRINTF_PLACEHOLDER_CONTRACT = "printf-placeholder-contract";

  private final MarkdownLinkIntegrityChecker markdownLinkChecker =
      new MarkdownLinkIntegrityChecker();
  private final PrintfLikeIntegrityChecker printfLikeChecker = new PrintfLikeIntegrityChecker();

  public void validate(String locale, String source, String localized) {
    LocalizationCatalog sourceCatalog = parse(locale, source, "source");
    LocalizationCatalog localizedCatalog = parse(locale, localized, "target");

    Map<String, AssetExtractorTextUnit> sourceById = projectById(sourceCatalog);
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (var projected : LocalizationShadowComparator.projectTextUnitsWithIds(localizedCatalog)) {
      AssetExtractorTextUnit sourceUnit = sourceById.get(projected.canonicalId());
      if (sourceUnit == null) {
        continue;
      }
      String target = projected.textUnit().getSource();
      check(
          diagnostics,
          locale,
          projected.canonicalId(),
          PRINTF_PLACEHOLDER_CONTRACT,
          () -> printfLikeChecker.check(sourceUnit.getSource(), target));
      check(
          diagnostics,
          locale,
          projected.canonicalId(),
          MARKDOWN_LINK_CONTRACT,
          () -> markdownLinkChecker.check(sourceUnit.getSource(), target));
    }
    if (!diagnostics.isEmpty()) {
      throw new AndroidLocalizedAssetIntegrityException(diagnostics, null);
    }
  }

  private static LocalizationCatalog parse(String locale, String content, String subject) {
    try {
      return LocalizationFileConverters.parse(
          LocalizationFileFormat.ANDROID,
          LocalizationFileConverters.encodeStringTransport(
              LocalizationFileFormat.ANDROID, content));
    } catch (LocalizationParseException exception) {
      String stringId = stringId(exception.getMessage());
      Diagnostic diagnostic =
          new Diagnostic(
              locale,
              stringId,
              ANDROID_RESOURCE_SYNTAX,
              subject + ": " + exception.code() + ": " + exception.getMessage());
      throw new AndroidLocalizedAssetIntegrityException(List.of(diagnostic), exception);
    }
  }

  private static Map<String, AssetExtractorTextUnit> projectById(LocalizationCatalog catalog) {
    Map<String, AssetExtractorTextUnit> byId = new LinkedHashMap<>();
    for (var projected : LocalizationShadowComparator.projectTextUnitsWithIds(catalog)) {
      byId.put(projected.canonicalId(), projected.textUnit());
    }
    return byId;
  }

  private static void check(
      List<Diagnostic> diagnostics, String locale, String stringId, String rule, Runnable check) {
    try {
      check.run();
    } catch (IntegrityCheckException exception) {
      diagnostics.add(new Diagnostic(locale, stringId, rule, exception.getMessage()));
    }
  }

  private static String stringId(String message) {
    String marker = "[resource=";
    int start = message == null ? -1 : message.lastIndexOf(marker);
    if (start < 0 || !message.endsWith("]")) {
      return "<document>";
    }
    return message.substring(start + marker.length(), message.length() - 1);
  }

  public record Diagnostic(String locale, String stringId, String rule, String message) {
    @Override
    public String toString() {
      return "locale="
          + locale
          + ", stringId="
          + stringId
          + ", rule="
          + rule
          + ", message="
          + message;
    }
  }
}
