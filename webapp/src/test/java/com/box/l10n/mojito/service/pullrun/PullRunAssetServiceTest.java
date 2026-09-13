package com.box.l10n.mojito.service.pullrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PullRunAsset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class PullRunAssetServiceTest extends ServiceTestBase {

  @Autowired PullRunAssetService service;
  @Autowired PullRunService pullRunService;
  @Autowired RepositoryService repositoryService;
  @Autowired AssetService assetService;
  @Autowired TMService tmService;
  @Autowired LocaleService localeService;
  @Autowired JdbcTemplate jdbc;

  private PullRunAsset pullRunAsset;
  private Long localeId;
  private Long firstVariant;
  private Long secondVariant;

  @Before
  public void createLineage() throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    Repository repository = repositoryService.createRepository("pull-retry-" + UUID.randomUUID());
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    Asset asset = assetService.createAssetWithContent(repository.getId(), "test.xliff", "source");
    TMTextUnit unit =
        tmService.addTMTextUnit(repository.getTm().getId(), asset.getId(), "key", "Source", null);
    localeId = localeService.findByBcp47Tag("fr-FR").getId();
    firstVariant = tmService.addCurrentTMTextUnitVariant(unit.getId(), localeId, "First").getId();
    secondVariant = tmService.addCurrentTMTextUnitVariant(unit.getId(), localeId, "Second").getId();
    pullRunAsset =
        service.getOrCreate(
            pullRunService.getOrCreate("pull-" + UUID.randomUUID(), repository), asset);
  }

  @Test
  public void repeatedNullTagReplacesCommittedAssociation() {
    replace(null, firstVariant);
    replace(null, firstVariant);

    assertThat(variants(null)).containsExactly(firstVariant);
    assertThat(variants("null")).isEmpty();
    assertThat(totalRows()).isEqualTo(1);
  }

  @Test
  public void nullTagReplayRemovesPreviousVariant() {
    replace(null, firstVariant);
    replace(null, secondVariant);

    assertThat(variants(null)).containsExactly(secondVariant);
    assertThat(totalRows()).isEqualTo(1);
  }

  @Test
  public void explicitTagReplayDoesNotModifyAnotherTag() {
    replace("fr-FR", firstVariant);
    replace("fr-CA", firstVariant);
    replace("fr-FR", secondVariant);

    assertThat(variants("fr-FR")).containsExactly(secondVariant);
    assertThat(variants("fr-CA")).containsExactly(firstVariant);
    assertThat(totalRows()).isEqualTo(2);
  }

  @Test
  public void quotedTagIsBoundAsData() {
    replace("fr'CA", firstVariant);
    replace("fr'CA", secondVariant);

    assertThat(variants("fr'CA")).containsExactly(secondVariant);
    assertThat(totalRows()).isEqualTo(1);
  }

  @Test
  public void nullAndLiteralNullRemainDistinct() {
    replace("null", firstVariant);
    replace(null, secondVariant);
    replace(null, secondVariant);

    assertThat(variants("null")).containsExactly(firstVariant);
    assertThat(variants(null)).containsExactly(secondVariant);
    assertThat(totalRows()).isEqualTo(2);
  }

  @Test
  public void emptyReplacementRemovesNullTagWithoutTouchingOtherTags() {
    replace(null, firstVariant);
    replace("fr-FR", secondVariant);
    replace(null);

    assertThat(variants(null)).isEmpty();
    assertThat(variants("fr-FR")).containsExactly(secondVariant);
    assertThat(totalRows()).isEqualTo(1);
  }

  @Test
  public void multiRowInsertPreservesIdsAndLocalCreationTime() {
    LocalDateTime before = LocalDateTime.now().minusSeconds(1);
    replace(null, firstVariant, secondVariant);
    LocalDateTime after = LocalDateTime.now();

    assertThat(variants(null)).containsExactlyInAnyOrder(firstVariant, secondVariant);
    assertThat(
            jdbc.query(
                "select created_date from pull_run_text_unit_variant where pull_run_asset_id=?",
                (row, index) -> row.getObject(1, LocalDateTime.class),
                pullRunAsset.getId()))
        .hasSize(2)
        .allSatisfy(created -> assertThat(created).isBetween(before, after));
  }

  private void replace(String tag, Long... ids) {
    service.replaceTextUnitVariants(pullRunAsset, localeId, List.of(ids), tag);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private List<Long> variants(String tag) {
    String query =
        "select tm_text_unit_variant_id from pull_run_text_unit_variant where pull_run_asset_id=? and locale_id=? and output_bcp47_tag";
    return tag == null
        ? jdbc.queryForList(query + " is null", Long.class, pullRunAsset.getId(), localeId)
        : jdbc.queryForList(query + "=?", Long.class, pullRunAsset.getId(), localeId, tag);
  }

  private int totalRows() {
    return jdbc.queryForObject(
        "select count(*) from pull_run_text_unit_variant where pull_run_asset_id=?",
        Integer.class,
        pullRunAsset.getId());
  }
}
