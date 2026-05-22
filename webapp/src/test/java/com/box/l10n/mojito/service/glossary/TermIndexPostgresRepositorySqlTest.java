package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.service.DBUtils;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class TermIndexPostgresRepositorySqlTest {

  @Test
  public void insertExtractedTermIfAbsentUsesPostgresOnConflict() {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateReturning(1);
    TermIndexExtractedTermRepositoryImpl repository =
        new TermIndexExtractedTermRepositoryImpl(
            mock(EntityManager.class), postgresDbUtils(), jdbcTemplate);

    assertThat(repository.insertIfAbsent("en", "billing portal", "Billing portal")).isEqualTo(1);

    CapturedUpdate capturedUpdate = capturedUpdate(jdbcTemplate);
    assertThat(capturedUpdate.sql())
        .contains("on conflict (source_locale_tag, normalized_key) do nothing")
        .doesNotContain("insert ignore");
    assertThat(capturedUpdate.parameters().getValue("sourceLocaleTag")).isEqualTo("en");
    assertThat(capturedUpdate.parameters().getValue("normalizedKey")).isEqualTo("billing portal");
    assertThat(capturedUpdate.parameters().getValue("displayTerm")).isEqualTo("Billing portal");
    assertThat(capturedUpdate.parameters().getValue("reviewStatus"))
        .isEqualTo(TermIndexReview.STATUS_TO_REVIEW);
    assertThat(capturedUpdate.parameters().getValue("reviewAuthority"))
        .isEqualTo(TermIndexReview.AUTHORITY_NONE);
  }

  @Test
  public void insertRefreshRunEntriesUsesPostgresOnConflict() {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateReturning(2);
    TermIndexRefreshRunEntryRepositoryImpl repository =
        new TermIndexRefreshRunEntryRepositoryImpl(
            mock(EntityManager.class), postgresDbUtils(), jdbcTemplate);

    assertThat(repository.insertEntries(7L, List.of(101L, 102L))).isEqualTo(2);

    CapturedUpdate capturedUpdate = capturedUpdate(jdbcTemplate);
    assertThat(capturedUpdate.sql())
        .contains("on conflict (refresh_run_id, term_index_extracted_term_id) do nothing")
        .doesNotContain("insert ignore");
    assertThat(capturedUpdate.parameters().getValue("refreshRunId")).isEqualTo(7L);
    assertThat(capturedUpdate.parameters().getValue("termIndexExtractedTermIds"))
        .isEqualTo(List.of(101L, 102L));
  }

  @Test
  public void insertExistingRepositoryEntriesUsesPostgresOnConflict() {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateReturning(3);
    TermIndexRefreshRunEntryRepositoryImpl repository =
        new TermIndexRefreshRunEntryRepositoryImpl(
            mock(EntityManager.class), postgresDbUtils(), jdbcTemplate);

    assertThat(repository.insertExistingRepositoryEntries(7L, 5L)).isEqualTo(3);

    CapturedUpdate capturedUpdate = capturedUpdate(jdbcTemplate);
    assertThat(capturedUpdate.sql())
        .contains("on conflict (refresh_run_id, term_index_extracted_term_id) do nothing")
        .doesNotContain("insert ignore");
    assertThat(capturedUpdate.parameters().getValue("refreshRunId")).isEqualTo(7L);
    assertThat(capturedUpdate.parameters().getValue("repositoryId")).isEqualTo(5L);
  }

  private NamedParameterJdbcTemplate jdbcTemplateReturning(int updateCount) {
    NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
        .thenReturn(updateCount);
    return jdbcTemplate;
  }

  private DBUtils postgresDbUtils() {
    DBUtils dbUtils = mock(DBUtils.class);
    when(dbUtils.isPostgres()).thenReturn(true);
    return dbUtils;
  }

  private CapturedUpdate capturedUpdate(NamedParameterJdbcTemplate jdbcTemplate) {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> parametersCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), parametersCaptor.capture());
    return new CapturedUpdate(sqlCaptor.getValue(), parametersCaptor.getValue());
  }

  private record CapturedUpdate(String sql, MapSqlParameterSource parameters) {}
}
