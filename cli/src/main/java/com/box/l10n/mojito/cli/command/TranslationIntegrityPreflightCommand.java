package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.cli.command.param.Param;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.rest.client.TextUnitClient;
import com.box.l10n.mojito.rest.entity.IntegrityCheckerType;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.dollartemplate.DollarTemplateTranslationIntegrityEvaluator;
import com.box.l10n.mojito.translationintegrity.dollartemplate.DollarTemplateTranslationIntegrityOptions;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsTranslationIntegrityEvaluator;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsTranslationIntegrityOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.io.FilenameUtils;
import org.fusesource.jansi.Ansi.Color;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Runs a bounded, read-only sample before enabling a translation-integrity checker. */
@Component
@Scope("prototype")
@Parameters(
    commandNames = {"translation-integrity-preflight"},
    commandDescription =
        "Sample active translations with FORMATJS or DOLLAR_TEMPLATE before repository activation")
public class TranslationIntegrityPreflightCommand extends Command {

  static final int DEFAULT_MAX_TEXT_UNITS = 25;
  static final int MAX_TEXT_UNITS = 100;
  static final long MAX_SAMPLE_UTF16_CODE_UNITS = 5_000_000L;
  static final int DEFAULT_MAX_DETAILS = 100;
  static final int MAX_DETAILS = 1_000;
  static final int FINDINGS_EXIT_CODE = 2;

  @Autowired ConsoleWriter consoleWriter;
  @Autowired TextUnitClient textUnitClient;

  @Parameter(
      names = {Param.REPOSITORY_NAME_LONG, Param.REPOSITORY_NAME_SHORT},
      arity = 1,
      required = true,
      description = "Repository to sample")
  String repositoryNameParam;

  @Parameter(
      names = {"--asset-extension", "-e"},
      arity = 1,
      required = true,
      description = "Configured asset extension, for example json or properties")
  String assetExtensionParam;

  @Parameter(
      names = {"--checker-type"},
      arity = 1,
      required = true,
      description = "Candidate checker: FORMATJS or DOLLAR_TEMPLATE")
  IntegrityCheckerType checkerTypeParam;

  @Parameter(
      names = {Param.REPOSITORY_LOCALES_LONG, Param.REPOSITORY_LOCALES_SHORT},
      variableArity = true,
      description = "Optional target locales to sample")
  List<String> localesParam;

  @Parameter(
      names = {"--max-text-units"},
      arity = 1,
      description = "Maximum current text units to sample (1-100)")
  int maxTextUnitsParam = DEFAULT_MAX_TEXT_UNITS;

  @Parameter(
      names = {"--max-details"},
      arity = 1,
      description = "Maximum finding details to print (0-1000)")
  int maxDetailsParam = DEFAULT_MAX_DETAILS;

  @Override
  protected void execute() throws CommandException {
    validateParameters();
    PreflightResult result = preflight();
    print(result);
    if (result.evaluated() == 0) {
      if (result.inputBudgetReached()) {
        throw new CommandException(
            "No matching translation could be evaluated within the 5,000,000 "
                + "UTF-16-code-unit budget");
      }
      throw new CommandException(
          "No active translations matched the repository, locale, and extension scope");
    }
    if (result.rejectTarget() > 0 || result.autoRepairTarget() > 0) {
      throw new CommandWithExitStatusException(FINDINGS_EXIT_CODE);
    }
  }

