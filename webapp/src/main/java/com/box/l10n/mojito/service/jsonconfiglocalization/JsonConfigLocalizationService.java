package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.JsonConfigLocalizationEntity;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class JsonConfigLocalizationService {

  public static final String DEFAULT_ASSET_PATH = "json-config-localization/strings.json";
  public static final String PROVIDER_GENERIC_JSON = "GENERIC_JSON";
  public static final String PROVIDER_STATSIG = "STATSIG";

  private final JsonConfigLocalizationRepository jsonConfigLocalizationRepository;
  private final com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  private final UserService userService;
  private final ObjectProvider<JsonConfigLocalizationCronSchedulerService>
      cronSchedulerServiceProvider;
  private final StatsigJsonConfigLocalizationProperties statsigProperties;

  public JsonConfigLocalizationService(
      JsonConfigLocalizationRepository jsonConfigLocalizationRepository,
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      UserService userService,
      ObjectProvider<JsonConfigLocalizationCronSchedulerService> cronSchedulerServiceProvider,
      StatsigJsonConfigLocalizationProperties statsigProperties) {
    this.jsonConfigLocalizationRepository = jsonConfigLocalizationRepository;
    this.repositoryRepository = repositoryRepository;
    this.userService = userService;
    this.cronSchedulerServiceProvider = cronSchedulerServiceProvider;
    this.statsigProperties = statsigProperties;
  }

  @Transactional(readOnly = true)
  public List<JsonConfigLocalization> getAll() {
    requireAdminOrPm();
    return jsonConfigLocalizationRepository.findAllByOrderByNameAscIdAsc().stream()
        .map(this::toRecord)
        .toList();
  }

  @Transactional(readOnly = true)
  public JsonConfigLocalization getByRepositoryId(Long repositoryId) {
    requireAdminOrPm();
    return getByRepositoryIdForSystem(repositoryId);
  }

  @Transactional(readOnly = true)
  public List<JsonConfigLocalization> getAllByRepositoryId(Long repositoryId) {
    requireAdminOrPm();
    return getAllByRepositoryIdForSystem(repositoryId);
  }

  @Transactional(readOnly = true)
  public JsonConfigLocalization getByRepositoryIdForSystem(Long repositoryId) {
    return jsonConfigLocalizationRepository
        .findFirstByRepositoryIdOrderByNameAscIdAsc(repositoryId)
        .map(this::toRecord)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "JSON config localization not found for repository: " + repositoryId));
  }

  @Transactional(readOnly = true)
  public List<JsonConfigLocalization> getAllByRepositoryIdForSystem(Long repositoryId) {
    validateRepositoryId(repositoryId);
    return jsonConfigLocalizationRepository
        .findByRepositoryIdOrderByNameAscIdAsc(repositoryId)
        .stream()
        .map(this::toRecord)
        .toList();
  }

  @Transactional(readOnly = true)
  public JsonConfigLocalization getById(Long setupId) {
    requireAdminOrPm();
    return getByIdForSystem(setupId);
  }

  @Transactional(readOnly = true)
  public JsonConfigLocalization getByIdForSystem(Long setupId) {
    validateSetupId(setupId);
    return jsonConfigLocalizationRepository
        .findById(setupId)
        .map(this::toRecord)
        .orElseThrow(
            () -> new IllegalArgumentException("JSON config localization not found: " + setupId));
  }

  @Transactional
  public JsonConfigLocalization upsertForRepository(
      Long repositoryId, JsonConfigLocalizationInput input) {
    requireAdminOrPm();
    return upsertForRepositoryForSystem(repositoryId, input);
  }

  @Transactional
  public JsonConfigLocalization upsertForRepositoryForSystem(
      Long repositoryId, JsonConfigLocalizationInput input) {
    if (input == null) {
      throw new IllegalArgumentException("JSON config localization input is required");
    }

    JsonConfigLocalizationEntity entity =
        jsonConfigLocalizationRepository
            .findFirstByRepositoryIdOrderByNameAscIdAsc(repositoryId)
            .orElseGet(JsonConfigLocalizationEntity::new);
    Repository repository = requireRepository(repositoryId);
    assertExpectedLastModifiedDate(entity, input.expectedLastModifiedDate());
    applyInput(entity, repository, input, false);

    JsonConfigLocalization result = toRecord(jsonConfigLocalizationRepository.save(entity));
    syncSchedulerAfterCommit();
    return result;
  }

  @Transactional
  public JsonConfigLocalization createForRepository(
      Long repositoryId, JsonConfigLocalizationInput input) {
    requireAdminOrPm();
    return createForRepositoryForSystem(repositoryId, input);
  }

  @Transactional
  public JsonConfigLocalization createForRepositoryForSystem(
      Long repositoryId, JsonConfigLocalizationInput input) {
    Repository repository = requireRepository(repositoryId);
    JsonConfigLocalizationEntity entity = new JsonConfigLocalizationEntity();
    applyInput(entity, repository, input, true);

    JsonConfigLocalization result = toRecord(jsonConfigLocalizationRepository.save(entity));
    syncSchedulerAfterCommit();
    return result;
  }

  @Transactional
  public JsonConfigLocalization update(Long setupId, JsonConfigLocalizationInput input) {
    requireAdminOrPm();
    return updateForSystem(setupId, input);
  }

  @Transactional
  public JsonConfigLocalization updateForSystem(Long setupId, JsonConfigLocalizationInput input) {
    validateSetupId(setupId);
    if (input == null) {
      throw new IllegalArgumentException("JSON config localization input is required");
    }

    JsonConfigLocalizationEntity entity =
        jsonConfigLocalizationRepository
            .findById(setupId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException("JSON config localization not found: " + setupId));
    assertExpectedLastModifiedDate(entity, input.expectedLastModifiedDate());
    applyInput(entity, entity.getRepository(), input, false);

    JsonConfigLocalization result = toRecord(jsonConfigLocalizationRepository.save(entity));
    syncSchedulerAfterCommit();
    return result;
  }

  private void applyInput(
      JsonConfigLocalizationEntity entity,
      Repository repository,
      JsonConfigLocalizationInput input,
      boolean create) {
    if (input == null) {
      throw new IllegalArgumentException("JSON config localization input is required");
    }

    Long existingId = entity.getId();
    entity.setRepository(repository);
    String name = normalizeName(input.name(), repository.getName());
    String assetPath =
        normalizeAssetPath(
            input.assetPath(),
            create
                ? defaultAssetPathForNewSetup(repository.getId(), name)
                : firstNonBlank(entity.getAssetPath(), DEFAULT_ASSET_PATH));
    assertUniqueName(repository.getId(), name, existingId);
    assertUniqueAssetPath(repository.getId(), assetPath, existingId);
    entity.setName(name);
    entity.setAssetPath(assetPath);
    entity.setProvider(normalizeProvider(input.provider()));
    entity.setProviderConfigId(normalizeProviderConfigId(input.providerConfigId()));
    entity.setSchemaJson(normalizeOptionalText(input.schemaJson()));
    entity.setSourceConfigJson(normalizeOptionalText(input.sourceConfigJson()));
    entity.setExtractionMappingJson(normalizeOptionalText(input.extractionMappingJson()));
    entity.setOutputLocaleMappingJson(normalizeOptionalText(input.outputLocaleMappingJson()));
    entity.setAutomationEnabled(Boolean.TRUE.equals(input.automationEnabled()));
    entity.setAutomationCronExpression(
        normalizeAutomationCronExpression(input.automationCronExpression()));
    entity.setAutomationTimeZone(normalizeAutomationTimeZone(input.automationTimeZone()));
    entity.setAutomationOptionsJson(normalizeOptionalText(input.automationOptionsJson()));
  }

  @Transactional
  public void deleteForRepository(Long repositoryId) {
    requireAdminOrPm();
    validateRepositoryId(repositoryId);

    jsonConfigLocalizationRepository
        .findByRepositoryIdOrderByNameAscIdAsc(repositoryId)
        .forEach(jsonConfigLocalizationRepository::delete);
    syncSchedulerAfterCommit();
  }

  @Transactional
  public void delete(Long setupId) {
    requireAdminOrPm();
    validateSetupId(setupId);
    jsonConfigLocalizationRepository
        .findById(setupId)
        .ifPresent(
            entity -> {
              jsonConfigLocalizationRepository.delete(entity);
              syncSchedulerAfterCommit();
            });
  }

  private void syncSchedulerAfterCommit() {
    if (cronSchedulerServiceProvider.getIfAvailable() == null) {
      return;
    }

    Runnable syncRunnable = () -> cronSchedulerServiceProvider.getObject().syncAll();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      syncRunnable.run();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            syncRunnable.run();
          }
        });
  }

  private JsonConfigLocalization toRecord(JsonConfigLocalizationEntity entity) {
    Repository repository = entity.getRepository();
    return new JsonConfigLocalization(
        entity.getId(),
        entity.getCreatedDate(),
        entity.getLastModifiedDate(),
        entity.getName(),
        toRepositoryRef(repository),
        entity.getAssetPath(),
        entity.getProvider(),
        entity.getProviderConfigId(),
        statsigConsoleUrl(entity),
        entity.getSchemaJson(),
        entity.getSourceConfigJson(),
        entity.getExtractionMappingJson(),
        entity.getOutputLocaleMappingJson(),
        Boolean.TRUE.equals(entity.getAutomationEnabled()),
        entity.getAutomationCronExpression(),
        entity.getAutomationTimeZone(),
        entity.getAutomationOptionsJson());
  }

  private String statsigConsoleUrl(JsonConfigLocalizationEntity entity) {
    if (!PROVIDER_STATSIG.equals(entity.getProvider())
        || entity.getProviderConfigId() == null
        || entity.getProviderConfigId().isBlank()) {
      return null;
    }
    return statsigProperties == null
        ? null
        : statsigProperties.getConsoleUrl(entity.getProviderConfigId());
  }

  private RepositoryRef toRepositoryRef(Repository repository) {
    String sourceLocaleTag =
        repository.getSourceLocale() == null ? null : repository.getSourceLocale().getBcp47Tag();
    int targetLocaleCount =
        (int)
            repository.getRepositoryLocales().stream()
                .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
                .count();
    return new RepositoryRef(
        repository.getId(), repository.getName(), sourceLocaleTag, targetLocaleCount);
  }

  private String normalizeName(String name, String defaultName) {
    String normalized =
        name == null || name.isBlank() ? defaultName : name.trim().replaceAll("\\s+", " ");
    if (normalized == null || normalized.isBlank()) {
      throw new IllegalArgumentException("Name is required");
    }
    if (normalized.length() > JsonConfigLocalizationEntity.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Name must be at most " + JsonConfigLocalizationEntity.NAME_MAX_LENGTH + " characters");
    }
    return normalized;
  }

  private String normalizeAssetPath(String assetPath, String defaultAssetPath) {
    String normalized =
        assetPath == null || assetPath.isBlank() ? defaultAssetPath : assetPath.trim();
    if (normalized.length() > JsonConfigLocalizationEntity.ASSET_PATH_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Asset path must be at most "
              + JsonConfigLocalizationEntity.ASSET_PATH_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private String defaultAssetPathForNewSetup(Long repositoryId, String name) {
    if (jsonConfigLocalizationRepository
        .findByRepositoryIdOrderByNameAscIdAsc(repositoryId)
        .isEmpty()) {
      return DEFAULT_ASSET_PATH;
    }

    String base =
        "json-config-localization/"
            + slug(name == null || name.isBlank() ? "config" : name)
            + "/strings.json";
    String candidate = base;
    int suffix = 2;
    while (jsonConfigLocalizationRepository.existsByRepositoryIdAndAssetPath(
        repositoryId, candidate)) {
      candidate =
          "json-config-localization/"
              + slug(name == null || name.isBlank() ? "config" : name)
              + "-"
              + suffix
              + "/strings.json";
      suffix++;
    }
    return candidate;
  }

  private String slug(String value) {
    String normalized =
        value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    return normalized.isBlank() ? "config" : normalized;
  }

  private void assertUniqueName(Long repositoryId, String name, Long existingId) {
    boolean exists =
        existingId == null
            ? jsonConfigLocalizationRepository.existsByRepositoryIdAndName(repositoryId, name)
            : jsonConfigLocalizationRepository.existsByRepositoryIdAndNameAndIdNot(
                repositoryId, name, existingId);
    if (exists) {
      throw new IllegalArgumentException(
          "JSON config localization setup name already exists in this repository: " + name);
    }
  }

  private void assertUniqueAssetPath(Long repositoryId, String assetPath, Long existingId) {
    boolean exists =
        existingId == null
            ? jsonConfigLocalizationRepository.existsByRepositoryIdAndAssetPath(
                repositoryId, assetPath)
            : jsonConfigLocalizationRepository.existsByRepositoryIdAndAssetPathAndIdNot(
                repositoryId, assetPath, existingId);
    if (exists) {
      throw new IllegalArgumentException(
          "JSON config localization asset path already exists in this repository: " + assetPath);
    }
  }

  private String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  private String normalizeOptionalText(String text) {
    if (text == null) {
      return null;
    }
    return text.isBlank() ? null : text;
  }

  private String normalizeProvider(String provider) {
    String normalized =
        provider == null || provider.isBlank()
            ? PROVIDER_GENERIC_JSON
            : provider.trim().toUpperCase().replace('-', '_');
    if (!PROVIDER_GENERIC_JSON.equals(normalized) && !PROVIDER_STATSIG.equals(normalized)) {
      throw new IllegalArgumentException(
          "Unsupported JSON config localization provider: " + provider);
    }
    if (normalized.length() > JsonConfigLocalizationEntity.PROVIDER_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Provider must be at most "
              + JsonConfigLocalizationEntity.PROVIDER_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private String normalizeProviderConfigId(String providerConfigId) {
    String normalized = normalizeOptionalText(providerConfigId);
    if (normalized != null
        && normalized.length() > JsonConfigLocalizationEntity.PROVIDER_CONFIG_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Provider config id must be at most "
              + JsonConfigLocalizationEntity.PROVIDER_CONFIG_ID_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private String normalizeAutomationCronExpression(String cronExpression) {
    String normalized = normalizeOptionalText(cronExpression);
    if (normalized != null
        && normalized.length()
            > JsonConfigLocalizationEntity.AUTOMATION_CRON_EXPRESSION_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Automation cron expression must be at most "
              + JsonConfigLocalizationEntity.AUTOMATION_CRON_EXPRESSION_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private String normalizeAutomationTimeZone(String timeZone) {
    String normalized = normalizeOptionalText(timeZone);
    if (normalized != null
        && normalized.length() > JsonConfigLocalizationEntity.AUTOMATION_TIME_ZONE_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Automation time zone must be at most "
              + JsonConfigLocalizationEntity.AUTOMATION_TIME_ZONE_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private void requireAdminOrPm() {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("Admin or PM role required");
    }
  }

  private Repository requireRepository(Long repositoryId) {
    validateRepositoryId(repositoryId);
    return repositoryRepository
        .findById(repositoryId)
        .filter(candidate -> !Boolean.TRUE.equals(candidate.getDeleted()))
        .filter(candidate -> !Boolean.TRUE.equals(candidate.getHidden()))
        .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
  }

  private void validateRepositoryId(Long repositoryId) {
    if (repositoryId == null || repositoryId <= 0) {
      throw new IllegalArgumentException("Repository id is required");
    }
  }

  private void validateSetupId(Long setupId) {
    if (setupId == null || setupId <= 0) {
      throw new IllegalArgumentException("JSON config localization setup id is required");
    }
  }

  private void assertExpectedLastModifiedDate(
      JsonConfigLocalizationEntity entity, ZonedDateTime expectedLastModifiedDate) {
    if (entity.getId() == null || expectedLastModifiedDate == null) {
      return;
    }

    ZonedDateTime actualLastModifiedDate = entity.getLastModifiedDate();
    if (actualLastModifiedDate == null
        || !actualLastModifiedDate.toInstant().equals(expectedLastModifiedDate.toInstant())) {
      throw new JsonConfigLocalizationConflictException(
          "JSON config localization was modified by another request. Refresh and retry.");
    }
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
      String automationOptionsJson,
      ZonedDateTime expectedLastModifiedDate) {

    public JsonConfigLocalizationInput(
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
        String automationOptionsJson) {
      this(
          name,
          assetPath,
          provider,
          providerConfigId,
          schemaJson,
          sourceConfigJson,
          extractionMappingJson,
          outputLocaleMappingJson,
          automationEnabled,
          automationCronExpression,
          automationTimeZone,
          automationOptionsJson,
          null);
    }
  }

  public record RepositoryRef(
      Long id, String name, String sourceLocaleTag, int targetLocaleCount) {}

  public static class JsonConfigLocalizationConflictException extends RuntimeException {
    public JsonConfigLocalizationConflictException(String message) {
      super(message);
    }
  }
}
