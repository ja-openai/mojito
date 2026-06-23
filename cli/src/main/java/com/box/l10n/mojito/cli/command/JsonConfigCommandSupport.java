package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.box.l10n.mojito.cli.command.param.Param;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.ExportResult;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.JsonConfigLocalization;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.JsonConfigLocalizationInput;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.SourceConfigProfile;
import com.box.l10n.mojito.rest.client.RepositoryMachineTranslationClient;
import com.box.l10n.mojito.rest.entity.Repository;
import com.box.l10n.mojito.rest.entity.RepositoryLocale;
import com.box.l10n.mojito.rest.entity.RepositoryMachineTranslationBody;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fusesource.jansi.Ansi.Color;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

public abstract class JsonConfigCommandSupport extends Command {

  static final String DEFAULT_ASSET_PATH = "json-config-localization/strings.json";

  @Autowired ConsoleWriter consoleWriter;

  @Autowired CommandHelper commandHelper;

  @Autowired JsonConfigLocalizationClient jsonConfigLocalizationClient;

  @Autowired RepositoryMachineTranslationClient repositoryMachineTranslationClient;

  @Autowired
  @Qualifier("outputIndented")
  ObjectMapper objectMapper;

  @Parameter(
      names = {Param.REPOSITORY_LONG, Param.REPOSITORY_SHORT},
      arity = 1,
      required = true,
      description = Param.REPOSITORY_DESCRIPTION)
  String repositoryParam;

  @Parameter(
      names = {"--setup"},
      arity = 1,
      description =
          "Saved JSON config setup name or id. Required when a repository has multiple setups.")
  String setupParam;

  protected Repository getRepository() {
    return commandHelper.findRepositoryByName(repositoryParam);
  }

  protected void writeExport(
      Repository repository,
      JsonConfigLocalization setup,
      String outputFileParam,
      String outputDirectoryParam)
      throws CommandException {
    if (hasText(outputFileParam) && hasText(outputDirectoryParam)) {
      throw new CommandException("Use either --output or --output-dir, not both");
    }

    ExportResult result =
        setup == null
            ? jsonConfigLocalizationClient.exportForRepository(repository.getId())
            : jsonConfigLocalizationClient.exportForSetup(setup.id());
    if (result == null || result.json() == null) {
      throw new CommandException("JSON config export returned no JSON");
    }

    printWarnings(result.warnings() == null ? List.of() : result.warnings());
    if (hasText(outputDirectoryParam)) {
      writeLocaleFiles(result.json(), Path.of(outputDirectoryParam));
    } else if (!hasText(outputFileParam)) {
      consoleWriter.a(result.json()).println();
    } else {
      Path outputPath = Path.of(outputFileParam);
      commandHelper.writeFileContent(result.json() + "\n", outputPath);
      consoleWriter
          .fg(Color.GREEN)
          .a("Wrote localized JSON config to ")
          .a(outputPath.toString())
          .println();
    }
  }

  protected void writeResponse(String json, String outputFileParam) throws CommandException {
    if (!hasText(outputFileParam)) {
      return;
    }
    Path outputPath = Path.of(outputFileParam);
    commandHelper.writeFileContent(json + "\n", outputPath);
    consoleWriter.fg(Color.GREEN).a("Wrote response JSON to ").a(outputPath.toString()).println();
  }

  protected void triggerMachineTranslation(Repository repository, int sourceTextMaxCount)
      throws CommandException {
    List<String> targetLocaleTags = getTargetLocaleTags(repository);
    if (targetLocaleTags.isEmpty()) {
      consoleWriter.a("No target locales configured; skipped machine translation.").println();
      return;
    }

    RepositoryMachineTranslationBody body = new RepositoryMachineTranslationBody();
    body.setRepositoryName(repository.getName());
    body.setTargetBcp47tags(targetLocaleTags);
    body.setSourceTextMaxCountPerLocale(sourceTextMaxCount);

    RepositoryMachineTranslationBody response =
        repositoryMachineTranslationClient.translateRepository(body);
    commandHelper.waitForPollableTask(response.getPollableTask().getId());
  }

  protected SourceConfigProfile getProfileOverrides(
      String formatParam,
      String collectionKeyParam,
      String itemIdFieldParam,
      String translationsFieldParam,
      String sourceLocaleParam,
      String translatableFieldsParam,
      String sourceFieldParam,
      String commentFieldParam) {
    List<String> fields = parseFields(translatableFieldsParam);
    if (collectionKeyParam == null
        && itemIdFieldParam == null
        && translationsFieldParam == null
        && sourceLocaleParam == null
        && fields.isEmpty()
        && formatParam == null
        && sourceFieldParam == null
        && commentFieldParam == null) {
      return null;
    }

    return new SourceConfigProfile(
        normalizeFormat(formatParam),
        collectionKeyParam,
        itemIdFieldParam,
        translationsFieldParam,
        sourceLocaleParam,
        fields,
        sourceFieldParam,
        commentFieldParam);
  }

