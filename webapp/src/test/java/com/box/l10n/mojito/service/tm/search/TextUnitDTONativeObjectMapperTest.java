package com.box.l10n.mojito.service.tm.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class TextUnitDTONativeObjectMapperTest {

  @Test
  public void parseNativeBooleanHandlesMysqlBitStrings() {
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("\u0001")).isTrue();
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("\u0000")).isFalse();
  }

  @Test
  public void parseNativeBooleanHandlesTextBooleans() {
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("true")).isTrue();
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("1")).isTrue();
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("false")).isFalse();
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean("0")).isFalse();
    assertThat(TextUnitDTONativeObjectMapper.parseNativeBoolean(null)).isFalse();
  }
}
