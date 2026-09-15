package com.box.l10n.mojito.rest.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.service.agentreview.AgentReviewReReviewService;
import com.box.l10n.mojito.service.review.GetProjectDetailView;
import com.box.l10n.mojito.service.review.ReviewProjectCurrentVariantConflictException;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import java.util.List;
import org.junit.Test;

public class ReviewProjectWSAgentReviewTest {
  private final ReviewProjectService projects = mock(ReviewProjectService.class);
  private final AgentReviewReReviewService reviews = mock(AgentReviewReReviewService.class);
  private final ReviewProjectWS endpoint =
      new ReviewProjectWS(projects, null, null, null, null, reviews);

  private GetProjectDetailView.ReviewProjectTextUnit row(String revision, long variantId) {
    var variant =
        new GetProjectDetailView.TmTextUnitVariant(variantId, "Traduction", "APPROVED", true, null);
    var unit =
        new GetProjectDetailView.TmTextUnit(
            9L,
            "key",
            "Translation",
            null,
            null,
            new GetProjectDetailView.Asset(
                "strings.json", new GetProjectDetailView.Asset.Repository(1L, "repo")),
            1L);
    return new GetProjectDetailView.ReviewProjectTextUnit(
        8L, unit, variant, variant, null, null, null, List.of(), List.of(), revision, null);
  }

  @Test
  public void completedEditReturnsTheOrdinaryFullRowResponse() {
    var saved = row("saved", 21L);
    when(reviews.reopenAndSave(7L, 10L, null)).thenReturn(saved);
    var response = endpoint.reopenAndSave(7L, 10L, null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().id()).isEqualTo(8L);
    assertThat(response.getBody().reviewStateRevision()).isEqualTo("saved");
    assertThat(response.getBody().currentTmTextUnitVariant().id()).isEqualTo(21L);
  }

  @Test
  public void conflictResponseReloadsTheRowAfterTheReopenTransactionRolledBack() {
    var rolledBackPending = row("uncommitted-pending", 20L);
    var persisted = row("still-reviewed", 20L);
    when(reviews.reopenAndSave(7L, 10L, null))
        .thenThrow(new ReviewProjectCurrentVariantConflictException(20L, 20L, rolledBackPending));
    when(projects.getReviewProjectTextUnit(8L)).thenReturn(persisted);
    var response = endpoint.reopenAndSave(7L, 10L, null);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().reviewStateRevision()).isEqualTo("still-reviewed");
    verify(projects).getReviewProjectTextUnit(8L);
  }
}
