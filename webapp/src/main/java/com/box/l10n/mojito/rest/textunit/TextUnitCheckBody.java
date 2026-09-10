package com.box.l10n.mojito.rest.textunit;

public class TextUnitCheckBody {

  Long tmTextUnitId;
  String content;
  Long localeId;

  /** Optional for legacy clients; required for locale-specific plural validation. */
  public Long getLocaleId() {
    return localeId;
  }

  public void setLocaleId(Long localeId) {
    this.localeId = localeId;
  }

  public Long getTmTextUnitId() {
    return tmTextUnitId;
  }

  public void setTmTextUnitId(Long tmTextUnitId) {
    this.tmTextUnitId = tmTextUnitId;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }
}
