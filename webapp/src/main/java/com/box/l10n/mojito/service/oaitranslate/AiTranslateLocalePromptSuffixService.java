package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateLocalePromptSuffixEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Service
public class AiTranslateLocalePromptSuffixService {

  private final AiTranslateLocalePromptSuffixRepository repository;
  private final LocaleService localeService;
  private final PlatformTransactionManager transactionManager;

  public AiTranslateLocalePromptSuffixService(
      AiTranslateLocalePromptSuffixRepository repository,
      LocaleService localeService,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.localeService = localeService;
    this.transactionManager = transactionManager;
  }

  public record LocalePromptSuffix(
      String localeTag, String promptSuffix, ZonedDateTime createdAt, ZonedDateTime updatedAt) {}

  public List<LocalePromptSuffix> getAll() {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      List<LocalePromptSuffix> promptSuffixes =
          repository.findAllByOrderByLocaleBcp47TagAsc().stream().map(this::toRecord).toList();
      transactionManager.commit(transaction);
      return promptSuffixes;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  public String getEffectivePromptSuffix(String localeTag, String requestPromptSuffix) {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      String promptSuffix = getEffectivePromptSuffixNoTx(localeTag, requestPromptSuffix);
      transactionManager.commit(transaction);
      return promptSuffix;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  String getEffectivePromptSuffixNoTx(String localeTag, String requestPromptSuffix) {
    String normalizedLocaleTag = requireLocaleTag(localeTag);
    String savedPromptSuffix = null;

    AiTranslateLocalePromptSuffixEntity entity =
        repository.findByLocaleBcp47TagIgnoreCase(normalizedLocaleTag);
    if (entity != null) {
      savedPromptSuffix = normalizeOptionalPromptSuffix(entity.getPromptSuffix());
    }

    return combinePromptSuffixes(savedPromptSuffix, requestPromptSuffix);
  }

  public LocalePromptSuffix upsert(String localeTag, String promptSuffix) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      LocalePromptSuffix localePromptSuffix = upsertNoTx(localeTag, promptSuffix);
      transactionManager.commit(transaction);
      return localePromptSuffix;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  LocalePromptSuffix upsertNoTx(String localeTag, String promptSuffix) {
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

  public void delete(String localeTag) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      deleteNoTx(localeTag);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void deleteNoTx(String localeTag) {
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

  static String combinePromptSuffixes(String... suffixes) {
    return java.util.Arrays.stream(suffixes)
        .map(AiTranslateLocalePromptSuffixService::normalizeOptionalPromptSuffix)
        .filter(java.util.Objects::nonNull)
        .reduce((left, right) -> "%s %s".formatted(left, right))
        .orElse(null);
  }

  private static String normalizeOptionalPromptSuffix(String promptSuffix) {
    if (promptSuffix == null) {
      return null;
    }
    String normalized = promptSuffix.trim();
    return normalized.isEmpty() ? null : normalized;
  }
}
