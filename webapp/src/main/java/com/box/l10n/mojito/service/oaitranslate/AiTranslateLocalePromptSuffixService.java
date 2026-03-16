package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateLocalePromptSuffixEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateLocalePromptSuffixService {

  private final AiTranslateLocalePromptSuffixRepository repository;
  private final LocaleService localeService;

  public AiTranslateLocalePromptSuffixService(
      AiTranslateLocalePromptSuffixRepository repository, LocaleService localeService) {
    this.repository = repository;
    this.localeService = localeService;
  }

  public record LocalePromptSuffix(
      String localeTag, String promptSuffix, ZonedDateTime createdAt, ZonedDateTime updatedAt) {}

  @Transactional(readOnly = true)
  public List<LocalePromptSuffix> getAll() {
    return repository.findAllByOrderByLocaleBcp47TagAsc().stream().map(this::toRecord).toList();
  }

  @Transactional
  public LocalePromptSuffix upsert(String localeTag, String promptSuffix) {
    String normalizedLocaleTag = requireLocaleTag(localeTag);
    String normalizedPromptSuffix = requirePromptSuffix(promptSuffix);

    Locale locale = localeService.findByBcp47Tag(normalizedLocaleTag);
    if (locale == null) {
      throw new IllegalArgumentException("Locale not found: " + normalizedLocaleTag);
    }

    AiTranslateLocalePromptSuffixEntity entity =
        repository.findByLocaleBcp47TagIgnoreCase(normalizedLocaleTag);
    if (entity == null) {
      entity = new AiTranslateLocalePromptSuffixEntity();
    }

    entity.setLocale(locale);
    entity.setPromptSuffix(normalizedPromptSuffix);
    repository.save(entity);
    return toRecord(entity);
  }

  @Transactional
  public void delete(String localeTag) {
    String normalizedLocaleTag = requireLocaleTag(localeTag);
    AiTranslateLocalePromptSuffixEntity entity =
        repository.findByLocaleBcp47TagIgnoreCase(normalizedLocaleTag);
    if (entity == null) {
      throw new IllegalArgumentException("Locale prompt suffix not found: " + normalizedLocaleTag);
    }
    repository.delete(entity);
  }

  private LocalePromptSuffix toRecord(AiTranslateLocalePromptSuffixEntity entity) {
    return new LocalePromptSuffix(
        entity.getLocale().getBcp47Tag(),
        entity.getPromptSuffix(),
        entity.getCreatedDate(),
        entity.getLastModifiedDate());
  }

  private String requireLocaleTag(String localeTag) {
    if (localeTag == null || localeTag.trim().isEmpty()) {
      throw new IllegalArgumentException("Locale tag is required");
    }
    return localeTag.trim();
  }

  private String requirePromptSuffix(String promptSuffix) {
    if (promptSuffix == null || promptSuffix.trim().isEmpty()) {
      throw new IllegalArgumentException("Prompt suffix is required");
    }
    return promptSuffix.trim();
  }
}
