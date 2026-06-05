package com.box.l10n.mojito.rest.client;

import com.box.l10n.mojito.rest.entity.PollableTask;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class JsonConfigLocalizationClient extends BaseClient {

  @Override
  public String getEntityName() {
    return "json-config-localizations";
  }

  public List<JsonConfigLocalization> getAll() {
    JsonConfigLocalization[] localizations =
        authenticatedRestTemplate.getForObject(
            getBasePathForEntity(), JsonConfigLocalization[].class);
    return Arrays.asList(localizations);
  }

  public JsonConfigLocalization getByRepositoryId(Long repositoryId) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString())
            .toUriString();
    return authenticatedRestTemplate.getForObject(path, JsonConfigLocalization.class);
  }

  public List<JsonConfigLocalization> getSetupsByRepositoryId(Long repositoryId) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString(), "setups")
            .toUriString();
    JsonConfigLocalization[] localizations =
        authenticatedRestTemplate.getForObject(path, JsonConfigLocalization[].class);
    return Arrays.asList(localizations);
  }

  public JsonConfigLocalization upsertForRepository(
      Long repositoryId, JsonConfigLocalizationInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString())
            .toUriString();
    return authenticatedRestTemplate.putForObject(path, input, JsonConfigLocalization.class);
  }

  public JsonConfigLocalization createForRepository(
      Long repositoryId, JsonConfigLocalizationInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString())
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, JsonConfigLocalization.class);
  }

  public DetectMappingResult detectMapping(DetectMappingInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("detect-mapping")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, DetectMappingResult.class);
  }

  public ExtractionResult extract(ExtractionInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity()).pathSegment("extract").toUriString();
    return authenticatedRestTemplate.postForObject(path, input, ExtractionResult.class);
  }

  public ExtractForRepositoryResult extractForRepository(
      Long repositoryId, ExtractForRepositoryInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString(), "extract")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, ExtractForRepositoryResult.class);
  }

  public ExtractForRepositoryResult extractForSetup(Long setupId, ExtractForRepositoryInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("setups", setupId.toString(), "extract")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, ExtractForRepositoryResult.class);
  }

  public ExportResult exportForRepository(Long repositoryId) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString(), "export")
            .toUriString();
    return authenticatedRestTemplate.getForObject(path, ExportResult.class);
  }

  public ExportResult exportForSetup(Long setupId) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("setups", setupId.toString(), "export")
            .toUriString();
    return authenticatedRestTemplate.getForObject(path, ExportResult.class);
  }

  public ExtractForRepositoryResult pullStatsig(Long repositoryId, StatsigPullInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString(), "statsig", "pull")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, ExtractForRepositoryResult.class);
  }

  public ExtractForRepositoryResult pullStatsigForSetup(Long setupId, StatsigPullInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("setups", setupId.toString(), "statsig", "pull")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, ExtractForRepositoryResult.class);
  }

  public StatsigPushResult pushStatsig(Long repositoryId, StatsigPushInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("repositories", repositoryId.toString(), "statsig", "push")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, StatsigPushResult.class);
  }

  public StatsigPushResult pushStatsigForSetup(Long setupId, StatsigPushInput input) {
    String path =
        UriComponentsBuilder.fromPath(getBasePathForEntity())
            .pathSegment("setups", setupId.toString(), "statsig", "push")
            .toUriString();
    return authenticatedRestTemplate.postForObject(path, input, StatsigPushResult.class);
  }

  public record JsonConfigLocalization(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      RepositoryRef repository,
      String assetPath,
      String provider,
      String providerConfigId,
      String statsigConsoleUrl,
      String schemaJson,
      String sourceConfigJson,
      String extractionMappingJson,
      String outputLocaleMappingJson,
      boolean automationEnabled,
      String automationCronExpression,
      String automationTimeZone,
      String automationOptionsJson) {}

  public record JsonConfigLocalizationInput(
      String name,
      String assetPath,
      String provider,
      String providerConfigId,
      String schemaJson,
      String sourceConfigJson,
      String extractionMappingJson,
      String outputLocaleMappingJson,
      Boolean automationEnabled,
      String automationCronExpression,
      String automationTimeZone,
      String automationOptionsJson) {}

  public record RepositoryRef(
      Long id, String name, String sourceLocaleTag, int targetLocaleCount) {}

  public record SourceConfigProfile(
      String format,
      String collectionKey,
      String itemIdField,
      String translationsField,
      String sourceLocaleTag,
      List<String> translatableFields,
      String sourceField,
      String commentField) {

    public SourceConfigProfile(
        String collectionKey,
        String itemIdField,
        String translationsField,
        String sourceLocaleTag,
        List<String> translatableFields) {
      this(
          null,
          collectionKey,
          itemIdField,
          translationsField,
          sourceLocaleTag,
          translatableFields,
          null,
          null);
    }
  }

  public record JsonConfigString(
      String stringId, String source, String comment, boolean used, boolean doNotTranslate) {}

  public record DetectMappingInput(String schemaJson) {}

  public record DetectMappingResult(SourceConfigProfile profile, List<String> warnings) {}

  public record ExtractionInput(
      String schemaJson, String sourceConfigJson, SourceConfigProfile profile) {}

  public record ExtractionResult(
      SourceConfigProfile profile,
      String sourceConfigJson,
      List<JsonConfigString> strings,
      List<String> warnings) {}

  public record ExtractForRepositoryInput(
      String name,
      String assetPath,
      String provider,
      String providerConfigId,
      String schemaJson,
      String sourceConfigJson,
      SourceConfigProfile profile,
      List<JsonConfigString> strings,
      String outputLocaleMappingJson) {}

  public record ExtractForRepositoryResult(
      JsonConfigLocalization setup,
      List<JsonConfigString> strings,
      List<String> warnings,
      PollableTask pollableTask) {}

  public record ExportResult(String json, List<String> warnings) {}

  public record StatsigPullInput(
      String configId,
      String assetPath,
      SourceConfigProfile profile,
      String outputLocaleMappingJson,
      Boolean extract) {}

  public record StatsigPushInput(String configId, Boolean dryRun) {}

  public record StatsigPushResult(
      String configId,
      boolean dryRun,
      boolean skipped,
      String responseJson,
      List<String> warnings) {}
}
