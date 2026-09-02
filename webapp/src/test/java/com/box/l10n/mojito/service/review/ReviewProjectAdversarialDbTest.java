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

public class ReviewProjectAdversarialDbTest extends ServiceTestBase {

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
  @Autowired private org.springframework.web.context.WebApplicationContext webApplicationContext;

  @Test
  public void stateOnlyReacceptanceRecordsTheCurrentVariantRatherThanAnOldDecision()
      throws Exception {
    Fixture fixture = createFixture(true);
    var accepted =
        saveDecision(
            fixture.secondRowId(), decision("first accepted", fixture.originalVariantId(), false));
    var pending = decision(null, accepted.getBody().currentTmTextUnitVariant().id(), false);
    pending.setDecisionState(DecisionState.PENDING);
    pending.setExpectedReviewStateRevision(accepted.getBody().reviewStateRevision());
    assertThat(saveDecision(fixture.secondRowId(), pending).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    Long latestVariant = writeOutsideReview(fixture, "new current chosen in conflict");
    var latest = row(fixture);
    var useCurrent = decision(null, latestVariant, false);
    useCurrent.setExpectedReviewStateRevision(latest.reviewStateRevision());
    var response = saveDecision(fixture.secondRowId(), useCurrent);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().reviewProjectTextUnitDecision().decisionState())
        .isEqualTo("DECIDED");
    assertThat(response.getBody().reviewProjectTextUnitDecision().decisionTmTextUnitVariant().id())
        .as("Use current must accept the current variant, not the older retained decision")
        .isEqualTo(latestVariant);
    assertThat(
            decisionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getDecisionVariant()
                .getId())
        .isEqualTo(latestVariant);
  }

