package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projects portable catalogs into the real legacy extraction shape without calling Okapi. */
public final class LocalizationShadowComparator {

  private static final List<String> CATEGORIES =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final Pattern ARGUMENT = Pattern.compile("\\{([\\p{L}\\p{N}\\p{M}\\p{So}_]+)\\}");
  private static final Pattern ANDROID_PRODUCT = Pattern.compile("@product=[^@\\[]+");
  private static final Pattern ANDROID_FEATURE_FLAG = Pattern.compile("@flag=[^\\[]+");
  private static final Pattern ANDROID_ARRAY_INDEX = Pattern.compile("\\[(\\d+)]$");

  private LocalizationShadowComparator() {}

  /** Project native catalog messages into Mojito's existing translation-memory text-unit shape. */
  public static List<AssetExtractorTextUnit> projectTextUnits(LocalizationCatalog catalog) {
    return projectTextUnitsWithIds(catalog).stream().map(ProjectedTextUnit::textUnit).toList();
  }

  /** Keep canonical source-slot identity beside each legacy-compatible translation-memory unit. */
  public static List<ProjectedTextUnit> projectTextUnitsWithIds(LocalizationCatalog catalog) {
    List<ProjectedTextUnit> extracted = new ArrayList<>();
    for (Unit projected : project(catalog)) {
      AssetExtractorTextUnit unit = new AssetExtractorTextUnit();
      unit.setName(projected.name());
      unit.setSource(projected.source());
      unit.setComments(projected.comments());
      unit.setPluralForm(projected.pluralForm());
      unit.setPluralFormOther(projected.pluralFormOther());
      if (!projected.usages().isEmpty()) {
        unit.setUsages(Set.copyOf(projected.usages()));
      }
      extracted.add(new ProjectedTextUnit(projected.canonicalId(), unit));
    }
    return extracted;
  }

  public record ProjectedTextUnit(String canonicalId, AssetExtractorTextUnit textUnit) {}

  public static LocalizationShadowReport compare(
      LocalizationCatalog catalog, List<AssetExtractorTextUnit> extracted) {
    List<Unit> canonical = project(catalog);
    Map<String, List<Unit>> expected = group(canonical);
    List<Unit> legacy =
        extracted.stream()
            .map(
                unit ->
                    new Unit(
                        unit.getName(),
                        unit.getSource(),
                        unit.getComments(),
                        unit.getPluralForm(),
                        unit.getPluralFormOther(),
                        unit.getUsages() == null
                            ? List.of()
                            : unit.getUsages().stream().sorted().toList(),
                        null))
            .toList();
    Map<String, List<Unit>> observed = group(legacy);
    TreeSet<String> ids = new TreeSet<>();
    ids.addAll(expected.keySet());
    ids.addAll(observed.keySet());
    List<LocalizationShadowDifference> differences = new ArrayList<>();
    for (String id : ids) {
      List<Unit> current = expected.getOrDefault(id, List.of());
      List<Unit> previous = observed.getOrDefault(id, List.of());
      if (current.size() > 1) {
        differences.add(
            new LocalizationShadowDifference(
                "legacy_projection_collision",
                id,
                current.size(),
                current.stream().map(Unit::canonicalId).sorted().toList()));
      }
      if (previous.size() > 1) {
        differences.add(new LocalizationShadowDifference("duplicate_legacy", id, previous.size()));
      }
      if (current.isEmpty()) {
        differences.add(new LocalizationShadowDifference("unexpected_legacy", id, previous.size()));
      } else if (previous.isEmpty()) {
        differences.add(new LocalizationShadowDifference("missing_legacy", id, current.size()));
      } else if (current.size() == 1 && previous.size() == 1) {
        Unit present = current.get(0);
        Unit actual = previous.get(0);
        if (!Objects.equals(present.source(), actual.source())) {
          differences.add(new LocalizationShadowDifference("source_mismatch", id, null));
        }
        if (!Objects.equals(present.comments(), actual.comments())) {
          differences.add(new LocalizationShadowDifference("comment_mismatch", id, null));
        }
        if (!Objects.equals(present.pluralForm(), actual.pluralForm())
            || !Objects.equals(present.pluralFormOther(), actual.pluralFormOther())) {
          differences.add(new LocalizationShadowDifference("plural_mismatch", id, null));
        }
        if (!present.usages().equals(actual.usages())) {
          differences.add(new LocalizationShadowDifference("usage_mismatch", id, null));
        }
      }
    }
    differences.sort(
        Comparator.comparing(LocalizationShadowDifference::category)
            .thenComparing(LocalizationShadowDifference::id));
    return new LocalizationShadowReport(
        catalog.sourceFormat(),
        canonical.size(),
        legacy.size(),
        differences.isEmpty() ? "match" : "mismatch",
        List.copyOf(differences));
  }

