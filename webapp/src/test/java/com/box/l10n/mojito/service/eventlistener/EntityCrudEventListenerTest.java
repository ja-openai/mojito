package com.box.l10n.mojito.service.eventlistener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.repository.statistics.RepositoryStatisticsUpdatedReactor;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

public class EntityCrudEventListenerTest {
  @Test
  public void alreadyLoadedImportGraphsDoNotPerformPerRowRepositoryLookups() {
    EntityCrudEventListener listener = new EntityCrudEventListener();
    listener.jdbcTemplate = mock(JdbcTemplate.class);
    listener.repositoryStatisticsUpdatedReactor = mock(RepositoryStatisticsUpdatedReactor.class);
    Repository repository = new Repository();
    repository.setId(11L);
    Asset asset = new Asset();
    asset.setId(12L);
    asset.setRepository(repository);

    for (long row = 1; row <= 32; row++) {
      TMTextUnit textUnit = new TMTextUnit();
      textUnit.setId(row);
      textUnit.setAsset(asset);
      TMTextUnitVariant variant = new TMTextUnitVariant();
      variant.setId(row);
      variant.setTmTextUnit(textUnit);
      TMTextUnitCurrentVariant current = new TMTextUnitCurrentVariant();
      current.setId(row);
      current.setTmTextUnit(textUnit);
      listener.onPostInsert(new PostInsertEvent(variant, row, null, null, null));
      listener.onPostUpdate(new PostUpdateEvent(current, row, null, null, null, null, null));
    }

    verifyNoInteractions(listener.jdbcTemplate);
    verify(listener.repositoryStatisticsUpdatedReactor, times(64))
        .generateEvent(repository.getId());
  }
}
