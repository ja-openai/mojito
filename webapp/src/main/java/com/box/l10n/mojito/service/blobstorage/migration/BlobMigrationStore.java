package com.box.l10n.mojito.service.blobstorage.migration;

import com.box.l10n.mojito.service.DBUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable snapshot evidence only: nothing here deletes or changes a source mblob row. */
@Repository
public class BlobMigrationStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final boolean mysql;

  public BlobMigrationStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, DBUtils dbUtils) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(5);
    mysql = dbUtils.isMysql();
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
  }

  public record Run(
      String id,
      String allowedPrefixes,
      String destinationRoot,
      long highWaterId,
      long cursorId,
      int maxRows,
      long maxBytes,
      int maxSeconds,
      int maxRetries,
      String status,
      boolean requested,
      String leaseToken,
      Instant leaseUntil,
      long scanned,
      long verified,
      long verifiedBytes,
      long preserved,
      long failed,
      String lastError) {}

  /** A scan candidate has not read or measured the payload. */
  public record Candidate(long id, String name, Instant createdDate, Long expireSeconds) {}

  /** Payload metadata from scan/metadata/read. Use Candidate before measuring a payload. */
  public record Source(long id, String name, Long length, Instant createdDate, Long expireSeconds) {
    public Candidate candidate() {
      return new Candidate(id, name, createdDate, expireSeconds);
    }
  }

  public record Evidence(
      String runId,
      long sourceId,
      String name,
      Long length,
      Instant createdDate,
      Long expireSeconds,
      String digest,
      String snapshotName,
      String disposition,
      int attempts,
      String error,
      Instant verifiedAt) {}

  public record Snapshot(Source source, byte[] content) {}

  public Run create(
      String allowedPrefixes,
      String destinationRoot,
      int maxRows,
      long maxBytes,
      int maxSeconds,
      int maxRetries) {
    String id = UUID.randomUUID().toString();
    Long boundary = jdbc.queryForObject("select coalesce(max(id),0) from mblob", Long.class);
    jdbc.update(
        "insert into mblob_migration_run (id, allowed_prefixes, destination_root, high_water_id, cursor_id, max_rows, max_bytes, max_seconds, max_retries, status, requested, scanned_count, verified_count, verified_bytes, preserved_count, failed_count) values (?, ?, ?, ?, 0, ?, ?, ?, ?, 'PAUSED', false, 0, 0, 0, 0, 0)",
        id,
        allowedPrefixes,
        destinationRoot,
        boundary,
        maxRows,
        maxBytes,
        maxSeconds,
        maxRetries);
    return get(id);
  }

  public Run get(String id) {
    return jdbc.query("select * from mblob_migration_run where id = ?", this::run, id).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown migration run"));
  }

  public List<Run> list() {
    return jdbc.query("select * from mblob_migration_run order by id limit 100", this::run);
  }

  public List<String> ready() {
    return jdbc.queryForList(
        "select id from mblob_migration_run where requested = true and status in ('READY','RUNNING') order by id limit 1",
        String.class);
  }

  public Run request(String id, boolean requested) {
    return tx.execute(
        s -> {
          Run run = locked(id);
          if (requested && run.status().startsWith("SNAPSHOT_")) {
            throw new IllegalArgumentException(
                "Snapshot scan is finished; create a new run for reconciliation");
          }
          // Revoking the token fences an in-flight worker's subsequent evidence/cursor writes.
          jdbc.update(
              "update mblob_migration_run set requested = ?, status = ?, lease_token = null, lease_until = null where id = ?",
              requested,
              requested ? "READY" : "PAUSED",
              id);
          return get(id);
        });
  }

  public String claim(String id, int leaseSeconds) {
    return tx.execute(
        s -> {
          Run run = locked(id);
          Instant now = now();
          if (!run.requested()
              || !List.of("READY", "RUNNING").contains(run.status())
              || (run.leaseToken() != null
                  && run.leaseUntil() != null
                  && run.leaseUntil().isAfter(now))) return null;
          String token = UUID.randomUUID().toString();
          jdbc.update(
              "update mblob_migration_run set status = 'RUNNING', lease_token = ?, lease_until = ?, last_error = null where id = ?",
              token,
              Timestamp.from(now.plusSeconds(leaseSeconds)),
              id);
          return token;
        });
  }

  public void renew(String id, String token, int leaseSeconds) {
    tx.executeWithoutResult(
        s -> {
          requireLease(locked(id), token);
          jdbc.update(
              "update mblob_migration_run set lease_until = ? where id = ?",
              Timestamp.from(now().plusSeconds(leaseSeconds)),
              id);
        });
  }

  public List<Source> scan(Run run, int limit) {
    return scan(run.cursorId(), run.highWaterId(), limit);
  }

  public List<Candidate> scanCandidates(Run run, int limit) {
    List<String> branches = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    for (String prefix : Arrays.stream(run.allowedPrefixes().split(",")).distinct().toList()) {
      if (!prefix.matches("[a-z][a-z0-9_]*"))
        throw new IllegalArgumentException("Invalid snapshot semantic prefix");
      String start = prefix + "/";
      branches.add(
          "(select id from mblob"
              + (mysql ? " force index (UK__MBLOB__NAME)" : "")
              + " where name >= ? and name < ? and "
              + (mysql ? "binary left(name, ?) = binary ?" : "left(name, ?) = ?")
              + " and id > ? and id <= ? order by id limit ?)");
      parameters.addAll(
          List.of(
              start,
              prefix + "0",
              start.length(),
              start,
              run.cursorId(),
              run.highWaterId(),
              limit));
    }
    parameters.add(limit);
    // Disjoint literal prefixes: every global next-N ID is in its prefix's next N.
    // Each MySQL branch reads only the covering name index; only the final N rows need metadata.
    // Keep selection and the metadata join in one statement snapshot: a separate fetch could lose
    // deleted candidates, creating a falsely short page and premature scan completion.
    String sql =
        "select "
            + (mysql ? "/*+ MAX_EXECUTION_TIME(5000) */ " : "")
            + "b.id, b.name, b.created_date, b.expire_after_seconds from "
            + "(select id from ("
            + String.join(" union all ", branches)
            + ") selected_ids order by id limit ?) chosen "
            + "join mblob b on b.id = chosen.id order by b.id";
    return jdbc.query(
        sql,
        (rs, n) ->
            new Candidate(
                rs.getLong("id"),
                rs.getString("name"),
                instant(rs, "created_date"),
                rs.getObject("expire_after_seconds", Long.class)),
        parameters.toArray());
  }

  public long maxSourceId() {
    return Objects.requireNonNull(
        jdbc.queryForObject("select coalesce(max(id),0) from mblob", Long.class));
  }

  public List<Source> scan(long cursor, long highWaterId, int limit) {
    return jdbc.query(
        "select id, name, octet_length(content) as payload_length, created_date, expire_after_seconds from mblob where id > ? and id <= ? order by id limit ?",
        this::source,
        cursor,
        highWaterId,
        limit);
  }

  public Source metadata(long id) {
    return jdbc
        .query(
            "select id, name, octet_length(content) as payload_length, created_date, expire_after_seconds from mblob where id = ?",
            this::source,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Snapshot read(long id, long maxBytes) {
    // The WHERE predicate is repeated on the LOB read so growth after metadata selection cannot
    // cause an unbounded payload allocation. A missing/oversized row remains preserved in MySQL.
    return jdbc
        .query(
            "select id, name, octet_length(content) as payload_length, created_date, expire_after_seconds, content from mblob where id = ? and octet_length(content) <= ?",
            (rs, n) -> new Snapshot(source(rs, n), rs.getBytes("content")),
            id,
            maxBytes)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Evidence> evidence(String id, long afterId, int limit) {
    return jdbc.query(
        "select * from mblob_migration_item where run_id = ? and source_id > ? order by source_id limit ?",
        this::evidence,
        id,
        afterId,
        limit);
  }

  public List<Evidence> retryable(Run run, int limit) {
    return jdbc.query(
        "select * from mblob_migration_item where run_id = ? and disposition = 'FAILED' and attempts < ? order by source_id limit ?",
        this::evidence,
        run.id(),
        run.maxRetries(),
        limit);
  }

  public void record(Run scope, String token, Evidence result, boolean advanceCursor) {
    tx.executeWithoutResult(
        s -> {
          Run current = locked(scope.id());
          requireLease(current, token);
          List<Evidence> previous =
              jdbc.query(
                  "select * from mblob_migration_item where run_id = ? and source_id = ?",
                  this::evidence,
                  scope.id(),
                  result.sourceId());
          Evidence old = previous.isEmpty() ? null : previous.getFirst();
          if (old != null) {
            jdbc.update(
                "delete from mblob_migration_item where run_id = ? and source_id = ?",
                scope.id(),
                result.sourceId());
          }
          jdbc.update(
              "insert into mblob_migration_item (run_id, source_id, source_name, source_length, source_created_date, source_expire_seconds, sha256, snapshot_name, disposition, attempts, last_error, verified_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              result.runId(),
              result.sourceId(),
              result.name(),
              result.length(),
              timestamp(result.createdDate()),
              result.expireSeconds(),
              result.digest(),
              result.snapshotName(),
              result.disposition(),
              result.attempts(),
              result.error(),
              timestamp(result.verifiedAt()));
          jdbc.update(
              "update mblob_migration_run set cursor_id = ?, scanned_count = scanned_count + ?, verified_count = verified_count + ?, verified_bytes = verified_bytes + ?, preserved_count = preserved_count + ?, failed_count = failed_count + ? where id = ?",
              advanceCursor ? Math.max(current.cursorId(), result.sourceId()) : current.cursorId(),
              old == null ? 1 : 0,
              verified(result) - verified(old),
              verifiedBytes(result) - verifiedBytes(old),
              preserved(result) - preserved(old),
              failed(result) - failed(old),
              scope.id());
        });
  }

  public void release(String id, String token, String status, String error) {
    tx.executeWithoutResult(
        s -> {
          Run run = locked(id);
          requireLease(run, token);
          jdbc.update(
              "update mblob_migration_run set status = ?, requested = ?, lease_token = null, lease_until = null, last_error = ? where id = ?",
              status,
              "READY".equals(status),
              error,
              id);
        });
  }

  private Run locked(String id) {
    return jdbc
        .query("select * from mblob_migration_run where id = ? for update", this::run, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown migration run"));
  }

  private void requireLease(Run run, String token) {
    if (!run.requested()
        || !"RUNNING".equals(run.status())
        || !Objects.equals(token, run.leaseToken())
        || run.leaseUntil() == null
        || !run.leaseUntil().isAfter(now())) throw new LeaseLost();
  }

  Instant now() {
    return Objects.requireNonNull(jdbc.queryForObject("select current_timestamp", Timestamp.class))
        .toInstant();
  }

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static Instant instant(ResultSet rs, String name) throws SQLException {
    Timestamp value = rs.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  static long verified(Evidence e) {
    return e != null && "VERIFIED_SNAPSHOT".equals(e.disposition()) ? 1 : 0;
  }

  static long verifiedBytes(Evidence e) {
    return verified(e) == 1 ? e.length() : 0;
  }

  static long failed(Evidence e) {
    return e != null && "FAILED".equals(e.disposition()) ? 1 : 0;
  }

  static long preserved(Evidence e) {
    return e != null && verified(e) == 0 && failed(e) == 0 ? 1 : 0;
  }

  private Source source(ResultSet rs, int n) throws SQLException {
    return new Source(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getObject("payload_length", Long.class),
        instant(rs, "created_date"),
        rs.getObject("expire_after_seconds", Long.class));
  }

  private Evidence evidence(ResultSet rs, int n) throws SQLException {
    return new Evidence(
        rs.getString("run_id"),
        rs.getLong("source_id"),
        rs.getString("source_name"),
        rs.getObject("source_length", Long.class),
        instant(rs, "source_created_date"),
        rs.getObject("source_expire_seconds", Long.class),
        rs.getString("sha256"),
        rs.getString("snapshot_name"),
        rs.getString("disposition"),
        rs.getInt("attempts"),
        rs.getString("last_error"),
        instant(rs, "verified_at"));
  }

  private Run run(ResultSet rs, int n) throws SQLException {
    return new Run(
        rs.getString("id"),
        rs.getString("allowed_prefixes"),
        rs.getString("destination_root"),
        rs.getLong("high_water_id"),
        rs.getLong("cursor_id"),
        rs.getInt("max_rows"),
        rs.getLong("max_bytes"),
        rs.getInt("max_seconds"),
        rs.getInt("max_retries"),
        rs.getString("status"),
        rs.getBoolean("requested"),
        rs.getString("lease_token"),
        instant(rs, "lease_until"),
        rs.getLong("scanned_count"),
        rs.getLong("verified_count"),
        rs.getLong("verified_bytes"),
        rs.getLong("preserved_count"),
        rs.getLong("failed_count"),
        rs.getString("last_error"));
  }

  public static class LeaseLost extends IllegalStateException {
    public LeaseLost() {
      super("Migration lease expired, was paused, or was replaced");
    }
  }
}
