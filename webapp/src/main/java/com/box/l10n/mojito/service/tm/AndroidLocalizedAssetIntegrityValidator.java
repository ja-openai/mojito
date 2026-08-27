package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.fileformat.LocalizationCatalog;
import com.box.l10n.mojito.fileformat.LocalizationFileConverters;
import com.box.l10n.mojito.fileformat.LocalizationFileFormat;
import com.box.l10n.mojito.fileformat.LocalizationMessage;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.fileformat.LocalizationShadowComparator;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MarkdownLinkIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
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
  private final PluralIntegrityCheckerRelaxer pluralIntegrityCheckerRelaxer =
      new PluralIntegrityCheckerRelaxer();

  public void validate(String locale, String source, String localized) {
    validate(locale, null, source, localized);
  }

  public void validate(String locale, String assetPath, String source, String localized) {
    if (localized == null || localized.isBlank()) {
      return;
    }

    LocalizationCatalog sourceCatalog = parse(locale, assetPath, source, "source");
    LocalizationCatalog localizedCatalog = parse(locale, assetPath, localized, "target");

    Map<String, AssetExtractorTextUnit> sourceById = projectPresentById(sourceCatalog);
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (var projected :
        LocalizationShadowComparator.projectImportTextUnitsWithIds(localizedCatalog)) {
      AssetExtractorTextUnit sourceUnit = sourceUnit(sourceById, projected);
      if (sourceUnit == null) {
        continue;
      }
      String target = projected.textUnit().getSource();
      LocalizationMessage sourceMessage = sourceCatalog.messages().get(projected.messageId());
      if (isFormatted(sourceMessage)) {
        check(
            diagnostics,
            locale,
            assetPath,
            projected.canonicalId(),
            PRINTF_PLACEHOLDER_CONTRACT,
            () -> checkPrintf(sourceUnit, projected.textUnit(), target));
      }
      check(
          diagnostics,
          locale,
          assetPath,
          projected.canonicalId(),
          MARKDOWN_LINK_CONTRACT,
          () -> markdownLinkChecker.check(sourceUnit.getSource(), target));
    }
    if (!diagnostics.isEmpty()) {
      throw new AndroidLocalizedAssetIntegrityException(diagnostics, null);
    }
  }

  private void checkPrintf(
      AssetExtractorTextUnit sourceUnit, AssetExtractorTextUnit targetUnit, String target) {
    try {
      printfLikeChecker.check(sourceUnit.getSource(), target);
    } catch (IntegrityCheckException exception) {
      if (!pluralIntegrityCheckerRelaxer.shouldRelaxIntegrityCheck(
          sourceUnit.getSource(), target, targetUnit.getPluralForm(), printfLikeChecker)) {
        throw exception;
      }
    }
  }

  private static boolean isFormatted(LocalizationMessage sourceMessage) {
    return sourceMessage == null
        || sourceMessage.metadata() == null
        || !Boolean.FALSE.equals(sourceMessage.metadata().get("formatted"));
  }

  private static LocalizationCatalog parse(
      String locale, String assetPath, String content, String subject) {
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
              subject + ": " + exception.code() + ": " + exception.getMessage(),
              assetPath);
      throw new AndroidLocalizedAssetIntegrityException(List.of(diagnostic), exception);
    }
  }

  private static AssetExtractorTextUnit sourceUnit(
      Map<String, AssetExtractorTextUnit> sourceById,
      LocalizationShadowComparator.ProjectedTextUnit target) {
    AssetExtractorTextUnit exact = sourceById.get(target.canonicalId());
    if (exact != null || target.textUnit().getPluralForm() == null) {
      return exact;
    }
    return sourceById.get(target.messageId() + "#other");
  }

  private static Map<String, AssetExtractorTextUnit> projectPresentById(
      LocalizationCatalog catalog) {
    Map<String, AssetExtractorTextUnit> byId = new LinkedHashMap<>();
    for (var projected : LocalizationShadowComparator.projectImportTextUnitsWithIds(catalog)) {
      byId.put(projected.canonicalId(), projected.textUnit());
    }
    return byId;
  }

  private static void check(
      List<Diagnostic> diagnostics,
      String locale,
      String assetPath,
      String stringId,
      String rule,
      Runnable check) {
    try {
      check.run();
    } catch (IntegrityCheckException exception) {
      diagnostics.add(new Diagnostic(locale, stringId, rule, exception.getMessage(), assetPath));
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

  public record Diagnostic(
      String locale, String stringId, String rule, String message, String assetPath) {

    public Diagnostic(String locale, String stringId, String rule, String message) {
      this(locale, stringId, rule, message, null);
    }

    @Override
    public String toString() {
      return "locale="
          + locale
          + (assetPath == null ? "" : ", assetPath=" + assetPath)
          + ", stringId="
          + stringId
          + ", rule="
          + rule
          + ", message="
          + message;
    }
  }
}
