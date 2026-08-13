package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Original ordered Android source files with translation slots owned only by overlay winners. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AndroidOverlaySourceSkeleton(
    int schemaVersion,
    String sourceFormat,
    List<AndroidOverlaySourceFile> sources,
    List<String> androidSelectedProducts,
    Map<String, String> androidRuntimeSlotOwners,
    String androidApplicationPackage,
    Map<String, AndroidMacroOwner> androidMacroOwners) {

  public AndroidOverlaySourceSkeleton(
      int schemaVersion, String sourceFormat, List<AndroidOverlaySourceFile> sources) {
    this(schemaVersion, sourceFormat, sources, null, null, null, null);
  }

  public AndroidOverlaySourceSkeleton(
      int schemaVersion,
      String sourceFormat,
      List<AndroidOverlaySourceFile> sources,
      List<String> androidSelectedProducts,
      Map<String, String> androidRuntimeSlotOwners) {
    this(
        schemaVersion,
        sourceFormat,
        sources,
        androidSelectedProducts,
        androidRuntimeSlotOwners,
        null,
        null);
  }

  public AndroidOverlaySourceSkeleton {
    sources = List.copyOf(sources);
    androidSelectedProducts =
        androidSelectedProducts == null ? null : List.copyOf(androidSelectedProducts);
    androidRuntimeSlotOwners =
        androidRuntimeSlotOwners == null ? null : Map.copyOf(androidRuntimeSlotOwners);
    androidMacroOwners = androidMacroOwners == null ? null : Map.copyOf(androidMacroOwners);
  }

  /** One original Gradle source-set file and its independently reversible source sidecar. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AndroidOverlaySourceFile(
      String sourceSet, String resourcePath, LocalizationSourceSkeleton skeleton) {}

  /** Actual winning declaration file for one AAPT2 build-only macro. */
  public record AndroidMacroOwner(String sourceSet, String resourcePath) {}
}
