package com.box.l10n.mojito.service.pluralform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.DBUtils;
import org.junit.Test;

public class PluralFormUpdaterJobTest {

  @Test
  public void postgresUpdateUsesUpdateFromSyntax() {
    PluralFormUpdaterJob job = new PluralFormUpdaterJob();
    job.dbUtils = mock(DBUtils.class);
    when(job.dbUtils.isPostgres()).thenReturn(true);

    assertThat(job.getUpdatePluralFormsSql())
        .contains("update tm_text_unit tu")
        .contains("from (")
        .contains("tu.id = d.tu_id")
        .doesNotContain("update tm_text_unit tu,");
  }

  @Test
  public void mysqlUpdateKeepsJoinUpdateSyntax() {
    PluralFormUpdaterJob job = new PluralFormUpdaterJob();
    job.dbUtils = mock(DBUtils.class);

    assertThat(job.getUpdatePluralFormsSql())
        .contains("update tm_text_unit tu,")
        .contains("set tu.plural_form_id")
        .doesNotContain("update tm_text_unit tu\nset");
  }
}
