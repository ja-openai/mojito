package com.box.l10n.mojito.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.DBUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ApplicationCacheUpdaterServiceTest {

  @Test
  public void upsertWithTTLUsesPostgresOnConflictSql() {
    ApplicationCacheUpdaterService service = postgresService();

    service.upsertWithTTL((short) 1, "key-md5", new byte[] {1, 2, 3}, 60L);

    String sql = createdNativeSql(service);
    assertThat(sql)
        .contains("ON CONFLICT (cache_type_id, key_md5) DO UPDATE")
        .contains("INTERVAL '1 second'")
        .doesNotContain("ON DUPLICATE KEY UPDATE");
    verify(service.entityManager).flush();
    verify(service.entityManager).clear();
  }

  @Test
  public void upsertNoExpiryDateUsesPostgresOnConflictSql() {
    ApplicationCacheUpdaterService service = postgresService();

    service.upsertNoExpiryDate((short) 1, "key-md5", new byte[] {1, 2, 3});

    String sql = createdNativeSql(service);
    assertThat(sql)
        .contains("ON CONFLICT (cache_type_id, key_md5) DO UPDATE")
        .contains("expiry_date")
        .contains("NULL")
        .doesNotContain("ON DUPLICATE KEY UPDATE");
    verify(service.entityManager).flush();
    verify(service.entityManager).clear();
  }

  private ApplicationCacheUpdaterService postgresService() {
    ApplicationCacheUpdaterService service = new ApplicationCacheUpdaterService();
    service.dbUtils = mock(DBUtils.class);
    service.entityManager = mock(EntityManager.class);

    Query query = mock(Query.class);
    when(service.dbUtils.isPostgres()).thenReturn(true);
    when(service.entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);

    return service;
  }

  private String createdNativeSql(ApplicationCacheUpdaterService service) {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(service.entityManager).createNativeQuery(sqlCaptor.capture());
    return sqlCaptor.getValue();
  }
}
