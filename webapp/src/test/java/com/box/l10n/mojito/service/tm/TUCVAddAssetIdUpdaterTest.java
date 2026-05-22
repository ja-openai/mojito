package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class TUCVAddAssetIdUpdaterTest {

  @Test
  public void postgresUpdateUsesUpdateFromSyntax() {
    String sql = new TUCVAddAssetIdUpdater().getUpdateSql(true);

    assertThat(sql)
        .contains("update tm_text_unit_current_variant tucv")
        .contains("set asset_id = d.asset_id")
        .contains("from (")
        .contains("limit 100000")
        .doesNotContain("update tm_text_unit_current_variant tucv,");
  }

  @Test
  public void mysqlUpdateKeepsJoinUpdateSyntax() {
    String sql = new TUCVAddAssetIdUpdater().getUpdateSql(false);

    assertThat(sql)
        .contains("update tm_text_unit_current_variant tucv,")
        .contains("set tucv.asset_id = d.asset_id")
        .contains("limit 100000")
        .doesNotContain("set asset_id = d.asset_id");
  }
}