  protected Map<String, String> parseLocaleMapping(
      String mappingText, List<String> mojitoLocaleTags, List<String> warnings) {
    if (mappingText == null || mappingText.isBlank()) {
      return Map.of();
    }

    String spec = stripLocaleMappingFlag(mappingText);
    Set<String> mojitoLocales = new HashSet<>(mojitoLocaleTags);
    Map<String, String> mapping = new LinkedHashMap<>();

    for (String rawEntry : spec.split("[,\\n]+")) {
      String entry = rawEntry.trim();
      if (entry.isBlank()) {
        continue;
      }
      String[] parts = entry.split(":", 2);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        warnings.add("Skipped invalid locale mapping \"" + entry + "\".");
        continue;
      }

      String left = parts[0].trim();
      String right = parts[1].trim();
      if (mojitoLocales.contains(right)) {
        mapping.put(right, left);
      } else if (mojitoLocales.contains(left)) {
        mapping.put(left, right);
      } else {
        warnings.add(
            "Skipped \"" + entry + "\" because neither side matches a Mojito target locale.");
      }
    }

    return mapping;
  }

  protected List<String> getTargetLocaleTags(Repository repository) {
    String sourceLocaleTag = getRepositorySourceLocale(repository);
    return repository.getRepositoryLocales().stream()
        .map(RepositoryLocale::getLocale)
        .filter(locale -> locale != null && locale.getBcp47Tag() != null)
        .map(locale -> locale.getBcp47Tag())
        .filter(localeTag -> !localeTag.equals(sourceLocaleTag))
        .distinct()
        .sorted()
        .toList();
  }

  protected String normalizedAssetPath(String assetPathParam) {
    return assetPathParam == null || assetPathParam.isBlank()
        ? DEFAULT_ASSET_PATH
        : assetPathParam.trim();
  }

  protected String normalizedAssetPath(
      String assetPathParam, String setupName, boolean repositoryAlreadyHasSetups) {
    if (assetPathParam != null && !assetPathParam.isBlank()) {
      return assetPathParam.trim();
    }
    if (!repositoryAlreadyHasSetups) {
      return DEFAULT_ASSET_PATH;
    }
    String slug =
        firstNonBlank(setupName, "json-config")
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    return slug.isBlank()
        ? DEFAULT_ASSET_PATH
        : "json-config-localization/" + slug + "/strings.json";
  }

  protected JsonConfigLocalization resolveSetup(
      Repository repository, String providerConfigIdParam, boolean required) {
    List<JsonConfigLocalization> setups =
        jsonConfigLocalizationClient.getSetupsByRepositoryId(repository.getId());
    JsonConfigLocalization setup = findSetup(setups, setupParam, providerConfigIdParam);
    if (setup != null) {
      return setup;
    }

    if (!required) {
      return null;
    }
    if (setupParam != null && !setupParam.isBlank()) {
      throw new CommandException("JSON config setup not found: " + setupParam);
    }
    if (providerConfigIdParam != null && !providerConfigIdParam.isBlank()) {
      throw new CommandException(
          "JSON config setup not found for provider config id: " + providerConfigIdParam);
    }
    if (setups.isEmpty()) {
      throw new CommandException("Repository has no JSON config setup.");
    }
    if (setups.size() > 1) {
      throw new CommandException(
          "Repository has multiple JSON config setups; pass --setup or --config-id.");
    }
    return setups.getFirst();
  }

  protected JsonConfigLocalization resolveOrCreateSetup(
      Repository repository,
      String setupName,
      String assetPathParam,
      String provider,
      String providerConfigIdParam) {
    List<JsonConfigLocalization> setups =
        jsonConfigLocalizationClient.getSetupsByRepositoryId(repository.getId());
    JsonConfigLocalization existingSetup = findSetup(setups, setupParam, providerConfigIdParam);
    if (existingSetup != null) {
      return existingSetup;
    }
    if ((setupParam == null || setupParam.isBlank())
        && (providerConfigIdParam == null || providerConfigIdParam.isBlank())) {
      if (setups.size() == 1) {
        return setups.getFirst();
      }
      if (setups.size() > 1) {
        throw new CommandException(
            "Repository has multiple JSON config setups; pass --setup or --config-id.");
      }
    }

    String name =
        firstNonBlank(
            setupParam,
            firstNonBlank(providerConfigIdParam, firstNonBlank(setupName, repository.getName())));
    String assetPath = normalizedAssetPath(assetPathParam, name, !setups.isEmpty());
    return jsonConfigLocalizationClient.createForRepository(
        repository.getId(),
        new JsonConfigLocalizationInput(
            name,
            assetPath,
            provider,
            providerConfigIdParam,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  protected String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first.trim();
  }

  protected String writeJson(Object value) throws CommandException {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new CommandException("Unable to serialize JSON", e);
    }
  }

  protected void printWarnings(List<String> warnings) {
    warnings.stream()
        .distinct()
        .forEach(warning -> consoleWriter.fg(Color.YELLOW).a(warning).println());
  }

  protected boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void writeLocaleFiles(String exportJson, Path outputDirectory) throws CommandException {
    ObjectNode localeMap = readLocaleMap(exportJson);
    try {
      Files.createDirectories(outputDirectory);
    } catch (IOException e) {
      throw new CommandException(
          "Cannot create output directory: " + outputDirectory.toString(), e);
    }

    int fileCount = 0;
    for (Map.Entry<String, JsonNode> localeEntry : localeMap.properties()) {
      String localeTag = localeEntry.getKey();
      if (!looksLikeLocaleTag(localeTag) || !localeEntry.getValue().isObject()) {
        throw new CommandException(
            "Export JSON does not look like a locale map; use --output for embedded config exports");
      }

      Path outputPath = outputDirectory.resolve(toSafeLocaleFilename(localeTag) + ".json");
      commandHelper.writeFileContent(prettyJson(localeEntry.getValue()) + "\n", outputPath);
      fileCount++;
    }

    consoleWriter
        .fg(Color.GREEN)
        .a("Wrote ")
        .a(fileCount)
        .a(" locale JSON files to ")
        .a(outputDirectory.toString())
        .println();
  }

  private ObjectNode readLocaleMap(String exportJson) throws CommandException {
    try {
      JsonNode root = objectMapper.readTree(exportJson);
      if (root instanceof ObjectNode objectNode) {
        return objectNode;
      }
    } catch (JsonProcessingException e) {
      throw new CommandException("Unable to parse exported JSON", e);
    }
    throw new CommandException("Export JSON root must be an object");
  }

  private String getRepositorySourceLocale(Repository repository) {
    if (repository.getSourceLocale() != null
        && repository.getSourceLocale().getBcp47Tag() != null) {
      return repository.getSourceLocale().getBcp47Tag();
    }
    return "en";
  }

  private List<String> parseFields(String fields) {
    if (fields == null || fields.isBlank()) {
      return List.of();
    }
    return List.of(fields.split(",")).stream()
        .map(String::trim)
        .filter(field -> !field.isBlank())
        .toList();
  }

  private String stripLocaleMappingFlag(String mappingText) {
    String spec = mappingText.trim().replaceFirst("^-lm(?:=|\\s+)?", "").trim();
    if (spec.length() >= 2
        && (spec.startsWith("'") && spec.endsWith("'")
            || spec.startsWith("\"") && spec.endsWith("\""))) {
      return spec.substring(1, spec.length() - 1).trim();
    }
    return spec;
  }

  private String prettyJson(Object value) throws CommandException {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new CommandException("Unable to serialize JSON", e);
    }
  }

  private String normalizeFormat(String format) {
    if (format == null || format.isBlank()) {
      return null;
    }
    return switch (format.trim().toLowerCase()) {
      case "embedded", "embedded_translations" -> "EMBEDDED_TRANSLATIONS";
      case "flat", "flat_source_array" -> "FLAT_SOURCE_ARRAY";
      case "formatjs", "formatjs_map" -> "FORMATJS_MAP";
      case "formatjs_multilingual", "formatjs_multilingual_map", "multilingual_formatjs" ->
          "FORMATJS_MULTILINGUAL_MAP";
      default -> format.trim();
    };
  }

  private boolean looksLikeLocaleTag(String value) {
    return value.matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
  }

  private String toSafeLocaleFilename(String localeTag) {
    return localeTag.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private JsonConfigLocalization findSetup(
      List<JsonConfigLocalization> setups, String setupNameOrId, String providerConfigIdParam) {
    if (setupNameOrId != null && !setupNameOrId.isBlank()) {
      String selector = setupNameOrId.trim();
      return setups.stream()
          .filter(
              setup -> selector.equals(String.valueOf(setup.id())) || selector.equals(setup.name()))
          .findFirst()
          .orElse(null);
    }
    if (providerConfigIdParam != null && !providerConfigIdParam.isBlank()) {
      String providerConfigId = providerConfigIdParam.trim();
      return setups.stream()
          .filter(setup -> providerConfigId.equals(setup.providerConfigId()))
          .findFirst()
          .orElse(null);
    }
    return null;
  }
}
