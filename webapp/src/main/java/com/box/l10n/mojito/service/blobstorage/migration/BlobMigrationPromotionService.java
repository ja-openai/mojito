package com.box.l10n.mojito.service.blobstorage.migration;

import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage.MigrationBlob;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationMaintenanceFence.Lease;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionStore.Item;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionStore.Run;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Evidence;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Snapshot;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Explicit maintenance batches only: no scheduler calls this service. An externally enforced writer
 * barrier must outlive lease expiration and in-flight Azure calls; a database lease alone cannot
 * fence API/worker writes, lazy backfills, lifecycle deletion, or external Azure clients.
 */
@Service
public class BlobMigrationPromotionService {
  private static final Duration READ_BUDGET = Duration.ofSeconds(95);
  private static final Duration WRITE_BUDGET = Duration.ofSeconds(35);
  private static final Duration DATABASE_BUDGET = Duration.ofSeconds(15);
  private final BlobMigrationStore snapshots;
  private final BlobMigrationPromotionStore promotions;
  private final BlobMigrationProperties properties;
  private final BlobMigrationMaintenanceFence fence;
  private final ObjectProvider<AzureBlobStorage> azureProvider;

  public BlobMigrationPromotionService(
      BlobMigrationStore snapshots,
      BlobMigrationPromotionStore promotions,
      BlobMigrationProperties properties,
      BlobMigrationMaintenanceFence fence,
      ObjectProvider<AzureBlobStorage> azureProvider) {
    this.snapshots = snapshots;
    this.promotions = promotions;
    this.properties = properties;
    this.fence = fence;
    this.azureProvider = azureProvider;
  }

  public record CreateRequest(String snapshotRunId, String fenceId) {}

  public record Readiness(Run evidence, Instant validUntil) {}

  public Run create(CreateRequest request) {
    requireEnabled();
    if (request == null
        || request.snapshotRunId() == null
        || request.fenceId() == null
        || !request.fenceId().matches("[A-Za-z0-9_.:-]{1,128}"))
      throw new IllegalArgumentException(
          "A snapshot run and explicit maintenance fence identity are required");
    BlobMigrationStore.Run source = snapshots.get(request.snapshotRunId());
    if (!source.status().startsWith("SNAPSHOT_"))
      throw new IllegalArgumentException("Finish the bounded snapshot scan before promotion");
    azure(source.destinationRoot());
    Lease lease = fence.verify(source.id(), request.fenceId(), source.destinationRoot(), null);
    lease.requireRemaining(DATABASE_BUDGET);
    long boundary = snapshots.maxSourceId();
    return promotions.create(
        source.id(),
        request.fenceId(),
        lease.manifestSha256(),
        source.destinationRoot(),
        boundary,
        lease.validUntil());
  }

  public List<Run> list() {
    return promotions.list();
  }

  public Run get(String id) {
    return promotions.get(id);
  }

  public Run pause(String id) {
    return promotions.pause(id);
  }

