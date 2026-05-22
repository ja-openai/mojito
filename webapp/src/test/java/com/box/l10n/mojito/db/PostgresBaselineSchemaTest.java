package com.box.l10n.mojito.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.Test;

public class PostgresBaselineSchemaTest {

  @Test
  public void baselineUsesPostgresBlobTypesAndSafeTextIndexes() throws IOException {
    String schema = readBaselineSchema();

    assertThat(schema)
        .contains("value bytea")
        .contains("content bytea")
        .contains("I__TM_TEXT_UNIT__NAME on tm_text_unit using hash (name)")
        .contains(
            "I__TM_TEXT_UNIT__PLURAL_FORM_OTHER on tm_text_unit using hash (plural_form_other)")
        .doesNotContain("varchar(2147483647)")
        .doesNotContain(" tinyint")
        .doesNotContain(" blob");
  }

  private String readBaselineSchema() throws IOException {
    try (var inputStream =
        getClass().getResourceAsStream("/db/migration/postgresql/V1__Application_Schema.sql")) {
      assertThat(inputStream).isNotNull();
      return new String(inputStream.readAllBytes(), UTF_8);
    }
  }
}
