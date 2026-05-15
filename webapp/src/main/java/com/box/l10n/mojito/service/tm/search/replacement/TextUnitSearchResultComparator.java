package com.box.l10n.mojito.service.tm.search.replacement;

import com.box.l10n.mojito.service.tm.search.TextUnitAndWordCount;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParametersForTesting;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TextUnitSearchResultComparator {

  private TextUnitSearchResultComparator() {}

  public static boolean shouldCompareOrder(TextUnitSearcherParameters parameters) {
    if (parameters.isOrderedByTextUnitID()) {
      return true;
    }
    if (parameters instanceof TextUnitSearcherParametersForTesting testingParameters) {
      return testingParameters.isOrdered();
    }
    return true;
  }

  public static Comparison compareSearch(
      List<TextUnitDTO> expected, List<TextUnitDTO> actual, boolean compareOrder) {
    List<ComparableTextUnitDTO> expectedRows =
        expected.stream().map(ComparableTextUnitDTO::from).toList();
    List<ComparableTextUnitDTO> actualRows =
        actual.stream().map(ComparableTextUnitDTO::from).toList();

    if (compareOrder) {
      if (expectedRows.equals(actualRows)) {
        return Comparison.match();
      }
      return orderedMismatch(expectedRows, actualRows);
    }

    Map<ComparableTextUnitDTO, Long> expectedCounts = counts(expectedRows);
    Map<ComparableTextUnitDTO, Long> actualCounts = counts(actualRows);
    if (expectedCounts.equals(actualCounts)) {
      return Comparison.match();
    }

    Map<ComparableTextUnitDTO, Long> missing = diff(expectedCounts, actualCounts);
    Map<ComparableTextUnitDTO, Long> extra = diff(actualCounts, expectedCounts);
    return Comparison.mismatch(
        "unordered search results differ: expected size="
            + expectedRows.size()
            + ", actual size="
            + actualRows.size()
            + ", missing="
            + sample(missing)
            + ", extra="
            + sample(extra));
  }

  public static Comparison compareCount(
      TextUnitAndWordCount expected, TextUnitAndWordCount actual) {
    if (expected.getTextUnitCount() == actual.getTextUnitCount()
        && expected.getTextUnitWordCount() == actual.getTextUnitWordCount()) {
      return Comparison.match();
    }

    return Comparison.mismatch(
        "count differs: expected textUnitCount="
            + expected.getTextUnitCount()
            + ", actual textUnitCount="
            + actual.getTextUnitCount()
            + ", expected textUnitWordCount="
            + expected.getTextUnitWordCount()
            + ", actual textUnitWordCount="
            + actual.getTextUnitWordCount());
  }

  private static Comparison orderedMismatch(
      List<ComparableTextUnitDTO> expectedRows, List<ComparableTextUnitDTO> actualRows) {
    int commonSize = Math.min(expectedRows.size(), actualRows.size());
    for (int i = 0; i < commonSize; i++) {
      if (!Objects.equals(expectedRows.get(i), actualRows.get(i))) {
        return Comparison.mismatch(
            "ordered search results differ: expected size="
                + expectedRows.size()
                + ", actual size="
                + actualRows.size()
                + ", first difference at index="
                + i
                + ", expected="
                + expectedRows.get(i)
                + ", actual="
                + actualRows.get(i));
      }
    }

    return Comparison.mismatch(
        "ordered search results differ: expected size="
            + expectedRows.size()
            + ", actual size="
            + actualRows.size());
  }

  private static Map<ComparableTextUnitDTO, Long> counts(List<ComparableTextUnitDTO> rows) {
    return rows.stream()
        .collect(
            Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
  }

  private static Map<ComparableTextUnitDTO, Long> diff(
      Map<ComparableTextUnitDTO, Long> left, Map<ComparableTextUnitDTO, Long> right) {
    Map<ComparableTextUnitDTO, Long> result = new LinkedHashMap<>();
    left.forEach(
        (row, count) -> {
          long delta = count - right.getOrDefault(row, 0L);
          if (delta > 0) {
            result.put(row, delta);
          }
        });
    return result;
  }

  private static String sample(Map<ComparableTextUnitDTO, Long> rows) {
    return rows.entrySet().stream()
        .limit(3)
        .map(entry -> entry.getValue() + "x " + entry.getKey())
        .collect(Collectors.joining("; "));
  }

  private static Long millis(ZonedDateTime zonedDateTime) {
    return zonedDateTime == null ? null : zonedDateTime.toInstant().toEpochMilli();
  }

  private static Long variantCreatedDateMillis(TextUnitDTO dto) {
    if (dto.getTmTextUnitVariantId() == null) {
      return null;
    }
    return millis(dto.getCreatedDate());
  }

  private static List<String> normalizedUsages(String usages) {
    if (usages == null || usages.isBlank()) {
      return List.of();
    }
    return Arrays.stream(usages.split(","))
        .map(String::trim)
        .filter(usage -> !usage.isEmpty())
        .sorted()
        .toList();
  }

  public record Comparison(boolean matches, String message) {
    static Comparison match() {
      return new Comparison(true, "");
    }

    static Comparison mismatch(String message) {
      return new Comparison(false, message);
    }
  }

  private record ComparableTextUnitDTO(
      Long tmTextUnitId,
      Long tmTextUnitVariantId,
      Long localeId,
      String targetLocale,
      String name,
      String source,
      String comment,
      String target,
      String targetComment,
      Long assetId,
      Long lastSuccessfulAssetExtractionId,
      Long assetExtractionId,
      Long tmTextUnitCurrentVariantId,
      String status,
      boolean includedInLocalizedFile,
      Long createdDateMillis,
      boolean assetDeleted,
      String pluralForm,
      String pluralFormOther,
      String repositoryName,
      String assetPath,
      Long assetTextUnitId,
      List<String> assetTextUnitUsages,
      Long tmTextUnitCreatedDateMillis,
      boolean doNotTranslate,
      Long branchId) {

    static ComparableTextUnitDTO from(TextUnitDTO dto) {
      return new ComparableTextUnitDTO(
          dto.getTmTextUnitId(),
          dto.getTmTextUnitVariantId(),
          dto.getLocaleId(),
          dto.getTargetLocale(),
          dto.getName(),
          dto.getSource(),
          dto.getComment(),
          dto.getTarget(),
          dto.getTargetComment(),
          dto.getAssetId(),
          dto.getLastSuccessfulAssetExtractionId(),
          dto.getAssetExtractionId(),
          dto.getTmTextUnitCurrentVariantId(),
          dto.getStatus() == null ? null : dto.getStatus().name(),
          dto.isIncludedInLocalizedFile(),
          variantCreatedDateMillis(dto),
          dto.isAssetDeleted(),
          dto.getPluralForm(),
          dto.getPluralFormOther(),
          dto.getRepositoryName(),
          dto.getAssetPath(),
          dto.getAssetTextUnitId(),
          normalizedUsages(dto.getAssetTextUnitUsages()),
          millis(dto.getTmTextUnitCreatedDate()),
          dto.isDoNotTranslate(),
          dto.getBranchId());
    }
  }
}
