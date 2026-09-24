package com.box.l10n.mojito.service.blobstorage.migration;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Candidate;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Evidence;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Run;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Snapshot;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Source;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Copies source snapshots into a non-serving, immutable Azure namespace. It deliberately cannot
 * promote, delete, or assert cutover readiness: canonical promotion requires a separately enforced
 * writer fence, including all application writers, cleanup, and lazy fallback backfills.
 */
@Service
public class BlobMigrationService {
  static final String SNAPSHOT_PREFIX = "mblob_migration/v1/";
  private final BlobMigrationStore store;
  private final BlobMigrationProperties properties;
  private final ObjectProvider<AzureBlobStorage> azureProvider;
  private final QuartzSchedulerManager schedulers;

  public BlobMigrationService(
      BlobMigrationStore store,
      BlobMigrationProperties properties,
      ObjectProvider<AzureBlobStorage> azureProvider,
      QuartzSchedulerManager schedulers) {
    this.store = store;
    this.properties = properties;
    this.azureProvider = azureProvider;
    this.schedulers = schedulers;
  }

  public record CreateRequest(
      List<String> copyPrefixes, int maxRows, long maxBytes, int maxSeconds, int maxRetries) {}

  public Run create(CreateRequest request) {
    requireEnabled();
    if (request == null || request.copyPrefixes() == null || request.copyPrefixes().isEmpty())
      throw new IllegalArgumentException("Choose explicit semantic prefixes to copy");
    if (request.maxRows() < 1
        || request.maxRows() > 1000
        || request.maxBytes() < 1
        || request.maxBytes() > 256L * 1024 * 1024
        || request.maxSeconds() < 1
        || request.maxSeconds() > 300
        || request.maxRetries() < 1
        || request.maxRetries() > 10)
      throw new IllegalArgumentException(
          "Invalid migration bounds: rows 1-1000, bytes 1-268435456, seconds 1-300, attempts 1-10");
    Set<String> prefixes = new TreeSet<>();
    for (String input : request.copyPrefixes()) {
      if (input == null || !input.matches("[a-z][a-z0-9_]*"))
        throw new IllegalArgumentException("Use a lower-case semantic prefix without a slash");
      try {
        StructuredBlobStorage.Prefix.valueOf(input.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Unknown semantic prefix: " + input);
      }
      prefixes.add(input);
    }
    String destination = azure().getTargetDescription(SNAPSHOT_PREFIX);
    if (destination == null || destination.isBlank())
      throw new IllegalStateException("Missing migration destination");
    return store.create(
        String.join(",", prefixes),
        destination,
        request.maxRows(),
        request.maxBytes(),
        request.maxSeconds(),
        request.maxRetries());
  }

  public Run resume(String id) {
    requireEnabled();
    azure();
    Run run = store.request(id, true);
    try {
      Scheduler scheduler = schedulers.getScheduler(QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME);
      JobKey key = JobKey.jobKey(BlobMigrationJobConfiguration.JOB_NAME);
      if (!scheduler.checkExists(key)) {
        scheduler.addJob(
            JobBuilder.newJob(BlobMigrationJob.class)
                .withIdentity(key)
                .storeDurably()
                .requestRecovery()
                .build(),
            true);
      }
      JobDataMap data = new JobDataMap();
      data.put(BlobMigrationJob.MANUAL_RUN_ID, id);
      scheduler.triggerJob(key, data);
    } catch (SchedulerException e) {
      store.request(id, false);
      throw new IllegalStateException("Could not schedule migration batch", e);
    }
    return run;
  }

  public Run pause(String id) {
    return store.request(id, false);
  }

  public Run get(String id) {
    return store.get(id);
  }

  public List<Run> list() {
    return store.list();
  }

  public List<Evidence> evidence(String id, long afterId, int limit) {
    if (afterId < 0 || limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Invalid evidence page");
    return store.evidence(id, afterId, limit);
  }

  /** One bounded pass per requested run. Recurring execution is separately opt-in. */
  public void runRequested() {
    if (!properties.isEnabled()) return;
    for (String id : store.ready()) runBatch(id);
  }

  public void runBatch(String id) {
    requireEnabled();
    AzureBlobStorage azure = azure();
    Run scope = store.get(id);
    if (!scope.destinationRoot().equals(azure.getTargetDescription(SNAPSHOT_PREFIX)))
      throw new IllegalStateException(
          "Migration destination changed; restore the pinned account/container/prefix");
    String token = store.claim(id, properties.getLeaseSeconds());
    if (token == null) return;
    long start = nanoTime();
    scope = store.get(id);
    long bytes = 0;
    int rows = 0;
    try {
      // New source IDs make progress even when the oldest row fails. Each retry is counted against
      // the same row/byte/time budgets; exhausted failures remain visible, never silently skipped.
      List<Candidate> candidates = store.scanCandidates(scope, scope.maxRows());
      for (Candidate candidate : candidates) {
        if (!withinBudget(scope, start, rows)) break;
        store.renew(id, token, properties.getLeaseSeconds());
        requireEnabled();
        Attempt attempt = attempt(scope, candidate, null, 1, scope.maxBytes() - bytes, azure);
        if (attempt == null) break;
        Evidence result = attempt.evidence();
        bytes += attempt.bytes();
        rows++;
        store.record(scope, token, result, true);
        if ("VERIFICATION_FAILED".equals(result.error())) {
          store.release(id, token, "PAUSED", result.error());
          return;
        }
      }
      Run progressed = store.get(id);
      // Retry on a later invocation, not immediately after a new failure in this batch.
      if (candidates.isEmpty()) {
        for (Evidence failed : store.retryable(progressed, scope.maxRows())) {
          if (!withinBudget(scope, start, rows)) break;
          Candidate candidate =
              new Candidate(
                  failed.sourceId(), failed.name(), failed.createdDate(), failed.expireSeconds());
          // A measurement failure has no known length. Never interpret that as NULL content.
          Source expected =
              failed.length() == null
                  ? null
                  : new Source(
                      failed.sourceId(),
                      failed.name(),
                      failed.length(),
                      failed.createdDate(),
                      failed.expireSeconds());
          store.renew(id, token, properties.getLeaseSeconds());
          requireEnabled();
          Attempt attempt =
              attempt(
                  scope,
                  candidate,
                  expected,
                  failed.attempts() + 1,
                  scope.maxBytes() - bytes,
                  azure);
          if (attempt == null) break;
          Evidence result = attempt.evidence();
          bytes += attempt.bytes();
          rows++;
          store.record(scope, token, result, false);
          if ("VERIFICATION_FAILED".equals(result.error())) {
            store.release(id, token, "PAUSED", result.error());
            return;
          }
        }
      }
      progressed = store.get(id);
      // The fetched page already establishes exhaustion when it is short and fully processed.
      // A full page needs a later scan; stopping before its last candidate never proves completion.
      boolean scannedAll =
          candidates.isEmpty()
              || (candidates.size() < scope.maxRows()
                  && progressed.cursorId() >= candidates.getLast().id());
      boolean retry = !store.retryable(progressed, 1).isEmpty();
      String status =
          !scannedAll || retry
              ? "READY"
              : progressed.failed() > 0 ? "SNAPSHOT_WITH_FAILURES" : "SNAPSHOT_COMPLETE";
      store.release(id, token, status, null);
    } catch (BlobMigrationStore.LeaseLost lost) {
      // An in-flight immutable upload may finish, but a stale worker cannot publish evidence.
    } catch (RuntimeException failure) {
      try {
        store.release(id, token, "PAUSED", failure.getClass().getSimpleName());
      } catch (BlobMigrationStore.LeaseLost lost) {
        /* already fenced */
      }
      throw failure;
    }
  }

  private boolean withinBudget(Run run, long started, int rows) {
    return rows < run.maxRows()
        && nanoTime() - started < TimeUnit.SECONDS.toNanos(run.maxSeconds());
  }

  long nanoTime() {
    return System.nanoTime();
  }

  private record Attempt(Evidence evidence, long bytes) {}

  /** Returns null only when the next payload must wait for a fresh batch byte budget. */
  private Attempt attempt(
      Run scope,
      Candidate candidate,
      Source expected,
      int attempts,
      long remainingBytes,
      AzureBlobStorage azure) {
    if (!needsCopy(scope, candidate.name())) {
      return new Attempt(
          evidence(scope, candidate, null, preserveReason(candidate.name()), attempts, null), 0);
    }
    Source measured;
    try {
      // Payload length can require LOB I/O. Measure one selected row inside its attempt, not the
      // page.
      measured = store.metadata(candidate.id());
    } catch (RuntimeException failure) {
      return new Attempt(
          evidence(
              scope,
              candidate,
              expected == null ? null : expected.length(),
              "FAILED",
              attempts,
              "SOURCE_METADATA_" + failure.getClass().getSimpleName()),
          0);
    }
    if (measured == null
        || !candidate.equals(measured.candidate())
        || (expected != null && !sameMetadata(expected, measured))) {
      return new Attempt(
          evidence(
              scope,
              candidate,
              expected == null ? null : expected.length(),
              "SOURCE_CHANGED",
              attempts,
              null),
          0);
    }
    if (measured.length() != null
        && measured.length() <= scope.maxBytes()
        && measured.length() > remainingBytes) return null;
    return new Attempt(copy(scope, measured, attempts, azure), attemptedBytes(scope, measured));
  }

  // An unavailable evidence length is not a claim of NULL content; only PRESERVED_NULL makes that
  // claim.
  private static Evidence evidence(
      Run scope, Candidate candidate, Long length, String disposition, int attempts, String error) {
    return new Evidence(
        scope.id(),
        candidate.id(),
        candidate.name(),
        length,
        candidate.createdDate(),
        candidate.expireSeconds(),
        null,
        null,
        disposition,
        attempts,
        error,
        null);
  }

  private Evidence copy(Run scope, Source initial, int attempts, AzureBlobStorage azure) {
    if (initial.length() == null)
      return evidence(scope, initial, null, null, "PRESERVED_NULL", attempts, null, null);
    if (initial.length() > scope.maxBytes())
      return evidence(scope, initial, null, null, "PRESERVED_OVERSIZE", attempts, null, null);
    Source source = initial;
    String digest = null;
    String target = null;
    try {
      Snapshot snapshot = store.read(source.id(), source.length());
      if (snapshot == null || !sameMetadata(source, snapshot.source())) {
        return evidence(scope, source, null, null, "SOURCE_CHANGED", attempts, null, null);
      }
      source = snapshot.source();
      digest = sha256(snapshot.content());
      target = SNAPSHOT_PREFIX + scope.id() + "/" + source.id() + "/" + digest;
      // Backups are deliberately permanent. Original creation/TTL metadata is retained in the
      // manifest; canonical promotion must restore the proper retention contract under a fence.
      azure.createSnapshotIfAbsent(target, snapshot.content());
      byte[] remote = azure.getSnapshotBytes(target, snapshot.content().length);
      if (!Arrays.equals(snapshot.content(), remote)) throw new VerificationFailed();
      Snapshot current = store.read(source.id(), source.length());
      boolean unchanged =
          current != null
              && sameMetadata(source, current.source())
              && MessageDigest.isEqual(snapshot.content(), current.content());
      return evidence(
          scope,
          source,
          digest,
          target,
          unchanged ? "VERIFIED_SNAPSHOT" : "SOURCE_CHANGED",
          attempts,
          null,
          store.now());
    } catch (VerificationFailed | AzureBlobStorage.SnapshotVerificationException failure) {
      return evidence(
          scope, source, digest, target, "FAILED", attempts, "VERIFICATION_FAILED", null);
    } catch (RuntimeException failure) {
      // Exception messages can contain provider URLs or payload fragments. Persist only the type.
      return evidence(
          scope,
          source,
          digest,
          target,
          "FAILED",
          attempts,
          failure.getClass().getSimpleName(),
          null);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static boolean sameMetadata(Source one, Source two) {
    return one.equals(two);
  }

  static String prefix(String name) {
    if (name == null || !name.contains("/")) return "";
    return name.substring(0, name.indexOf('/'));
  }

  static boolean needsCopy(Run scope, String name) {
    return Arrays.asList(scope.allowedPrefixes().split(",")).contains(prefix(name));
  }

  static String preserveReason(String name) {
    String prefix = prefix(name);
    if ("ai_review_execution".equals(prefix) || "ai_review_submission_rate".equals(prefix))
      return "PRESERVED_CONTROL";
    try {
      StructuredBlobStorage.Prefix.valueOf(prefix.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return "PRESERVED_UNKNOWN";
    }
    return "PRESERVED_NOT_SELECTED";
  }

  static long attemptedBytes(Run scope, Source source) {
    return needsCopy(scope, source.name())
            && source.length() != null
            && source.length() <= scope.maxBytes()
        ? source.length()
        : 0;
  }

  private static Evidence evidence(
      Run scope,
      Source source,
      String digest,
      String target,
      String disposition,
      int attempts,
      String error,
      java.time.Instant verifiedAt) {
    return new Evidence(
        scope.id(),
        source.id(),
        source.name(),
        source.length(),
        source.createdDate(),
        source.expireSeconds(),
        digest,
        target,
        disposition,
        attempts,
        error,
        verifiedAt);
  }

  private void requireEnabled() {
    if (!properties.isEnabled()) throw new IllegalStateException("Blob migration is disabled");
  }

  private AzureBlobStorage azure() {
    AzureBlobStorage storage = azureProvider.getIfAvailable();
    if (storage == null)
      throw new IllegalStateException("Azure blob storage is required for migration");
    return storage;
  }

  static class VerificationFailed extends IllegalStateException {}
}