  PreflightResult preflight() {
    String extension = normalizedExtension();
    TextUnitClient.TextUnitSearchBody search = new TextUnitClient.TextUnitSearchBody();
    search.setRepositoryNames(List.of(repositoryNameParam));
    search.setAssetPath("\\." + escapeRegexLiteral(extension) + "$");
    search.setSearchType(TextUnitClient.SearchType.REGEX);
    search.setUsedFilter(TextUnitClient.UsedFilter.USED);
    search.setStatusFilter(TextUnitClient.StatusFilter.TRANSLATED_AND_NOT_REJECTED);
    search.setPluralFormFiltered(false);
    search.setLimit(maxTextUnitsParam + 1);
    if (localesParam != null && !localesParam.isEmpty()) {
      search.setLocaleTags(List.copyOf(localesParam));
    }

    List<TextUnitClient.TextUnit> candidates = textUnitClient.searchTextUnits(search);
    int sampleSize = Math.min(candidates.size(), maxTextUnitsParam);
    int sampled = 0;
    int evaluated = 0;
    int passed = 0;
    int rejectTarget = 0;
    int autoRepairTarget = 0;
    int rejectSource = 0;
    int skipped = 0;
    long sampledUtf16CodeUnits = 0;
    boolean inputBudgetReached = false;
    List<PreflightFinding> blockingFindings = new ArrayList<>();
    List<PreflightFinding> advisoryFindings = new ArrayList<>();

    for (TextUnitClient.TextUnit textUnit : candidates.subList(0, sampleSize)) {
      long candidateUtf16CodeUnits = length(textUnit.source()) + (long) length(textUnit.target());
      if (sampledUtf16CodeUnits + candidateUtf16CodeUnits > MAX_SAMPLE_UTF16_CODE_UNITS) {
        inputBudgetReached = true;
        break;
      }
      sampled++;
      sampledUtf16CodeUnits += candidateUtf16CodeUnits;
      inputBudgetReached |= sampledUtf16CodeUnits >= MAX_SAMPLE_UTF16_CODE_UNITS;

      if (!extension.equals(FilenameUtils.getExtension(textUnit.assetPath()))) {
        skipped++;
      } else {
        evaluated++;
        if (textUnit.target() == null) {
          rejectTarget++;
          addFinding(
              blockingFindings,
              textUnit,
              TranslationIntegrityDisposition.REJECT_TARGET,
              "target-missing");
        } else if (textUnit.source() == null) {
          rejectSource++;
          addFinding(
              advisoryFindings,
              textUnit,
              TranslationIntegrityDisposition.REJECT_SOURCE,
              "source-missing");
        } else {
          TranslationIntegrityEvaluation evaluation =
              evaluate(textUnit.source(), textUnit.target());
          switch (evaluation.disposition()) {
            case PASS, EXEMPT -> passed++;
            case REJECT_TARGET -> {
              rejectTarget++;
              addFinding(blockingFindings, textUnit, evaluation);
            }
            case AUTO_REPAIR_TARGET -> {
              autoRepairTarget++;
              addFinding(blockingFindings, textUnit, evaluation);
            }
            case REJECT_SOURCE -> {
              rejectSource++;
              addFinding(advisoryFindings, textUnit, evaluation);
            }
          }
        }
      }
    }

    List<PreflightFinding> findings = new ArrayList<>(maxDetailsParam);
    appendFindings(findings, blockingFindings);
    appendFindings(findings, advisoryFindings);
    int totalFindingCount = rejectTarget + autoRepairTarget + rejectSource;
    return new PreflightResult(
        sampled,
        evaluated,
        passed,
        rejectTarget,
        autoRepairTarget,
        rejectSource,
        skipped,
        candidates.size() > sampled,
        sampledUtf16CodeUnits,
        inputBudgetReached,
        totalFindingCount - findings.size(),
        List.copyOf(findings));
  }

  private static int length(String value) {
    return value == null ? 0 : value.length();
  }

  private void appendFindings(List<PreflightFinding> destination, List<PreflightFinding> source) {
    int remaining = maxDetailsParam - destination.size();
    if (remaining > 0) {
      destination.addAll(source.subList(0, Math.min(source.size(), remaining)));
    }
  }

  private TranslationIntegrityEvaluation evaluate(String source, String target) {
    return switch (checkerTypeParam) {
      case FORMATJS ->
          new FormatJsTranslationIntegrityEvaluator()
              .evaluate(source, target, FormatJsTranslationIntegrityOptions.web());
      case DOLLAR_TEMPLATE ->
          new DollarTemplateTranslationIntegrityEvaluator()
              .evaluate(source, target, DollarTemplateTranslationIntegrityOptions.common());
      default ->
          throw new IllegalStateException("Unsupported preflight checker: " + checkerTypeParam);
    };
  }

  private void addFinding(
      List<PreflightFinding> findings,
      TextUnitClient.TextUnit textUnit,
      TranslationIntegrityEvaluation evaluation) {
    if (findings.size() >= maxDetailsParam) {
      return;
    }
    List<String> diagnosticCodes =
        evaluation.diagnostics().stream()
            .map(diagnostic -> diagnostic.subject().wireValue() + ":" + diagnostic.code())
            .toList();
    findings.add(
        new PreflightFinding(
            textUnit.tmTextUnitId(),
            textUnit.targetLocale(),
            textUnit.assetPath(),
            evaluation.disposition(),
            diagnosticCodes));
  }

