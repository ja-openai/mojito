package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.json.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateAutomationConfigService {

  private static final int DEFAULT_SOURCE_TEXT_MAX_COUNT_PER_LOCALE = 100;

  private final AiTranslateAutomationConfigRepository repository;
  private final ObjectMapper objectMapper;

  public AiTranslateAutomationConfigService(
      AiTranslateAutomationConfigRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public record Config(
      boolean enabled,
      List<Long> repositoryIds,
      int sourceTextMaxCountPerLocale,
      String cronExpression) {}

  public Config getConfig() {
    AiTranslateAutomationConfigEntity entity = repository.findFirstByOrderByIdAsc();
    if (entity == null) {
      return new Config(false, List.of(), DEFAULT_SOURCE_TEXT_MAX_COUNT_PER_LOCALE, null);
    }

    return new Config(
        entity.isEnabled(),
        decodeRepositoryIds(entity.getRepositoryIdsJson()),
        normalizeSourceTextMaxCountPerLocale(entity.getSourceTextMaxCountPerLocale()),
        normalizeCronExpression(entity.getCronExpression()));
  }

  @Transactional
  public Config updateConfig(Config config) {
    AiTranslateAutomationConfigEntity entity = repository.findFirstByOrderByIdAsc();
    if (entity == null) {
      entity = new AiTranslateAutomationConfigEntity();
    }

    List<Long> normalizedRepositoryIds = normalizeRepositoryIds(config.repositoryIds());
    entity.setEnabled(config.enabled());
    entity.setRepositoryIdsJson(objectMapper.writeValueAsStringUnchecked(normalizedRepositoryIds));
    entity.setSourceTextMaxCountPerLocale(
        normalizeSourceTextMaxCountPerLocale(config.sourceTextMaxCountPerLocale()));
    entity.setCronExpression(normalizeCronExpression(config.cronExpression()));
    repository.save(entity);

    return new Config(
        entity.isEnabled(),
        normalizedRepositoryIds,
        entity.getSourceTextMaxCountPerLocale(),
        entity.getCronExpression());
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
}
