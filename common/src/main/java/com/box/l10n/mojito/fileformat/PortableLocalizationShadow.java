package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional, observational migration comparison that can never replace a legacy extraction result.
 */
@Component
@ConditionalOnProperty(name = "l10n.file-formats.portable.shadow.enabled", havingValue = "true")
public final class PortableLocalizationShadow {

  public static final String COMPARISONS = "localization.file_formats.shadow.comparisons";
  public static final String DIFFERENCES = "localization.file_formats.shadow.differences";
  public static final String SKIPPED = "localization.file_formats.shadow.skipped";
  public static final String DURATION = "localization.file_formats.shadow.duration";

  private static final Logger logger = LoggerFactory.getLogger(PortableLocalizationShadow.class);

  private final MeterRegistry registry;
  private final double sampleRate;
  private final int maxBytes;
  private final Set<String> formats;

  public PortableLocalizationShadow(
      MeterRegistry registry,
      @Value("${l10n.file-formats.portable.shadow.sample-rate:1.0}") double sampleRate,
      @Value("${l10n.file-formats.portable.shadow.max-bytes:1048576}") int maxBytes,
      @Value(
              "${l10n.file-formats.portable.shadow.formats:android,apple_strings,apple_stringsdict,gettext_po,java_properties,formatjs_json}")
          String formats) {
    this.registry = registry;
    this.sampleRate = Double.isNaN(sampleRate) ? 0 : Math.max(0, Math.min(1, sampleRate));
    this.maxBytes = Math.max(0, maxBytes);
    this.formats =
        Arrays.stream(formats.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  public void observe(
      String assetPath,
      String source,
      FilterConfigIdOverride override,
      List<String> options,
      List<AssetExtractorTextUnit> legacy) {
    LocalizationFileFormat format = format(assetPath);
    String name = format == null ? "unsupported" : format.id();
    if (format == null || !formats.contains(name)) {
      skip(name, "unsupported_format");
      return;
    }
    if (override != null) {
      skip(name, "filter_override");
      return;
    }
    if (options != null && !options.isEmpty()) {
      skip(name, "filter_options");
      return;
    }
    byte[] input = LocalizationFileConverters.encodeStringTransport(format, source);
    if (input.length > maxBytes) {
      skip(name, "size_limit");
      return;
    }
    if (Math.floorMod(assetPath.hashCode(), 10_000) >= sampleRate * 10_000) {
      skip(name, "sampled_out");
      return;
    }

    Timer.Sample started = Timer.start(registry);
    String outcome = "error";
    try {
      String resourcePath =
          format == LocalizationFileFormat.ANDROID
                  && (assetPath.startsWith("res/values") || assetPath.contains("/res/values"))
              ? assetPath
              : null;
      LocalizationCatalog catalog =
          LocalizationFileConverters.parse(format, input, StandardCharsets.UTF_8, resourcePath);
      LocalizationShadowReport report = LocalizationShadowComparator.compare(catalog, legacy);
      outcome = report.outcome();
      for (LocalizationShadowDifference difference : report.differences()) {
        registry
            .counter(DIFFERENCES, Tags.of("format", name, "category", difference.category()))
            .increment(difference.count() == null ? 1 : difference.count());
      }
    } catch (RuntimeException failure) {
      logger.debug("Portable localization shadow failed for format {}", name, failure);
    } finally {
      Tags tags = Tags.of("format", name, "outcome", outcome);
      registry.counter(COMPARISONS, tags).increment();
      started.stop(registry.timer(DURATION, tags));
    }
  }

  private void skip(String format, String reason) {
    registry.counter(SKIPPED, Tags.of("format", format, "reason", reason)).increment();
  }

  private static LocalizationFileFormat format(String assetPath) {
    String path = assetPath.toLowerCase(Locale.ROOT);
    if (path.endsWith(".xml")) {
      return LocalizationFileFormat.ANDROID;
    }
    if (path.endsWith(".stringsdict")) {
      return LocalizationFileFormat.APPLE_STRINGSDICT;
    }
    if (path.endsWith(".strings")) {
      return LocalizationFileFormat.APPLE_STRINGS;
    }
    if (path.endsWith(".pot") || path.endsWith(".po")) {
      return LocalizationFileFormat.GETTEXT_PO;
    }
    if (path.endsWith(".properties")) {
      return LocalizationFileFormat.JAVA_PROPERTIES;
    }
    if (path.endsWith(".json")) {
      return LocalizationFileFormat.FORMATJS_JSON;
    }
    return null;
  }
}
