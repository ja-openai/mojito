package com.box.l10n.mojito.service.tm.importer;

import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.HUMAN;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.SERVICE;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.SYSTEM;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.UNKNOWN;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.BULK_IMPORT_LINEAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.BulkImportRun;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.asset.ImportTextUnitJobInput;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService.ImportContext;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.ImportMode;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.ImportResult;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public class BulkImportLineageServiceTest {

  private final BulkImportRunRepository runRepository = mock(BulkImportRunRepository.class);
  private final StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
  private final AuditorAwareImpl auditorAware = mock(AuditorAwareImpl.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

  private BulkImportLineageService service;
  private Repository repository;
  private Asset asset;
  private Locale locale;
  private User user;

  @Before
  public void setUp() {
    SecurityContextHolder.clearContext();
    service = createService();
    repository = new Repository();
    repository.setId(11L);
    repository.setName("translation-repository");
    asset = new Asset();
    asset.setId(12L);
    asset.setPath("messages.json");
    asset.setRepository(repository);
    locale = new Locale();
    locale.setId(13L);
    locale.setBcp47Tag("it-IT");
    user = new User();
    user.setId(14L);
    user.setUsername("translator@example.com");
    user.setPassword("credential-must-never-appear");

    when(runRepository.saveAndFlush(any(BulkImportRun.class)))
        .thenAnswer(
            invocation -> {
              BulkImportRun run = invocation.getArgument(0);
              if (run.getId() == null) {
                run.setId(91L);
              }
              return run;
            });
  }

  @After
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void storesNormalizedInputAndCorrelatedOutputPermanently() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    TextUnitForBatchMatcherImport textUnit =
        createTextUnit(99514L, "SplashScreenV2.diveInWithName");
    textUnit.setTranslatorIdentity("italian-translator@example.com");
    textUnit.setReviewerIdentity("italian-reviewer@example.com");
    BulkImportRun run =
        service.startRun(
            asset,
            locale,
            IntegrityChecksType.SKIP,
            ImportMode.ALWAYS_IMPORT,
            service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API),
            List.of(textUnit));

    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(99514L);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(3299044L);
    variant.setTmTextUnit(tmTextUnit);
    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnit(tmTextUnit);
    currentVariant.setTmTextUnitVariant(variant);
    ImportResult result =
        new ImportResult(
            new AddTMTextUnitCurrentVariantResult(true, currentVariant), List.of(), 3299000L);

    service.completeRun(run, List.of(textUnit), List.of(result));

    ArgumentCaptor<String> blobNames = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
    verify(structuredBlobStorage, org.mockito.Mockito.times(2))
        .put(
            eq(BULK_IMPORT_LINEAGE),
            blobNames.capture(),
            payloads.capture(),
            eq(Retention.PERMANENT));
    assertThat(blobNames.getAllValues())
        .containsExactly(run.getRunId() + "/input.json", run.getRunId() + "/output.json");
    assertThat(payloads.getAllValues().getFirst())
        .contains("SplashScreenV2.diveInWithName", "Traduzione importata", "it-IT")
        .contains(
            "\"translatorIdentity\":\"italian-translator@example.com\"",
            "\"reviewerIdentity\":\"italian-reviewer@example.com\"")
        .doesNotContain("credential-must-never-appear", "password");
    assertThat(payloads.getAllValues().get(1))
        .contains(
            "\"tmTextUnitId\":99514",
            "\"previousTmTextUnitVariantId\":3299000",
            "\"resultingTmTextUnitVariantId\":3299044",
            "\"translatorIdentity\":\"italian-translator@example.com\"",
            "\"reviewerIdentity\":\"italian-reviewer@example.com\"")
        .contains("\"status\":\"IMPORTED\"")
        .contains("\"status\":\"COMPLETED\"");
    assertThat(run.getActorType()).isEqualTo(HUMAN);
    assertThat(run.getActorIdentity()).isEqualTo("translator@example.com");
    assertThat(run.getStatus()).isEqualTo(BulkImportRun.Status.COMPLETED);
    assertThat(run.getImportedCount()).isEqualTo(1);

    assertThat(run.getActorIdentity()).isNotEqualTo("italian-translator@example.com");
  }

  @Test
  public void preservesResolvedTextUnitNameForIdOnlyImports() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    TextUnitForBatchMatcherImport textUnit =
        createTextUnit(99514L, "SplashScreenV2.diveInWithName");
    textUnit.setName(null);

    BulkImportRun run =
        service.startRun(
            asset,
            locale,
            IntegrityChecksType.SKIP,
            ImportMode.ALWAYS_IMPORT,
            service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API),
            List.of(textUnit));

    service.completeRun(run, List.of(textUnit), List.of());

    ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
    verify(structuredBlobStorage, org.mockito.Mockito.times(2))
        .put(eq(BULK_IMPORT_LINEAGE), any(), payloads.capture(), eq(Retention.PERMANENT));
    assertThat(payloads.getAllValues().getFirst())
        .contains("\"name\":\"SplashScreenV2.diveInWithName\"");
    assertThat(payloads.getAllValues().get(1))
        .contains("\"name\":\"SplashScreenV2.diveInWithName\"");
  }

  @Test
  public void preservesExplicitAuthorAndReviewerAliasesAcrossQuartzSerialization() {
    ObjectMapper jobObjectMapper = ObjectMapper.withNoFailOnUnknownProperties();
    TextUnitDTO textUnit =
        jobObjectMapper.readValueUnchecked(
            """
            {"name":"welcome","translatedBy":"actual-author@example.com",\
            "approvedBy":"actual-reviewer@example.com"}
            """,
            TextUnitDTO.class);
    ImportTextUnitJobInput input = new ImportTextUnitJobInput();
    input.setTextUnitDTOs(List.of(textUnit));

    ImportTextUnitJobInput reloadedInput =
        jobObjectMapper.readValueUnchecked(
            jobObjectMapper.writeValueAsStringUnchecked(input), ImportTextUnitJobInput.class);

    assertThat(reloadedInput.getTextUnitDTOs().getFirst().getTranslatorIdentity())
        .isEqualTo("actual-author@example.com");
    assertThat(reloadedInput.getTextUnitDTOs().getFirst().getReviewerIdentity())
        .isEqualTo("actual-reviewer@example.com");
  }

  @Test
  public void recordsExplicitUnknownTranslatorAndReviewerWithoutUsingImporterIdentity() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    TextUnitForBatchMatcherImport textUnit = createTextUnit(99514L, "key");
    BulkImportRun run =
        service.startRun(
            asset,
            locale,
            IntegrityChecksType.SKIP,
            ImportMode.SKIP_IF_ACCEPTED,
            service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API),
            List.of(textUnit));

    service.completeRun(run, List.of(textUnit), List.of());

    ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
    verify(structuredBlobStorage, org.mockito.Mockito.times(2))
        .put(eq(BULK_IMPORT_LINEAGE), any(), payloads.capture(), eq(Retention.PERMANENT));
    assertThat(payloads.getAllValues().get(1))
        .contains("\"translatorIdentity\":\"UNKNOWN\"", "\"reviewerIdentity\":\"UNKNOWN\"")
        .doesNotContain(run.getActorIdentity());
  }

  @Test
  public void recordsExplicitUnknownWhenNoPrincipalExists() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.empty());

    ImportContext context =
        service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API);

    assertThat(context.actorType()).isEqualTo(UNKNOWN);
    assertThat(context.actorIdentity()).isNull();
    assertThat(context.initiatingUser()).isNull();
  }

  @Test
  public void recordsAuthenticatedNonUserPrincipalAsServiceIdentity() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.empty());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "translation-import-service", "secret-credential", List.of()));

    ImportContext context =
        service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API);

    assertThat(context.actorType()).isEqualTo(SERVICE);
    assertThat(context.actorIdentity()).isEqualTo("translation-import-service");
    assertThat(context.actorIdentity()).doesNotContain("secret-credential");
  }

  @Test
  public void distinguishesSystemUserFromHumanActor() {
    user.setUsername("system");
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));

    ImportContext context =
        service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API);

    assertThat(context.actorType()).isEqualTo(SYSTEM);
    assertThat(context.actorIdentity()).isEqualTo("system");
  }

  @Test
  public void recoversActorFromPersistedPollableTaskWhenWorkerContextIsMissing() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.empty());
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    PollableTask task = new PollableTask();
    task.setId(42L);
    task.setCreatedByUser(user);

    ImportContext context =
        service.contextForPollableTask(
            task, null, UNKNOWN, null, BulkImportLineageService.SOURCE_BATCH_API);

    assertThat(context.actorType()).isEqualTo(HUMAN);
    assertThat(context.initiatingUser()).isSameAs(user);
    assertThat(context.pollableTask()).isSameAs(task);
  }

  @Test
  public void preservesCapturedUnknownActorInsteadOfUsingWorkerIdentity() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    PollableTask task = new PollableTask();
    task.setId(42L);

    ImportContext context =
        service.contextForPollableTask(
            task, null, UNKNOWN, null, BulkImportLineageService.SOURCE_BATCH_API);

    assertThat(context.actorType()).isEqualTo(UNKNOWN);
    assertThat(context.actorIdentity()).isNull();
    assertThat(context.initiatingUser()).isNull();
    assertThat(context.pollableTask()).isSameAs(task);
  }

  @Test
  public void failsClosedBeforeImportWhenPermanentInputCannotBeWritten() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    doThrow(new IllegalStateException("storage unavailable"))
        .when(structuredBlobStorage)
        .put(eq(BULK_IMPORT_LINEAGE), anyString(), anyString(), eq(Retention.PERMANENT));

    assertThatThrownBy(
            () ->
                service.startRun(
                    asset,
                    locale,
                    IntegrityChecksType.SKIP,
                    ImportMode.ALWAYS_IMPORT,
                    service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API),
                    List.of(createTextUnit(99514L, "key"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("storage unavailable");

    ArgumentCaptor<BulkImportRun> runCaptor = ArgumentCaptor.forClass(BulkImportRun.class);
    verify(runRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(runCaptor.capture());
    assertThat(runCaptor.getValue().getStatus()).isEqualTo(BulkImportRun.Status.FAILED);
    assertThat(runCaptor.getValue().getErrorMessage())
        .isEqualTo("Import failed: IllegalStateException");
    assertThat(runCaptor.getValue().getInputPayloadBlobName()).isNull();
  }

  @Test
  public void marksRunFailedWhenPermanentOutputCannotBeWritten() {
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(user));
    TextUnitForBatchMatcherImport textUnit = createTextUnit(99514L, "key");
    BulkImportRun run =
        service.startRun(
            asset,
            locale,
            IntegrityChecksType.SKIP,
            ImportMode.SKIP_IF_ACCEPTED,
            service.captureCurrentContext(BulkImportLineageService.SOURCE_BATCH_API),
            List.of(textUnit));
    doThrow(new IllegalStateException("output unavailable"))
        .when(structuredBlobStorage)
        .put(
            eq(BULK_IMPORT_LINEAGE),
            eq(run.getRunId() + "/output.json"),
            anyString(),
            eq(Retention.PERMANENT));

    assertThatThrownBy(() -> service.completeRun(run, List.of(textUnit), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("output unavailable");

    assertThat(run.getStatus()).isEqualTo(BulkImportRun.Status.FAILED);
    assertThat(run.getSkippedCount()).isEqualTo(1);
    assertThat(run.getOutputPayloadBlobName()).isNull();
  }

  @Test
  public void listsRecentRunsWithABoundedQuery() {
    BulkImportRun run = new BulkImportRun();
    run.setRunId("batch-run-id");
    run.setRepository(repository);
    run.setAsset(asset);
    run.setLocale(locale);
    run.setActorType(HUMAN);
    run.setActorIdentity(user.getUsername());
    run.setSource(BulkImportLineageService.SOURCE_BATCH_API);
    run.setImportMode(ImportMode.ALWAYS_IMPORT.name());
    run.setIntegrityChecksType(IntegrityChecksType.SKIP.name());
    run.setStatus(BulkImportRun.Status.COMPLETED);
    run.setRequestedCount(4);
    run.setImportedCount(3);
    run.setSkippedCount(1);
    when(runRepository.findAllByOrderByCreatedDateDescIdDesc(any(Pageable.class)))
        .thenReturn(List.of(run));

    assertThat(service.findRecentRuns(500))
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.runId()).isEqualTo("batch-run-id");
              assertThat(summary.repositoryName()).isEqualTo(repository.getName());
              assertThat(summary.importedCount()).isEqualTo(3);
            });

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(runRepository).findAllByOrderByCreatedDateDescIdDesc(pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
  }

  private BulkImportLineageService createService() {
    return new BulkImportLineageService(
        runRepository, structuredBlobStorage, auditorAware, userRepository, objectMapper);
  }

  private TextUnitForBatchMatcherImport createTextUnit(Long tmTextUnitId, String name) {
    TextUnitDTO current = new TextUnitDTO();
    current.setTmTextUnitId(tmTextUnitId);
    current.setName(name);
    current.setSource("Source text");

    TextUnitForBatchMatcherImport textUnit = new TextUnitForBatchMatcherImport();
    textUnit.setRepository(repository);
    textUnit.setAsset(asset);
    textUnit.setLocale(locale);
    textUnit.setCurrentTextUnit(current);
    textUnit.setName(name);
    textUnit.setContent("Traduzione importata");
    textUnit.setStatus(TMTextUnitVariant.Status.APPROVED);
    textUnit.setIncludedInLocalizedFile(true);
    return textUnit;
  }
}
