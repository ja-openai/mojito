package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.ExtractForRepositoryInput;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.ExtractForRepositoryResult;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.JsonConfigLocalization;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.StatsigPullInput;
import com.box.l10n.mojito.rest.entity.Repository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fusesource.jansi.Ansi.Color;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
@Parameters(
    commandNames = {"json-config-pull"},
    commandDescription = "Pull or import a JSON config setup and extract source strings")
public class JsonConfigPullCommand extends JsonConfigCommandSupport {

  @Parameter(
      names = {"--config-id"},
      arity = 1,
      description = "Provider config id to pull, or provider id saved with a local source import")
  String configIdParam;

  @Parameter(
      names = {"--schema-file"},
      arity = 1,
      description = "JSON schema file to persist with a local source import")
  String schemaFileParam;

  @Parameter(
      names = {"--source-file"},
      arity = 1,
      description = "Source JSON config file to persist and extract")
  String sourceFileParam;

  @Parameter(
      names = {"--asset-path"},
      arity = 1,
      description = "Mojito virtual asset path used for extracted strings")
  String assetPathParam;

  @Parameter(
      names = {"--locale-mapping", "-lm"},
      arity = 1,
      description = "Output-to-Mojito locale mapping, for example 'de-DE:de,zh-TW:zh-Hant'")
  String localeMappingParam;

  @Parameter(
      names = {"--output", "-o"},
      arity = 1,
      description = "Output file for localized JSON after extraction")
  String outputFileParam;

  @Parameter(
      names = {"--output-dir", "--output-directory"},
      arity = 1,
      description = "Output directory for one JSON file per locale after extraction")
  String outputDirectoryParam;

  @Parameter(
      names = {"--translate"},
      description = "After extraction, trigger repository machine translation for target locales")
  boolean translateParam;

  @Parameter(
      names = {"--source-text-max-count"},
      arity = 1,
      description = "Max source text count per locale sent to MT when --translate is used")
  int sourceTextMaxCountParam = 100;

  @Parameter(
      names = {"--collection-key"},
      arity = 1,
      description = "Source config collection key")
  String collectionKeyParam;

  @Parameter(
      names = {"--item-id-field"},
      arity = 1,
      description = "Stable item id field")
  String itemIdFieldParam;

  @Parameter(
      names = {"--translations-field"},
      arity = 1,
      description = "Translations object field")
  String translationsFieldParam;

  @Parameter(
      names = {"--source-locale"},
      arity = 1,
      description = "Source locale key in the source JSON config")
  String sourceLocaleParam;

  @Parameter(
      names = {"--translatable-fields"},
      arity = 1,
      description = "Comma-separated fields under the source locale object to extract")
  String translatableFieldsParam;

  @Parameter(
      names = {"--format"},
      arity = 1,
      description = "Config shape: embedded, flat, or formatjs")
  String formatParam;

  @Parameter(
      names = {"--source-field"},
      arity = 1,
      description = "Source text field for flat or FormatJS configs")
  String sourceFieldParam;

  @Parameter(
      names = {"--comment-field"},
      arity = 1,
      description = "Source comment field for flat or FormatJS configs")
  String commentFieldParam;

  @Override
  protected void execute() throws CommandException {
    Repository repository = getRepository();
    List<String> targetLocaleTags = getTargetLocaleTags(repository);
    List<String> warnings = new ArrayList<>();
    Map<String, String> outputLocaleMapping =
        parseLocaleMapping(localeMappingParam, targetLocaleTags, warnings);

    ExtractForRepositoryResult result;
    if (hasText(sourceFileParam) || hasText(schemaFileParam)) {
      result = pullFromLocalFiles(repository, outputLocaleMapping);
    } else {
      result = pullFromStatsig(repository, outputLocaleMapping);
    }

    if (result.pollableTask() != null && result.pollableTask().getId() != null) {
      commandHelper.waitForPollableTask(result.pollableTask().getId());
    }

    consoleWriter
        .fg(Color.GREEN)
        .a("Saved JSON config setup and extracted ")
        .a(result.strings() == null ? 0 : result.strings().size())
        .a(" strings into ")
        .a(
            result.setup() == null
                ? normalizedAssetPath(assetPathParam)
                : result.setup().assetPath())
        .println();
    printWarnings(warnings);
    printWarnings(result.warnings() == null ? List.of() : result.warnings());

    if (translateParam) {
      triggerMachineTranslation(repository, sourceTextMaxCountParam);
    }

    if (hasText(outputFileParam) || hasText(outputDirectoryParam)) {
      writeExport(repository, result.setup(), outputFileParam, outputDirectoryParam);
    }
  }

  private ExtractForRepositoryResult pullFromLocalFiles(
      Repository repository, Map<String, String> outputLocaleMapping) {
    if (!hasText(schemaFileParam)) {
      throw new CommandException("json-config-pull with --source-file requires --schema-file");
    }
    if (!hasText(sourceFileParam)) {
      throw new CommandException("json-config-pull with --schema-file requires --source-file");
    }

    String schemaText = commandHelper.getFileContent(Path.of(schemaFileParam));
    String sourceConfigText = commandHelper.getFileContent(Path.of(sourceFileParam));
    String setupName = firstNonBlank(configIdParam, repository.getName());
    String provider = hasText(configIdParam) ? "STATSIG" : "GENERIC_JSON";
    JsonConfigLocalization setup =
        resolveOrCreateSetup(repository, setupName, assetPathParam, provider, configIdParam);
    return jsonConfigLocalizationClient.extractForSetup(
        setup.id(),
        new ExtractForRepositoryInput(
            setup.name(),
            setup.assetPath(),
            provider,
            configIdParam,
            schemaText,
            sourceConfigText,
            getProfileOverrides(),
            null,
            writeJson(outputLocaleMapping)));
  }

  private ExtractForRepositoryResult pullFromStatsig(
      Repository repository, Map<String, String> outputLocaleMapping) {
    if (!hasText(configIdParam)) {
      throw new CommandException(
          "json-config-pull requires --config-id, or both --schema-file and --source-file");
    }

    JsonConfigLocalization setup =
        resolveOrCreateSetup(repository, configIdParam, assetPathParam, "STATSIG", configIdParam);
    return jsonConfigLocalizationClient.pullStatsigForSetup(
        setup.id(),
        new StatsigPullInput(
            configIdParam,
            setup.assetPath(),
            getProfileOverrides(),
            writeJson(outputLocaleMapping),
            true));
  }

  private com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.SourceConfigProfile
      getProfileOverrides() {
    return getProfileOverrides(
        formatParam,
        collectionKeyParam,
        itemIdFieldParam,
        translationsFieldParam,
        sourceLocaleParam,
        translatableFieldsParam,
        sourceFieldParam,
        commentFieldParam);
  }
}