  @Test
  public void firstStateOnlyDecisionRetainsSuppliedNotes() throws Exception {
    Fixture fixture = createFixture(true);
    var request = decision(null, fixture.originalVariantId(), false);
    request.setDecisionNotes("Reviewer explanation must be retained");
    request.setExpectedReviewStateRevision(row(fixture).reviewStateRevision());
    var response = saveDecision(fixture.secondRowId(), request);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().reviewProjectTextUnitDecision().notes())
        .isEqualTo(request.getDecisionNotes());
    assertThat(
            decisionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getNotes())
        .isEqualTo(request.getDecisionNotes());
  }

  @Test
  public void concurrentSuggestionUpdatesRejectTheStaleWriter() throws Exception {
    Fixture fixture = createFixture(true);
    var initial = stageRevision(fixture, "initial suggestion", row(fixture).reviewStateRevision());
    String revision = initial.getBody().reviewStateRevision();
    var conflict =
        whileFirstTransactionHeld(
            () -> stageRevision(fixture, "winning suggestion", revision),
            () -> stageRevision(fixture, "stale suggestion", revision));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion().target())
        .isEqualTo("winning suggestion");
    assertThat(
            suggestionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getTarget())
        .isEqualTo("winning suggestion");
  }

  @Test
  public void concurrentDeletePreventsResurrectionFromAStaleSuggestionEdit() throws Exception {
    Fixture fixture = createFixture(true);
    String revision =
        stageRevision(fixture, "initial suggestion", row(fixture).reviewStateRevision())
            .getBody()
            .reviewStateRevision();
    var conflict =
        whileFirstTransactionHeld(
            () -> reviewProjectWS.deleteSuggestion(fixture.secondRowId(), revision),
            () -> stageRevision(fixture, "stale resurrection", revision));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion()).isNull();
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  @Test
  public void concurrentAcceptanceMakesAnOlderDeleteConflictWithoutRemovingTheDecision()
      throws Exception {
    Fixture fixture = createFixture(true);
    String revision =
        stageRevision(fixture, "suggested acceptance", row(fixture).reviewStateRevision())
            .getBody()
            .reviewStateRevision();
    var request = decision("suggested acceptance", fixture.originalVariantId(), false);
    request.setExpectedReviewStateRevision(revision);
    var conflict =
        whileFirstTransactionHeld(
            () -> saveDecision(fixture.secondRowId(), request),
            () -> reviewProjectWS.deleteSuggestion(fixture.secondRowId(), revision));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion()).isNull();
    assertThat(conflict.getBody().reviewProjectTextUnitDecision().decisionState())
        .isEqualTo("DECIDED");
    assertThat(currentTarget(fixture)).isEqualTo("suggested acceptance");
  }

  @Test
  public void concurrentStagingPreventsAnOlderNotesWriteEvenWithoutATranslationChange()
      throws Exception {
    Fixture fixture = createFixture(true);
    String revision = row(fixture).reviewStateRevision();
    var request = decision(null, fixture.originalVariantId(), false);
    request.setDecisionState(DecisionState.PENDING);
    request.setDecisionNotes("stale notes");
    request.setExpectedReviewStateRevision(revision);
    var conflict =
        whileFirstTransactionHeld(
            () -> stageRevision(fixture, "new suggestion", revision),
            () -> saveDecision(fixture.secondRowId(), request));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().reviewProjectTextUnitSuggestion().target())
        .isEqualTo("new suggestion");
    assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  @Test
  public void constraintFailureRollsBackTranslationDecisionAndSuggestionConsumption()
      throws Exception {
    Fixture fixture = createFixture(true);
    var staged = stageRevision(fixture, "retained suggestion", row(fixture).reviewStateRevision());
    String revision = staged.getBody().reviewStateRevision();
    var request = decision("must roll back", fixture.originalVariantId(), false);
    request.setDecisionNotes("x".repeat(4001));
    request.setExpectedReviewStateRevision(revision);
    assertThatThrownBy(() -> saveDecision(fixture.secondRowId(), request))
        .isInstanceOf(RuntimeException.class);
    assertThat(currentTarget(fixture)).isEqualTo("original target");
    assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
    assertThat(row(fixture).reviewStateRevision()).isEqualTo(revision);
    assertThat(
            suggestionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getTarget())
        .isEqualTo("retained suggestion");
    assertThat(
            variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                fixture.tmTextUnitId(), fixture.localeId()))
        .extracting(TMTextUnitVariant::getContent)
        .doesNotContain("must roll back");
  }

  @Test
  public void deletingAndRecreatingIdenticalSuggestionDoesNotReuseTheOldRevision()
      throws Exception {
    Fixture fixture = createFixture(true);
    String first =
        stageRevision(fixture, "same target", row(fixture).reviewStateRevision())
            .getBody()
            .reviewStateRevision();
    String deleted =
        reviewProjectWS
            .deleteSuggestion(fixture.secondRowId(), first)
            .getBody()
            .reviewStateRevision();
    String recreated =
        stageRevision(fixture, "same target", deleted).getBody().reviewStateRevision();
    assertThat(recreated).isNotEqualTo(first);
    var stale = reviewProjectWS.deleteSuggestion(fixture.secondRowId(), first);
    assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(stale.getBody().reviewProjectTextUnitSuggestion().target()).isEqualTo("same target");
  }

  @Test
  public void revisionFromAnotherProjectRowCannotAuthorizeASave() throws Exception {
    Fixture fixture = createFixture(true);
    Long otherProject =
        textUnitRepository.findById(fixture.firstRowId()).orElseThrow().getReviewProject().getId();
    String otherRevision =
        reviewProjectService
            .getProjectDetail(otherProject)
            .reviewProjectTextUnits()
            .getFirst()
            .reviewStateRevision();
    var request = decision("wrong row revision", fixture.originalVariantId(), false);
    request.setExpectedReviewStateRevision(otherRevision);
    var response = saveDecision(fixture.secondRowId(), request);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(currentTarget(fixture)).isEqualTo("original target");
    assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  @Test
  public void malformedRevisionRejectsEvenStateOnlyNoopsAndEmptyDeletes() throws Exception {
    Fixture fixture = createFixture(false);
    String valid = row(fixture).reviewStateRevision();
    for (String malformed :
        new String[] {"", "null", valid + ":junk", "v1:0:null:null:null:null:null"}) {
      var request = decision(null, null, false);
      request.setDecisionState(DecisionState.PENDING);
      request.setExpectedReviewStateRevision(malformed);
      var noop = saveDecision(fixture.secondRowId(), request);
      assertThat(noop.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(noop.getBody().currentTmTextUnitVariant().id()).isNull();
      var deletion = reviewProjectWS.deleteSuggestion(fixture.secondRowId(), malformed);
      assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(deletion.getBody().reviewStateRevision()).isEqualTo(valid);
    }
    assertThat(decisionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  @Test
  public void initialUntranslatedRevisionSupportsStagingAndAcceptance() throws Exception {
    Fixture fixture = createFixture(false);
    var staged = stageRevision(fixture, "first translation", row(fixture).reviewStateRevision());
    assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(staged.getBody().currentTmTextUnitVariant().id()).isNull();
    var request = decision("first translation", null, false);
    request.setExpectedReviewStateRevision(staged.getBody().reviewStateRevision());
    var accepted = saveDecision(fixture.secondRowId(), request);
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(accepted.getBody().currentTmTextUnitVariant().content())
        .isEqualTo("first translation");
    assertThat(accepted.getBody().reviewProjectTextUnitSuggestion()).isNull();
  }

  @Test
  public void separateLocalesSharingTheParentCanBothCommitTheirOwnRevision() throws Exception {
    Fixture fixture = createFixture(true);
    TMTextUnit textUnit =
        textUnitRepository.findById(fixture.secondRowId()).orElseThrow().getTmTextUnit();
    Locale otherLocale = localeService.findByBcp47Tag("de-DE");
    Long otherRow = createRow(textUnit, otherLocale, null);
    Fixture other =
        new Fixture(otherRow, otherRow, fixture.tmTextUnitId(), otherLocale.getId(), null);
    var firstRequest = decision("French accepted", fixture.originalVariantId(), false);
    firstRequest.setExpectedReviewStateRevision(row(fixture).reviewStateRevision());
    var secondRequest = decision("German accepted", null, false);
    secondRequest.setExpectedReviewStateRevision(row(other).reviewStateRevision());
    var second =
        whileFirstTransactionHeld(
            () -> saveDecision(fixture.secondRowId(), firstRequest),
            () -> saveDecision(otherRow, secondRequest));
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(currentTarget(fixture)).isEqualTo("French accepted");
    assertThat(currentTarget(other)).isEqualTo("German accepted");
  }

  @Test
  public void authenticatedMvcDecisionRoundTripCarriesTheRevisionAndRejectsStaleNotes()
      throws Exception {
    Fixture fixture = createFixture(true);
    var request = decision(null, fixture.originalVariantId(), false);
    request.setDecisionState(DecisionState.PENDING);
    request.setDecisionNotes("Saved through the MVC route");
    request.setExpectedReviewStateRevision(row(fixture).reviewStateRevision());
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                webApplicationContext)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String path = "/api/review-project-text-units/" + fixture.secondRowId() + "/decision";
    var saved =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.authentication(authentication))
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse();
    var savedJson = mapper.readTree(saved.getContentAsString());
    assertThat(savedJson.path("reviewStateRevision").asText())
        .isNotEqualTo(request.getExpectedReviewStateRevision());
    assertThat(savedJson.path("reviewProjectTextUnitDecision").path("notes").asText())
        .isEqualTo(request.getDecisionNotes());
    request.setDecisionNotes("Stale MVC notes");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authentication))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
    assertThat(
            decisionRepository
                .findByReviewProjectTextUnitId(fixture.secondRowId())
                .orElseThrow()
                .getNotes())
        .isEqualTo("Saved through the MVC route");
  }

  @Test
  public void authenticatedMvcDeleteReadsTheRevisionQueryAndPreservesANewerSuggestion()
      throws Exception {
    Fixture fixture = createFixture(true);
    String initialRevision = row(fixture).reviewStateRevision();
    var staged = stageRevision(fixture, "New suggestion", initialRevision);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                webApplicationContext)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String path = "/api/review-project-text-units/" + fixture.secondRowId() + "/suggestion";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .param("expectedReviewStateRevision", initialRevision)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authentication))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId()))
        .isPresent();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .param("expectedReviewStateRevision", staged.getBody().reviewStateRevision())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authentication))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.secondRowId())).isEmpty();
  }

  private GetProjectDetailView.ReviewProjectTextUnit row(Fixture fixture) {
    Long projectId =
        textUnitRepository.findById(fixture.secondRowId()).orElseThrow().getReviewProject().getId();
    return reviewProjectService.getProjectDetail(projectId).reviewProjectTextUnits().getFirst();
  }

  private ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit> stageRevision(
      Fixture fixture, String target, String revision) {
    return reviewProjectWS.saveSuggestion(
        fixture.secondRowId(),
        new ReviewProjectTextUnitSuggestionRequest(
            target, "AI_REVIEW", null, null, fixture.originalVariantId(), false, revision));
  }

  private ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit> whileFirstTransactionHeld(
      java.util.function.Supplier<ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit>>
          firstWrite,
      java.util.function.Supplier<ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit>>
          secondWrite)
      throws Exception {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    CountDownLatch firstSaved = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ResponseEntity<GetReviewProjectResponse.ReviewProjectTextUnit>> first =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                  transaction.setIsolationLevel(
                      org.springframework.transaction.TransactionDefinition
                          .ISOLATION_READ_COMMITTED);
                  return transaction.execute(
                      status -> {
                        var response = firstWrite.get();
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        firstSaved.countDown();
                        await(release);
                        return response;
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
                  return secondWrite.get();
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(secondStarted.await(5, SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(1, SECONDS)).isInstanceOf(TimeoutException.class);
      release.countDown();
      assertThat(first.get(10, SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
      return second.get(10, SECONDS);
    } finally {
      release.countDown();
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
