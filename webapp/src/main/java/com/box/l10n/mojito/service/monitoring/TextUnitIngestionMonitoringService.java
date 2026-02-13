package com.box.l10n.mojito.service.monitoring;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.monitoring.MonitoringTextUnitIngestionDaily;
import com.box.l10n.mojito.entity.monitoring.MonitoringTextUnitIngestionState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TextUnitIngestionMonitoringService {

  static final int STATE_ROW_ID = 1;

  private final JdbcTemplate jdbcTemplate;
  private final MonitoringTextUnitIngestionDailyRepository dailyRepository;
  private final MonitoringTextUnitIngestionStateRepository stateRepository;

  @PersistenceContext EntityManager entityManager;

  public TextUnitIngestionMonitoringService(
      JdbcTemplate jdbcTemplate,
      MonitoringTextUnitIngestionDailyRepository dailyRepository,
      MonitoringTextUnitIngestionStateRepository stateRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.dailyRepository = dailyRepository;
    this.stateRepository = stateRepository;
  }

  @Transactional
  public IngestionRecomputeResult recomputeMissingDays() {
    MonitoringTextUnitIngestionState stateEntity = getOrCreateState();
    LocalDate latestComputedDayBefore = stateEntity.getLatestComputedDay();
    Instant computedAt = Instant.now();

    LocalDate yesterdayUtc = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    LocalDate recomputeFromDay =
        latestComputedDayBefore != null
            ? latestComputedDayBefore.plusDays(1)
            : getEarliestSourceDay();

    if (recomputeFromDay == null || recomputeFromDay.isAfter(yesterdayUtc)) {
      updateState(stateEntity, latestComputedDayBefore, computedAt);
      return new IngestionRecomputeResult(
          latestComputedDayBefore, latestComputedDayBefore, null, null, 0, 0, computedAt);
    }

    LocalDate recomputeToDay = yesterdayUtc;
    List<SourceAggregateRow> sourceRows = querySourceAggregates(recomputeFromDay, recomputeToDay);
    int insertedRows = saveDailyRows(sourceRows, computedAt);

    updateState(stateEntity, recomputeToDay, computedAt);

    long daysComputed = ChronoUnit.DAYS.between(recomputeFromDay, recomputeToDay) + 1;

    return new IngestionRecomputeResult(
        latestComputedDayBefore,
        recomputeToDay,
        recomputeFromDay,
        recomputeToDay,
        daysComputed,
        insertedRows,
        computedAt);
  }

  @Transactional(readOnly = true)
  public IngestionSnapshot getSnapshot(
      IngestionGroupBy groupBy, boolean groupByRepository, LocalDate fromDay, LocalDate toDay) {
    MonitoringTextUnitIngestionState currentState = getOrCreateState();
    List<MonitoringTextUnitIngestionDaily> storedRows = dailyRepository.findAllOrdered();

    if (fromDay != null && toDay != null && fromDay.isAfter(toDay)) {
      throw new IllegalArgumentException(
          "Invalid date range. fromDay must be before or equal to toDay.");
    }

    Map<IngestionSeriesKey, CountAccumulator> periodCounts = new LinkedHashMap<>();

    for (MonitoringTextUnitIngestionDaily row : storedRows) {
      LocalDate rowDay = row.getDayUtc();
      if ((fromDay != null && rowDay.isBefore(fromDay))
          || (toDay != null && rowDay.isAfter(toDay))) {
        continue;
      }

      String period = toPeriod(rowDay, groupBy);
      Long repositoryId = groupByRepository ? row.getRepository().getId() : null;
      String repositoryName = groupByRepository ? row.getRepository().getName() : null;
      IngestionSeriesKey key = new IngestionSeriesKey(period, repositoryId, repositoryName);

      CountAccumulator accumulator =
          periodCounts.computeIfAbsent(key, ignored -> new CountAccumulator());
      accumulator.stringCount += row.getStringCount() != null ? row.getStringCount() : 0;
      accumulator.wordCount += row.getWordCount() != null ? row.getWordCount() : 0;
    }

    List<IngestionPoint> points = new ArrayList<>(periodCounts.size());
    for (Map.Entry<IngestionSeriesKey, CountAccumulator> entry : periodCounts.entrySet()) {
      points.add(
          new IngestionPoint(
              entry.getKey().period,
              entry.getKey().repositoryId,
              entry.getKey().repositoryName,
              entry.getValue().stringCount,
              entry.getValue().wordCount));
    }

    return new IngestionSnapshot(
        groupBy.name().toLowerCase(Locale.ROOT),
        groupByRepository,
        currentState.getLatestComputedDay(),
        currentState.getLastComputedAt() != null
            ? currentState.getLastComputedAt().toInstant()
            : null,
        points);
  }

  private String toPeriod(LocalDate dayUtc, IngestionGroupBy groupBy) {
    return switch (groupBy) {
      case DAY -> dayUtc.toString();
      case MONTH -> YearMonth.from(dayUtc).toString();
      case YEAR -> String.valueOf(dayUtc.getYear());
    };
  }

  private int saveDailyRows(List<SourceAggregateRow> sourceRows, Instant computedAt) {
    if (sourceRows.isEmpty()) {
      return 0;
    }

    ZonedDateTime computedAtUtc = ZonedDateTime.ofInstant(computedAt, ZoneOffset.UTC);
    List<MonitoringTextUnitIngestionDaily> rowsToSave = new ArrayList<>(sourceRows.size());

    for (SourceAggregateRow sourceRow : sourceRows) {
      MonitoringTextUnitIngestionDaily row = new MonitoringTextUnitIngestionDaily();
      row.setDayUtc(sourceRow.dayUtc);
      row.setRepository(entityManager.getReference(Repository.class, sourceRow.repositoryId));
      row.setStringCount(sourceRow.stringCount);
      row.setWordCount(sourceRow.wordCount);
      row.setComputedAt(computedAtUtc);
      rowsToSave.add(row);
    }

    dailyRepository.saveAll(rowsToSave);
    return rowsToSave.size();
  }

  private List<SourceAggregateRow> querySourceAggregates(
      LocalDate fromDayInclusive, LocalDate toDayInclusive) {
    ZonedDateTime from = fromDayInclusive.atStartOfDay(ZoneOffset.UTC);
    ZonedDateTime toExclusive = toDayInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC);

    String sql =
        "SELECT CAST(t.created_date AS DATE) AS day_utc,"
            + " a.repository_id AS repository_id,"
            + " COUNT(*) AS string_count,"
            + " COALESCE(SUM(COALESCE(t.word_count, 0)), 0) AS word_count"
            + " FROM tm_text_unit t"
            + " JOIN asset a ON a.id = t.asset_id"
            + " WHERE t.created_date >= ? AND t.created_date < ?"
            + " GROUP BY CAST(t.created_date AS DATE), a.repository_id"
            + " ORDER BY day_utc ASC, repository_id ASC";

    return jdbcTemplate.query(
        sql,
        (rs, ignored) ->
            new SourceAggregateRow(
                rs.getDate("day_utc").toLocalDate(),
                rs.getLong("repository_id"),
                rs.getLong("string_count"),
                rs.getLong("word_count")),
        Timestamp.from(from.toInstant()),
        Timestamp.from(toExclusive.toInstant()));
  }

  private LocalDate getEarliestSourceDay() {
    return jdbcTemplate.query(
        "SELECT CAST(MIN(created_date) AS DATE) AS min_day FROM tm_text_unit",
        rs -> {
          if (!rs.next()) {
            return null;
          }
          Date date = rs.getDate("min_day");
          return date != null ? date.toLocalDate() : null;
        });
  }

  private MonitoringTextUnitIngestionState getOrCreateState() {
    return stateRepository
        .findById(STATE_ROW_ID)
        .orElseGet(
            () -> {
              MonitoringTextUnitIngestionState state = new MonitoringTextUnitIngestionState();
              state.setId(STATE_ROW_ID);
              return stateRepository.save(state);
            });
  }

  private void updateState(
      MonitoringTextUnitIngestionState stateEntity,
      LocalDate latestComputedDay,
      Instant computedAt) {
    stateEntity.setLatestComputedDay(latestComputedDay);
    stateEntity.setLastComputedAt(ZonedDateTime.ofInstant(computedAt, ZoneOffset.UTC));
    stateRepository.save(stateEntity);
  }

  static class SourceAggregateRow {
    final LocalDate dayUtc;
    final long repositoryId;
    final long stringCount;
    final long wordCount;

    SourceAggregateRow(LocalDate dayUtc, long repositoryId, long stringCount, long wordCount) {
      this.dayUtc = dayUtc;
      this.repositoryId = repositoryId;
      this.stringCount = stringCount;
      this.wordCount = wordCount;
    }
  }

  static class IngestionSeriesKey {
    final String period;
    final Long repositoryId;
    final String repositoryName;

    IngestionSeriesKey(String period, Long repositoryId, String repositoryName) {
      this.period = period;
      this.repositoryId = repositoryId;
      this.repositoryName = repositoryName;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof IngestionSeriesKey other)) {
        return false;
      }
      return period.equals(other.period)
          && (repositoryId == null
              ? other.repositoryId == null
              : repositoryId.equals(other.repositoryId));
    }

    @Override
    public int hashCode() {
      int result = period.hashCode();
      result = 31 * result + (repositoryId != null ? repositoryId.hashCode() : 0);
      return result;
    }
  }

  static class CountAccumulator {
    long stringCount;
    long wordCount;
  }

  public enum IngestionGroupBy {
    DAY,
    MONTH,
    YEAR;

    public static IngestionGroupBy fromParam(String value) {
      if (value == null || value.isBlank()) {
        return DAY;
      }

      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "day" -> DAY;
        case "month" -> MONTH;
        case "year" -> YEAR;
        default -> throw new IllegalArgumentException("Invalid groupBy. Use: day, month, or year.");
      };
    }
  }

  public static class IngestionPoint {
    final String period;
    final Long repositoryId;
    final String repositoryName;
    final long stringCount;
    final long wordCount;

    public IngestionPoint(
        String period, Long repositoryId, String repositoryName, long stringCount, long wordCount) {
      this.period = period;
      this.repositoryId = repositoryId;
      this.repositoryName = repositoryName;
      this.stringCount = stringCount;
      this.wordCount = wordCount;
    }

    public String getPeriod() {
      return period;
    }

    public Long getRepositoryId() {
      return repositoryId;
    }

    public String getRepositoryName() {
      return repositoryName;
    }

    public long getStringCount() {
      return stringCount;
    }

    public long getWordCount() {
      return wordCount;
    }
  }

  public static class IngestionSnapshot {
    final String groupBy;
    final boolean groupedByRepository;
    final LocalDate latestComputedDay;
    final Instant lastComputedAt;
    final List<IngestionPoint> rows;

    public IngestionSnapshot(
        String groupBy,
        boolean groupedByRepository,
        LocalDate latestComputedDay,
        Instant lastComputedAt,
        List<IngestionPoint> rows) {
      this.groupBy = groupBy;
      this.groupedByRepository = groupedByRepository;
      this.latestComputedDay = latestComputedDay;
      this.lastComputedAt = lastComputedAt;
      this.rows = List.copyOf(rows);
    }

    public String getGroupBy() {
      return groupBy;
    }

    public boolean isGroupedByRepository() {
      return groupedByRepository;
    }

    public LocalDate getLatestComputedDay() {
      return latestComputedDay;
    }

    public Instant getLastComputedAt() {
      return lastComputedAt;
    }

    public List<IngestionPoint> getRows() {
      return rows;
    }
  }

  public static class IngestionRecomputeResult {
    final LocalDate latestComputedDayBefore;
    final LocalDate latestComputedDayAfter;
    final LocalDate recomputedFromDay;
    final LocalDate recomputedToDay;
    final long daysComputed;
    final int savedRows;
    final Instant computedAt;

    public IngestionRecomputeResult(
        LocalDate latestComputedDayBefore,
        LocalDate latestComputedDayAfter,
        LocalDate recomputedFromDay,
        LocalDate recomputedToDay,
        long daysComputed,
        int savedRows,
        Instant computedAt) {
      this.latestComputedDayBefore = latestComputedDayBefore;
      this.latestComputedDayAfter = latestComputedDayAfter;
      this.recomputedFromDay = recomputedFromDay;
      this.recomputedToDay = recomputedToDay;
      this.daysComputed = daysComputed;
      this.savedRows = savedRows;
      this.computedAt = computedAt;
    }

    public LocalDate getLatestComputedDayBefore() {
      return latestComputedDayBefore;
    }

    public LocalDate getLatestComputedDayAfter() {
      return latestComputedDayAfter;
    }

    public LocalDate getRecomputedFromDay() {
      return recomputedFromDay;
    }

    public LocalDate getRecomputedToDay() {
      return recomputedToDay;
    }

    public long getDaysComputed() {
      return daysComputed;
    }

    public int getSavedRows() {
      return savedRows;
    }

    public Instant getComputedAt() {
      return computedAt;
    }
  }
}