  private void addFinding(
      List<PreflightFinding> findings,
      TextUnitClient.TextUnit textUnit,
      TranslationIntegrityDisposition disposition,
      String diagnosticCode) {
    if (findings.size() < maxDetailsParam) {
      findings.add(
          new PreflightFinding(
              textUnit.tmTextUnitId(),
              textUnit.targetLocale(),
              textUnit.assetPath(),
              disposition,
              List.of(
                  (disposition == TranslationIntegrityDisposition.REJECT_SOURCE
                          ? "source:"
                          : "target:")
                      + diagnosticCode)));
    }
  }

  private void print(PreflightResult result) {
    consoleWriter
        .newLine()
        .a("Translation-integrity preflight: ")
        .fg(Color.CYAN)
        .a(repositoryNameParam)
        .reset()
        .a(" ")
        .a(normalizedExtension())
        .a(":")
        .a(checkerTypeParam.name())
        .println();
    for (PreflightFinding finding : result.findings()) {
      consoleWriter
          .fg(Color.YELLOW)
          .a(finding.disposition().name())
          .reset()
          .a(" tmTextUnitId=")
          .a(finding.tmTextUnitId())
          .a(" locale=")
          .a(Objects.toString(finding.locale(), ""))
          .a(" asset=")
          .a(Objects.toString(finding.assetPath(), ""))
          .a(" diagnostics=")
          .a(String.join(",", finding.diagnosticCodes()))
          .println();
    }
    consoleWriter
        .newLine()
        .a("sampled=")
        .a(result.sampled())
        .a(" evaluated=")
        .a(result.evaluated())
        .a(" pass=")
        .a(result.passed())
        .a(" rejectTarget=")
        .a(result.rejectTarget())
        .a(" autoRepairTarget=")
        .a(result.autoRepairTarget())
        .a(" rejectSource=")
        .a(result.rejectSource())
        .a(" skipped=")
        .a(result.skipped())
        .a(" sampledUtf16CodeUnits=")
        .a(result.sampledUtf16CodeUnits())
        .println();
    if (result.truncated()) {
      consoleWriter
          .fg(Color.YELLOW)
          .a("Sample limit reached; results are not exhaustive.")
          .reset()
          .println();
    }
    if (result.inputBudgetReached()) {
      consoleWriter
          .fg(Color.YELLOW)
          .a("The 5,000,000 UTF-16-code-unit sample budget was reached.")
          .reset()
          .println();
    }
    if (result.omittedFindings() > 0) {
      consoleWriter
          .fg(Color.YELLOW)
          .a("Finding details omitted: ")
          .a(result.omittedFindings())
          .reset()
          .println();
    }
    if (result.rejectSource() > 0) {
      consoleWriter
          .fg(Color.YELLOW)
          .a(
              "Source defects are advisory because a target save cannot repair persisted source text.")
          .reset()
          .println();
    }
  }

  private void validateParameters() throws CommandException {
    if (repositoryNameParam == null || repositoryNameParam.isBlank()) {
      throw new CommandException("Repository name must not be blank");
    }
    if (assetExtensionParam == null || normalizedExtension().isBlank()) {
      throw new CommandException("Asset extension must not be blank");
    }
    if (checkerTypeParam != IntegrityCheckerType.FORMATJS
        && checkerTypeParam != IntegrityCheckerType.DOLLAR_TEMPLATE) {
      throw new CommandException("Checker type must be FORMATJS or DOLLAR_TEMPLATE");
    }
    if (maxTextUnitsParam < 1 || maxTextUnitsParam > MAX_TEXT_UNITS) {
      throw new CommandException("max-text-units must be between 1 and " + MAX_TEXT_UNITS);
    }
    if (maxDetailsParam < 0 || maxDetailsParam > MAX_DETAILS) {
      throw new CommandException("max-details must be between 0 and " + MAX_DETAILS);
    }
  }

  private String normalizedExtension() {
    String extension = assetExtensionParam == null ? "" : assetExtensionParam.trim();
    return extension.startsWith(".") ? extension.substring(1) : extension;
  }

  private static String escapeRegexLiteral(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return escaped.toString();
  }

  record PreflightFinding(
      Long tmTextUnitId,
      String locale,
      String assetPath,
      TranslationIntegrityDisposition disposition,
      List<String> diagnosticCodes) {}

  record PreflightResult(
      int sampled,
      int evaluated,
      int passed,
      int rejectTarget,
      int autoRepairTarget,
      int rejectSource,
      int skipped,
      boolean truncated,
      long sampledUtf16CodeUnits,
      boolean inputBudgetReached,
      int omittedFindings,
      List<PreflightFinding> findings) {}
}
