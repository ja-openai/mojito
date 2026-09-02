package com.box.l10n.mojito.service.eventlistener;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.repository.statistics.RepositoryStatisticsUpdatedReactor;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.Hibernate;
import org.hibernate.event.spi.PostCommitDeleteEventListener;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sets repository statistics outdated when the events that requires repository statistics to
 * re-compute occurs.
 *
 * @author jyi
 */
@Component
public class EntityCrudEventListener
    implements PostCommitInsertEventListener,
        PostCommitUpdateEventListener,
        PostCommitDeleteEventListener {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(EntityCrudEventListener.class);

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired RepositoryStatisticsUpdatedReactor repositoryStatisticsUpdatedReactor;

  private static final Set<String> ENTITY_NAMES =
      Sets.newHashSet(
          RepositoryLocale.class.getName(),
          Asset.class.getName(),
          TMTextUnitVariant.class.getName(),
          TMTextUnitCurrentVariant.class.getName());

  @Override
  public void onPostInsert(PostInsertEvent event) {
    notifyStatisticsAfterCommit(
        event.getEntity(),
        () -> {
          if (event.getEntity() instanceof RepositoryLocale locale) {
            return locale.getRepository().getId();
          }
          if (event.getEntity() instanceof TMTextUnitVariant variant) {
            return findRepositoryId(variant.getTmTextUnit());
          }
          return null;
        });
  }

  @Override
  public void onPostUpdate(PostUpdateEvent event) {
    notifyStatisticsAfterCommit(
        event.getEntity(),
        () -> {
          if (event.getEntity() instanceof RepositoryLocale locale) {
            return locale.getRepository().getId();
          }
          if (event.getEntity() instanceof Asset asset) {
            return asset.getRepository().getId();
          }
          if (event.getEntity() instanceof TMTextUnitCurrentVariant current) {
            return findRepositoryId(current.getTmTextUnit());
          }
          if (event.getEntity() instanceof AssetExtraction extraction) {
            return jdbcTemplate.query(
                "select repository_id from asset where id = ?",
                result -> result.next() ? result.getLong(1) : null,
                extraction.getAsset().getId());
          }
          return null;
        });
  }

  @Override
  public void onPostDelete(PostDeleteEvent event) {
    notifyStatisticsAfterCommit(
        event.getEntity(),
        () ->
            event.getEntity() instanceof RepositoryLocale locale
                ? locale.getRepository().getId()
                : null);
  }

  private Long findRepositoryId(TMTextUnit textUnit) {
    // Import batches commonly already loaded this graph; avoid one lookup per row in that case.
    if (Hibernate.isInitialized(textUnit)) {
      Asset asset = textUnit.getAsset();
      if (Hibernate.isInitialized(asset)) {
        return asset.getRepository().getId();
      }
    }
    // Post-commit entities may have been detached by a clear during the transaction. Read a
    // scalar ID without traversing their lazy graph or acquiring a second transaction/connection.
    return jdbcTemplate.query(
        "select a.repository_id from tm_text_unit tu join asset a on a.id = tu.asset_id where tu.id = ?",
        result -> result.next() ? result.getLong(1) : null,
        textUnit.getId());
  }

  private void notifyStatisticsAfterCommit(Object entity, Supplier<Long> repositoryIdSupplier) {
    try {
      Long repositoryId = repositoryIdSupplier.get();
      if (repositoryId != null) {
        repositoryStatisticsUpdatedReactor.generateEvent(repositoryId);
      }
    } catch (RuntimeException failure) {
      // The write has already committed. Ancillary statistics must not turn that success into
      // an HTTP error suggesting that a translator's save failed.
      logger.error(
          "Could not schedule repository statistics after committed {} change",
          entity.getClass().getSimpleName(),
          failure);
    }
  }

  @Override
  public boolean requiresPostCommitHandling(EntityPersister ep) {
    String entityName = ep.getEntityName();
    return ENTITY_NAMES.contains(entityName);
  }

  @Override
  public void onPostInsertCommitFailed(PostInsertEvent pie) {}

  @Override
  public void onPostUpdateCommitFailed(PostUpdateEvent pue) {}

  @Override
  public void onPostDeleteCommitFailed(PostDeleteEvent pde) {}
}
