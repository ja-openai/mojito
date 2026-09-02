package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.rest.review.ReviewProjectWS;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Regression coverage for a row whose backing term changes while a reviewer owns its draft. */
public class ReviewProjectIdentityRevisionDbTest extends ServiceTestBase {

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired private GlossaryManagementService glossaryManagementService;
  @Autowired private GlossaryTermService glossaryTermService;
  @Autowired private LocaleService localeService;
  @Autowired private TMTextUnitRepository tmTextUnitRepository;
  @Autowired private ReviewProjectRepository projectRepository;
  @Autowired private ReviewProjectTextUnitRepository rowRepository;
  @Autowired private ReviewProjectTextUnitSuggestionRepository suggestionRepository;
  @Autowired private ReviewProjectService reviewProjectService;
  @Autowired private ReviewProjectWS reviewProjectWS;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoSpyBean private TMTextUnitIntegrityCheckService integrityCheckService;

  @Test
  public void replacingAnUntranslatedBackingTermInvalidatesTheReviewedRevision() {
    Fixture fixture = fixture();
    var before = row(fixture);
    var after = replaceDefinition(fixture);

    assertThat(after.tmTextUnit().id()).isNotEqualTo(before.tmTextUnit().id());
    assertThat(after.tmTextUnit().comment()).isEqualTo("A different meaning of the term");
    assertThat(after.currentTmTextUnitVariant().id()).isNull();
    assertThat(after.reviewStateRevision())
        .as("A revision must bind the backing text unit whose source and context were reviewed")
        .isNotEqualTo(before.reviewStateRevision());
  }