  private static Map<String, List<Unit>> group(List<Unit> units) {
    Map<String, List<Unit>> grouped = new LinkedHashMap<>();
    for (Unit unit : units) {
      grouped.computeIfAbsent(unit.name(), ignored -> new ArrayList<>()).add(unit);
    }
    return grouped;
  }

  private static List<Unit> project(LocalizationCatalog catalog) {
    List<Unit> projected = new ArrayList<>();
    String format = catalog.sourceFormat();
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      String id = entry.getKey();
      LocalizationMessage message = entry.getValue();
      Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
      List<String> usages = usages(metadata);
      if (message.variants() == null) {
        if (LocalizationFileFormat.ANDROID.id().equals(format)) {
          id = ANDROID_PRODUCT.matcher(id).replaceFirst("");
          id = ANDROID_FEATURE_FLAG.matcher(id).replaceFirst("");
          id = ANDROID_ARRAY_INDEX.matcher(id).replaceFirst("_$1");
        }
        String source =
            LocalizationFileFormat.GETTEXT_PO.id().equals(format)
                ? Objects.toString(metadata.get("sourceMessage"), message.defaultMessage())
                : restore(message.defaultMessage(), message, format, metadata);
        if (LocalizationFileFormat.GETTEXT_PO.id().equals(format)) {
          id = gettextId(source, metadata);
        }
        projected.add(
            new Unit(id, source, message.description(), null, null, usages, entry.getKey()));
        continue;
      }

      String base;
      if (LocalizationFileFormat.GETTEXT_PO.id().equals(format)) {
        base = gettextId(Objects.toString(metadata.get("sourceMessage"), id), metadata) + " _";
      } else if (LocalizationFileFormat.APPLE_STRINGSDICT.id().equals(format)) {
        base = id + "_" + Objects.toString(metadata.get("pluralVariable"), "count") + "_";
      } else {
        int product = id.indexOf("@product=");
        int runtime = id.indexOf("@flag=");
        int suffix = product < 0 ? runtime : runtime < 0 ? product : Math.min(product, runtime);
        base = (suffix < 0 ? id : id.substring(0, suffix)) + "_";
      }
      String fallback = message.variants().get("other");
      for (String category : CATEGORIES) {
        String source;
        if (LocalizationFileFormat.GETTEXT_PO.id().equals(format)) {
          source =
              Objects.toString(
                  metadata.get("one".equals(category) ? "sourceMessage" : "sourcePlural"),
                  fallback);
        } else {
          source =
              restore(
                  message.variants().getOrDefault(category, fallback), message, format, metadata);
        }
        projected.add(
            new Unit(
                base + category,
                source,
                message.description(),
                category,
                base + "other",
                usages,
                entry.getKey() + "#" + category));
      }
    }
    return projected;
  }

  private static String gettextId(String source, Map<String, Object> metadata) {
    return metadata.get("context") instanceof String context ? source + " --- " + context : source;
  }

  private static List<String> usages(Map<String, Object> metadata) {
    if (!(metadata.get("references") instanceof List<?> references)) {
      return List.of();
    }
    return references.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .sorted()
        .toList();
  }

  private static String restore(
      String canonical, LocalizationMessage message, String format, Map<String, Object> metadata) {
    String source =
        ("icu-quoted-angle".equals(metadata.get("androidMarkupEscaping"))
                || "icu-quoted-angle".equals(metadata.get("appleMarkupEscaping")))
            ? canonical.replace("'<'", "<").replace("''", "'")
            : canonical;
    if (LocalizationFileFormat.ANDROID.id().equals(format)
        && !Boolean.FALSE.equals(metadata.get("formatted"))) {
      source = source.replace("%", "%%");
    }
    if (message.placeholders() == null) {
      return source;
    }
    Map<String, List<LocalizationPlaceholder>> placeholders = new LinkedHashMap<>();
    for (LocalizationPlaceholder placeholder : message.placeholders()) {
      placeholders
          .computeIfAbsent(placeholder.name(), ignored -> new ArrayList<>())
          .add(placeholder);
    }
    Map<String, Integer> positions = new LinkedHashMap<>();
    Matcher matcher = ARGUMENT.matcher(source);
    StringBuilder result = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> options = placeholders.get(matcher.group(1));
      if (options == null) {
        continue;
      }
      int occurrence = positions.getOrDefault(matcher.group(1), 0);
      positions.put(matcher.group(1), occurrence + 1);
      LocalizationPlaceholder placeholder = options.get(Math.min(occurrence, options.size() - 1));
      result.append(source, previous, matcher.start()).append(placeholder.source());
      previous = matcher.end();
    }
    return result.append(source, previous, source.length()).toString();
  }

  private record Unit(
      String name,
      String source,
      String comments,
      String pluralForm,
      String pluralFormOther,
      List<String> usages,
      String canonicalId) {}
}
