package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.rest.review.ReviewFeatureWS;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;

public class ReviewFeatureServiceTest {

  private final ReviewFeatureRepository reviewFeatureRepository =
      mock(ReviewFeatureRepository.class);
  private final UserService userService = mock(UserService.class);
  private final ReviewFeatureService service =
      new ReviewFeatureService(
          reviewFeatureRepository,
          mock(ReviewAutomationRepository.class),
          mock(RepositoryRepository.class),
          userService);

  @Test
  public void localeEndpointReturnsSortedCanonicalUnionForSelectedFeatures() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(reviewFeatureRepository.findNonRootLocaleRowsByFeatureIds(List.of(2L, 5L)))
        .thenReturn(
            List.of(
                new ReviewFeatureLocaleRow(4L, "he"),
                new ReviewFeatureLocaleRow(3L, "fr-FR"),
                new ReviewFeatureLocaleRow(4L, "he")));

    assertEquals(
        List.of("fr-FR", "he"),
        new ReviewFeatureWS(service).getReviewFeatureLocales(Arrays.asList(5L, null, 2L, 5L, 0L)));

    verify(reviewFeatureRepository).findNonRootLocaleRowsByFeatureIds(List.of(2L, 5L));
  }

  @Test
  public void localeEndpointWithoutSelectedFeaturesReturnsEmptyWithoutQueryingCatalog() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    ReviewFeatureWS webService = new ReviewFeatureWS(service);

    assertEquals(List.of(), webService.getReviewFeatureLocales(null));
    assertEquals(List.of(), webService.getReviewFeatureLocales(List.of()));
    assertEquals(List.of(), webService.getReviewFeatureLocales(List.of(0L, -1L)));

    verifyNoInteractions(reviewFeatureRepository);
  }

  @Test
  public void localeEndpointRequiresAdminIncludingForEmptySelection() {
    ReviewFeatureWS webService = new ReviewFeatureWS(service);

    assertThrows(
        AccessDeniedException.class, () -> webService.getReviewFeatureLocales(List.of(2L)));
    assertThrows(AccessDeniedException.class, () -> webService.getReviewFeatureLocales(List.of()));

    verifyNoInteractions(reviewFeatureRepository);
  }
}
