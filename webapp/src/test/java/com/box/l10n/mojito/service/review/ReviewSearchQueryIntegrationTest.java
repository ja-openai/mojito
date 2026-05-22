package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ReviewSearchQueryIntegrationTest extends ServiceTestBase {

  @Autowired ReviewAutomationService reviewAutomationService;

  @Autowired ReviewFeatureService reviewFeatureService;

  @Test
  public void searchReviewAutomationsSupportsBlankAndNonBlankSearchQueries() {
    SearchReviewAutomationsView blankSearchResult =
        reviewAutomationService.searchReviewAutomations("  ", null, 10);
    SearchReviewAutomationsView nonBlankSearchResult =
        reviewAutomationService.searchReviewAutomations("missing", null, 10);

    assertThat(blankSearchResult.reviewAutomations()).isEmpty();
    assertThat(nonBlankSearchResult.reviewAutomations()).isEmpty();
  }

  @Test
  public void searchReviewFeaturesSupportsBlankAndNonBlankSearchQueries() {
    SearchReviewFeaturesView blankSearchResult =
        reviewFeatureService.searchReviewFeatures("  ", null, 10);
    SearchReviewFeaturesView nonBlankSearchResult =
        reviewFeatureService.searchReviewFeatures("missing", null, 10);

    assertThat(blankSearchResult.reviewFeatures()).isEmpty();
    assertThat(nonBlankSearchResult.reviewFeatures()).isEmpty();
  }
}
