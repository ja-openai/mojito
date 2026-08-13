package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Original source text and ordered, byte-addressed translation slots. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalizationSourceSkeleton(
    int schemaVersion,
    String sourceFormat,
    String encoding,
    String source,
    String androidResourcePath,
    List<AndroidFeatureFlag> androidFeatureFlags,
    String appleTargetLocale,
    List<LocalizationSourceSlot> slots) {

  public LocalizationSourceSkeleton(
      int schemaVersion,
      String sourceFormat,
      String encoding,
      String source,
      List<LocalizationSourceSlot> slots) {
    this(schemaVersion, sourceFormat, encoding, source, null, null, null, slots);
  }

  public LocalizationSourceSkeleton(
      int schemaVersion,
      String sourceFormat,
      String encoding,
      String source,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<LocalizationSourceSlot> slots) {
    this(schemaVersion, sourceFormat, encoding, source, null, androidFeatureFlags, null, slots);
  }

  public LocalizationSourceSkeleton(
      int schemaVersion,
      String sourceFormat,
      String encoding,
      String source,
      String androidResourcePath,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<LocalizationSourceSlot> slots) {
    this(
        schemaVersion,
        sourceFormat,
        encoding,
        source,
        androidResourcePath,
        androidFeatureFlags,
        null,
        slots);
  }

  public LocalizationSourceSkeleton {
    androidFeatureFlags = androidFeatureFlags == null ? null : List.copyOf(androidFeatureFlags);
    slots = List.copyOf(slots);
  }

  /** One scalar/array/plural body or explicit-null Xcode locale in its encoded source file. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LocalizationSourceSlot(
      String id, String selector, String variant, int start, int end, Integer appleObjectIndex) {

    public LocalizationSourceSlot(String id, String selector, String variant, int start, int end) {
      this(id, selector, variant, start, end, null);
    }

    public LocalizationSourceSlot(String id, String variant, int start, int end) {
      this(id, null, variant, start, end, null);
    }

    public String translationKey() {
      if (selector != null) {
        return id + "#" + selector + "#" + variant;
      }
      return variant == null ? id : id + "#" + variant;
    }
  }
}
