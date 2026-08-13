package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Test;

/** Opt-in observation must remain bounded, content-safe, and unable to change legacy extraction. */
public class PortableLocalizationShadowTest {

  @Test
  public void recordsEquivalentPlainAndroidExtraction() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PortableLocalizationShadow shadow =
        new PortableLocalizationShadow(registry, 1, 1024, "android");
    shadow.observe(
        "res/values/strings.xml",
        "<resources><string name=\"signal\">Steady</string></resources>",
        null,
        null,
        List.of(unit("signal", "Steady")));

    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.COMPARISONS)
            .tags("format", "android", "outcome", "match")
            .counter()
            .count(),
        0);
    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.DURATION)
            .tags("format", "android", "outcome", "match")
            .timer()
            .count());
  }

  @Test
  public void classifiesDifferencesWithoutMetricContentOrAssetPaths() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PortableLocalizationShadow shadow =
        new PortableLocalizationShadow(registry, 1, 1024, "android");
    shadow.observe(
        "res/values/private-product.xml",
        "<resources><string name=\"sensitive_signal\">Canonical secret</string></resources>",
        null,
        null,
        List.of(unit("sensitive_signal", "Legacy secret")));

    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.DIFFERENCES)
            .tags("format", "android", "category", "source_mismatch")
            .counter()
            .count(),
        0);
    for (Meter metric : registry.getMeters()) {
      assertFalse(metric.getId().getTags().toString().contains("sensitive_signal"));
      assertFalse(metric.getId().getTags().toString().contains("private-product"));
      assertFalse(metric.getId().getTags().toString().contains("secret"));
    }
  }

  @Test
  public void isolatesMalformedNativeInputAndReturnsNormally() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PortableLocalizationShadow shadow =
        new PortableLocalizationShadow(registry, 1, 1024, "android");
    shadow.observe("res/values/strings.xml", "<resources>", null, null, List.of());

    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.COMPARISONS)
            .tags("format", "android", "outcome", "error")
            .counter()
            .count(),
        0);
  }

  @Test
  public void skipsOverridesOptionsPayloadLimitsSamplingAndUnsupportedFormats() {
    assertSkipped(
        1,
        1024,
        "java_properties",
        "messages.properties",
        "signal=steady",
        FilterConfigIdOverride.PROPERTIES_JAVA,
        null,
        "java_properties",
        "filter_override");
    assertSkipped(
        1,
        1024,
        "android",
        "res/values/strings.xml",
        "<resources/>",
        null,
        List.of("oldEscaping=true"),
        "android",
        "filter_options");
    assertSkipped(
        1,
        2,
        "android",
        "res/values/strings.xml",
        "<resources/>",
        null,
        null,
        "android",
        "size_limit");
    assertSkipped(
        0,
        1024,
        "android",
        "res/values/strings.xml",
        "<resources/>",
        null,
        null,
        "android",
        "sampled_out");
    assertSkipped(
        Double.NaN,
        1024,
        "android",
        "res/values/strings.xml",
        "<resources/>",
        null,
        null,
        "android",
        "sampled_out");
    assertSkipped(
        1,
        1024,
        "android",
        "settings.yaml",
        "signal: steady",
        null,
        null,
        "unsupported",
        "unsupported_format");
  }

  private static void assertSkipped(
      double sampleRate,
      int maxBytes,
      String formats,
      String path,
      String source,
      FilterConfigIdOverride override,
      List<String> options,
      String format,
      String reason) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PortableLocalizationShadow shadow =
        new PortableLocalizationShadow(registry, sampleRate, maxBytes, formats);
    shadow.observe(path, source, override, options, List.of());
    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.SKIPPED)
            .tags("format", format, "reason", reason)
            .counter()
            .count(),
        0);
  }

  private static AssetExtractorTextUnit unit(String name, String source) {
    AssetExtractorTextUnit unit = new AssetExtractorTextUnit();
    unit.setName(name);
    unit.setSource(source);
    return unit;
  }
}
