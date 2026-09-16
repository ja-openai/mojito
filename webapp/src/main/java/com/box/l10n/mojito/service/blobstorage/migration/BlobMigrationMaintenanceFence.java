package com.box.l10n.mojito.service.blobstorage.migration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates short-lived evidence from the external maintenance verifier. This client does not fence
 * Azure or database writers: the verifier must check the independently enforced maintenance
 * controls.
 */
@Component
public class BlobMigrationMaintenanceFence {
  static final int MAX_STDOUT_BYTES = 64 * 1024;
  static final Duration VERIFIER_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration MAX_LEASE_WINDOW = Duration.ofSeconds(300);
  private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(5);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private final Supplier<String> verifierExecutable;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final ProcessInvoker processInvoker;

  @Autowired
  public BlobMigrationMaintenanceFence(BlobMigrationProperties properties, ObjectMapper mapper) {
    this(
        properties::getFenceVerifierExecutable,
        mapper,
        Clock.systemUTC(),
        System::nanoTime,
        command -> runVerifier(command, VERIFIER_TIMEOUT));
  }

  BlobMigrationMaintenanceFence(
      Supplier<String> verifierExecutable,
      ObjectMapper mapper,
      Clock clock,
      LongSupplier nanoTime,
      ProcessInvoker processInvoker) {
    this.verifierExecutable = verifierExecutable;
    this.mapper = mapper;
    this.clock = clock;
    this.nanoTime = nanoTime;
    this.processInvoker = processInvoker;
  }

  /** The caller caches this lease and renews at checkpoints, not once per object. */
  public Lease verify(
      String snapshotRunId, String fenceId, String destinationRoot, String expectedManifestSha256) {
    try {
      String executable = verifierExecutable.get();
      if (executable == null
          || !Path.of(executable).isAbsolute()
          || snapshotRunId == null
          || snapshotRunId.isBlank()
          || fenceId == null
          || fenceId.isBlank()
          || destinationRoot == null
          || destinationRoot.isBlank()) {
        throw denied();
      }
      Instant startedAt = clock.instant();
      long startedNanos = nanoTime.getAsLong();
      byte[] stdout =
          processInvoker.invoke(
              List.of(
                  executable,
                  "--run-id",
                  snapshotRunId,
                  "--fence-id",
                  fenceId,
                  "--destination-root",
                  destinationRoot));
      if (stdout == null || stdout.length > MAX_STDOUT_BYTES) {
        throw denied();
      }
      JsonNode result =
          mapper
              .readerFor(JsonNode.class)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(stdout);
      if (result == null
          || !result.isObject()
          || integer(result, "version") != 1
          || !snapshotRunId.equals(text(result, "runId"))
          || !fenceId.equals(text(result, "fenceId"))
          || !destinationRoot.equals(text(result, "destinationRoot"))) {
        throw denied();
      }
      String manifestSha256 = text(result, "manifestSha256");
      if (!SHA256.matcher(manifestSha256).matches()
          || (expectedManifestSha256 != null && !expectedManifestSha256.equals(manifestSha256))) {
        throw denied();
      }
      Instant verifiedAt = Instant.ofEpochSecond(integer(result, "verifiedAtEpochSeconds"));
      Instant validUntil = Instant.ofEpochSecond(integer(result, "validUntilEpochSeconds"));
      Instant now = clock.instant();
      long nowNanos = nanoTime.getAsLong();
      long elapsedNanos = nowNanos - startedNanos;
      Duration leaseWindow = Duration.between(verifiedAt, validUntil);
      if (verifiedAt.isAfter(now.plus(MAX_FUTURE_SKEW))
          || !validUntil.isAfter(now)
          || leaseWindow.isNegative()
          || leaseWindow.isZero()
          || leaseWindow.compareTo(MAX_LEASE_WINDOW) > 0
          || elapsedNanos < 0) {
        throw denied();
      }
      // Account for elapsed time even if the wall clock moved backwards during verification.
      Duration wallRemaining = Duration.between(now, validUntil);
      Duration initialRemaining = Duration.between(startedAt, validUntil).minusNanos(elapsedNanos);
      Duration remaining =
          wallRemaining.compareTo(initialRemaining) < 0 ? wallRemaining : initialRemaining;
      if (remaining.isNegative() || remaining.isZero()) {
        throw denied();
      }
      Lease lease =
          new Lease(manifestSha256, validUntil, clock, nanoTime, nowNanos, remaining.toNanos());
      lease.requireRemaining(Duration.ZERO);
      return lease;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw denied();
    } catch (Exception e) {
      // Never retain subprocess output, paths, provider URLs, or exception messages as a cause.
      throw denied();
    }
  }

