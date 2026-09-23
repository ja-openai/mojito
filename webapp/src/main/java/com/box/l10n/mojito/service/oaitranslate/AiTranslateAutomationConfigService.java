package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateAutomationConfigService {

  private static final int DEFAULT_SOURCE_TEXT_MAX_COUNT_PER_LOCALE = 100;

  private final AiTranslateAutomationConfigRepository repository;
  private final ObjectMapper objectMapper;
  private final RepositoryRepository repositoryRepository;
  private final LocaleService localeService;

  public AiTranslateAutomationConfigService(
      AiTranslateAutomationConfigRepository repository,
      ObjectMapper objectMapper,
      RepositoryRepository repositoryRepository,
      LocaleService localeService) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.repositoryRepository = repositoryRepository;
    this.localeService = localeService;
  }

  public record Config(
      boolean enabled,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds,
      int sourceTextMaxCountPerLocale,
      String cronExpression,
      Map<Long, List<String>> excludedLocaleTagsByRepositoryId) {}

  public Config getConfig() {
    AiTranslateAutomationConfigEntity entity = repository.findFirstByOrderByIdAsc();
    if (entity == null) {
      return new Config(
          false, List.of(), List.of(), DEFAULT_SOURCE_TEXT_MAX_COUNT_PER_LOCALE, null, Map.of());
    }

    List<Long> repositoryIds = decodeRepositoryIds(entity.getRepositoryIdsJson());
    return new Config(
        entity.isEnabled(),
        repositoryIds,
        repositoryIds.isEmpty()
            ? decodeRepositoryIds(entity.getExcludedRepositoryIdsJson())
            : List.of(),
        normalizeSourceTextMaxCountPerLocale(entity.getSourceTextMaxCountPerLocale()),
        sanitizeStoredCronExpression(entity.getCronExpression()),
        decodeLocaleExclusions(entity.getExcludedLocaleTagsByRepositoryIdJson()));
  }

  public ZonedDateTime getLastModifiedDate() {
    AiTranslateAutomationConfigEntity entity = repository.findFirstByOrderByIdAsc();
    return entity == null ? null : entity.getLastModifiedDate();
  }

  @Transactional
  public Config updateConfig(Config config) {
    AiTranslateAutomationConfigEntity entity = repository.findFirstByOrderByIdAsc();
    if (entity == null) {
      entity = new AiTranslateAutomationConfigEntity();
    }

    List<Long> normalizedRepositoryIds = normalizeRepositoryIds(config.repositoryIds());
    List<Long> normalizedExcludedRepositoryIds =
        normalizedRepositoryIds.isEmpty()
            ? normalizeRepositoryIds(config.excludedRepositoryIds())
            : List.of();
    Map<Long, List<String>> savedLocaleExclusions =
        decodeLocaleExclusions(entity.getExcludedLocaleTagsByRepositoryIdJson());
    Map<Long, List<String>> normalizedLocaleExclusions =
        config.excludedLocaleTagsByRepositoryId() == null
            ? savedLocaleExclusions
            : normalizeLocaleExclusions(
                config.excludedLocaleTagsByRepositoryId(), savedLocaleExclusions);
    entity.setEnabled(config.enabled());
    entity.setRepositoryIdsJson(objectMapper.writeValueAsStringUnchecked(normalizedRepositoryIds));
    entity.setExcludedRepositoryIdsJson(
        objectMapper.writeValueAsStringUnchecked(normalizedExcludedRepositoryIds));
    entity.setExcludedLocaleTagsByRepositoryIdJson(
        objectMapper.writeValueAsStringUnchecked(normalizedLocaleExclusions));
    entity.setSourceTextMaxCountPerLocale(
        normalizeSourceTextMaxCountPerLocale(config.sourceTextMaxCountPerLocale()));
    entity.setCronExpression(normalizeCronExpression(config.cronExpression()));
    repository.save(entity);

    return new Config(
        entity.isEnabled(),
        normalizedRepositoryIds,
        normalizedExcludedRepositoryIds,
        entity.getSourceTextMaxCountPerLocale(),
        entity.getCronExpression(),
        normalizedLocaleExclusions);
  }

  private Map<Long, List<String>> decodeLocaleExclusions(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return objectMapper.readValueUnchecked(json, new TypeReference<Map<Long, List<String>>>() {});
  }

  private Map<Long, List<String>> normalizeLocaleExclusions(
      Map<Long, List<String>> excludedLocaleTagsByRepositoryId,
      Map<Long, List<String>> savedLocaleExclusions) {
    Map<Long, List<String>> normalized = new TreeMap<>();
    for (var entry : excludedLocaleTagsByRepositoryId.entrySet()) {
      Long repositoryId = entry.getKey();
      // Preserve unchanged rules even if their repository was deleted since the config was saved.
      if (entry.getValue() != null
          && entry.getValue().equals(savedLocaleExclusions.get(repositoryId))) {
        normalized.put(repositoryId, entry.getValue());
        continue;
      }
      if (repositoryId == null
          || repositoryRepository
              .findNoGraphById(repositoryId)
              .filter(repo -> !Boolean.TRUE.equals(repo.getDeleted()))
              .isEmpty()) {
        throw new IllegalArgumentException("Repository not found: " + repositoryId);
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Excluded locale tags are required for repository " + repositoryId);
      }
      TreeSet<String> localeTags = new TreeSet<>();
      for (String localeTag : entry.getValue()) {
        if (localeTag == null || localeTag.isBlank()) {
          throw new IllegalArgumentException("Excluded locale tags must not be blank");
        }
        Locale locale = localeService.findByBcp47Tag(localeTag.trim());
        if (locale == null) {
          throw new IllegalArgumentException("Unknown locale tag: " + localeTag.trim());
        }
        localeTags.add(locale.getBcp47Tag());
      }
      if (!localeTags.isEmpty()) {
        normalized.put(repositoryId, List.copyOf(localeTags));
      }
    }
    return normalized;
  }

  private List<Long> decodeRepositoryIds(String repositoryIdsJson) {
    if (repositoryIdsJson == null || repositoryIdsJson.isBlank()) {
      return List.of();
    }

    Long[] parsed = objectMapper.readValueUnchecked(repositoryIdsJson, Long[].class);
    return normalizeRepositoryIds(List.of(parsed));
  }

  private List<Long> normalizeRepositoryIds(List<Long> repositoryIds) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }

    return repositoryIds.stream()
        .filter(Objects::nonNull)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private int normalizeSourceTextMaxCountPerLocale(int sourceTextMaxCountPerLocale) {
    return Math.max(1, sourceTextMaxCountPerLocale);
  }

  private String normalizeCronExpression(String cronExpression) {
    if (cronExpression == null) {
      return null;
    }

    String trimmed = cronExpression.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (!CronExpression.isValidExpression(trimmed)) {
      throw new IllegalArgumentException("Invalid cron expression");
    }
    return trimmed;
  }

  private String sanitizeStoredCronExpression(String cronExpression) {
    if (cronExpression == null) {
      return null;
    }

    String trimmed = cronExpression.trim();
    if (trimmed.isEmpty()) {
      return null;
    }

    return trimmed;
  }
}
