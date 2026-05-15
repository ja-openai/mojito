package com.box.l10n.mojito.service.tm.search.replacement;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.tm.search.TextUnitAndWordCount;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import org.junit.Test;

public class TextUnitSearchResultComparatorTest {

  @Test
  public void compareSearchCanIgnoreOrderForUnorderedQueries() {
    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareSearch(
            List.of(dto(1L), dto(2L)), List.of(dto(2L), dto(1L)), false);

    assertThat(comparison.matches()).isTrue();
  }

  @Test
  public void compareSearchDetectsOrderDifferenceForOrderedQueries() {
    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareSearch(
            List.of(dto(1L), dto(2L)), List.of(dto(2L), dto(1L)), true);

    assertThat(comparison.matches()).isFalse();
    assertThat(comparison.message()).contains("first difference at index=0");
  }

  @Test
  public void compareSearchNormalizesAggregatedUsages() {
    TextUnitDTO nativeRow = dto(1L);
    nativeRow.setAssetTextUnitUsages("src/a.properties:2,src/a.properties:1");
    TextUnitDTO criteriaRow = dto(1L);
    criteriaRow.setAssetTextUnitUsages("src/a.properties:1, src/a.properties:2");

    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareSearch(
            List.of(nativeRow), List.of(criteriaRow), true);

    assertThat(comparison.matches()).isTrue();
  }

  @Test
  public void compareCountDetectsWordCountMismatch() {
    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareCount(
            new TextUnitAndWordCount(2, 10), new TextUnitAndWordCount(2, 11));

    assertThat(comparison.matches()).isFalse();
    assertThat(comparison.message()).contains("expected textUnitWordCount=10");
  }

  private TextUnitDTO dto(Long id) {
    TextUnitDTO dto = new TextUnitDTO();
    dto.setTmTextUnitId(id);
    dto.setLocaleId(1L);
    dto.setTargetLocale("fr-FR");
    dto.setName("name-" + id);
    dto.setSource("source-" + id);
    dto.setAssetId(1L);
    dto.setLastSuccessfulAssetExtractionId(2L);
    dto.setAssetExtractionId(2L);
    dto.setIncludedInLocalizedFile(true);
    dto.setRepositoryName("repo");
    dto.setAssetPath("asset.properties");
    dto.setAssetTextUnitId(id);
    return dto;
  }
}
