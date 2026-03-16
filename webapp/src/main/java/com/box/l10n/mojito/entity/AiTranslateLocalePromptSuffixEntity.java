package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "ai_translate_locale_prompt_suffix",
    indexes = {
      @Index(
          name = "UK__AI_TRANSLATE_LOCALE_PROMPT_SUFFIX__LOCALE__ID",
          columnList = "locale_id",
          unique = true)
    })
public class AiTranslateLocalePromptSuffixEntity extends AuditableEntity {

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(
      name = "locale_id",
      foreignKey = @ForeignKey(name = "FK__AI_TRANSLATE_LOCALE_PROMPT_SUFFIX__LOCALE__ID"),
      nullable = false)
  private Locale locale;

  @Column(name = "prompt_suffix", nullable = false, length = Integer.MAX_VALUE)
  private String promptSuffix;

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public String getPromptSuffix() {
    return promptSuffix;
  }

  public void setPromptSuffix(String promptSuffix) {
    this.promptSuffix = promptSuffix;
  }
}
