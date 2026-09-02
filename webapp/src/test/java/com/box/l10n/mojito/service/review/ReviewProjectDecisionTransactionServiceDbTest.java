package com.box.l10n.mojito.service.review;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.rest.review.ReviewProjectWS;
import com.box.l10n.mojito.rest.review.ReviewProjectWS.GetReviewProjectResponse;
import com.box.l10n.mojito.rest.review.ReviewProjectWS.ReviewProjectTextUnitSuggestionRequest;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ReviewProjectDecisionTransactionServiceDbTest extends ServiceTestBase {

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired private RepositoryService repositoryService;
  @Autowired private AssetService assetService;
  @Autowired private TMService tmService;
  @Autowired private LocaleService localeService;
  @Autowired private ReviewProjectRepository projectRepository;
  @Autowired private ReviewProjectTextUnitRepository textUnitRepository;
  @Autowired private ReviewProjectTextUnitSuggestionRepository suggestionRepository;
  @Autowired private ReviewProjectTextUnitDecisionRepository decisionRepository;
  @Autowired private TMTextUnitCurrentVariantRepository currentVariantRepository;
  @Autowired private TMTextUnitVariantRepository variantRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ReviewProjectWS reviewProjectWS;
  @Autowired private ReviewProjectService reviewProjectService;

  @Test
  public void stateOnlyAndNoopResponsesRetainStagedSuggestion() throws Exception {
    Fixture fixture = createFixture(true);
    var staged = stage(fixture, "staged target", fixture.originalVariantId(), false);
    assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);

    for (String notes : new String[] {null, "Keep this note"}) {
      ReviewProjectTextUnitDecisionRequest request =
          decision(null, fixture.originalVariantId(), false);
      request.setDecisionState(DecisionState.PENDING);
      request.setDecisionNotes(notes);
      var saved = saveDecision(fixture.secondRowId(), request);
      assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(saved.getBody().reviewProjectTextUnitSuggestion())
          .usingRecursiveComparison()
          .withComparatorForType(
              java.util.Comparator.comparing(ZonedDateTime::toInstant), ZonedDateTime.class)
          .isEqualTo(staged.getBody().reviewProjectTextUnitSuggestion());
      assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId()))
          .isPresent();
      assertThat(currentTarget(fixture)).isEqualTo("original target");
    }
  }

  @Test
  public void conflictRetryMustMatchTheLatestVariantAndRetainsStagedSuggestion() throws Exception {
    Fixture fixture = createFixture(true);
    var staged = stage(fixture, "staged target", fixture.originalVariantId(), false);
    Long secondVariantId = writeOutsideReview(fixture, "another writer");
    var conflict =
        saveDecision(fixture.secondRowId(), decision("mine", fixture.originalVariantId(), false));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion())
        .usingRecursiveComparison()
        .withComparatorForType(
            java.util.Comparator.comparing(ZonedDateTime::toInstant), ZonedDateTime.class)
        .isEqualTo(staged.getBody().reviewProjectTextUnitSuggestion());
    assertThat(conflict.getBody().currentTmTextUnitVariant().id()).isEqualTo(secondVariantId);

    Long thirdVariantId = writeOutsideReview(fixture, "newer writer");
    var retry = saveDecision(fixture.secondRowId(), decision("mine", secondVariantId, true));
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(retry.getBody().currentTmTextUnitVariant().id()).isEqualTo(thirdVariantId);
    assertThat(currentTarget(fixture)).isEqualTo("newer writer");
    assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();

    var accepted = saveDecision(fixture.secondRowId(), decision("mine", thirdVariantId, true));
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(accepted.getBody().reviewProjectTextUnitSuggestion()).isNull();
    assertThat(currentTarget(fixture)).isEqualTo("mine");
  }

  @Test
  public void suggestionRetryMustMatchTheLatestVariant() throws Exception {
    Fixture fixture = createFixture(true);
    var staged = stage(fixture, "staged target", fixture.originalVariantId(), false);
    Long latestVariantId = writeOutsideReview(fixture, "another writer");

    var conflict = stage(fixture, "replacement suggestion", fixture.originalVariantId(), true);
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion())
        .usingRecursiveComparison()
        .withComparatorForType(
            java.util.Comparator.comparing(ZonedDateTime::toInstant), ZonedDateTime.class)
        .isEqualTo(staged.getBody().reviewProjectTextUnitSuggestion());
    assertThat(conflict.getBody().currentTmTextUnitVariant().id()).isEqualTo(latestVariantId);
    assertThat(
            suggestionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getTarget())
        .isEqualTo("staged target");
    assertThat(currentTarget(fixture)).isEqualTo("another writer");
  }

  @Test
  public void staleReviewRevisionRejectsNotesSuggestionsAndDeletion() throws Exception {
    Fixture fixture = createFixture(true);
    String initialRevision = row(fixture).reviewStateRevision();
    assertThat(initialRevision).isNotBlank();
    ReviewProjectTextUnitDecisionRequest firstNotes =
        decision(null, fixture.originalVariantId(), false);
    firstNotes.setDecisionState(DecisionState.PENDING);
    firstNotes.setDecisionNotes("first note");
    firstNotes.setExpectedReviewStateRevision(initialRevision);
    var savedNotes = saveDecision(fixture.secondRowId(), firstNotes);
    String notesRevision = savedNotes.getBody().reviewStateRevision();
    assertThat(notesRevision).isNotEqualTo(initialRevision);

    firstNotes.setExpectedReviewStateRevision(notesRevision);
    firstNotes.setDecisionNotes("newer note");
    var updatedNotes = saveDecision(fixture.secondRowId(), firstNotes);
    assertThat(updatedNotes.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updatedNotes.getBody().reviewStateRevision()).isNotEqualTo(notesRevision);
    firstNotes.setDecisionNotes("stale note");
    var staleNotes = saveDecision(fixture.secondRowId(), firstNotes);
    assertThat(staleNotes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(staleNotes.getBody().reviewProjectTextUnitDecision().notes())
        .isEqualTo("newer note");
    notesRevision = updatedNotes.getBody().reviewStateRevision();

    var staged =
        reviewProjectWS.saveSuggestion(
            fixture.secondRowId(),
            new ReviewProjectTextUnitSuggestionRequest(
                "first suggestion",
                "AI_REVIEW",
                null,
                null,
                fixture.originalVariantId(),
                false,
                notesRevision));
    assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
    String stagedRevision = staged.getBody().reviewStateRevision();
    assertThat(stagedRevision).isNotEqualTo(notesRevision);

    var updatedSuggestion =
        reviewProjectWS.saveSuggestion(
            fixture.secondRowId(),
            new ReviewProjectTextUnitSuggestionRequest(
                "newer suggestion",
                "AI_REVIEW",
                null,
                null,
                fixture.originalVariantId(),
                false,
                stagedRevision));
    assertThat(updatedSuggestion.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updatedSuggestion.getBody().reviewStateRevision()).isNotEqualTo(stagedRevision);
    var staleSuggestion =
        reviewProjectWS.saveSuggestion(
            fixture.secondRowId(),
            new ReviewProjectTextUnitSuggestionRequest(
                "stale suggestion",
                "AI_REVIEW",
                null,
                null,
                fixture.originalVariantId(),
                true,
                stagedRevision));
    assertThat(staleSuggestion.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(staleSuggestion.getBody().reviewProjectTextUnitSuggestion().target())
        .isEqualTo("newer suggestion");

    var staleDelete = reviewProjectWS.deleteSuggestion(fixture.secondRowId(), notesRevision);
    assertThat(staleDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId()))
        .isPresent();

    ReviewProjectTextUnitDecisionRequest staleAcceptance =
        decision("stale acceptance", fixture.originalVariantId(), true);
    staleAcceptance.setExpectedReviewStateRevision(notesRevision);
    assertThat(saveDecision(fixture.secondRowId(), staleAcceptance).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(currentTarget(fixture)).isEqualTo("original target");

    var deleted =
        reviewProjectWS.deleteSuggestion(
            fixture.secondRowId(), updatedSuggestion.getBody().reviewStateRevision());
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deleted.getBody().reviewProjectTextUnitSuggestion()).isNull();
    assertThat(deleted.getBody().reviewStateRevision()).isNotEqualTo(stagedRevision);
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  private GetProjectDetailView.ReviewProjectTextUnit row(Fixture fixture) {
    Long projectId =
        textUnitRepository.findById(fixture.secondRowId()).orElseThrow().getReviewProject().getId();
    return reviewProjectService.getProjectDetail(projectId).reviewProjectTextUnits().getFirst();
  }

  @Test
  public void concurrentRowsSharingTranslationRejectStaleDecision() throws Exception {
    assertConcurrentDecisionConflicts(true, false, false);
  }

  @Test
  public void concurrentRowsWithoutCurrentTranslationRejectStaleDecision() throws Exception {
    assertConcurrentDecisionConflicts(false, false, false);
  }

  @Test
  public void concurrentWriterOutsideReviewCannotBeOverwrittenByStaleDecision() throws Exception {
    assertConcurrentDecisionConflicts(true, true, false);
  }

  @Test
  public void concurrentNotesWithUnchangedTranslationRejectStaleRevision() throws Exception {
    assertConcurrentDecisionConflicts(true, false, true);
  }

  private void assertConcurrentDecisionConflicts(
      boolean hasCurrentTranslation, boolean externalWriter, boolean notesOnly) throws Exception {
    Fixture fixture = createFixture(hasCurrentTranslation);
    var firstRequest =
        decision(notesOnly ? null : "first accepted", fixture.originalVariantId(), false);
    var secondRequest =
        decision(notesOnly ? null : "stale replacement", fixture.originalVariantId(), false);
    if (notesOnly) {
      String revision = row(fixture).reviewStateRevision();
      firstRequest.setDecisionState(DecisionState.PENDING);
      firstRequest.setDecisionNotes("first note");
      firstRequest.setExpectedReviewStateRevision(revision);
      secondRequest.setDecisionState(DecisionState.PENDING);
      secondRequest.setDecisionNotes("stale note");
      secondRequest.setExpectedReviewStateRevision(revision);
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    CountDownLatch firstSaved = new CountDownLatch(1);
    CountDownLatch releaseFirstCommit = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Long> first =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  return new TransactionTemplate(transactionManager)
                      .execute(
                          ignored -> {
                            try {
                              Long acceptedVariantId;
                              if (externalWriter) {
                                acceptedVariantId = writeOutsideReview(fixture, "first accepted");
                                currentVariantRepository.flush();
                              } else {
                                var response =
                                    saveDecision(
                                        notesOnly ? fixture.secondRowId() : fixture.firstRowId(),
                                        firstRequest);
                                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                                acceptedVariantId =
                                    response.getBody().currentTmTextUnitVariant().id();
                              }
                              firstSaved.countDown();
                              await(releaseFirstCommit);
                              return acceptedVariantId;
                            } catch (Exception exception) {
                              throw new RuntimeException(exception);
                            }
                          });
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(firstSaved.await(10, SECONDS)).isTrue();
      Future<ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit>> second =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  secondStarted.countDown();
                  return saveDecision(fixture.secondRowId(), secondRequest);
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(secondStarted.await(5, SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(1, SECONDS)).isInstanceOf(TimeoutException.class);
      releaseFirstCommit.countDown();
      Long acceptedVariantId = first.get(10, SECONDS);
      var response = second.get(10, SECONDS);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody().id()).isEqualTo(fixture.secondRowId());
      assertThat(response.getBody().currentTmTextUnitVariant().id()).isEqualTo(acceptedVariantId);
      assertThat(currentTarget(fixture))
          .isEqualTo(notesOnly ? "original target" : "first accepted");
      if (notesOnly) {
        assertThat(
                decisionRepository
                    .findByReviewProjectTextUnitId(fixture.secondRowId())
                    .orElseThrow()
                    .getNotes())
            .isEqualTo("first note");
      } else {
        assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId()))
            .isEmpty();
      }
      assertThat(
              variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                  fixture.tmTextUnitId(), fixture.localeId()))
          .extracting(TMTextUnitVariant::getContent)
          .doesNotContain("stale replacement");
    } finally {
      releaseFirstCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  private ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit> saveDecision(
      Long rowId, ReviewProjectTextUnitDecisionRequest request) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction.execute(
        status -> {
          try {
            var response = reviewProjectWS.saveDecision(rowId, request);
            if (response.getStatusCode() == HttpStatus.CONFLICT) {
              status.setRollbackOnly();
            }
            return response;
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  private ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit> stage(
      Fixture fixture, String target, Long expectedVariantId, boolean override) {
    return reviewProjectWS.saveSuggestion(
        fixture.secondRowId(),
        new ReviewProjectTextUnitSuggestionRequest(
            target,
            "AI_REVIEW",
            "Suggestion note",
            "original target",
            expectedVariantId,
            override,
            null));
  }

  private ReviewProjectTextUnitDecisionRequest decision(
      String target, Long expectedVariantId, boolean override) {
    ReviewProjectTextUnitDecisionRequest request = new ReviewProjectTextUnitDecisionRequest();
    request.setTarget(target);
    request.setStatus("APPROVED");
    request.setIncludedInLocalizedFile(true);
    request.setDecisionState(DecisionState.DECIDED);
    request.setExpectedCurrentTmTextUnitVariantId(expectedVariantId);
    request.setOverrideChangedCurrent(override);
    return request;
  }

  private String currentTarget(Fixture fixture) {
    return currentVariantRepository
        .findByLocale_IdAndTmTextUnit_Id(fixture.localeId(), fixture.tmTextUnitId())
        .getTmTextUnitVariant()
        .getContent();
  }

  private Long writeOutsideReview(Fixture fixture, String target) {
    return tmService
        .addCurrentTMTextUnitVariant(
            fixture.tmTextUnitId(),
            fixture.localeId(),
            target,
            TMTextUnitVariant.Status.APPROVED,
            true)
        .getId();
  }

  private Fixture createFixture(boolean hasCurrentTranslation) throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("repository"));
    Asset asset =
        assetService.createAssetWithContent(repository.getId(), "path/to/messages.json", "{}");
    TMTextUnit textUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "greeting", "Hello", null);
    Locale locale = localeService.findByBcp47Tag("fr-FR");
    TMTextUnitVariant original =
        hasCurrentTranslation
            ? tmService.addCurrentTMTextUnitVariant(
                textUnit.getId(),
                locale.getId(),
                "original target",
                TMTextUnitVariant.Status.APPROVED,
                true)
            : null;
    return new Fixture(
        createRow(textUnit, locale, original),
        createRow(textUnit, locale, original),
        textUnit.getId(),
        locale.getId(),
        original == null ? null : original.getId());
  }

  private Long createRow(TMTextUnit textUnit, Locale locale, TMTextUnitVariant original) {
    ReviewProject project = new ReviewProject();
    project.setLocale(locale);
    project.setDueDate(ZonedDateTime.now().plusDays(1));
    project = projectRepository.saveAndFlush(project);
    ReviewProjectTextUnit row = new ReviewProjectTextUnit();
    row.setReviewProject(project);
    row.setTmTextUnit(textUnit);
    row.setTmTextUnitVariant(original);
    return textUnitRepository.saveAndFlush(row).getId();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new AssertionError("Timed out waiting to release first transaction");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private record Fixture(
      Long firstRowId,
      Long secondRowId,
      Long tmTextUnitId,
      Long localeId,
      Long originalVariantId) {}
}