  private static String text(JsonNode result, String field) {
    JsonNode value = result.get(field);
    if (value == null || !value.isTextual()) {
      throw denied();
    }
    return value.textValue();
  }

  private static long integer(JsonNode result, String field) {
    JsonNode value = result.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw denied();
    }
    return value.longValue();
  }

  @FunctionalInterface
  interface ProcessInvoker {
    byte[] invoke(List<String> command) throws IOException, InterruptedException;
  }

  /** Starts the configured executable directly, inheriting MOJITO_STORAGE_FENCE_MANIFEST. */
  static byte[] runVerifier(List<String> command, Duration timeout)
      throws IOException, InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    Process process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    FutureTask<byte[]> stdout =
        new FutureTask<>(
            () -> {
              byte[] bytes = process.getInputStream().readNBytes(MAX_STDOUT_BYTES + 1);
              if (bytes.length > MAX_STDOUT_BYTES) {
                stopProcess(process);
                throw new IOException("Maintenance fence verifier output limit exceeded");
              }
              return bytes;
            });
    Thread.ofVirtual().name("blob-maintenance-fence-stdout").start(stdout);
    try {
      if (!process.waitFor(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
          || process.exitValue() != 0) {
        throw new IOException("Maintenance fence verifier did not succeed");
      }
      return stdout.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException("Maintenance fence verifier did not succeed");
    } finally {
      stopProcess(process);
      stdout.cancel(true);
      // A child may retain a pipe after its parent exits. Never let pipe cleanup extend the
      // deadline.
      Thread.ofVirtual()
          .name("blob-maintenance-fence-cleanup")
          .start(
              () -> {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                try {
                  process.getOutputStream().close();
                } catch (IOException ignored) {
                  // No input is sent to the verifier.
                }
              });
    }
  }

  private static void stopProcess(Process process) {
    if (process.isAlive()) {
      try {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
      } catch (RuntimeException ignored) {
        // Restricted runtimes can prohibit process enumeration; still stop the verifier itself.
      } finally {
        // Unlike Process.destroyForcibly(), the handle does not synchronously close retained pipes.
        process.toHandle().destroyForcibly();
      }
    }
  }

  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // Output is never logged, including during failed verification or timeout cleanup.
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("External maintenance fence verification failed");
  }

  public static class Lease {
    private final String manifestSha256;
    private final Instant validUntil;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final long verifiedNanos;
    private final long remainingNanos;

    private Lease(
        String manifestSha256,
        Instant validUntil,
        Clock clock,
        LongSupplier nanoTime,
        long verifiedNanos,
        long remainingNanos) {
      this.manifestSha256 = manifestSha256;
      this.validUntil = validUntil;
      this.clock = clock;
      this.nanoTime = nanoTime;
      this.verifiedNanos = verifiedNanos;
      this.remainingNanos = remainingNanos;
    }

    public String manifestSha256() {
      return manifestSha256;
    }

    public Instant validUntil() {
      return validUntil;
    }

    public void requireRemaining(Duration required) {
      if (required == null || required.isNegative()) {
        throw new IllegalArgumentException(
            "Required maintenance lease duration must be nonnegative");
      }
      Duration wallRemaining = Duration.between(clock.instant(), validUntil);
      long elapsedNanos = nanoTime.getAsLong() - verifiedNanos;
      if (elapsedNanos < 0
          || elapsedNanos >= remainingNanos
          || wallRemaining.isNegative()
          || wallRemaining.isZero()
          || wallRemaining.compareTo(required) < 0
          || Duration.ofNanos(remainingNanos - elapsedNanos).compareTo(required) < 0) {
        throw new IllegalStateException(
            "External maintenance fence lease has insufficient time remaining");
      }
    }
  }
}
