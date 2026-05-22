package com.box.l10n.mojito.service.review;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

public class ReviewFeatureServiceTest {

  private final ReviewFeatureRepository reviewFeatureRepository =
      Mockito.mock(ReviewFeatureRepository.class);
  private final ReviewAutomationRepository reviewAutomationRepository =
      Mockito.mock(ReviewAutomationRepository.class);
  private final RepositoryRepository repositoryRepository = Mockito.mock(RepositoryRepository.class);
  private final UserService userService = Mockito.mock(UserService.class);

  private ReviewFeatureService reviewFeatureService;

  @Before
  public void setUp() {
    reviewFeatureService =
        new ReviewFeatureService(
            reviewFeatureRepository, reviewAutomationRepository, repositoryRepository, userService);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
  }

  @Test
  public void searchReviewFeaturesUsesNoSearchQueryWhenSearchIsBlank() {
    PageRequest pageRequest = PageRequest.of(0, ReviewFeatureService.DEFAULT_LIMIT);
    when(reviewFeatureRepository.findSummaryRows(null, pageRequest)).thenReturn(Page.empty());

    reviewFeatureService.searchReviewFeatures("  ", null, null);

    verify(reviewFeatureRepository).findSummaryRows(null, pageRequest);
    verify(reviewFeatureRepository, never())
        .searchSummaryRows(Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  public void searchReviewFeaturesUsesSearchQueryWhenSearchIsNotBlank() {
    PageRequest pageRequest = PageRequest.of(0, ReviewFeatureService.DEFAULT_LIMIT);
    when(reviewFeatureRepository.searchSummaryRows("checkout", null, pageRequest))
        .thenReturn(Page.empty());

    reviewFeatureService.searchReviewFeatures(" checkout ", null, null);

    verify(reviewFeatureRepository).searchSummaryRows("checkout", null, pageRequest);
    verify(reviewFeatureRepository, never()).findSummaryRows(Mockito.any(), Mockito.any());
  }
}
