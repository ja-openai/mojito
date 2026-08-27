package com.box.l10n.mojito.service.tm.importer;

import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.HUMAN;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.SERVICE;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.SYSTEM;
import static com.box.l10n.mojito.entity.BulkImportRun.ActorType.UNKNOWN;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.BULK_IMPORT_LINEAGE;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.BulkImportRun;
import com.box.l10n.mojito.entity.BulkImportRun.ActorType;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.ImportResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BulkImportLineageService {

  private static final Logger logger = LoggerFactory.getLogger(BulkImportLineageService.class);

  public static final String SOURCE_BATCH_API = "TEXT_UNITS_BATCH_API";
  public static final String SOURCE_VIRTUAL_ASSET = "VIRTUAL_ASSET";
  public static final String SOURCE_AI_TRANSLATE = "AI_TRANSLATE";
  public static final String SOURCE_MACHINE_TRANSLATION = "MACHINE_TRANSLATION";
  public static final String SOURCE_GLOSSARY_TERM = "GLOSSARY_TERM";
  public static final String SOURCE_GLOSSARY_IMPORT = "GLOSSARY_IMPORT";
  public static final String SOURCE_BATCH_IMPORTER = "TEXT_UNIT_BATCH_IMPORTER";
  public static final String UNKNOWN_IDENTITY = "UNKNOWN";

  private enum OutputStatus {
    IMPORTED,
    SKIPPED,
    UNMATCHED
  }

  private final BulkImportRunRepository runRepository;
  private final StructuredBlobStorage structuredBlobStorage;
  private final AuditorAwareImpl auditorAware;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  public BulkImportLineageService(
      BulkImportRunRepository runRepository,
      StructuredBlobStorage structuredBlobStorage,
      AuditorAwareImpl auditorAware,
      UserRepository userRepository,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
    this.runRepository = runRepository;
    this.structuredBlobStorage = structuredBlobStorage;
    this.auditorAware = auditorAware;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
  }

  public record ImportContext(
      User initiatingUser,
      ActorType actorType,
      String actorIdentity,
      String source,
      PollableTask pollableTask) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InputComment(String type, String severity, String content) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InputTextUnit(
      Long tmTextUnitId,
      String name,
      String source,
      String sourceComment,
      String target,
      String targetComment,
      String status,
      boolean includedInLocalizedFile,
      List<InputComment> comments,
      String translatorIdentity,
      String reviewerIdentity) {}

  public record InputPayload(
      String runId,
      String repository,
      String locale,
      String assetPath,
      String source,
      String importMode,
      String integrityChecksType,
      List<InputTextUnit> textUnits) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutputTextUnit(
      Long tmTextUnitId,
      String name,
      Long previousTmTextUnitVariantId,
      Long resultingTmTextUnitVariantId,
      String status,
      String translatorIdentity,
      String reviewerIdentity) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutputPayload(
      String runId,
      String status,
      int requestedCount,
      int importedCount,
      int skippedCount,
      List<OutputTextUnit> textUnits,
      String error) {}

  public record RunSummary(
      String runId,
      ZonedDateTime createdDate,
      ZonedDateTime completedDate,
      Long repositoryId,
      String repositoryName,
      Long assetId,
      String assetPath,
      String locale,
      Long pollableTaskId,
      Long initiatingUserId,
      String actorType,
      String actorIdentity,
      String source,
      String importMode,
      String integrityChecksType,
      String status,
      int requestedCount,
      int importedCount,
      int skippedCount,
      String inputPayloadBlobName,
      String outputPayloadBlobName,
      String errorMessage) {}

  public ImportContext captureCurrentContext(String source) {
    Optional<User> currentUser = auditorAware.getCurrentAuditor();
    if (currentUser.isPresent()) {
      return contextForUser(currentUser.get(), source, null);
    }

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null
        && !authentication.getName().isBlank()
        && !"anonymousUser".equals(authentication.getName())) {
      return new ImportContext(null, SERVICE, authentication.getName(), source, null);
    }

    return new ImportContext(null, UNKNOWN, null, source, null);
  }

  public ImportContext contextForPollableTask(
      PollableTask pollableTask,
      Long capturedUserId,
      ActorType capturedActorType,
      String capturedActorIdentity,
      String source) {
    Long initiatingUserId = capturedUserId;
    if (initiatingUserId == null
        && pollableTask != null
        && pollableTask.getCreatedByUser() != null) {
      initiatingUserId = pollableTask.getCreatedByUser().getId();
    }

    if (initiatingUserId != null) {
      User initiatingUser = userRepository.findById(initiatingUserId).orElse(null);
      if (initiatingUser != null) {
        return contextForUser(initiatingUser, source, pollableTask);
      }
    }

    if (capturedActorType != null) {
      return new ImportContext(
          null, capturedActorType, capturedActorIdentity, source, pollableTask);
    }

    ImportContext currentContext = captureCurrentContext(source);
    return new ImportContext(
        currentContext.initiatingUser(),
        currentContext.actorType(),
        currentContext.actorIdentity(),
        source,
        pollableTask);
  }

  public BulkImportRun startRun(
      Asset asset,
      Locale locale,
      TextUnitBatchImporterService.IntegrityChecksType integrityChecksType,
      TextUnitBatchImporterService.ImportMode importMode,
      ImportContext context,
      List<TextUnitForBatchMatcherImport> textUnits) {
    BulkImportRun run = new BulkImportRun();
    run.setRunId(UUID.randomUUID().toString());
    run.setRepository(asset.getRepository());
    run.setAsset(asset);
    run.setLocale(locale);
    run.setPollableTask(context.pollableTask());
    run.setInitiatingUser(context.initiatingUser());
    run.setActorType(context.actorType());
    run.setActorIdentity(context.actorIdentity());
    run.setSource(context.source());
    run.setImportMode(importMode.name());
    run.setIntegrityChecksType(integrityChecksType.name());
    run.setStatus(BulkImportRun.Status.RUNNING);
    run.setRequestedCount(textUnits.size());
    run = runRepository.saveAndFlush(run);

    try {
      String inputBlobName = run.getRunId() + "/input.json";
      InputPayload inputPayload =
          new InputPayload(
              run.getRunId(),
              asset.getRepository().getName(),
              locale.getBcp47Tag(),
              asset.getPath(),
              context.source(),
              importMode.name(),
              integrityChecksType.name(),
              textUnits.stream().map(this::toInputTextUnit).toList());
      structuredBlobStorage.put(
          BULK_IMPORT_LINEAGE,
          inputBlobName,
          objectMapper.writeValueAsStringUnchecked(inputPayload),
          Retention.PERMANENT);
      run.setInputPayloadBlobName(inputBlobName);
      return runRepository.saveAndFlush(run);
    } catch (RuntimeException failure) {
      failRun(run, failure);
      throw failure;
    }
  }

  public void completeRun(
      BulkImportRun run,
      List<TextUnitForBatchMatcherImport> textUnits,
      List<ImportResult> importResults) {
    try {
      List<OutputTextUnit> outputTextUnits = createOutputTextUnits(textUnits, importResults);

      run.setImportedCount(importResults.size());
      run.setSkippedCount(textUnits.size() - importResults.size());
      String outputBlobName = run.getRunId() + "/output.json";
      OutputPayload outputPayload =
          new OutputPayload(
              run.getRunId(),
              BulkImportRun.Status.COMPLETED.name(),
              run.getRequestedCount(),
              run.getImportedCount(),
              run.getSkippedCount(),
              outputTextUnits,
              null);
      structuredBlobStorage.put(
          BULK_IMPORT_LINEAGE,
          outputBlobName,
          objectMapper.writeValueAsStringUnchecked(outputPayload),
          Retention.PERMANENT);

      run.setOutputPayloadBlobName(outputBlobName);
      run.setStatus(BulkImportRun.Status.COMPLETED);
      run.setCompletedDate(ZonedDateTime.now());
      runRepository.saveAndFlush(run);
    } catch (RuntimeException failure) {
      failRun(run, failure);
      throw failure;
    }
  }

  public void failRun(BulkImportRun run, Throwable failure) {
    if (run.getStatus() == BulkImportRun.Status.FAILED) {
      return;
    }

    run.setStatus(BulkImportRun.Status.FAILED);
    run.setCompletedDate(ZonedDateTime.now());
    String safeError = "Import failed: " + failure.getClass().getSimpleName();
    run.setErrorMessage(safeError);

    if (run.getInputPayloadBlobName() != null && run.getOutputPayloadBlobName() == null) {
      String outputBlobName = run.getRunId() + "/output.json";
      try {
        structuredBlobStorage.put(
            BULK_IMPORT_LINEAGE,
            outputBlobName,
            objectMapper.writeValueAsStringUnchecked(
                new OutputPayload(
                    run.getRunId(),
                    BulkImportRun.Status.FAILED.name(),
                    run.getRequestedCount(),
                    run.getImportedCount(),
                    run.getSkippedCount(),
                    List.of(),
                    safeError)),
            Retention.PERMANENT);
        run.setOutputPayloadBlobName(outputBlobName);
      } catch (RuntimeException outputFailure) {
        logger.warn(
            "Unable to persist failed bulk import output: runId={}, errorType={}",
            run.getRunId(),
            outputFailure.getClass().getSimpleName());
      }
    }

    runRepository.saveAndFlush(run);
    logger.warn(
        "Bulk import failed: runId={}, source={}, errorType={}",
        run.getRunId(),
        run.getSource(),
        failure.getClass().getSimpleName());
  }

  @Transactional(readOnly = true)
  public Optional<RunSummary> findRun(String runId) {
    return runRepository.findByRunId(runId).map(this::toRunSummary);
  }

  @Transactional(readOnly = true)
  public List<RunSummary> findRecentRuns(int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 200));
    return runRepository
        .findAllByOrderByCreatedDateDescIdDesc(PageRequest.of(0, normalizedLimit))
        .stream()
        .map(this::toRunSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<String> findInputPayload(String runId) {
    return findPayload(runId, true);
  }

  @Transactional(readOnly = true)
  public Optional<String> findOutputPayload(String runId) {
    return findPayload(runId, false);
  }

  private ImportContext contextForUser(User user, String source, PollableTask pollableTask) {
    ActorType actorType =
        UserService.SYSTEM_USERNAME.equals(user.getUsername())
            ? SYSTEM
            : UserService.LEVERAGE_USERNAME.equals(user.getUsername()) ? SERVICE : HUMAN;
    return new ImportContext(user, actorType, user.getUsername(), source, pollableTask);
  }

  private InputTextUnit toInputTextUnit(TextUnitForBatchMatcherImport textUnit) {
    return new InputTextUnit(
        textUnit.getCurrentTextUnit() == null
            ? textUnit.getTmTextUnitId()
            : textUnit.getCurrentTextUnit().getTmTextUnitId(),
        getTextUnitName(textUnit),
        textUnit.getCurrentTextUnit() == null ? null : textUnit.getCurrentTextUnit().getSource(),
        textUnit.getComment(),
        textUnit.getContent(),
        textUnit.getTargetComment(),
        textUnit.getStatus() == null ? null : textUnit.getStatus().name(),
        textUnit.isIncludedInLocalizedFile(),
        textUnit.getTmTextUnitVariantComments().stream().map(this::toInputComment).toList(),
        normalizeIdentity(textUnit.getTranslatorIdentity()),
        normalizeIdentity(textUnit.getReviewerIdentity()));
  }

  private InputComment toInputComment(TMTextUnitVariantComment comment) {
    return new InputComment(
        comment.getType() == null ? null : comment.getType().name(),
        comment.getSeverity() == null ? null : comment.getSeverity().name(),
        comment.getContent());
  }

  private List<OutputTextUnit> createOutputTextUnits(
      List<TextUnitForBatchMatcherImport> textUnits, List<ImportResult> importResults) {
    Map<Long, ArrayDeque<ImportResult>> resultsByTextUnitId = new HashMap<>();
    for (ImportResult result : importResults) {
      TMTextUnitCurrentVariant currentVariant =
          result.addTMTextUnitCurrentVariantResult().getTmTextUnitCurrentVariant();
      resultsByTextUnitId
          .computeIfAbsent(currentVariant.getTmTextUnit().getId(), ignored -> new ArrayDeque<>())
          .add(result);
    }

    List<OutputTextUnit> outputTextUnits = new ArrayList<>(textUnits.size());
    for (TextUnitForBatchMatcherImport textUnit : textUnits) {
      Long tmTextUnitId =
          textUnit.getCurrentTextUnit() == null
              ? null
              : textUnit.getCurrentTextUnit().getTmTextUnitId();
      ArrayDeque<ImportResult> results =
          tmTextUnitId == null ? null : resultsByTextUnitId.get(tmTextUnitId);
      ImportResult result = results == null ? null : results.pollFirst();
      if (result != null) {
        TMTextUnitCurrentVariant currentVariant =
            result.addTMTextUnitCurrentVariantResult().getTmTextUnitCurrentVariant();
        outputTextUnits.add(
            new OutputTextUnit(
                currentVariant.getTmTextUnit().getId(),
                getTextUnitName(textUnit),
                result.previousTmTextUnitVariantId(),
                currentVariant.getTmTextUnitVariant().getId(),
                OutputStatus.IMPORTED.name(),
                normalizeIdentity(textUnit.getTranslatorIdentity()),
                normalizeIdentity(textUnit.getReviewerIdentity())));
      } else if (tmTextUnitId != null) {
        outputTextUnits.add(
            new OutputTextUnit(
                tmTextUnitId,
                getTextUnitName(textUnit),
                null,
                null,
                OutputStatus.SKIPPED.name(),
                normalizeIdentity(textUnit.getTranslatorIdentity()),
                normalizeIdentity(textUnit.getReviewerIdentity())));
      } else {
        outputTextUnits.add(
            new OutputTextUnit(
                null,
                getTextUnitName(textUnit),
                null,
                null,
                OutputStatus.UNMATCHED.name(),
                normalizeIdentity(textUnit.getTranslatorIdentity()),
                normalizeIdentity(textUnit.getReviewerIdentity())));
      }
    }
    return outputTextUnits;
  }

  private String getTextUnitName(TextUnitForBatchMatcherImport textUnit) {
    if (textUnit.getName() != null) {
      return textUnit.getName();
    }
    return textUnit.getCurrentTextUnit() == null ? null : textUnit.getCurrentTextUnit().getName();
  }

  private Optional<String> findPayload(String runId, boolean input) {
    return runRepository
        .findByRunId(runId)
        .map(
            input
                ? BulkImportRun::getInputPayloadBlobName
                : BulkImportRun::getOutputPayloadBlobName)
        .flatMap(blobName -> structuredBlobStorage.getString(BULK_IMPORT_LINEAGE, blobName));
  }

  private RunSummary toRunSummary(BulkImportRun run) {
    return new RunSummary(
        run.getRunId(),
        run.getCreatedDate(),
        run.getCompletedDate(),
        run.getRepository().getId(),
        run.getRepository().getName(),
        run.getAsset().getId(),
        run.getAsset().getPath(),
        run.getLocale().getBcp47Tag(),
        run.getPollableTask() == null ? null : run.getPollableTask().getId(),
        run.getInitiatingUser() == null ? null : run.getInitiatingUser().getId(),
        run.getActorType().name(),
        run.getActorIdentity(),
        run.getSource(),
        run.getImportMode(),
        run.getIntegrityChecksType(),
        run.getStatus().name(),
        run.getRequestedCount(),
        run.getImportedCount(),
        run.getSkippedCount(),
        run.getInputPayloadBlobName(),
        run.getOutputPayloadBlobName(),
        run.getErrorMessage());
  }

  private String normalizeIdentity(String identity) {
    if (identity == null || identity.isBlank()) {
      return UNKNOWN_IDENTITY;
    }
    String normalized = identity.trim();
    if (normalized.length() > User.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException("Translation attribution identity exceeds 255 characters");
    }
    return normalized;
  }
}
