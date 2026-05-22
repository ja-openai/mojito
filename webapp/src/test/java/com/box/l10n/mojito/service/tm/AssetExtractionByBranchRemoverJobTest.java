package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.DBUtils;
import org.junit.Test;

public class AssetExtractionByBranchRemoverJobTest {

  @Test
  public void postgresDeleteUsesDeleteUsingSyntax() {
    AssetExtractionByBranchRemoverJob job = new AssetExtractionByBranchRemoverJob();
    job.dbUtils = mock(DBUtils.class);
    when(job.dbUtils.isPostgres()).thenReturn(true);

    assertThat(job.getDeleteAssetExtractionByBranchSql())
        .contains("delete from asset_extraction_by_branch aebb")
        .contains("using asset a")
        .doesNotContain("delete aebb");
  }

  @Test
  public void mysqlDeleteKeepsDeleteJoinSyntax() {
    AssetExtractionByBranchRemoverJob job = new AssetExtractionByBranchRemoverJob();
    job.dbUtils = mock(DBUtils.class);

    assertThat(job.getDeleteAssetExtractionByBranchSql())
        .contains("delete aebb")
        .contains("inner join asset a")
        .doesNotContain("using asset a");
  }
}
