package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

public class Mf2InflectionPerformanceSmokeTest {

  private static final String ENABLED_PROPERTY = "mojito.test.mf2InflectionPerfSmoke";
  private static final String ITERATIONS_PROPERTY = "mojito.test.mf2InflectionPerfIterations";
  private static final String WARMUP_ITERATIONS_PROPERTY =
      "mojito.test.mf2InflectionPerfWarmupIterations";
  private static final String MAX_RETAINED_HEAP_KB_PROPERTY =
      "mojito.test.mf2InflectionPerfMaxRetainedHeapKb";
  private static final int DEFAULT_ITERATIONS = 20_000;
  private static final int DEFAULT_WARMUP_ITERATIONS = 2_000;
  private static final long DEFAULT_MAX_RETAINED_HEAP_KB = 16 * 1024;

  @Test
  public void rendersBoundMessagesWithoutExcessiveRetainedHeapGrowth() {
    assumeTrue(
        "Set -D" + ENABLED_PROPERTY + "=true to run the MF2 inflection performance smoke.",
        Boolean.getBoolean(ENABLED_PROPERTY));

    int iterations = positiveIntProperty(ITERATIONS_PROPERTY, DEFAULT_ITERATIONS);
    int warmupIterations =
        positiveIntProperty(WARMUP_ITERATIONS_PROPERTY, DEFAULT_WARMUP_ITERATIONS);
    long maxRetainedHeapKb =
        positiveLongProperty(MAX_RETAINED_HEAP_KB_PROPERTY, DEFAULT_MAX_RETAINED_HEAP_KB);
    Mf2TermRenderer renderer =
        Mf2TermRenderer.forCompiledTerms(
            compiledTermPack(
                "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json"));
    TermUsageCatalog usageCatalog =
        usageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.water"]
                }
              }
            }
            """);
    Map<String, String> singular = Map.of("count", "1");
    Map<String, String> plural = Map.of("count", "2");

    renderer.requireRenderableBoundMessage(usageCatalog, "inventory.deleted");
    renderLoop(renderer, usageCatalog, warmupIterations, singular, plural);
    long beforeHeapBytes = usedHeapAfterGc();
    long startedAtNanos = System.nanoTime();
    long checksum = renderLoop(renderer, usageCatalog, iterations, singular, plural);
    long elapsedNanos = System.nanoTime() - startedAtNanos;
    long retainedHeapDeltaKb = Math.max(0, (usedHeapAfterGc() - beforeHeapBytes) / 1024);
    double rendersPerSecond = iterations / Math.max(1.0e-9, elapsedNanos / 1.0e9);

    System.out.printf(
        Locale.ROOT,
        "MF2 inflection renderer smoke iterations=%d warmup=%d rendersPerSecond=%.1f"
            + " elapsedMs=%d retainedHeapDeltaKb=%d checksum=%d%n",
        iterations,
        warmupIterations,
        rendersPerSecond,
        elapsedNanos / 1_000_000,
        retainedHeapDeltaKb,
        checksum);
    assertThat(checksum).isGreaterThan(0);
    assertThat(retainedHeapDeltaKb).isLessThanOrEqualTo(maxRetainedHeapKb);
  }

  private static long renderLoop(
      Mf2TermRenderer renderer,
      TermUsageCatalog usageCatalog,
      int iterations,
      Map<String, String> singular,
      Map<String, String> plural) {
    long checksum = 0;
    for (int i = 0; i < iterations; i++) {
      String rendered =
          renderer.renderBoundMessage(
              usageCatalog, "inventory.deleted", (i & 1) == 0 ? singular : plural);
      checksum += rendered.length();
    }
    return checksum;
  }

  private static long usedHeapAfterGc() {
    Runtime runtime = Runtime.getRuntime();
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while measuring heap", e);
      }
    }
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static int positiveIntProperty(String name, int defaultValue) {
    long value = positiveLongProperty(name, defaultValue);
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " must be <= " + Integer.MAX_VALUE);
    }
    return (int) value;
  }

  private static long positiveLongProperty(String name, long defaultValue) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    long parsed = Long.parseLong(value);
    if (parsed <= 0) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
    return parsed;
  }

  private static CompiledTermPack compiledTermPack(String path) {
    return new CompiledTermPackJsonLoader().load(readResource(path));
  }

  private static TermUsageCatalog usageCatalog(String json) {
    return new TermRequirementJsonLoader().loadUsageCatalog(json);
  }

  private static String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
