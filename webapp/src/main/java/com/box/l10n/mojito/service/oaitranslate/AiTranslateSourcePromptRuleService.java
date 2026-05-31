package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateSourcePromptRuleEntity;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Service
public class AiTranslateSourcePromptRuleService {

  static final String MATCH_TYPE_REGEX = "REGEX";

  private static final Logger logger =
      LoggerFactory.getLogger(AiTranslateSourcePromptRuleService.class);

  private final AiTranslateSourcePromptRuleRepository repository;
  private final PlatformTransactionManager transactionManager;

  public AiTranslateSourcePromptRuleService(
      AiTranslateSourcePromptRuleRepository repository,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionManager = transactionManager;
  }

  public record SourcePromptRule(
      Long id,
      String name,
      String description,
      boolean enabled,
      int priority,
      String matchType,
      String sourceRegex,
      String promptSuffix,
      ZonedDateTime createdAt,
      ZonedDateTime updatedAt) {}

  public record SourcePromptRuleInput(
      Long id,
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String matchType,
      String sourceRegex,
      String promptSuffix) {}

  public record ActiveSourcePromptRule(
      Long id,
      String name,
      int priority,
      String sourceRegex,
      String promptSuffix,
      Pattern pattern) {}

  public record MatchedPromptSuffixes(
      List<Long> ruleIds, List<String> ruleNames, String promptSuffix) {}

  public record RegexMatch(int start, int end, String snippet) {}

  public record RegexTestResult(boolean matches, List<RegexMatch> matchesList) {}

  public List<SourcePromptRule> getAll() {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      List<SourcePromptRule> rules = getAllNoTx();
      transactionManager.commit(transaction);
      return rules;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  List<SourcePromptRule> getAllNoTx() {
    return repository.findAllByOrderByPriorityAscNameAsc().stream().map(this::toRecord).toList();
  }

  public List<ActiveSourcePromptRule> getActiveRules() {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      List<ActiveSourcePromptRule> rules = getActiveRulesNoTx();
      transactionManager.commit(transaction);
      return rules;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  List<ActiveSourcePromptRule> getActiveRulesNoTx() {
    return repository.findByEnabledTrueOrderByPriorityAscIdAsc().stream()
        .map(this::toActiveRuleOrNull)
        .filter(Objects::nonNull)
        .toList();
  }

  public SourcePromptRule upsert(SourcePromptRuleInput input) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      SourcePromptRule rule = upsertNoTx(input);
      transactionManager.commit(transaction);
      return rule;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  SourcePromptRule upsertNoTx(SourcePromptRuleInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Rule input is required");
    }

    String name = requireText(input.name(), "Name is required");
    String matchType = normalizeMatchType(input.matchType());
    String sourceRegex = requireText(input.sourceRegex(), "Source regex is required");
    String promptSuffix = requireText(input.promptSuffix(), "Prompt suffix is required");
    compileRegex(sourceRegex);

    AiTranslateSourcePromptRuleEntity existingByName = repository.findByNameIgnoreCase(name);
    if (existingByName != null && !Objects.equals(existingByName.getId(), input.id())) {
      throw new IllegalArgumentException("Source prompt rule already exists: " + name);
    }

    AiTranslateSourcePromptRuleEntity entity;
    if (input.id() == null) {
      entity = new AiTranslateSourcePromptRuleEntity();
    } else {
      entity =
          repository
              .findById(input.id())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("Source prompt rule not found: " + input.id()));
    }

    entity.setName(name);
    entity.setDescription(normalizeOptionalText(input.description()));
    entity.setEnabled(input.enabled() == null || input.enabled());
    entity.setPriority(input.priority() == null ? 0 : input.priority());
    entity.setMatchType(matchType);
    entity.setSourceRegex(sourceRegex);
    entity.setPromptSuffix(promptSuffix);
    repository.save(entity);
    return toRecord(entity);
  }

  public void delete(long id) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      deleteNoTx(id);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void deleteNoTx(long id) {
    AiTranslateSourcePromptRuleEntity entity =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Source prompt rule not found: " + id));
    repository.delete(entity);
  }

  public RegexTestResult test(String sourceRegex, String sourceText) {
    Pattern pattern = compileRegex(requireText(sourceRegex, "Source regex is required"));
    String text = sourceText == null ? "" : sourceText;
    Matcher matcher = pattern.matcher(text);
    List<RegexMatch> matches = new ArrayList<>();
    while (matcher.find() && matches.size() < 10) {
      matches.add(new RegexMatch(matcher.start(), matcher.end(), matcher.group()));
    }
    return new RegexTestResult(!matches.isEmpty(), matches);
  }

  public MatchedPromptSuffixes matchPromptSuffixes(
      String sourceText, List<ActiveSourcePromptRule> activeRules) {
    if (sourceText == null
        || sourceText.isEmpty()
        || activeRules == null
        || activeRules.isEmpty()) {
      return new MatchedPromptSuffixes(List.of(), List.of(), null);
    }

    List<Long> ruleIds = new ArrayList<>();
    List<String> ruleNames = new ArrayList<>();
    List<String> promptSuffixes = new ArrayList<>();
    for (ActiveSourcePromptRule rule : activeRules) {
      if (rule.pattern().matcher(sourceText).find()) {
        ruleIds.add(rule.id());
        ruleNames.add(rule.name());
        promptSuffixes.add(rule.promptSuffix());
      }
    }

    return new MatchedPromptSuffixes(
        List.copyOf(ruleIds),
        List.copyOf(ruleNames),
        AiTranslateLocalePromptSuffixService.combinePromptSuffixes(
            promptSuffixes.toArray(String[]::new)));
  }

  private ActiveSourcePromptRule toActiveRuleOrNull(AiTranslateSourcePromptRuleEntity entity) {
    try {
      return new ActiveSourcePromptRule(
          entity.getId(),
          entity.getName(),
          entity.getPriority(),
          entity.getSourceRegex(),
          entity.getPromptSuffix(),
          compileRegex(entity.getSourceRegex()));
    } catch (IllegalArgumentException e) {
      logger.warn(
          "Skipping invalid AI Translate source prompt rule: id={}, name={}",
          entity.getId(),
          entity.getName(),
          e);
      return null;
    }
  }

  private SourcePromptRule toRecord(AiTranslateSourcePromptRuleEntity entity) {
    return new SourcePromptRule(
        entity.getId(),
        entity.getName(),
        entity.getDescription(),
        entity.isEnabled(),
        entity.getPriority(),
        entity.getMatchType(),
        entity.getSourceRegex(),
        entity.getPromptSuffix(),
        entity.getCreatedDate(),
        entity.getLastModifiedDate());
  }

  private String normalizeMatchType(String matchType) {
    String normalized =
        matchType == null || matchType.trim().isEmpty() ? MATCH_TYPE_REGEX : matchType.trim();
    if (!MATCH_TYPE_REGEX.equalsIgnoreCase(normalized)) {
      throw new IllegalArgumentException("Unsupported match type: " + normalized);
    }
    return MATCH_TYPE_REGEX;
  }

  private Pattern compileRegex(String sourceRegex) {
    try {
      return Pattern.compile(sourceRegex);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid source regex: " + e.getDescription(), e);
    }
  }

  private String requireText(String value, String message) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private String normalizeOptionalText(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }
}
