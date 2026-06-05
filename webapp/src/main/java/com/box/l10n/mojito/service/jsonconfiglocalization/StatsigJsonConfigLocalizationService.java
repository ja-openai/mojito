package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.asset.VirtualAssetRequiredException;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExportResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractForRepositoryInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractForRepositoryResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.SourceConfigProfile;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalization;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StatsigJsonConfigLocalizationService {

  private final ObjectMapper objectMapper;
  private final JsonConfigLocalizationService jsonConfigLocalizationService;
  private final JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService;
  private final StatsigJsonConfigLocalizationProperties properties;
  private final HttpClient httpClient;

  @Autowired
  public StatsigJsonConfigLocalizationService(
      ObjectMapper objectMapper,
      JsonConfigLocalizationService jsonConfigLocalizationService,
      JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService,
      StatsigJsonConfigLocalizationProperties properties) {
    this(
        objectMapper,
        jsonConfigLocalizationService,
        jsonConfigLocalizationProcessorService,
        properties,
        HttpClient.newHttpClient());
  }

  StatsigJsonConfigLocalizationService(
      ObjectMapper objectMapper,
      JsonConfigLocalizationService jsonConfigLocalizationService,
      JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService,
      StatsigJsonConfigLocalizationProperties properties,
      HttpClient httpClient) {
    this.objectMapper = objectMapper;
    this.jsonConfigLocalizationService = jsonConfigLocalizationService;
    this.jsonConfigLocalizationProcessorService = jsonConfigLocalizationProcessorService;
    this.properties = properties;
    this.httpClient = httpClient;
  }

  public ExtractForRepositoryResult pull(Long repositoryId, StatsigPullInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return pull(repositoryId, input, false);
  }

  public ExtractForRepositoryResult pullForSystem(Long repositoryId, StatsigPullInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return pull(repositoryId, input, true);
  }

  public ExtractForRepositoryResult pullForSetup(Long setupId, StatsigPullInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return pullForSetup(setupId, input, false);
  }

  public ExtractForRepositoryResult pullForSetupForSystem(Long setupId, StatsigPullInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return pullForSetup(setupId, input, true);
  }

  private ExtractForRepositoryResult pull(
      Long repositoryId, StatsigPullInput input, boolean systemAccess)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    String configId = requireConfigId(input == null ? null : input.configId());
    StatsigDynamicConfig dynamicConfig = fetchDynamicConfig(configId);
    boolean extract = input == null || input.extract() == null || input.extract();

    if (!extract) {
      JsonConfigLocalization setup =
          systemAccess
              ? jsonConfigLocalizationService.upsertForRepositoryForSystem(
                  repositoryId,
                  new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                      firstNonBlank(dynamicConfig.name(), configId),
                      input == null ? null : input.assetPath(),
                      JsonConfigLocalizationService.PROVIDER_STATSIG,
                      configId,
                      dynamicConfig.schemaJson(),
                      dynamicConfig.sourceConfigJson(),
                      input == null || input.profile() == null ? null : writeJson(input.profile()),
                      input == null ? null : input.outputLocaleMappingJson(),
                      null,
                      null,
                      null,
                      null,
                      input == null ? null : input.expectedLastModifiedDate()))
              : jsonConfigLocalizationService.upsertForRepository(
                  repositoryId,
                  new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                      firstNonBlank(dynamicConfig.name(), configId),
                      input == null ? null : input.assetPath(),
                      JsonConfigLocalizationService.PROVIDER_STATSIG,
                      configId,
                      dynamicConfig.schemaJson(),
                      dynamicConfig.sourceConfigJson(),
                      input == null || input.profile() == null ? null : writeJson(input.profile()),
                      input == null ? null : input.outputLocaleMappingJson(),
                      null,
                      null,
                      null,
                      null,
                      input == null ? null : input.expectedLastModifiedDate()));
      return new ExtractForRepositoryResult(setup, List.of(), List.of(), null);
    }

    ExtractForRepositoryInput extractInput =
        new ExtractForRepositoryInput(
            firstNonBlank(dynamicConfig.name(), configId),
            input == null ? null : input.assetPath(),
            JsonConfigLocalizationService.PROVIDER_STATSIG,
            configId,
            dynamicConfig.schemaJson(),
            dynamicConfig.sourceConfigJson(),
            input == null ? null : input.profile(),
            null,
            input == null ? null : input.outputLocaleMappingJson(),
            input == null ? null : input.expectedLastModifiedDate());
    return systemAccess
        ? jsonConfigLocalizationProcessorService.extractForRepositoryForSystem(
            repositoryId, extractInput)
        : jsonConfigLocalizationProcessorService.extractForRepository(repositoryId, extractInput);
  }

  private ExtractForRepositoryResult pullForSetup(
      Long setupId, StatsigPullInput input, boolean systemAccess)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    JsonConfigLocalization setup =
        systemAccess
            ? jsonConfigLocalizationService.getByIdForSystem(setupId)
            : jsonConfigLocalizationService.getById(setupId);
    String configId =
        requireConfigId(
            firstNonBlank(input == null ? null : input.configId(), setup.providerConfigId()));
    StatsigDynamicConfig dynamicConfig = fetchDynamicConfig(configId);
    boolean extract = input == null || input.extract() == null || input.extract();

    if (!extract) {
      JsonConfigLocalization updatedSetup =
          systemAccess
              ? jsonConfigLocalizationService.updateForSystem(
                  setupId,
                  new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                      firstNonBlank(dynamicConfig.name(), setup.name()),
                      input == null
                          ? setup.assetPath()
                          : firstNonBlank(input.assetPath(), setup.assetPath()),
                      JsonConfigLocalizationService.PROVIDER_STATSIG,
                      configId,
                      dynamicConfig.schemaJson(),
                      dynamicConfig.sourceConfigJson(),
                      input == null || input.profile() == null
                          ? setup.extractionMappingJson()
                          : writeJson(input.profile()),
                      input == null
                          ? setup.outputLocaleMappingJson()
                          : firstNonBlank(
                              input.outputLocaleMappingJson(), setup.outputLocaleMappingJson()),
                      setup.automationEnabled(),
                      setup.automationCronExpression(),
                      setup.automationTimeZone(),
                      setup.automationOptionsJson(),
                      input == null ? null : input.expectedLastModifiedDate()))
              : jsonConfigLocalizationService.update(
                  setupId,
                  new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                      firstNonBlank(dynamicConfig.name(), setup.name()),
                      input == null
                          ? setup.assetPath()
                          : firstNonBlank(input.assetPath(), setup.assetPath()),
                      JsonConfigLocalizationService.PROVIDER_STATSIG,
                      configId,
                      dynamicConfig.schemaJson(),
                      dynamicConfig.sourceConfigJson(),
                      input == null || input.profile() == null
                          ? setup.extractionMappingJson()
                          : writeJson(input.profile()),
                      input == null
                          ? setup.outputLocaleMappingJson()
                          : firstNonBlank(
                              input.outputLocaleMappingJson(), setup.outputLocaleMappingJson()),
                      setup.automationEnabled(),
                      setup.automationCronExpression(),
                      setup.automationTimeZone(),
                      setup.automationOptionsJson(),
                      input == null ? null : input.expectedLastModifiedDate()));
      return new ExtractForRepositoryResult(updatedSetup, List.of(), List.of(), null);
    }

    ExtractForRepositoryInput extractInput =
        new ExtractForRepositoryInput(
            firstNonBlank(dynamicConfig.name(), setup.name()),
            input == null ? setup.assetPath() : firstNonBlank(input.assetPath(), setup.assetPath()),
            JsonConfigLocalizationService.PROVIDER_STATSIG,
            configId,
            dynamicConfig.schemaJson(),
            dynamicConfig.sourceConfigJson(),
            input == null ? null : input.profile(),
            null,
            input == null
                ? setup.outputLocaleMappingJson()
                : firstNonBlank(input.outputLocaleMappingJson(), setup.outputLocaleMappingJson()),
            input == null ? null : input.expectedLastModifiedDate());
    return systemAccess
        ? jsonConfigLocalizationProcessorService.extractForSetupForSystem(setupId, extractInput)
        : jsonConfigLocalizationProcessorService.extractForSetup(setupId, extractInput);
  }

  public StatsigPushResult push(Long repositoryId, StatsigPushInput input) {
    JsonConfigLocalization setup = jsonConfigLocalizationService.getByRepositoryId(repositoryId);
    return push(repositoryId, input, setup, false);
  }

  public StatsigPushResult pushForSetup(Long setupId, StatsigPushInput input) {
    JsonConfigLocalization setup = jsonConfigLocalizationService.getById(setupId);
    return push(setup.repository().id(), input, setup, false);
  }

  public StatsigPushResult pushForSystem(Long repositoryId) {
    JsonConfigLocalization setup =
        jsonConfigLocalizationService.getByRepositoryIdForSystem(repositoryId);
    return push(repositoryId, new StatsigPushInput(setup.providerConfigId(), null), setup, true);
  }

  public StatsigPushResult pushForSetupForSystem(Long setupId) {
    JsonConfigLocalization setup = jsonConfigLocalizationService.getByIdForSystem(setupId);
    return push(
        setup.repository().id(), new StatsigPushInput(setup.providerConfigId(), null), setup, true);
  }

  private StatsigPushResult push(
      Long repositoryId,
      StatsigPushInput input,
      JsonConfigLocalization setup,
      boolean systemAccess) {
    String configId =
        requireConfigId(
            firstNonBlank(input == null ? null : input.configId(), setup.providerConfigId()));
    boolean dryRun =
        input != null && input.dryRun() != null ? input.dryRun() : properties.isDryRunPush();

    ExportResult exportResult =
        systemAccess
            ? jsonConfigLocalizationProcessorService.exportForSetupForSystem(setup.id())
            : jsonConfigLocalizationProcessorService.exportForSetup(setup.id());
    JsonNode localizedConfig = readJson(exportResult.json(), "localized config export");

    List<String> warnings = new ArrayList<>();
    if (exportResult.warnings() != null) {
      warnings.addAll(exportResult.warnings());
    }

    StatsigDynamicConfig currentConfig = fetchDynamicConfig(configId);
    JsonNode currentDefaultValue =
        readJson(currentConfig.sourceConfigJson(), "current Statsig config");
    if (currentDefaultValue.equals(localizedConfig)) {
      warnings.add("Statsig config already matches Mojito output; skipped push.");
      return new StatsigPushResult(
          configId, dryRun, true, writeJson(currentDefaultValue), warnings);
    }

    ObjectNode update = objectMapper.createObjectNode();
    update.set("defaultValue", localizedConfig);
    JsonNode response = send("PATCH", configPath(configId, dryRun), update);

    if (dryRun) {
      warnings.add("Statsig push ran in dry-run mode; no remote config was changed.");
    }

    return new StatsigPushResult(configId, dryRun, false, writeJson(response), warnings);
  }

  private StatsigDynamicConfig fetchDynamicConfig(String configId) {
    JsonNode response = send("GET", configPath(configId, false), null);
    JsonNode data = response.get("data");
    if (!(data instanceof ObjectNode dataObject)) {
      throw new IllegalArgumentException(
          "Statsig response did not include a dynamic config object.");
    }

    String schemaJson = text(dataObject.get("schema"));
    if ((schemaJson == null || schemaJson.isBlank()) && dataObject.get("schemaJson5") != null) {
      schemaJson = text(dataObject.get("schemaJson5"));
    }

    JsonNode defaultValue = dataObject.get("defaultValue");
    String sourceConfigJson;
    if (defaultValue != null && !defaultValue.isNull()) {
      sourceConfigJson = writeJson(defaultValue);
    } else {
      sourceConfigJson = text(dataObject.get("defaultValueJson5"));
    }

    if (sourceConfigJson == null || sourceConfigJson.isBlank()) {
      throw new IllegalArgumentException(
          "Statsig dynamic config has no defaultValue or defaultValueJson5 to localize.");
    }

    return new StatsigDynamicConfig(
        configId,
        firstNonBlank(text(dataObject.get("name")), configId),
        schemaJson,
        sourceConfigJson);
  }

  private JsonNode send(String method, String path, JsonNode body) {
    requireConfigured();
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(uri(path))
              .header("Accept", "application/json")
              .header("STATSIG-API-KEY", properties.getApiKey().trim());
      if (properties.getApiVersion() != null && !properties.getApiVersion().isBlank()) {
        requestBuilder.header("STATSIG-API-VERSION", properties.getApiVersion().trim());
      }

      if (body == null) {
        requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        requestBuilder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
      }

      HttpResponse<String> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalArgumentException(
            "Statsig "
                + method
                + " failed with status "
                + response.statusCode()
                + ": "
                + truncate(redactSecrets(response.body())));
      }
      return readJson(response.body(), "Statsig response");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Statsig request was interrupted", e);
    } catch (IOException e) {
      throw new IllegalArgumentException("Statsig request failed: " + e.getMessage(), e);
    }
  }

  private URI uri(String path) {
    String baseUrl =
        properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
            ? "https://statsigapi.net/console/v1"
            : properties.getBaseUrl().trim();
    return URI.create(stripTrailingSlash(baseUrl) + path);
  }

  private String configPath(String configId, boolean dryRun) {
    String encoded = URLEncoder.encode(configId, StandardCharsets.UTF_8).replace("+", "%20");
    return "/dynamic_configs/" + encoded + (dryRun ? "?dryRun=true" : "");
  }

  private String requireConfigId(String configId) {
    if (configId == null || configId.isBlank()) {
      throw new IllegalArgumentException("Statsig config id is required.");
    }
    return configId.trim();
  }

  private void requireConfigured() {
    if (!properties.isConfigured()) {
      throw new IllegalArgumentException(
          "Statsig integration is not configured. Set l10n.json-config-localization.statsig.api-key.");
    }
  }

  private JsonNode readJson(String json, String label) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Invalid " + label + " JSON: " + e.getOriginalMessage(), e);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to write JSON: " + e.getMessage(), e);
    }
  }

  private String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first.trim();
  }

  private String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String truncate(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= 500 ? value : value.substring(0, 500) + "...";
  }

  private String redactSecrets(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("console-[A-Za-z0-9_\\-]+", "console-[redacted]");
  }

  public record StatsigPullInput(
      String configId,
      String assetPath,
      SourceConfigProfile profile,
      String outputLocaleMappingJson,
      Boolean extract,
      ZonedDateTime expectedLastModifiedDate) {

    public StatsigPullInput(
        String configId,
        String assetPath,
        SourceConfigProfile profile,
        String outputLocaleMappingJson,
        Boolean extract) {
      this(configId, assetPath, profile, outputLocaleMappingJson, extract, null);
    }
  }

  public record StatsigPushInput(String configId, Boolean dryRun) {}

  public record StatsigPushResult(
      String configId,
      boolean dryRun,
      boolean skipped,
      String responseJson,
      List<String> warnings) {}

  private record StatsigDynamicConfig(
      String configId, String name, String schemaJson, String sourceConfigJson) {}
}
