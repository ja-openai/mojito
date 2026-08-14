package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

public class Mf2InflectionPerformanceSmokeTest {

  private static final String ENABLE_PROPERTY = "mojito.test.mf2InflectionPerfSmoke";
  private static final String ITERATIONS_PROPERTY = "mojito.test.mf2InflectionPerfIterations";
  private static final String WARMUP_ITERATIONS_PROPERTY =
      "mojito.test.mf2InflectionPerfWarmupIterations";
  private static final String MAX_RETAINED_HEAP_KB_PROPERTY =
      "mojito.test.mf2InflectionPerfMaxRetainedHeapKb";

  @Test
  public void optInRendererSmokeTracksThroughputAndRetainedHeap() throws Exception {
    Assume.assumeTrue(
        "MF2 inflection perf smoke is opt-in; enable with -D" + ENABLE_PROPERTY + "=true",
        Boolean.getBoolean(ENABLE_PROPERTY));

    int iterations = Integer.getInteger(ITERATIONS_PROPERTY, 20_000);
    int warmupIterations = Integer.getInteger(WARMUP_ITERATIONS_PROPERTY, 2_000);
    int maxRetainedHeapKb = Integer.getInteger(MAX_RETAINED_HEAP_KB_PROPERTY, 4_096);
    assertThat(iterations).isPositive();
    assertThat(warmupIterations).isNotNegative();
    assertThat(maxRetainedHeapKb).isPositive();

    Mf2TermRenderer renderer =
        Mf2TermRenderer.forCompiledTerms(
            new CompiledTermPackJsonLoader()
                .load(
                    readResource(
                        "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json")));
    TermRequirementJsonLoader.TermUsageCatalog usageCatalog =
        new TermRequirementJsonLoader.TermUsageCatalog(
            TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
            "es",
            Map.of(
                "definite-one",
                "Has eliminado {$item :term article=definite count=$count}.",
                "definite-other",
                "Has eliminado {$item :term article=definite count=$count}.",
                "indefinite-one",
                "Has encontrado {$item :term article=indefinite count=$count}."),
            Map.of(
                "definite-one",
                Map.of("item", List.of("item.water")),
                "definite-other",
                Map.of("item", List.of("item.bee")),
                "indefinite-one",
                Map.of("item", List.of("item.poppy"))));
    List<RenderCase> cases =
        List.of(
            new RenderCase("definite-one", Map.of("count", "1")),
            new RenderCase("definite-other", Map.of("count", "2")),
            new RenderCase("indefinite-one", Map.of("count", "1")));

    int checksum = renderLoop(renderer, usageCatalog, cases, warmupIterations);
    gcAndPause();
    long heapBeforeBytes = usedHeapBytes();
    long startedNanos = System.nanoTime();

    checksum += renderLoop(renderer, usageCatalog, cases, iterations);

    long elapsedNanos = System.nanoTime() - startedNanos;
    gcAndPause();
    long retainedHeapDeltaBytes = Math.max(0, usedHeapBytes() - heapBeforeBytes);
    long retainedHeapDeltaKb = retainedHeapDeltaBytes / 1024;
    double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
    double rendersPerSecond = elapsedSeconds == 0 ? 0 : iterations / elapsedSeconds;

    System.out.printf(
        "MF2 inflection renderer smoke iterations=%d warmup=%d rendersPerSecond=%.1f "
            + "elapsedMs=%d retainedHeapDeltaKb=%d checksum=%d%n",
        iterations,
        warmupIterations,
        rendersPerSecond,
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
        retainedHeapDeltaKb,
        checksum);

    assertThat(checksum).isPositive();
    assertThat(retainedHeapDeltaKb).isLessThanOrEqualTo(maxRetainedHeapKb);
  }

  private int renderLoop(
      Mf2TermRenderer renderer,
      TermRequirementJsonLoader.TermUsageCatalog usageCatalog,
      List<RenderCase> cases,
      int iterations) {
    int checksum = 0;
    for (int i = 0; i < iterations; i++) {
      RenderCase renderCase = cases.get(i % cases.size());
      checksum +=
          renderer
              .renderBoundMessage(usageCatalog, renderCase.messageId(), renderCase.variables())
              .length();
    }
    return checksum;
  }

  private void gcAndPause() throws InterruptedException {
    System.gc();
    Thread.sleep(50);
  }

  private long usedHeapBytes() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private String readResource(String resourceName) {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      if (stream == null) {
        throw new IllegalArgumentException("Missing test resource: " + resourceName);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record RenderCase(String messageId, Map<String, String> variables) {}
}
