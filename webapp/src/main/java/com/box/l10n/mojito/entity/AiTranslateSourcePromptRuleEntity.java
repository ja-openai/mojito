package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "ai_translate_source_prompt_rule",
    indexes = {
      @Index(
          name = "UK__AI_TRANSLATE_SOURCE_PROMPT_RULE__NAME",
          columnList = "name",
          unique = true),
      @Index(
          name = "I__AI_TRANSLATE_SOURCE_PROMPT_RULE__ENABLED_PRIORITY_ID",
          columnList = "enabled, priority, id")
    })
public class AiTranslateSourcePromptRuleEntity extends AuditableEntity {

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description", length = Integer.MAX_VALUE)
  private String description;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "match_type", nullable = false)
  private String matchType;

  @Column(name = "source_regex", nullable = false, length = Integer.MAX_VALUE)
  private String sourceRegex;

  @Column(name = "prompt_suffix", nullable = false, length = Integer.MAX_VALUE)
  private String promptSuffix;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public String getMatchType() {
    return matchType;
  }

  public void setMatchType(String matchType) {
    this.matchType = matchType;
  }

  public String getSourceRegex() {
    return sourceRegex;
  }

  public void setSourceRegex(String sourceRegex) {
    this.sourceRegex = sourceRegex;
  }

  public String getPromptSuffix() {
    return promptSuffix;
  }

  public void setPromptSuffix(String promptSuffix) {
    this.promptSuffix = promptSuffix;
  }
}
