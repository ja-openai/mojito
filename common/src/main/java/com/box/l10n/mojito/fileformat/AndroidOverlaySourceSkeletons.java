package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assigns original byte ownership according to actual winning Android overlay declarations. */
final class AndroidOverlaySourceSkeletons {

  private AndroidOverlaySourceSkeletons() {}

  static AndroidOverlaySourceSkeleton extract(
      List<AndroidResourceInput> inputs,
      List<AndroidFeatureFlag> featureFlags,
      List<String> selectedProducts,
      String applicationPackage) {
    List<AndroidFeatureFlag> flags = featureFlags == null ? List.of() : featureFlags;
    LocalizationCatalog originalWinners =
        LocalizationFileConverters.parseAndroidOverlayWithFeatureFlags(
            inputs, flags, null, applicationPackage);
    LocalizationCatalog runtimeWinners =
        selectedProducts == null
            ? originalWinners
            : LocalizationFileConverters.parseAndroidOverlayWithFeatureFlags(
                inputs, flags, selectedProducts, applicationPackage);
    List<AndroidOverlaySourceSkeleton.AndroidOverlaySourceFile> sources = new ArrayList<>();
    Set<String> sourceIdentities = new HashSet<>();
    Set<String> slotIdentities = new HashSet<>();
    Map<String, String> runtimeSlotOwners = new LinkedHashMap<>();
    Map<String, AndroidOverlaySourceSkeleton.AndroidMacroOwner> macroOwners =
        AndroidResourceOverlays.macroOwners(inputs);
    for (AndroidResourceInput input : inputs) {
      String sourceIdentity = input.sourceSet() + "\0" + input.resourcePath();
      if (!sourceIdentities.add(sourceIdentity)) {
        throw invalid("Duplicate Android overlay source identity: " + input.resourcePath());
      }
      LocalizationSourceSkeleton original =
          AndroidSourceSkeleton.extract(
              input.source(),
              input.resourcePath(),
              flags,
              ownedCatalog(originalWinners, input.sourceSet(), input.resourcePath()));
      List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots = new ArrayList<>();
      for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : original.slots()) {
        LocalizationMessage winner = originalWinners.messages().get(slot.id());
        if (winner == null
            || winner.metadata() == null
            || !input.sourceSet().equals(winner.metadata().get("androidOverlaySourceSet"))
            || !input.resourcePath().equals(winner.metadata().get("androidResourcePath"))
            || (slot.variant() != null
                && (winner.variants() == null || !winner.variants().containsKey(slot.variant())))) {
          continue;
        }
        String runtimeId = slot.id();
        if (selectedProducts != null) {
          Object sourceProduct = winner.metadata().get("androidProduct");
          if (sourceProduct instanceof String product && !"default".equals(product)) {
            runtimeId = runtimeId.replace("@product=" + product, "");
          }
          LocalizationMessage runtime = runtimeWinners.messages().get(runtimeId);
          if (runtime == null
              || runtime.metadata() == null
              || !input.sourceSet().equals(runtime.metadata().get("androidOverlaySourceSet"))
              || !input.resourcePath().equals(runtime.metadata().get("androidResourcePath"))
              || (slot.variant() != null
                  && (runtime.variants() == null
                      || !runtime.variants().containsKey(slot.variant())))
              || !selectedProductOwner(originalWinners, winner, runtimeId, selectedProducts)) {
            continue;
          }
        }
        if (!slotIdentities.add(slot.translationKey())) {
          throw invalid("Duplicate winning Android overlay slot: " + slot.translationKey());
        }
        if (selectedProducts != null) {
          String runtimeKey = slot.variant() == null ? runtimeId : runtimeId + "#" + slot.variant();
          if (runtimeSlotOwners.putIfAbsent(runtimeKey, slot.translationKey()) != null) {
            throw invalid("Duplicate selected Android runtime slot: " + runtimeKey);
          }
        }
        slots.add(slot);
      }
      LocalizationSourceSkeleton winning =
          new LocalizationSourceSkeleton(
              original.schemaVersion(),
              original.sourceFormat(),
              original.encoding(),
              original.source(),
              original.androidResourcePath(),
              original.androidFeatureFlags(),
              slots);
      sources.add(
          new AndroidOverlaySourceSkeleton.AndroidOverlaySourceFile(
              input.sourceSet(), input.resourcePath(), winning));
    }
    return new AndroidOverlaySourceSkeleton(
        1,
        "android",
        sources,
        selectedProducts,
        selectedProducts == null ? null : runtimeSlotOwners,
        applicationPackage,
        macroOwners.isEmpty() ? null : macroOwners);
  }

  static List<AndroidResourceInput> render(
      AndroidOverlaySourceSkeleton overlay, Map<String, String> translations) {
    if (overlay.schemaVersion() != 1 || !"android".equals(overlay.sourceFormat())) {
      throw invalid("Unsupported Android overlay source skeleton");
    }
    Set<String> sourceIdentities = new HashSet<>();
    Set<String> slotIdentities = new HashSet<>();
    for (AndroidOverlaySourceSkeleton.AndroidOverlaySourceFile source : overlay.sources()) {
      if (!sourceIdentities.add(source.sourceSet() + "\0" + source.resourcePath())
          || !source.resourcePath().equals(source.skeleton().androidResourcePath())) {
        throw invalid("Invalid Android overlay source identity: " + source.resourcePath());
      }
      for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : source.skeleton().slots()) {
        if (!slotIdentities.add(slot.translationKey())) {
          throw invalid("Duplicate winning Android overlay slot: " + slot.translationKey());
        }
      }
    }
    Map<String, String> runtimeSlotOwners = overlay.androidRuntimeSlotOwners();
    if ((overlay.androidSelectedProducts() == null) != (runtimeSlotOwners == null)) {
      throw invalid("Selected Android products require complete runtime slot ownership");
    }
    if (runtimeSlotOwners != null
        && (runtimeSlotOwners.size() != slotIdentities.size()
            || !new HashSet<>(runtimeSlotOwners.values()).equals(slotIdentities))) {
      throw invalid("Selected Android runtime slots must own every source exactly once");
    }
    for (String key : translations.keySet()) {
      if (!(runtimeSlotOwners == null
          ? slotIdentities.contains(key)
          : runtimeSlotOwners.containsKey(key))) {
        throw new LocalizationParseException(
            "UNKNOWN_OVERLAY_SKELETON_SLOT", "Unknown winning Android overlay slot: " + key);
      }
    }
    List<AndroidResourceInput> originalSources = new ArrayList<>();
    List<AndroidFeatureFlag> featureFlags = List.of();
    for (AndroidOverlaySourceSkeleton.AndroidOverlaySourceFile source : overlay.sources()) {
      if (source.skeleton().androidFeatureFlags() != null) {
        featureFlags = source.skeleton().androidFeatureFlags();
      }
      originalSources.add(
          new AndroidResourceInput(
              source.sourceSet(),
              source.resourcePath(),
              SourceSkeletonEncoding.named(source.skeleton().encoding())
                  .encode(source.skeleton().source())));
    }
    LocalizationCatalog originals =
        LocalizationFileConverters.parseAndroidOverlayWithFeatureFlags(
            originalSources, featureFlags, null, overlay.androidApplicationPackage());
    Map<String, AndroidOverlaySourceSkeleton.AndroidMacroOwner> macroOwners =
        AndroidResourceOverlays.macroOwners(originalSources);
    if (!macroOwners.equals(
        overlay.androidMacroOwners() == null ? Map.of() : overlay.androidMacroOwners())) {
      throw invalid("Android macro definitions do not match their original source ownership");
    }
    Map<String, String> sourceToRuntime = new LinkedHashMap<>();
    if (runtimeSlotOwners != null) {
      runtimeSlotOwners.forEach((runtime, source) -> sourceToRuntime.put(source, runtime));
    }
    List<AndroidResourceInput> rendered = new ArrayList<>();
    for (AndroidOverlaySourceSkeleton.AndroidOverlaySourceFile source : overlay.sources()) {
      Map<String, String> owned = new LinkedHashMap<>();
      for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : source.skeleton().slots()) {
        String key = slot.translationKey();
        String runtime = runtimeSlotOwners == null ? key : sourceToRuntime.get(key);
        if (translations.containsKey(runtime)) {
          owned.put(key, translations.get(runtime));
        }
      }
      rendered.add(
          new AndroidResourceInput(
              source.sourceSet(),
              source.resourcePath(),
              AndroidSourceSkeleton.render(
                  source.skeleton(),
                  owned,
                  ownedCatalog(originals, source.sourceSet(), source.resourcePath()))));
    }
    return List.copyOf(rendered);
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_ANDROID_OVERLAY_SKELETON", message);
  }

  private static LocalizationCatalog ownedCatalog(
      LocalizationCatalog original, String sourceSet, String resourcePath) {
    LocalizationCatalog result = new LocalizationCatalog(LocalizationFileFormat.ANDROID);
    result.setLocale(original.locale());
    original
        .messages()
        .forEach(
            (id, message) -> {
              if (message.metadata() != null
                  && sourceSet.equals(message.metadata().get("androidOverlaySourceSet"))
                  && resourcePath.equals(message.metadata().get("androidResourcePath"))) {
                result.add(id, message);
              }
            });
    return result;
  }

  private static boolean selectedProductOwner(
      LocalizationCatalog catalog,
      LocalizationMessage source,
      String runtimeId,
      List<String> selectedProducts) {
    if ("read_write".equals(source.metadata().get("androidFeatureFlagMode"))
        || "read_write".equals(source.metadata().get("androidPathFeatureFlagMode"))) {
      return true;
    }
    Set<String> requested = new HashSet<>(selectedProducts);
    if (requested.size() > 1) {
      requested.remove("default");
    }
    Object product = source.metadata().get("androidProduct");
    String selected = product instanceof String value ? value : "default";
    if (!"default".equals(selected)) {
      return requested.contains(selected);
    }
    for (Map.Entry<String, LocalizationMessage> alternative : catalog.messages().entrySet()) {
      Map<String, Object> metadata = alternative.getValue().metadata();
      if (metadata == null || !(metadata.get("androidProduct") instanceof String candidate)) {
        continue;
      }
      if ("default".equals(candidate)
          || !requested.contains(candidate)
          || !runtimeId.equals(alternative.getKey().replace("@product=" + candidate, ""))) {
        continue;
      }
      return false;
    }
    return true;
  }
}
