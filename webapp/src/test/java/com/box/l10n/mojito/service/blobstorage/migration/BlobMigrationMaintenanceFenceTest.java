package com.box.l10n.mojito.service.blobstorage.migration;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BlobMigrationMaintenanceFenceTest {
  private static final String RUN = "snapshot-run";
  private static final String FENCE = "operator-fence";
  private static final String ROOT = "https://example.blob.core.windows.net/container/root/";
  private static final String HASH = "ab".repeat(32);
  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<Instant> wall = new AtomicReference<>(NOW);
  private final AtomicLong nanos = new AtomicLong(1000);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return wall.get();
        }
      };

  @Test
  public void acceptsBoundedLeaseAndPassesExactArgumentsWithoutShell() throws Exception {
    AtomicReference<List<String>> command = new AtomicReference<>();
    BlobMigrationMaintenanceFence fence =
        client(
            arguments -> {
              command.set(arguments);
              return json(valid());
            });
    var lease = fence.verify(RUN, FENCE, ROOT, null);
    assertEquals(HASH, lease.manifestSha256());
    assertEquals(NOW.plusSeconds(120), lease.validUntil());
    lease.requireRemaining(Duration.ofSeconds(119));
    assertEquals(
        List.of(
            "/trusted/verifier executable",
            "--run-id",
            RUN,
            "--fence-id",
            FENCE,
            "--destination-root",
            ROOT),
        command.get());
  }

  @Test
  public void renewalMustUseExactOriginalManifestHash() throws Exception {
    BlobMigrationMaintenanceFence fence = client(arguments -> json(valid()));
    assertEquals(HASH, fence.verify(RUN, FENCE, ROOT, HASH).manifestSha256());
    assertDenied(() -> fence.verify(RUN, FENCE, ROOT, "cd".repeat(32)));
  }

  @Test
  public void deniesWrongIdentityOrVersion() throws Exception {
    for (String field : List.of("runId", "fenceId", "destinationRoot")) {
      Map<String, Object> result = valid();
      result.put(field, "different");
      assertResponseDenied(json(result));
    }
    Map<String, Object> result = valid();
    result.put("version", 2);
    assertResponseDenied(json(result));
  }

  @Test
  public void requiresLowercaseSha256() throws Exception {
    for (String hash : List.of("", "ab".repeat(31), "AB".repeat(32), "z".repeat(64))) {
      Map<String, Object> result = valid();
      result.put("manifestSha256", hash);
      assertResponseDenied(json(result));
    }
  }

  @Test
  public void deniesMissingAndIncorrectlyTypedFields() throws Exception {
    for (String field : valid().keySet()) {
      Map<String, Object> missing = valid();
      missing.remove(field);
      assertResponseDenied(json(missing));
      Map<String, Object> nullField = valid();
      nullField.put(field, null);
      assertResponseDenied(json(nullField));
    }
    for (String field : List.of("version", "verifiedAtEpochSeconds", "validUntilEpochSeconds")) {
      Map<String, Object> textNumber = valid();
      textNumber.put(field, textNumber.get(field).toString());
      assertResponseDenied(json(textNumber));
      Map<String, Object> fractionalNumber = valid();
      fractionalNumber.put(field, 1.5);
      assertResponseDenied(json(fractionalNumber));
    }
  }

  @Test
  public void deniesMalformedDuplicateTrailingAndOversizedJson() throws Exception {
    String validJson = new String(json(valid()), StandardCharsets.UTF_8);
    for (String response :
        List.of(
            "",
            "{not JSON}",
            "null",
            "[]",
            validJson + " {}",
            validJson.substring(0, validJson.length() - 1) + ",\"version\":1}")) {
      assertResponseDenied(response.getBytes(StandardCharsets.UTF_8));
    }
    assertResponseDenied(new byte[BlobMigrationMaintenanceFence.MAX_STDOUT_BYTES + 1]);
  }

  @Test
  public void deniesExpiredFutureOrOverlongLease() throws Exception {
    for (long[] seconds :
        List.of(
            new long[] {-120, 0},
            new long[] {6, 120},
            new long[] {0, 301},
            new long[] {5, 4},
            new long[] {5, 5})) {
      Map<String, Object> result = valid();
      result.put("verifiedAtEpochSeconds", NOW.plusSeconds(seconds[0]).getEpochSecond());
      result.put("validUntilEpochSeconds", NOW.plusSeconds(seconds[1]).getEpochSecond());
      assertResponseDenied(json(result));
    }
  }

  @Test
  public void acceptsMaximumLeaseWindowAndAllowedFutureSkew() throws Exception {
    Map<String, Object> result = valid();
    result.put("verifiedAtEpochSeconds", NOW.plusSeconds(5).getEpochSecond());
    result.put("validUntilEpochSeconds", NOW.plusSeconds(305).getEpochSecond());
    var lease = client(arguments -> json(result)).verify(RUN, FENCE, ROOT, null);
    lease.requireRemaining(Duration.ofSeconds(300));
  }

  @Test
  public void insufficientRemainingTimeDeniesBeforeExpiration() throws Exception {
    var lease = client(arguments -> json(valid())).verify(RUN, FENCE, ROOT, null);
    wall.set(NOW.plusSeconds(110));
    nanos.addAndGet(Duration.ofSeconds(110).toNanos());
    assertThrows(IllegalStateException.class, () -> lease.requireRemaining(Duration.ofSeconds(11)));
    lease.requireRemaining(Duration.ofSeconds(9));
  }

  @Test
  public void wallClockAdvancingExpiresLeaseEvenIfMonotonicClockHasNotElapsed() throws Exception {
    var lease = client(arguments -> json(valid())).verify(RUN, FENCE, ROOT, null);
    wall.set(NOW.plusSeconds(120));
    assertThrows(IllegalStateException.class, () -> lease.requireRemaining(Duration.ZERO));
  }

  @Test
  public void wallClockRollbackCannotExtendMonotonicExpiry() throws Exception {
    var lease = client(arguments -> json(valid())).verify(RUN, FENCE, ROOT, null);
    wall.set(NOW.minusSeconds(3600));
    nanos.addAndGet(Duration.ofSeconds(120).toNanos());
    assertThrows(IllegalStateException.class, () -> lease.requireRemaining(Duration.ZERO));
  }

  @Test
  public void wallClockRollbackDuringVerificationCannotExtendLease() throws Exception {
    var lease =
        client(
                arguments -> {
                  wall.set(NOW.minusSeconds(5));
                  nanos.addAndGet(Duration.ofSeconds(10).toNanos());
                  return json(valid());
                })
            .verify(RUN, FENCE, ROOT, null);
    lease.requireRemaining(Duration.ofSeconds(109));
    assertThrows(
        IllegalStateException.class, () -> lease.requireRemaining(Duration.ofSeconds(111)));
  }

  @Test
  public void monotonicClockRegressionDeniesLease() throws Exception {
    var lease = client(arguments -> json(valid())).verify(RUN, FENCE, ROOT, null);
    nanos.decrementAndGet();
    assertThrows(IllegalStateException.class, () -> lease.requireRemaining(Duration.ZERO));
  }

  @Test
  public void rejectsRelativeOrMissingExecutableWithoutInvoking() {
    for (String executable : new String[] {null, "", "relative/verifier"}) {
      BlobMigrationMaintenanceFence fence =
          new BlobMigrationMaintenanceFence(
              () -> executable,
              mapper,
              clock,
              nanos::get,
              arguments -> {
                fail("Must not invoke an invalid executable");
                return null;
              });
      assertDenied(() -> fence.verify(RUN, FENCE, ROOT, null));
    }
  }

  @Test
  public void verifierFailuresNeverExposeMessagesOrCauses() {
    BlobMigrationMaintenanceFence fence =
        client(
            arguments -> {
              throw new IOException("secret manifest path and credential-bearing destination");
            });
    assertDenied(() -> fence.verify(RUN, FENCE, ROOT, null));
  }

  @Test
  public void interruptedInvocationPreservesInterruptStatus() {
    BlobMigrationMaintenanceFence fence =
        client(
            arguments -> {
              throw new InterruptedException("private details");
            });
    try {
      assertDenied(() -> fence.verify(RUN, FENCE, ROOT, null));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void processRunnerDiscardsStderrAndReadsSuccessfulStdout() throws Exception {
    Path script = script("printf '%s' 'result'\nprintf '%s' 'private diagnostic' >&2\n");
    assertArrayEquals(
        "result".getBytes(StandardCharsets.UTF_8),
        BlobMigrationMaintenanceFence.runVerifier(
            List.of(script.toString()), Duration.ofSeconds(2)));
  }

  @Test
  public void processRunnerRejectsNonzeroEvenWithValidJson() throws Exception {
    Path script =
        script("printf '%s' '" + new String(json(valid()), StandardCharsets.UTF_8) + "'\nexit 7\n");
    assertThrows(
        IOException.class,
        () ->
            BlobMigrationMaintenanceFence.runVerifier(
                List.of(script.toString()), Duration.ofSeconds(2)));
  }

  @Test
  public void processRunnerBoundsStdout() throws Exception {
    Path script = script("head -c 65537 /dev/zero\n");
    assertThrows(
        IOException.class,
        () ->
            BlobMigrationMaintenanceFence.runVerifier(
                List.of(script.toString()), Duration.ofSeconds(2)));
  }

  @Test
  public void processRunnerKillsTimedOutProcessAndReturnsPromptly() throws Exception {
    Path pidFile = temporary.newFile("pid").toPath();
    Path script = script("echo $$ > \"$1\"\nexec sleep 10\n");
    long started = System.nanoTime();
    assertThrows(
        IOException.class,
        () ->
            BlobMigrationMaintenanceFence.runVerifier(
                List.of(script.toString(), pidFile.toString()), Duration.ofMillis(200)));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    String pid = Files.readString(pidFile).strip();
    if (!pid.isEmpty()) {
      var process = ProcessHandle.of(Long.parseLong(pid));
      if (process.isPresent()) {
        process.get().onExit().get(2, TimeUnit.SECONDS);
        assertFalse(process.get().isAlive());
      }
    }
  }

  @Test
  public void retainedChildPipeCannotExtendTimeout() throws Exception {
    Path childPidFile = temporary.newFile("child-pid").toPath();
    Path script = script("sleep 10 &\necho $! > \"$1\"\nwait\n");
    long started = System.nanoTime();
    try {
      assertThrows(
          IOException.class,
          () ->
              BlobMigrationMaintenanceFence.runVerifier(
                  List.of(script.toString(), childPidFile.toString()), Duration.ofMillis(200)));
      assertTrue(
          Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    } finally {
      String pid = Files.readString(childPidFile).strip();
      if (!pid.isEmpty()) {
        ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
      }
    }
  }

  private BlobMigrationMaintenanceFence client(
      BlobMigrationMaintenanceFence.ProcessInvoker invoker) {
    return new BlobMigrationMaintenanceFence(
        () -> "/trusted/verifier executable", mapper, clock, nanos::get, invoker);
  }

  private Map<String, Object> valid() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", 1);
    result.put("runId", RUN);
    result.put("fenceId", FENCE);
    result.put("destinationRoot", ROOT);
    result.put("manifestSha256", HASH);
    result.put("verifiedAtEpochSeconds", NOW.getEpochSecond());
    result.put("validUntilEpochSeconds", NOW.plusSeconds(120).getEpochSecond());
    return result;
  }

  private byte[] json(Map<String, Object> value) throws IOException {
    return mapper.writeValueAsBytes(value);
  }

  private void assertResponseDenied(byte[] response) {
    assertDenied(() -> client(arguments -> response).verify(RUN, FENCE, ROOT, null));
  }

  private void assertDenied(Runnable invocation) {
    IllegalStateException exception = assertThrows(IllegalStateException.class, invocation::run);
    assertEquals("External maintenance fence verification failed", exception.getMessage());
    assertNull(exception.getCause());
  }

  private Path script(String body) throws IOException {
    Path script = temporary.newFile().toPath();
    Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
    assertTrue(script.toFile().setExecutable(true));
    return script;
  }
}