  public List<Item> items(String id, long afterId, int limit) {
    if (afterId < 0 || limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Invalid promotion evidence page");
    return promotions.items(id, afterId, limit);
  }

  /**
   * Historical status is not cutover authorization. This checks that its external fence still
   * holds.
   */
  public Readiness readiness(String id) {
    requireEnabled();
    Run run = promotions.get(id);
    if (!"RECONCILED".equals(run.status()) || !"COMPLETE".equals(run.phase()))
      throw new IllegalStateException("Final source reconciliation is incomplete");
    if (promotions.hasConflicts(id))
      throw new IllegalStateException(
          "Resolve canonical content or retention conflicts before cutover");
    if (promotions.hasUnreconciledCanonical(id))
      throw new IllegalStateException(
          "A promoted canonical row was not reconciled against the source");
    azure(run.destinationRoot());
    Lease lease =
        fence.verify(
            run.snapshotRunId(), run.fenceId(), run.destinationRoot(), run.manifestSha256());
    lease.requireRemaining(DATABASE_BUDGET);
    requireSourceBoundary(run);
    return new Readiness(run, lease.validUntil());
  }

  public Run runBatch(String id) {
    requireEnabled();
    Run observed = promotions.get(id);
    BlobMigrationStore.Run scope = snapshots.get(observed.snapshotRunId());
    AzureBlobStorage azure = azure(observed.destinationRoot());
    String token = promotions.claim(id, leaseSeconds());
    if (token == null) return promotions.get(id);
    // Another worker can finish a batch between the preflight read and this claim.
    // Phase and cursors must come from the state owned by the newly acquired lease.
    Run run = promotions.get(id);
    Guard guard = new Guard(run, token);
    Source current = null;
    long start = System.nanoTime();
    long bytes = 0;
    int rows = 0;
    try {
      guard.refresh();
      requireSourceBoundary(run);
      if ("PROMOTE".equals(run.phase())) {
        List<Evidence> candidates =
            snapshots.evidence(run.snapshotRunId(), run.promotionCursor(), scope.maxRows());
        for (Evidence evidence : candidates) {
          if (!withinBudget(scope, start, rows)) break;
          long charge =
              "VERIFIED_SNAPSHOT".equals(evidence.disposition()) && evidence.length() != null
                  ? evidence.length()
                  : 0;
          current = source(evidence);
          if (charge > scope.maxBytes()) throw new AzureBlobStorage.SnapshotVerificationException();
          if (charge > scope.maxBytes() - bytes) {
            current = null;
            break;
          }
          guard.before(DATABASE_BUDGET);
          Item result = promote(run, evidence, scope, azure, guard);
          guard.before(DATABASE_BUDGET);
          promotions.record(id, token, result, true);
          current = null;
          rows++;
          bytes += charge;
        }
        Run progressed = promotions.get(id);
        if (snapshots.evidence(run.snapshotRunId(), progressed.promotionCursor(), 1).isEmpty()) {
          guard.before(DATABASE_BUDGET);
          promotions.startReconciliation(id, token);
        }
        // A distinct invocation makes the complete source/delta pass visible and resumable.
      } else if ("RECONCILE".equals(run.phase())) {
        List<Source> candidates =
            snapshots.scan(run.reconciliationCursor(), run.sourceHighWaterId(), scope.maxRows());
        for (Source source : candidates) {
          if (!withinBudget(scope, start, rows)) break;
          Item previous = promotions.item(id, source.id());
          long charge =
              previous != null
                      && "CANONICAL_VERIFIED".equals(previous.disposition())
                      && source.length() != null
                  ? source.length()
                  : 0;
          current = source;
          if (charge > scope.maxBytes()) throw new FenceInvariantViolation();
          if (charge > scope.maxBytes() - bytes) {
            current = null;
            break;
          }
          guard.before(DATABASE_BUDGET);
          Item result = reconcile(run, source, previous, scope, azure, guard);
          guard.before(DATABASE_BUDGET);
          promotions.record(id, token, result, true);
          current = null;
          rows++;
          bytes += charge;
        }
      } else throw new IllegalStateException("Invalid promotion phase");
      // Fresh external verification at the batch publication boundary, not just a database lease.
      guard.refresh();
      guard.before(DATABASE_BUDGET);
      requireSourceBoundary(run);
      Run progressed = promotions.get(id);
      boolean complete =
          "RECONCILE".equals(run.phase())
              && snapshots
                  .scan(progressed.reconciliationCursor(), run.sourceHighWaterId(), 1)
                  .isEmpty();
      promotions.release(
          id, token, complete ? "RECONCILED" : "READY", null, guard.lease.validUntil());
    } catch (RuntimeException failure) {
      if (!(failure instanceof BlobMigrationStore.LeaseLost)) {
        try {
          // Preserve the failing row as evidence, but do not advance past a failed verification.
          if (current != null) {
            Item previous = promotions.item(id, current.id());
            Item failed =
                previous == null
                    ? item(
                        run,
                        current,
                        null,
                        null,
                        "FAILED",
                        false,
                        null,
                        failure.getClass().getSimpleName())
                    : new Item(
                        previous.promotionId(),
                        previous.sourceId(),
                        previous.sourceName(),
                        previous.sourceLength(),
                        previous.sourceCreatedDate(),
                        previous.sourceExpireSeconds(),
                        previous.sourceSha256(),
                        previous.canonicalEtag(),
                        previous.disposition(),
                        previous.reconciled(),
                        previous.verifiedAt(),
                        failure.getClass().getSimpleName());
            promotions.record(id, token, failed, false);
          }
          promotions.release(
              id,
              token,
              "PAUSED",
              failure.getClass().getSimpleName(),
              guard.lease == null ? run.fenceValidUntil() : guard.lease.validUntil());
        } catch (BlobMigrationStore.LeaseLost lost) {
          /* A paused/replaced worker cannot publish. */
        }
      }
      throw failure;
    }
    return promotions.get(id);
  }

  private Item promote(
      Run run,
      Evidence evidence,
      BlobMigrationStore.Run scope,
      AzureBlobStorage azure,
      Guard guard) {
    Source original = source(evidence);
    Source live = snapshots.metadata(evidence.sourceId());
    if (live == null) return item(run, original, null, null, "SOURCE_MISSING", false, null, null);
    if (!"VERIFIED_SNAPSHOT".equals(evidence.disposition()))
      return item(run, live, null, null, "RETAIN_" + evidence.disposition(), false, null, null);
    if (!original.equals(live) || live.length() == null || live.length() > scope.maxBytes())
      return item(run, live, null, null, "RETAIN_SOURCE_CHANGED", false, null, null);
    Snapshot source = snapshots.read(live.id(), live.length());
    if (source == null || !live.equals(source.source())) throw new FenceInvariantViolation();
    String digest = BlobMigrationService.sha256(source.content());
    if (!digest.equals(evidence.digest()))
      return item(run, live, digest, null, "RETAIN_SOURCE_CHANGED", false, null, null);
    if (evidence.verifiedAt() == null
        || !Objects.equals(
            evidence.snapshotName(),
            BlobMigrationService.SNAPSHOT_PREFIX
                + run.snapshotRunId()
                + "/"
                + live.id()
                + "/"
                + digest)) throw new AzureBlobStorage.SnapshotVerificationException();
    // Azure lifecycle age starts at upload, while the database TTL starts at created_date.
    // MIN_1_DAY cannot preserve an arbitrary remaining source lifetime. Keep every temporary
    // row in the replacement table until a separate expiry-preserving migration is available.
    if (live.expireSeconds() != null)
      return item(run, live, digest, null, "RETAIN_TEMPORARY_LIFETIME", false, null, null);
    guard.before(WRITE_BUDGET);
    byte[] archived = azure.getSnapshotBytes(evidence.snapshotName(), source.content().length);
    if (!Arrays.equals(archived, source.content()))
      throw new AzureBlobStorage.SnapshotVerificationException();
    guard.before(READ_BUDGET);
    Optional<MigrationBlob> canonical =
        azure.inspectCanonicalBlobForMigration(live.name(), source.content().length);
    if (canonical.isEmpty()) {
      guard.before(WRITE_BUDGET);
      azure.createCanonicalBlobForMigrationIfAbsent(live.name(), archived, retention(live));
      guard.before(READ_BUDGET);
      canonical = azure.inspectCanonicalBlobForMigration(live.name(), source.content().length);
    }
    MigrationBlob value =
        canonical.orElseThrow(AzureBlobStorage.SnapshotVerificationException::new);
    assertSourceUnchanged(live, source.content());
    if (value.content() == null || !Arrays.equals(source.content(), value.content()))
      return item(run, live, digest, value.eTag(), "RETAIN_CANONICAL_CONFLICT", false, null, null);
    if (!retention(live).toString().equals(value.retention()))
      return item(run, live, digest, value.eTag(), "RETAIN_RETENTION_CONFLICT", false, null, null);
    return item(
        run, live, digest, value.eTag(), "CANONICAL_VERIFIED", false, snapshots.now(), null);
  }

  private Item reconcile(
      Run run,
      Source source,
      Item previous,
      BlobMigrationStore.Run scope,
      AzureBlobStorage azure,
      Guard guard) {
    if (previous == null) return item(run, source, null, null, "RETAIN_DELTA", true, null, null);
    if (!"CANONICAL_VERIFIED".equals(previous.disposition())) {
      if (!source(previous).equals(source)) throw new FenceInvariantViolation();
      return item(
          run,
          source,
          previous.sourceSha256(),
          previous.canonicalEtag(),
          previous.disposition().startsWith("RETAIN_")
              ? previous.disposition()
              : "RETAIN_" + previous.disposition(),
          true,
          previous.verifiedAt(),
          previous.error());
    }
    if (!source(previous).equals(source)
        || source.length() == null
        || source.length() > scope.maxBytes()) throw new FenceInvariantViolation();
    Snapshot live = snapshots.read(source.id(), source.length());
    if (live == null
        || !source.equals(live.source())
        || !BlobMigrationService.sha256(live.content()).equals(previous.sourceSha256()))
      throw new FenceInvariantViolation();
    guard.before(READ_BUDGET);
    MigrationBlob remote =
        azure
            .inspectCanonicalBlobForMigration(source.name(), live.content().length)
            .orElseThrow(AzureBlobStorage.SnapshotVerificationException::new);
    if (remote.content() == null
        || !Arrays.equals(live.content(), remote.content())
        || !Objects.equals(previous.canonicalEtag(), remote.eTag())
        || !retention(source).toString().equals(remote.retention()))
      throw new AzureBlobStorage.SnapshotVerificationException();
    assertSourceUnchanged(source, live.content());
    return item(
        run,
        source,
        previous.sourceSha256(),
        remote.eTag(),
        "CANONICAL_VERIFIED",
        true,
        snapshots.now(),
        null);
  }

  private void assertSourceUnchanged(Source source, byte[] bytes) {
    Snapshot current = snapshots.read(source.id(), source.length());
    if (current == null
        || !source.equals(current.source())
        || !Arrays.equals(bytes, current.content())) throw new FenceInvariantViolation();
  }

  private void requireSourceBoundary(Run run) {
    if (snapshots.maxSourceId() != run.sourceHighWaterId()) throw new FenceInvariantViolation();
  }

  private static Retention retention(Source source) {
    if (source.expireSeconds() != null) throw new FenceInvariantViolation();
    return Retention.PERMANENT;
  }

  private static Source source(Evidence item) {
    return new Source(
        item.sourceId(), item.name(), item.length(), item.createdDate(), item.expireSeconds());
  }

  private static Source source(Item item) {
    return new Source(
        item.sourceId(),
        item.sourceName(),
        item.sourceLength(),
        item.sourceCreatedDate(),
        item.sourceExpireSeconds());
  }

  private Item item(
      Run run,
      Source source,
      String digest,
      String etag,
      String disposition,
      boolean reconciled,
      Instant verifiedAt,
      String error) {
    return new Item(
        run.id(),
        source.id(),
        source.name(),
        source.length(),
        source.createdDate(),
        source.expireSeconds(),
        digest,
        etag,
        disposition,
        reconciled,
        verifiedAt,
        error);
  }

  private boolean withinBudget(BlobMigrationStore.Run run, long start, int rows) {
    return rows < run.maxRows()
        && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(run.maxSeconds());
  }

  private void requireEnabled() {
    if (!properties.isPromotionEnabled())
      throw new IllegalStateException("Canonical migration promotion is disabled");
  }

  private int leaseSeconds() {
    return Math.max(120, properties.getLeaseSeconds());
  }

  private AzureBlobStorage azure(String destinationRoot) {
    AzureBlobStorage azure = azureProvider.getIfAvailable();
    if (azure == null
        || !destinationRoot.equals(
            azure.getTargetDescription(BlobMigrationService.SNAPSHOT_PREFIX)))
      throw new IllegalStateException(
          "Promotion requires the pinned Azure account/container/prefix");
    return azure;
  }

  private class Guard {
    private final Run run;
    private final String token;
    private Lease lease;

    Guard(Run run, String token) {
      this.run = run;
      this.token = token;
    }

    void refresh() {
      requireEnabled();
      promotions.renew(run.id(), token, leaseSeconds());
      lease =
          fence.verify(
              run.snapshotRunId(), run.fenceId(), run.destinationRoot(), run.manifestSha256());
      lease.requireRemaining(DATABASE_BUDGET);
    }

    void before(Duration budget) {
      requireEnabled();
      azure(run.destinationRoot());
      promotions.renew(run.id(), token, leaseSeconds());
      try {
        lease.requireRemaining(budget);
      } catch (IllegalStateException expired) {
        refresh();
        lease.requireRemaining(budget);
      }
    }
  }

  static class FenceInvariantViolation extends IllegalStateException {
    FenceInvariantViolation() {
      super("Source data changed while maintenance fence was held");
    }
  }
}
