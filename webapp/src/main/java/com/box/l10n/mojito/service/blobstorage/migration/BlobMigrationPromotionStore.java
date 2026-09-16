package com.box.l10n.mojito.service.blobstorage.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Progress and reconciliation evidence; never writes application payload rows. */
@Repository
public class BlobMigrationPromotionStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final BlobMigrationStore snapshots;

  public BlobMigrationPromotionStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, BlobMigrationStore snapshots) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(5);
    this.snapshots = snapshots;
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
  }

  public record Run(
      String id,
      String snapshotRunId,
      String fenceId,
      String manifestSha256,
      String destinationRoot,
      long sourceHighWaterId,
      long promotionCursor,
      long reconciliationCursor,
      String phase,
      String status,
      String leaseToken,
      Instant leaseUntil,
      long reconciledCount,
      long canonicalCount,
      long retainedCount,
      long retainedBytes,
      Instant reconciledAt,
      Instant fenceValidUntil,
      String lastError) {}

  public record Item(
      String promotionId,
      long sourceId,
      String sourceName,
      Long sourceLength,
      Instant sourceCreatedDate,
      Long sourceExpireSeconds,
      String sourceSha256,
      String canonicalEtag,
      String disposition,
      boolean reconciled,
      Instant verifiedAt,
      String error) {}

  public Run create(
      String snapshotRunId,
      String fenceId,
      String manifestSha,
      String destination,
      long highWaterId,
      Instant fenceValidUntil) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into mblob_migration_promotion (id, snapshot_run_id, fence_id, manifest_sha256, destination_root, source_high_water_id, promotion_cursor, reconciliation_cursor, phase, status, reconciled_count, canonical_count, retained_count, retained_bytes, fence_valid_until) values (?, ?, ?, ?, ?, ?, 0, 0, 'PROMOTE', 'READY', 0, 0, 0, 0, ?)",
        id,
        snapshotRunId,
        fenceId,
        manifestSha,
        destination,
        highWaterId,
        timestamp(fenceValidUntil));
    return get(id);
  }

  public Run get(String id) {
    return jdbc
        .query("select * from mblob_migration_promotion where id = ?", this::run, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown promotion"));
  }

  public List<Run> list() {
    return jdbc.query("select * from mblob_migration_promotion order by id limit 100", this::run);
  }

  public Item item(String promotionId, long sourceId) {
    return jdbc
        .query(
            "select * from mblob_migration_promotion_item where promotion_id = ? and source_id = ?",
            this::item,
            promotionId,
            sourceId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean hasConflicts(String id) {
    return !jdbc.queryForList(
            "select source_id from mblob_migration_promotion_item where promotion_id = ? and disposition in ('RETAIN_CANONICAL_CONFLICT', 'RETAIN_RETENTION_CONFLICT') and reconciled = true limit 1",
            Long.class,
            id)
        .isEmpty();
  }

  public boolean hasUnreconciledCanonical(String id) {
    return !jdbc.queryForList(
            "select source_id from mblob_migration_promotion_item where promotion_id = ? and disposition = 'CANONICAL_VERIFIED' and reconciled = false limit 1",
            Long.class,
            id)
        .isEmpty();
  }

  public List<Item> items(String id, long afterId, int limit) {
    return jdbc.query(
        "select * from mblob_migration_promotion_item where promotion_id = ? and source_id > ? order by source_id limit ?",
        this::item,
        id,
        afterId,
        limit);
  }

  public String claim(String id, int seconds) {
    return tx.execute(
        s -> {
          Run run = locked(id);
          if ("RECONCILED".equals(run.status()) || "COMPLETE".equals(run.phase()))
            throw new IllegalArgumentException(
                "Promotion already reconciled; verify readiness under its fence");
          if (run.leaseToken() != null
              && run.leaseUntil() != null
              && run.leaseUntil().isAfter(snapshots.now())) return null;
          String token = UUID.randomUUID().toString();
          jdbc.update(
              "update mblob_migration_promotion set status = 'RUNNING', lease_token = ?, lease_until = ?, last_error = null where id = ?",
              token,
              timestamp(snapshots.now().plusSeconds(seconds)),
              id);
          return token;
        });
  }

  public void renew(String id, String token, int seconds) {
    tx.executeWithoutResult(
        s -> {
          requireLease(locked(id), token);
          jdbc.update(
              "update mblob_migration_promotion set lease_until = ? where id = ?",
              timestamp(snapshots.now().plusSeconds(seconds)),
              id);
        });
  }

  public Run pause(String id) {
    return tx.execute(
        s -> {
          locked(id);
          jdbc.update(
              "update mblob_migration_promotion set status = 'PAUSED', lease_token = null, lease_until = null where id = ?",
              id);
          return get(id);
        });
  }

  public void record(String id, String token, Item item, boolean advance) {
    tx.executeWithoutResult(
        s -> {
          Run run = locked(id);
          requireLease(run, token);
          if (advance
              && ((!"PROMOTE".equals(run.phase()) && !"RECONCILE".equals(run.phase()))
                  || item.reconciled() != "RECONCILE".equals(run.phase())))
            throw new IllegalStateException(
                "Item verification does not match the leased promotion phase");
          Item previous = item(id, item.sourceId());
          if (previous != null)
            jdbc.update(
                "delete from mblob_migration_promotion_item where promotion_id = ? and source_id = ?",
                id,
                item.sourceId());
          jdbc.update(
              "insert into mblob_migration_promotion_item (promotion_id, source_id, source_name, source_length, source_created_date, source_expire_seconds, source_sha256, canonical_etag, disposition, reconciled, verified_at, last_error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              id,
              item.sourceId(),
              item.sourceName(),
              item.sourceLength(),
              timestamp(item.sourceCreatedDate()),
              item.sourceExpireSeconds(),
              item.sourceSha256(),
              item.canonicalEtag(),
              item.disposition(),
              item.reconciled(),
              timestamp(item.verifiedAt()),
              item.error());
          jdbc.update(
              "update mblob_migration_promotion set promotion_cursor = ?, reconciliation_cursor = ?, reconciled_count = reconciled_count + ?, canonical_count = canonical_count + ?, retained_count = retained_count + ?, retained_bytes = retained_bytes + ? where id = ?",
              advance && "PROMOTE".equals(run.phase())
                  ? Math.max(run.promotionCursor(), item.sourceId())
                  : run.promotionCursor(),
              advance && "RECONCILE".equals(run.phase())
                  ? Math.max(run.reconciliationCursor(), item.sourceId())
                  : run.reconciliationCursor(),
              reconciled(item) - reconciled(previous),
              canonical(item) - canonical(previous),
              retained(item) - retained(previous),
              retainedBytes(item) - retainedBytes(previous),
              id);
        });
  }

  public void startReconciliation(String id, String token) {
    tx.executeWithoutResult(
        s -> {
          Run run = locked(id);
          requireLease(run, token);
          if (!"PROMOTE".equals(run.phase()))
            throw new IllegalStateException("Invalid promotion phase");
          jdbc.update("update mblob_migration_promotion set phase = 'RECONCILE' where id = ?", id);
        });
  }

  public void release(
      String id, String token, String status, String error, Instant fenceValidUntil) {
    tx.executeWithoutResult(
        s -> {
          Run run = locked(id);
          requireLease(run, token);
          boolean complete = "RECONCILED".equals(status);
          if (complete
              && (!"RECONCILE".equals(run.phase())
                  || run.reconciliationCursor() != run.sourceHighWaterId()))
            throw new IllegalStateException(
                "Final reconciliation must reach the complete source boundary");
          if (complete && hasUnreconciledCanonical(id))
            throw new IllegalStateException(
                "A promoted canonical row was not reconciled against the source");
          jdbc.update(
              "update mblob_migration_promotion set status = ?, phase = ?, lease_token = null, lease_until = null, last_error = ?, fence_valid_until = ?, reconciled_at = ? where id = ?",
              status,
              complete ? "COMPLETE" : run.phase(),
              error,
              timestamp(fenceValidUntil),
              complete ? timestamp(snapshots.now()) : timestamp(run.reconciledAt()),
              id);
        });
  }

  private Run locked(String id) {
    return jdbc
        .query("select * from mblob_migration_promotion where id = ? for update", this::run, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown promotion"));
  }

  private void requireLease(Run run, String token) {
    if (!"RUNNING".equals(run.status())
        || !Objects.equals(token, run.leaseToken())
        || run.leaseUntil() == null
        || !run.leaseUntil().isAfter(snapshots.now())) throw new BlobMigrationStore.LeaseLost();
  }

  private static long reconciled(Item item) {
    return item != null && item.reconciled() ? 1 : 0;
  }

  private static long canonical(Item item) {
    return reconciled(item) == 1 && "CANONICAL_VERIFIED".equals(item.disposition()) ? 1 : 0;
  }

  private static long retained(Item item) {
    return reconciled(item) - canonical(item);
  }

  private static long retainedBytes(Item item) {
    return retained(item) == 1 && item.sourceLength() != null ? item.sourceLength() : 0;
  }

  private static Timestamp timestamp(Instant instant) {
    return BlobMigrationStore.timestamp(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return BlobMigrationStore.instant(rs, column);
  }

  private Run run(ResultSet rs, int n) throws SQLException {
    return new Run(
        rs.getString("id"),
        rs.getString("snapshot_run_id"),
        rs.getString("fence_id"),
        rs.getString("manifest_sha256"),
        rs.getString("destination_root"),
        rs.getLong("source_high_water_id"),
        rs.getLong("promotion_cursor"),
        rs.getLong("reconciliation_cursor"),
        rs.getString("phase"),
        rs.getString("status"),
        rs.getString("lease_token"),
        instant(rs, "lease_until"),
        rs.getLong("reconciled_count"),
        rs.getLong("canonical_count"),
        rs.getLong("retained_count"),
        rs.getLong("retained_bytes"),
        instant(rs, "reconciled_at"),
        instant(rs, "fence_valid_until"),
        rs.getString("last_error"));
  }

  private Item item(ResultSet rs, int n) throws SQLException {
    return new Item(
        rs.getString("promotion_id"),
        rs.getLong("source_id"),
        rs.getString("source_name"),
        rs.getObject("source_length", Long.class),
        instant(rs, "source_created_date"),
        rs.getObject("source_expire_seconds", Long.class),
        rs.getString("source_sha256"),
        rs.getString("canonical_etag"),
        rs.getString("disposition"),
        rs.getBoolean("reconciled"),
        instant(rs, "verified_at"),
        rs.getString("last_error"));
  }
}
