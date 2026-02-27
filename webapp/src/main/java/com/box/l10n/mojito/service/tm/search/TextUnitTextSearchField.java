package com.box.l10n.mojito.service.tm.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TextUnitTextSearchField {
  STRING_ID("stringId"),
  SOURCE("source"),
  TARGET("target"),
  COMMENT("comment"),
  ASSET("asset"),
  LOCATION("location"),
  PLURAL_FORM_OTHER("pluralFormOther"),
  TM_TEXT_UNIT_IDS("tmTextUnitIds");

  private final String jsonValue;

  TextUnitTextSearchField(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }

  @JsonCreator
  public static TextUnitTextSearchField fromJsonValue(String value) {
    return Arrays.stream(values())
        .filter(field -> field.jsonValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown text search field: " + value));
  }
}
