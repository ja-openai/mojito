package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_translate_automation_config")
public class AiTranslateAutomationConfigEntity extends AuditableEntity {

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "repository_ids_json", length = Integer.MAX_VALUE)
  private String repositoryIdsJson;

  @Column(name = "source_text_max_count_per_locale", nullable = false)
  private int sourceTextMaxCountPerLocale = 100;

  @Column(name = "cron_expression", length = 255)
  private String cronExpression;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getRepositoryIdsJson() {
    return repositoryIdsJson;
  }

  public void setRepositoryIdsJson(String repositoryIdsJson) {
    this.repositoryIdsJson = repositoryIdsJson;
  }

  public int getSourceTextMaxCountPerLocale() {
    return sourceTextMaxCountPerLocale;
  }

  public void setSourceTextMaxCountPerLocale(int sourceTextMaxCountPerLocale) {
    this.sourceTextMaxCountPerLocale = sourceTextMaxCountPerLocale;
  }

  public String getCronExpression() {
    return cronExpression;
  }

  public void setCronExpression(String cronExpression) {
    this.cronExpression = cronExpression;
  }
}