  @Test
  public void anOlderDraftCannotStageAgainstTheReplacementBackingTerm() {
    Fixture fixture = fixture();
    var before = row(fixture);
    var after = replaceDefinition(fixture);
    assertThat(after.tmTextUnit().id()).isNotEqualTo(before.tmTextUnit().id());

    var result =
        reviewProjectWS.saveSuggestion(
            fixture.rowId(),
            new ReviewProjectWS.ReviewProjectTextUnitSuggestionRequest(
                "Translation for the old meaning",
                "AI_REVIEW",
                null,
                null,
                before.currentTmTextUnitVariant().id(),
                false,
                before.reviewStateRevision()));

    assertThat(result.getStatusCode())
        .as("Staging must reject a draft reviewed before the backing term changed")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.rowId())).isEmpty();
  }

  @Test
  public void anOlderDraftCannotDecideAgainstTheReplacementBackingTerm() throws Exception {
    Fixture fixture = fixture();
    var before = row(fixture);
    replaceDefinition(fixture);
    var request = new ReviewProjectTextUnitDecisionRequest();
    request.setTarget("Translation for the old meaning");
    request.setStatus("APPROVED");
    request.setIncludedInLocalizedFile(true);
    request.setDecisionState(DecisionState.DECIDED);
    request.setExpectedCurrentTmTextUnitVariantId(before.currentTmTextUnitVariant().id());
    request.setExpectedReviewStateRevision(before.reviewStateRevision());

    assertThat(reviewProjectWS.saveDecision(fixture.rowId(), request).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(row(fixture).currentTmTextUnitVariant().id()).isNull();
    assertThat(row(fixture).reviewProjectTextUnitDecision()).isNull();
  }

  @Test
  public void replacingTheBackingTermWaitsForAnAlreadyValidatedSave() throws Exception {
    Fixture fixture = fixture();
    var before = row(fixture);
    String draft = "Suggestion being saved while the definition changes";
    var checked = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var replacementStarted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              checked.countDown();
              assertThat(releaseSave.await(10, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(integrityCheckService)
        .checkTMTextUnitIntegrity(eq(before.tmTextUnit().id()), eq(draft));
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var save =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  return reviewProjectWS.saveSuggestion(
                      fixture.rowId(),
                      new ReviewProjectWS.ReviewProjectTextUnitSuggestionRequest(
                          draft,
                          "AI_REVIEW",
                          null,
                          null,
                          null,
                          false,
                          before.reviewStateRevision()));
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
      var replacement =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  replacementStarted.countDown();
                  return replaceDefinition(fixture);
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(replacementStarted.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> replacement.get(1, TimeUnit.SECONDS))
            .as("Metadata cannot remap a row after a save checked that row's source identity")
            .isInstanceOf(TimeoutException.class);
      } finally {
        releaseSave.countDown();
      }
      assertThat(save.get(10, TimeUnit.SECONDS).getBody().tmTextUnit().id())
          .isEqualTo(before.tmTextUnit().id());
      assertThat(replacement.get(10, TimeUnit.SECONDS).tmTextUnit().id())
          .isNotEqualTo(before.tmTextUnit().id());
    } finally {
      releaseSave.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  public void saveWaitingForAReplacementMustRejectTheOldSourceIdentity() throws Exception {
    Fixture fixture = fixture();
    var before = row(fixture);
    var replaced = new CountDownLatch(1);
    var releaseReplacement = new CountDownLatch(1);
    var saveStarted = new CountDownLatch(1);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var replacement =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  var transaction = new TransactionTemplate(transactionManager);
                  transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                  return transaction.execute(
                      status -> {
                        var result = replaceDefinition(fixture);
                        replaced.countDown();
                        try {
                          assertThat(releaseReplacement.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(interrupted);
                        }
                        return result;
                      });
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(replaced.await(10, TimeUnit.SECONDS)).isTrue();
      var save =
          executor.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                  saveStarted.countDown();
                  return reviewProjectWS.saveSuggestion(
                      fixture.rowId(),
                      new ReviewProjectWS.ReviewProjectTextUnitSuggestionRequest(
                          "Old-context draft waiting for remap",
                          "AI_REVIEW",
                          null,
                          null,
                          null,
                          false,
                          before.reviewStateRevision()));
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(saveStarted.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> save.get(1, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        releaseReplacement.countDown();
      }
      assertThat(replacement.get(10, TimeUnit.SECONDS).tmTextUnit().id())
          .isNotEqualTo(before.tmTextUnit().id());
      var rejected = save.get(10, TimeUnit.SECONDS);
      assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(rejected.getBody().tmTextUnit().id()).isNotEqualTo(before.tmTextUnit().id());
      assertThat(suggestionRepository.findByReviewProjectTextUnitId(fixture.rowId())).isEmpty();
    } finally {
      releaseReplacement.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private GetProjectDetailView.ReviewProjectTextUnit replaceDefinition(Fixture fixture) {
    return reviewProjectService.updateTerminologyMetadata(
        fixture.rowId(),
        new ReviewProjectService.UpdateTerminologyMetadataCommand(
            "A different meaning of the term", null, null, null, null, null));
  }

  private GetProjectDetailView.ReviewProjectTextUnit row(Fixture fixture) {
    return reviewProjectService
        .getProjectDetail(fixture.projectId())
        .reviewProjectTextUnits()
        .getFirst();
  }

  private Fixture fixture() {
    var glossary =
        glossaryManagementService.createGlossary(
            testIdWatcher.getEntityName("glossary"),
            null,
            true,
            0,
            "GLOBAL",
            List.of("fr-FR"),
            List.of(),
            List.of());
    var term =
        glossaryTermService.upsertTerm(
            glossary.id(),
            null,
            new GlossaryTermService.TermUpsertCommand(
                "term",
                "Bank",
                null,
                "A place to keep money",
                null,
                null,
                null,
                "CANDIDATE",
                null,
                false,
                false,
                false,
                false,
                null,
                List.of(),
                List.of()));
    Locale locale = localeService.findByBcp47Tag("fr-FR");
    ReviewProject project = new ReviewProject();
    project.setLocale(locale);
    project.setType(ReviewProjectType.TERMINOLOGY);
    project.setDueDate(ZonedDateTime.now().plusDays(1));
    project = projectRepository.saveAndFlush(project);
    ReviewProjectTextUnit row = new ReviewProjectTextUnit();
    row.setReviewProject(project);
    row.setTmTextUnit(tmTextUnitRepository.findById(term.tmTextUnitId()).orElseThrow());
    row = rowRepository.saveAndFlush(row);
    return new Fixture(project.getId(), row.getId());
  }

  private record Fixture(Long projectId, Long rowId) {}
}
