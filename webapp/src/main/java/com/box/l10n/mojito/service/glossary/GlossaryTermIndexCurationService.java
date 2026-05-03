package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexDecision;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GlossaryTermIndexCurationService {

  private static final int DEFAULT_SUGGESTION_LIMIT = 50;
  private static final int EXAMPLE_LIMIT = 4;
  private static final int AI_SIGNAL_EXAMPLE_LIMIT = 8;
  private static final int LIVE_SEARCH_EXAMPLE_LIMIT = 3;
  private static final int AI_CANDIDATE_BATCH_SIZE = 50;
  private static final int AI_REVIEW_MAX_ATTEMPTS = 3;
  private static final long AI_REVIEW_RETRY_BACKOFF_MILLIS = 1_000L;
  private static final int TRIAGE_RESULT_ENTRY_LIMIT = 100;
  private static final int DEFAULT_GENERATION_LIMIT = 500;
  private static final int DEFAULT_CANDIDATE_EXPORT_LIMIT = 1_000;
  private static final List<Long> EMPTY_FILTER_SENTINEL = List.of(-1L);
  private static final String EXTRACTION_SOURCE_NAME = "term-index";
  private static final String GLOSSARY_SUBMISSION_SOURCE_TYPE = "HUMAN";
  private static final String GLOSSARY_SUBMISSION_SOURCE_NAME = "glossary-workspace";
  private static final String GLOSSARY_SUBMISSION_METADATA_GLOSSARY_ID = "glossaryId";
  private static final String GLOSSARY_SUBMISSION_METADATA_SUBMITTED_FROM = "submittedFrom";
  private static final String CANDIDATE_EXPORT_FORMAT_JSON = "json";
  private static final String SOURCE_SEARCH_METHOD = "SOURCE_SEARCH";
  private static final String REVIEW_STATUS_NEW = "NEW";
  private static final String REVIEW_STATUS_IGNORED = "IGNORED";
  private static final String REVIEW_STATUS_ACCEPTED = "ACCEPTED";
  private static final String REVIEW_STATUS_FILTER_ALL = "ALL";
  private static final String REVIEW_STATUS_FILTER_REVIEWED = "REVIEWED";
  private static final String GLOSSARY_PRESENCE_LINKED = "LINKED";
  private static final String GLOSSARY_PRESENCE_EXISTING_TERM = "EXISTING_TERM";
  private static final String GLOSSARY_PRESENCE_NOT_IN_GLOSSARY = "NOT_IN_GLOSSARY";
  private static final String GLOSSARY_PRESENCE_FILTER_ALL = "ALL";
  private static final String GLOSSARY_PRESENCE_FILTER_IN_GLOSSARY = "IN_GLOSSARY";

  private final GlossaryRepository glossaryRepository;
  private final com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository;
  private final GlossaryTermIndexDecisionRepository glossaryTermIndexDecisionRepository;
  private final TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final TermIndexCandidateRepository termIndexCandidateRepository;
  private final GlossaryTermService glossaryTermService;
  private final GlossaryAiExtractionService glossaryAiExtractionService;
  private final TextUnitSearcher textUnitSearcher;
  private final UserService userService;
  private final ObjectMapper objectMapper;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  private final PollableTaskService pollableTaskService;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public GlossaryTermIndexCurationService(
      GlossaryRepository glossaryRepository,
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository,
      GlossaryTermIndexDecisionRepository glossaryTermIndexDecisionRepository,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexCandidateRepository termIndexCandidateRepository,
      GlossaryTermService glossaryTermService,
      GlossaryAiExtractionService glossaryAiExtractionService,
      TextUnitSearcher textUnitSearcher,
      UserService userService,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      PollableTaskService pollableTaskService,
      PlatformTransactionManager transactionManager) {
    this.glossaryRepository = Objects.requireNonNull(glossaryRepository);
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.glossaryTermMetadataRepository = Objects.requireNonNull(glossaryTermMetadataRepository);
    this.glossaryTermIndexLinkRepository = Objects.requireNonNull(glossaryTermIndexLinkRepository);
    this.glossaryTermIndexDecisionRepository =
        Objects.requireNonNull(glossaryTermIndexDecisionRepository);
    this.termIndexExtractedTermRepository =
        Objects.requireNonNull(termIndexExtractedTermRepository);
    this.termIndexOccurrenceRepository = Objects.requireNonNull(termIndexOccurrenceRepository);
    this.termIndexCandidateRepository = Objects.requireNonNull(termIndexCandidateRepository);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
    this.glossaryAiExtractionService = Objects.requireNonNull(glossaryAiExtractionService);
    this.textUnitSearcher = Objects.requireNonNull(textUnitSearcher);
    this.userService = Objects.requireNonNull(userService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.quartzPollableTaskScheduler = Objects.requireNonNull(quartzPollableTaskScheduler);
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.requiresNewTransactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public SuggestionSearchView searchSuggestions(Long glossaryId, SuggestionSearchCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    SuggestionSearchCommand normalized = normalize(command);
    List<Long> scopeRepositoryIds = resolveScopeRepositoryIds(glossary);
    int prefetchLimit = prefetchLimit(normalized.limit());
    String searchQuery = normalizeOptional(normalized.searchQuery());

    List<TermIndexCandidateRepository.CandidateSearchRow> candidateRows =
        termIndexCandidateRepository
            .searchForGlossarySuggestions(
                false,
                repositoryIdsOrSentinel(scopeRepositoryIds),
                searchQuery,
                TermIndexReview.STATUS_FILTER_NON_REJECTED,
                PageRequest.of(0, prefetchLimit))
            .stream()
            .filter(row -> candidateVisibleForGlossary(row.getMetadataJson(), glossary.getId()))
            .toList();
    Map<String, Long> extractedTermMatchCountsByNormalizedKey =
        findExtractedTermMatchCountsByNormalizedKey(candidateRows, scopeRepositoryIds);

    LinkedHashMap<Long, SuggestionAccumulator> candidates = new LinkedHashMap<>();
    candidateRows.forEach(
        row ->
            candidates.putIfAbsent(
                row.getId(),
                fromCandidateSearchRow(
                    row,
                    extractedTermMatchCountsByNormalizedKey.getOrDefault(
                        row.getNormalizedKey(), 0L))));

    if (candidates.isEmpty()) {
      return new SuggestionSearchView(List.of(), 0);
    }

    List<Long> candidateIds = new ArrayList<>(candidates.keySet());
    Set<Long> linkedCandidateIds = linkedCandidateIds(glossary.getId(), candidateIds);
    Set<Long> ignoredCandidateIds = ignoredCandidateIds(glossary.getId(), candidateIds);
    Set<String> existingTermKeys = existingNormalizedTermKeys(glossary.getId());
    Map<Long, TermIndexCandidate> candidatesById =
        termIndexCandidateRepository.findAllById(candidateIds).stream()
            .collect(Collectors.toMap(TermIndexCandidate::getId, candidate -> candidate));

    List<SuggestionCandidate> heuristicCandidates = new ArrayList<>();
    for (SuggestionAccumulator accumulator : candidates.values()) {
      TermIndexCandidate candidate = candidatesById.get(accumulator.id());
      if (candidate == null) {
        continue;
      }
      SuggestionState state =
          suggestionState(accumulator, linkedCandidateIds, ignoredCandidateIds, existingTermKeys);
      if (!matchesReviewStatusFilter(state.reviewStatus(), normalized.reviewStatusFilter())) {
        continue;
      }
      if (!matchesGlossaryPresenceFilter(
          state.glossaryPresence(), normalized.glossaryPresenceFilter())) {
        continue;
      }
      heuristicCandidates.add(new SuggestionCandidate(accumulator, candidate, state));
      if (heuristicCandidates.size() >= prefetchLimit) {
        break;
      }
    }

    List<SuggestionView> heuristicSuggestions =
        heuristicCandidates.stream()
            .limit(normalized.limit())
            .map(
                suggestion ->
                    toSuggestion(
                        suggestion.accumulator(),
                        suggestion.candidate(),
                        scopeRepositoryIds,
                        suggestion.state()))
            .toList();

    List<SuggestionView> suggestions =
        Boolean.TRUE.equals(normalized.useAi())
            ? refineWithAi(heuristicSuggestions, normalized.limit())
            : heuristicSuggestions.stream().limit(normalized.limit()).toList();
    if (suggestions.isEmpty() && !heuristicSuggestions.isEmpty()) {
      suggestions = heuristicSuggestions.stream().limit(normalized.limit()).toList();
    }
    return new SuggestionSearchView(suggestions, heuristicCandidates.size());
  }

  @Transactional
  public GlossaryTermService.TermView acceptSuggestion(
      Long glossaryId, Long termIndexCandidateId, AcceptSuggestionCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    TermIndexCandidate candidate = getCandidate(termIndexCandidateId);
    AcceptSuggestionCommand normalized = normalize(command, candidate);
    List<Long> scopeRepositoryIds = resolveScopeRepositoryIds(glossary);
    List<GlossaryTermService.EvidenceInput> evidence =
        normalized.evidence() == null || normalized.evidence().isEmpty()
            ? defaultEvidence(normalized, candidate, scopeRepositoryIds)
            : toEvidenceInputs(normalized.evidence());

    GlossaryTermService.TermView term =
        glossaryTermService.upsertTerm(
            glossary.getId(),
            null,
            new GlossaryTermService.TermUpsertCommand(
                normalized.termKey(),
                normalized.source(),
                normalized.definition(),
                normalized.definition(),
                normalized.partOfSpeech(),
                normalized.termType(),
                normalized.enforcement(),
                normalized.status(),
                GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
                normalized.caseSensitive(),
                normalized.doNotTranslate(),
                true,
                false,
                null,
                List.of(),
                evidence));

    GlossaryTermMetadata metadata =
        glossaryTermMetadataRepository
            .findById(term.metadataId())
            .orElseThrow(() -> new IllegalStateException("Saved glossary term metadata not found"));
    GlossaryTermIndexLink link =
        glossaryTermIndexLinkRepository
            .findByGlossaryTermMetadataIdAndTermIndexCandidateIdAndRelationType(
                metadata.getId(), candidate.getId(), GlossaryTermIndexLink.RELATION_TYPE_PRIMARY)
            .orElseGet(
                () -> {
                  GlossaryTermIndexLink next = new GlossaryTermIndexLink();
                  next.setGlossaryTermMetadata(metadata);
                  next.setTermIndexCandidate(candidate);
                  next.setRelationType(GlossaryTermIndexLink.RELATION_TYPE_PRIMARY);
                  return next;
                });
    link.setConfidence(normalized.confidence());
    link.setRationale(normalized.rationale());
    glossaryTermIndexLinkRepository.save(link);

    glossaryTermIndexDecisionRepository
        .findByGlossaryIdAndTermIndexCandidateId(glossary.getId(), candidate.getId())
        .ifPresent(glossaryTermIndexDecisionRepository::delete);
    return term;
  }

  @Transactional
  public void ignoreSuggestion(
      Long glossaryId, Long termIndexCandidateId, IgnoreSuggestionCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    TermIndexCandidate candidate = getCandidate(termIndexCandidateId);
    GlossaryTermIndexDecision decision =
        glossaryTermIndexDecisionRepository
            .findByGlossaryIdAndTermIndexCandidateId(glossary.getId(), candidate.getId())
            .orElseGet(
                () -> {
                  GlossaryTermIndexDecision next = new GlossaryTermIndexDecision();
                  next.setGlossary(glossary);
                  next.setTermIndexCandidate(candidate);
                  return next;
                });
    decision.setDecision(GlossaryTermIndexDecision.DECISION_IGNORED);
    decision.setReason(
        command == null ? null : truncate(normalizeOptional(command.reason()), 2048));
    glossaryTermIndexDecisionRepository.save(decision);
  }

  @Transactional
  public CandidateReviewView updateCandidateReview(
      Long termIndexCandidateId, CandidateReviewCommand command) {
    requireTermManager();
    TermIndexCandidate candidate = getCandidate(termIndexCandidateId);
    CandidateReviewCommand normalized = normalize(command);
    applyHumanCandidateReview(candidate, normalized, currentUserOrNull());
    TermIndexCandidate saved = termIndexCandidateRepository.save(candidate);
    return new CandidateReviewView(
        saved.getId(),
        saved.getReviewStatus(),
        saved.getReviewAuthority(),
        saved.getReviewReason(),
        saved.getReviewRationale(),
        saved.getReviewConfidence(),
        saved.getReviewChangedAt(),
        saved.getReviewChangedByUser() == null ? null : saved.getReviewChangedByUser().getId(),
        saved.getReviewChangedByUser() == null
            ? null
            : saved.getReviewChangedByUser().getUsername(),
        saved.getReviewChangedByUser() == null
            ? null
            : saved.getReviewChangedByUser().getCommonName());
  }

  @Transactional
  public SeedResult seedTerms(SeedCommand command) {
    requireTermManager();
    SeedCommand normalized = normalize(command);
    return seedTermsInternal(normalized);
  }

  @Transactional
  public SeedResult seedTermsForGlossary(Long glossaryId, SeedCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    SeedCommand normalized = normalize(command);
    return seedTermsInternal(
        new SeedCommand(scopeCandidatesToGlossary(glossary.getId(), normalized.terms())));
  }

  @Transactional
  public GenerateCandidatesResult generateCandidatesForGlossary(
      Long glossaryId, GenerateCandidatesCommand command) {
    requireTermManager();
    return generateCandidatesForGlossaryInternal(glossaryId, command);
  }

  public PollableFuture<GenerateCandidatesResult> scheduleGenerateCandidatesForGlossary(
      Long glossaryId, GenerateCandidatesCommand command) {
    requireTermManager();
    GenerateCandidatesCommand normalized = normalize(command);
    getGlossary(glossaryId);
    return scheduleGenerateCandidates(
        new GenerateCandidatesJobCommand(glossaryId, normalized, null, currentUserIdOrNull()),
        "Generate glossary term index candidates");
  }

  @Transactional
  GenerateCandidatesResult generateCandidatesForGlossaryInternal(
      Long glossaryId, GenerateCandidatesCommand command) {
    Glossary glossary = getGlossary(glossaryId);
    GenerateCandidatesCommand normalized = normalize(command);
    List<Long> scopeRepositoryIds = resolveScopeRepositoryIds(glossary);
    if (scopeRepositoryIds.isEmpty()) {
      return new GenerateCandidatesResult(0, 0, 0, List.of());
    }

    List<TermIndexExtractedTermRepository.SearchRow> rows =
        termIndexExtractedTermRepository.searchEntries(
            false,
            repositoryIdsOrSentinel(scopeRepositoryIds),
            normalizeOptional(normalized.searchQuery()),
            normalizeOptional(normalized.extractionMethod()),
            TermIndexReview.STATUS_FILTER_NON_REJECTED,
            null,
            Math.max(1L, normalized.minOccurrences() == null ? 1L : normalized.minOccurrences()),
            null,
            null,
            null,
            null,
            "HITS",
            PageRequest.of(0, normalizeGenerationLimit(normalized.limit())));

    Map<Long, List<GeneratedCandidateFields>> candidateFieldsByExtractedTermId =
        candidateFieldsByExtractedTermId(
            rows, false, repositoryIdsOrSentinel(scopeRepositoryIds), null);
    List<SeededTermView> generatedCandidates = new ArrayList<>(rows.size());
    int createdCandidateCount = 0;
    int updatedCandidateCount = 0;
    for (TermIndexExtractedTermRepository.SearchRow row : rows) {
      for (GeneratedCandidateFields fields :
          candidateFieldsForRow(candidateFieldsByExtractedTermId, row)) {
        CandidateWriteResult writeResult = upsertExtractionCandidate(row, fields);
        if (writeResult.created()) {
          createdCandidateCount++;
        } else {
          updatedCandidateCount++;
        }
        generatedCandidates.add(toSeededTermView(writeResult.candidate()));
      }
    }

    return new GenerateCandidatesResult(
        generatedCandidates.size(),
        createdCandidateCount,
        updatedCandidateCount,
        generatedCandidates);
  }

  @Transactional
  public GenerateCandidatesResult generateCandidatesFromExtractedTerms(
      GenerateCandidatesFromExtractedTermsCommand command) {
    requireTermManager();
    return generateCandidatesFromExtractedTermsInternal(command);
  }

  public PollableFuture<GenerateCandidatesResult> scheduleGenerateCandidatesFromExtractedTerms(
      GenerateCandidatesFromExtractedTermsCommand command) {
    requireTermManager();
    GenerateCandidatesFromExtractedTermsCommand normalized = normalize(command);
    return scheduleGenerateCandidates(
        new GenerateCandidatesJobCommand(null, null, normalized, currentUserIdOrNull()),
        "Generate term index candidates");
  }

  public PollableFuture<TriageExtractedTermsResult> scheduleTriageExtractedTerms(
      TriageExtractedTermsCommand command) {
    requireTermManager();
    TriageExtractedTermsCommand normalized = normalize(command);
    QuartzJobInfo<TriageExtractedTermsCommand, TriageExtractedTermsResult> quartzJobInfo =
        QuartzJobInfo.newBuilder(TermIndexExtractedTermTriageJob.class)
            .withInput(normalized)
            .withMessage("Triage extracted term index terms")
            .withRequestRecovery(true)
            .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  @Transactional
  public GenerateCandidatesResult generateCandidates(GenerateCandidatesJobCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("candidate generation command is required");
    }
    if (command.generateCandidatesCommand() != null) {
      return generateCandidatesForGlossaryInternal(
          command.glossaryId(), command.generateCandidatesCommand());
    }
    return generateCandidatesFromExtractedTermsInternal(
        command.generateCandidatesFromExtractedTermsCommand(),
        userByIdOrNull(command.requestedByUserId()));
  }

  private PollableFuture<GenerateCandidatesResult> scheduleGenerateCandidates(
      GenerateCandidatesJobCommand command, String message) {
    QuartzJobInfo<GenerateCandidatesJobCommand, GenerateCandidatesResult> quartzJobInfo =
        QuartzJobInfo.newBuilder(TermIndexCandidateGenerationJob.class)
            .withInput(command)
            .withMessage(message)
            .withRequestRecovery(true)
            .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  @Transactional
  GenerateCandidatesResult generateCandidatesFromExtractedTermsInternal(
      GenerateCandidatesFromExtractedTermsCommand command) {
    return generateCandidatesFromExtractedTermsInternal(command, currentUserOrNull());
  }

  @Transactional
  GenerateCandidatesResult generateCandidatesFromExtractedTermsInternal(
      GenerateCandidatesFromExtractedTermsCommand command, User reviewChangedByUser) {
    GenerateCandidatesFromExtractedTermsCommand normalized = normalize(command);
    List<Long> repositoryIds = repositoryIdsOrSentinel(normalized.repositoryIds());
    boolean repositoryIdsEmpty = normalized.repositoryIds().isEmpty();
    CandidateFieldOverrides overrides = normalized.overrides();

    List<TermIndexExtractedTermRepository.SearchRow> rows =
        termIndexExtractedTermRepository.findCandidateGenerationRowsByIdIn(
            normalized.termIndexExtractedTermIds(), repositoryIdsEmpty, repositoryIds);

    Map<Long, List<GeneratedCandidateFields>> candidateFieldsByExtractedTermId =
        candidateFieldsByExtractedTermId(rows, repositoryIdsEmpty, repositoryIds, overrides);
    List<SeededTermView> generatedCandidates = new ArrayList<>(rows.size());
    int createdCandidateCount = 0;
    int updatedCandidateCount = 0;
    for (TermIndexExtractedTermRepository.SearchRow row : rows) {
      for (GeneratedCandidateFields fields :
          candidateFieldsForRow(candidateFieldsByExtractedTermId, row)) {
        CandidateWriteResult writeResult =
            upsertExtractionCandidate(row, fields, reviewChangedByUser);
        if (writeResult.created()) {
          createdCandidateCount++;
        } else {
          updatedCandidateCount++;
        }
        generatedCandidates.add(toSeededTermView(writeResult.candidate()));
      }
    }

    return new GenerateCandidatesResult(
        generatedCandidates.size(),
        createdCandidateCount,
        updatedCandidateCount,
        generatedCandidates);
  }

  public TriageExtractedTermsResult triageExtractedTerms(TriageExtractedTermsCommand command) {
    return triageExtractedTerms(command, null);
  }

  public TriageExtractedTermsResult triageExtractedTerms(
      TriageExtractedTermsCommand command, Long pollableTaskId) {
    TriageExtractedTermsCommand normalized = normalize(command);
    List<Long> repositoryIds = repositoryIdsOrSentinel(normalized.repositoryIds());
    boolean repositoryIdsEmpty = normalized.repositoryIds().isEmpty();
    List<TermIndexExtractedTermRepository.SearchRow> rows =
        findRowsForTriage(normalized, repositoryIdsEmpty, repositoryIds);
    boolean overwriteHumanReview = Boolean.TRUE.equals(normalized.overwriteHumanReview());
    List<TermIndexExtractedTermRepository.SearchRow> reviewableRows =
        rows.stream()
            .filter(
                row ->
                    overwriteHumanReview
                        || !TermIndexReview.AUTHORITY_HUMAN.equals(row.getReviewAuthority()))
            .toList();
    int skippedHumanReviewedCount = rows.size() - reviewableRows.size();

    List<TriagedExtractedTermView> resultEntries = new ArrayList<>();
    int acceptedCount = 0;
    int toReviewCount = 0;
    int rejectedCount = 0;
    int updatedEntryCount = 0;
    int processedEntryCount = 0;
    int batchCount = ceilDiv(reviewableRows.size(), AI_CANDIDATE_BATCH_SIZE);
    int completedBatchCount = 0;

    updateTriageProgress(
        pollableTaskId,
        new TriageProgressView(
            "TRIAGE_EXTRACTED_TERMS",
            batchCount == 0 ? "COMPLETED" : "RUNNING",
            rows.size(),
            reviewableRows.size(),
            processedEntryCount,
            updatedEntryCount,
            acceptedCount,
            toReviewCount,
            rejectedCount,
            skippedHumanReviewedCount,
            batchCount,
            completedBatchCount));

    for (int start = 0; start < reviewableRows.size(); start += AI_CANDIDATE_BATCH_SIZE) {
      List<TermIndexExtractedTermRepository.SearchRow> batch =
          reviewableRows.subList(
              start, Math.min(reviewableRows.size(), start + AI_CANDIDATE_BATCH_SIZE));
      List<GlossaryAiExtractionService.CandidateSignal> signals =
          batch.stream()
              .map(row -> toCandidateSignal(row, repositoryIdsEmpty, repositoryIds))
              .toList();
      List<TriagedExtractedTermView> triagedBatch;
      try {
        List<GlossaryAiExtractionService.AiExtractedTermReviewView> reviews =
            reviewExtractedTermsWithRetry(signals);
        triagedBatch =
            applyAiExtractedTermReviewsInNewTransaction(batch, reviews, overwriteHumanReview);
      } catch (RuntimeException e) {
        updateTriageProgress(
            pollableTaskId,
            new TriageProgressView(
                "TRIAGE_EXTRACTED_TERMS",
                "FAILED",
                rows.size(),
                reviewableRows.size(),
                processedEntryCount,
                updatedEntryCount,
                acceptedCount,
                toReviewCount,
                rejectedCount,
                skippedHumanReviewedCount,
                batchCount,
                completedBatchCount));
        throw new IllegalStateException(
            "AI review failed for extracted term batch "
                + (completedBatchCount + 1)
                + " of "
                + batchCount
                + " after "
                + completedBatchCount
                + " completed batches",
            e);
      }

      addResultEntries(resultEntries, triagedBatch);
      updatedEntryCount += triagedBatch.size();
      processedEntryCount += batch.size();

      for (TriagedExtractedTermView triagedTerm : triagedBatch) {
        switch (triagedTerm.reviewStatus()) {
          case TermIndexReview.STATUS_ACCEPTED -> acceptedCount++;
          case TermIndexReview.STATUS_REJECTED -> rejectedCount++;
          default -> toReviewCount++;
        }
      }
      completedBatchCount++;
      updateTriageProgress(
          pollableTaskId,
          new TriageProgressView(
              "TRIAGE_EXTRACTED_TERMS",
              completedBatchCount >= batchCount ? "COMPLETED" : "RUNNING",
              rows.size(),
              reviewableRows.size(),
              processedEntryCount,
              updatedEntryCount,
              acceptedCount,
              toReviewCount,
              rejectedCount,
              skippedHumanReviewedCount,
              batchCount,
              completedBatchCount));
    }

    return new TriageExtractedTermsResult(
        rows.size(),
        processedEntryCount,
        updatedEntryCount,
        acceptedCount,
        toReviewCount,
        rejectedCount,
        skippedHumanReviewedCount,
        resultEntries);
  }

  @Transactional
  public SeedResult importCandidatesForGlossary(Long glossaryId, CandidateImportCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    CandidateImportCommand normalized = normalize(command);
    List<SeedTermInput> candidates = parseCandidateImportRows(normalized);
    return seedTermsInternal(
        normalize(new SeedCommand(scopeCandidatesToGlossary(glossary.getId(), candidates))));
  }

  @Transactional(readOnly = true)
  public CandidateExportResult exportCandidatesForGlossary(
      Long glossaryId, CandidateExportCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    CandidateExportCommand normalized = normalize(command);
    List<Long> scopeRepositoryIds = resolveScopeRepositoryIds(glossary);
    List<TermIndexCandidate> candidates =
        termIndexCandidateRepository.findForGlossaryCandidateExport(
            false,
            repositoryIdsOrSentinel(scopeRepositoryIds),
            normalizeOptional(normalized.searchQuery()),
            TermIndexReview.STATUS_FILTER_NON_REJECTED,
            PageRequest.of(0, normalizeCandidateExportLimit(normalized.limit())));
    candidates =
        candidates.stream()
            .filter(candidate -> candidateVisibleForGlossary(candidate, glossary.getId()))
            .toList();
    CandidateExportDocument document =
        new CandidateExportDocument(
            new CandidateExportInfo(
                glossary.getId(),
                glossary.getName(),
                normalizeOptional(normalized.searchQuery()),
                candidates.size()),
            candidates.stream().map(this::toSeedTermInput).toList());
    String filename =
        slugify(glossary.getName()) + "-term-index-candidates." + CANDIDATE_EXPORT_FORMAT_JSON;
    return new CandidateExportResult(
        CANDIDATE_EXPORT_FORMAT_JSON,
        filename,
        objectMapper.writeValueAsStringUnchecked(document),
        candidates.size());
  }

  private SeedResult seedTermsInternal(SeedCommand normalized) {
    List<SeededTermView> seededTerms = new ArrayList<>(normalized.terms().size());
    int createdCandidateCount = 0;
    int updatedCandidateCount = 0;

    for (SeedTermInput term : normalized.terms()) {
      String normalizedKey = normalizeCandidateKey(term.term());
      if (normalizedKey == null) {
        throw new IllegalArgumentException("term is required");
      }
      String sourceLocaleTag =
          normalizeOptional(term.sourceLocaleTag()) == null
              ? TermIndexExtractedTerm.SOURCE_LOCALE_ROOT
              : normalizeOptional(term.sourceLocaleTag());
      String displayTerm = normalizeRequired(term.term(), "term");
      TermIndexExtractedTerm extractedTerm =
          termIndexExtractedTermRepository
              .findBySourceLocaleTagAndNormalizedKey(sourceLocaleTag, normalizedKey)
              .orElse(null);

      String sourceType =
          truncate(
              normalizeOptional(term.sourceType()) == null
                  ? TermIndexCandidate.SOURCE_TYPE_EXTERNAL
                  : normalizeOptional(term.sourceType()).toUpperCase(Locale.ROOT),
              64);
      String sourceName = normalizeSourceName(term.sourceName());
      String metadataJson =
          term.metadata() == null || term.metadata().isEmpty()
              ? null
              : objectMapper.writeValueAsStringUnchecked(term.metadata());
      String candidateHash =
          candidateHash(
              sourceType,
              sourceName,
              term.sourceExternalId(),
              sourceLocaleTag,
              normalizedKey,
              displayTerm,
              term.label(),
              term.definition(),
              term.rationale(),
              metadataJson);
      Optional<TermIndexCandidate> existingCandidate =
          findCandidate(sourceType, sourceName, candidateHash);
      TermIndexCandidate candidate =
          existingCandidate.orElseGet(
              () -> {
                TermIndexCandidate next = new TermIndexCandidate();
                next.setSourceType(sourceType);
                next.setSourceName(sourceName);
                next.setCandidateHash(candidateHash);
                return next;
              });
      if (existingCandidate.isPresent()) {
        updatedCandidateCount++;
      } else {
        createdCandidateCount++;
      }
      candidate.setTermIndexExtractedTerm(extractedTerm);
      candidate.setSourceLocaleTag(sourceLocaleTag);
      candidate.setNormalizedKey(normalizedKey);
      candidate.setTerm(displayTerm);
      candidate.setLabel(truncate(normalizeOptional(term.label()), 512));
      candidate.setSourceExternalId(truncate(normalizeOptional(term.sourceExternalId()), 255));
      candidate.setConfidence(clampConfidence(term.confidence()));
      candidate.setDefinition(truncate(normalizeOptional(term.definition()), 2048));
      candidate.setRationale(truncate(normalizeOptional(term.rationale()), 2048));
      candidate.setTermType(
          normalizeKnownValue(term.termType(), GlossaryTermMetadata.TERM_TYPES, null));
      candidate.setPartOfSpeech(truncate(normalizeOptional(term.partOfSpeech()), 64));
      candidate.setEnforcement(
          normalizeKnownValue(term.enforcement(), GlossaryTermMetadata.ENFORCEMENTS, null));
      candidate.setDoNotTranslate(term.doNotTranslate());
      candidate.setMetadataJson(metadataJson);
      applySeedReview(candidate, sourceType, term);
      termIndexCandidateRepository.save(candidate);

      seededTerms.add(toSeededTermView(candidate));
    }

    return new SeedResult(
        seededTerms.size(), createdCandidateCount, updatedCandidateCount, seededTerms);
  }

  private SuggestionView toSuggestion(
      SuggestionAccumulator accumulator,
      TermIndexCandidate candidate,
      List<Long> scopeRepositoryIds,
      SuggestionState state) {
    List<TermIndexOccurrenceRepository.DetailRow> examples =
        accumulator.termIndexExtractedTermId() == null
            ? List.of()
            : termIndexOccurrenceRepository.findDetailsByTermIndexExtractedTermId(
                accumulator.termIndexExtractedTermId(),
                scopeRepositoryIds.isEmpty(),
                repositoryIdsOrSentinel(scopeRepositoryIds),
                null,
                PageRequest.of(0, EXAMPLE_LIMIT));
    List<OccurrenceView> occurrenceViews = examples.stream().map(this::toOccurrenceView).toList();
    String definition =
        coalesce(normalizeOptional(candidate.getDefinition()), heuristicDefinition(accumulator));
    String rationale =
        coalesce(normalizeOptional(candidate.getRationale()), heuristicRationale(accumulator));
    int confidence =
        Optional.ofNullable(clampConfidence(candidate.getConfidence()))
            .orElseGet(() -> heuristicConfidence(accumulator));

    return new SuggestionView(
        candidate.getId(),
        accumulator.termIndexExtractedTermId(),
        accumulator.normalizedKey(),
        accumulator.displayTerm(),
        accumulator.label(),
        accumulator.sourceLocaleTag(),
        accumulator.occurrenceCount(),
        accumulator.repositoryCount(),
        accumulator.sourceCount(),
        accumulator.extractedTermMatchCount(),
        confidence,
        definition,
        rationale,
        normalizeKnownValue(
            candidate.getTermType(),
            GlossaryTermMetadata.TERM_TYPES,
            suggestTermType(accumulator.displayTerm())),
        GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
        normalizeOptional(candidate.getPartOfSpeech()),
        normalizeKnownValue(
            candidate.getEnforcement(),
            GlossaryTermMetadata.ENFORCEMENTS,
            GlossaryTermMetadata.ENFORCEMENT_SOFT),
        candidate.getDoNotTranslate() == null
            ? shouldPreserveSource(accumulator.displayTerm())
            : candidate.getDoNotTranslate(),
        occurrenceViews,
        List.of(toCandidateSourceView(candidate)),
        accumulator.lastSignalAt(),
        candidate.getReviewStatus(),
        candidate.getReviewAuthority(),
        candidate.getReviewReason(),
        candidate.getReviewRationale(),
        candidate.getReviewConfidence(),
        candidate.getReviewChangedAt(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getId(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getUsername(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getCommonName(),
        state.reviewStatus(),
        state.glossaryPresence(),
        "HEURISTIC");
  }

  private List<SuggestionView> refineWithAi(List<SuggestionView> heuristicSuggestions, int limit) {
    if (heuristicSuggestions.isEmpty()) {
      return List.of();
    }
    List<GlossaryAiExtractionService.CandidateSignal> signals =
        heuristicSuggestions.stream()
            .limit(limit)
            .map(
                suggestion ->
                    new GlossaryAiExtractionService.CandidateSignal(
                        String.valueOf(suggestion.termIndexCandidateId()),
                        suggestion.term(),
                        Math.toIntExact(Math.min(Integer.MAX_VALUE, suggestion.occurrenceCount())),
                        suggestion.repositoryCount(),
                        suggestion.examples().stream()
                            .map(OccurrenceView::repositoryName)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList(),
                        sampleSources(suggestion),
                        suggestion.suggestedTermType()))
            .toList();

    List<GlossaryAiExtractionService.AiCandidateView> aiCandidates;
    try {
      aiCandidates = glossaryAiExtractionService.refineCandidates(signals);
    } catch (RuntimeException ignored) {
      return heuristicSuggestions.stream().limit(limit).toList();
    }
    if (aiCandidates.isEmpty()) {
      return heuristicSuggestions.stream().limit(limit).toList();
    }

    Map<String, SuggestionView> heuristicByKey =
        heuristicSuggestions.stream()
            .collect(
                Collectors.toMap(
                    suggestion -> suggestion.normalizedKey(),
                    suggestion -> suggestion,
                    (left, ignored) -> left,
                    LinkedHashMap::new));
    return aiCandidates.stream()
        .map(
            aiCandidate -> {
              String key = normalizeCandidateKey(aiCandidate.term());
              SuggestionView heuristic = key == null ? null : heuristicByKey.get(key);
              if (heuristic == null) {
                return null;
              }
              return new SuggestionView(
                  heuristic.termIndexCandidateId(),
                  heuristic.termIndexExtractedTermId(),
                  heuristic.normalizedKey(),
                  heuristic.term(),
                  heuristic.label(),
                  heuristic.sourceLocaleTag(),
                  heuristic.occurrenceCount(),
                  heuristic.repositoryCount(),
                  heuristic.sourceCount(),
                  heuristic.extractedTermMatchCount(),
                  clampConfidence(aiCandidate.confidence()) == null
                      ? 75
                      : clampConfidence(aiCandidate.confidence()),
                  coalesce(normalizeOptional(aiCandidate.definition()), heuristic.definition()),
                  coalesce(normalizeOptional(aiCandidate.rationale()), heuristic.rationale()),
                  normalizeKnownValue(
                      aiCandidate.termType(),
                      GlossaryTermMetadata.TERM_TYPES,
                      heuristic.suggestedTermType()),
                  GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
                  normalizeOptional(aiCandidate.partOfSpeech()),
                  normalizeKnownValue(
                      aiCandidate.enforcement(),
                      GlossaryTermMetadata.ENFORCEMENTS,
                      heuristic.suggestedEnforcement()),
                  aiCandidate.doNotTranslate() == null
                      ? heuristic.suggestedDoNotTranslate()
                      : aiCandidate.doNotTranslate(),
                  heuristic.examples(),
                  heuristic.sources(),
                  heuristic.lastSignalAt(),
                  heuristic.candidateReviewStatus(),
                  heuristic.candidateReviewAuthority(),
                  heuristic.candidateReviewReason(),
                  heuristic.candidateReviewRationale(),
                  heuristic.candidateReviewConfidence(),
                  heuristic.candidateReviewChangedAt(),
                  heuristic.candidateReviewChangedByUserId(),
                  heuristic.candidateReviewChangedByUsername(),
                  heuristic.candidateReviewChangedByCommonName(),
                  heuristic.reviewStatus(),
                  heuristic.glossaryPresence(),
                  "AI_REVIEW");
            })
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparingInt(SuggestionView::confidence)
                .reversed()
                .thenComparing(Comparator.comparingInt(SuggestionView::repositoryCount).reversed())
                .thenComparing(Comparator.comparingLong(SuggestionView::occurrenceCount).reversed())
                .thenComparing(SuggestionView::term, String.CASE_INSENSITIVE_ORDER))
        .limit(limit)
        .toList();
  }

  private List<String> sampleSources(SuggestionView suggestion) {
    List<String> samples = new ArrayList<>();
    suggestion.examples().stream()
        .map(OccurrenceView::sourceText)
        .filter(Objects::nonNull)
        .map(source -> truncate(source, 320))
        .forEach(samples::add);
    suggestion.sources().stream()
        .flatMap(
            source -> java.util.stream.Stream.of(source.sourceName(), source.sourceExternalId()))
        .filter(Objects::nonNull)
        .map(value -> truncate(value, 320))
        .forEach(samples::add);
    return samples.stream().filter(Objects::nonNull).limit(6).toList();
  }

  private List<GlossaryTermService.EvidenceInput> defaultEvidence(
      AcceptSuggestionCommand command,
      TermIndexCandidate candidate,
      List<Long> scopeRepositoryIds) {
    List<GlossaryTermService.EvidenceInput> evidence = new ArrayList<>();
    if (normalizeOptional(command.rationale()) != null) {
      evidence.add(
          new GlossaryTermService.EvidenceInput(
              "NOTE", truncate(command.rationale(), 1024), null, null, null, null, null, null));
    }
    if (candidate.getTermIndexExtractedTerm() != null) {
      termIndexOccurrenceRepository
          .findDetailsByTermIndexExtractedTermId(
              candidate.getTermIndexExtractedTerm().getId(),
              scopeRepositoryIds.isEmpty(),
              repositoryIdsOrSentinel(scopeRepositoryIds),
              null,
              PageRequest.of(0, 3))
          .stream()
          .map(
              occurrence ->
                  new GlossaryTermService.EvidenceInput(
                      "STRING_USAGE",
                      truncate(
                          "Observed in "
                              + occurrence.getRepositoryName()
                              + ": "
                              + occurrence.getSourceText(),
                          1024),
                      null,
                      occurrence.getTmTextUnitId(),
                      null,
                      null,
                      null,
                      null))
          .forEach(evidence::add);
    }
    if (evidence.stream().noneMatch(item -> "STRING_USAGE".equalsIgnoreCase(item.evidenceType()))) {
      searchSourceExamples(command.source(), scopeRepositoryIds, LIVE_SEARCH_EXAMPLE_LIMIT).stream()
          .map(
              occurrence ->
                  new GlossaryTermService.EvidenceInput(
                      "STRING_USAGE",
                      truncate(
                          "Observed in "
                              + occurrence.repositoryName()
                              + ": "
                              + occurrence.sourceText(),
                          1024),
                      null,
                      occurrence.tmTextUnitId(),
                      null,
                      null,
                      null,
                      null))
          .forEach(evidence::add);
    }
    return evidence;
  }

  private List<OccurrenceView> searchSourceExamples(
      String term, List<Long> scopeRepositoryIds, int limit) {
    String normalizedTerm = normalizeOptional(term);
    if (normalizedTerm == null || limit <= 0 || scopeRepositoryIds.isEmpty()) {
      return List.of();
    }

    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setRepositoryIds(scopeRepositoryIds);
    parameters.setForRootLocale(true);
    parameters.setRootLocaleExcluded(false);
    parameters.setPluralFormsFiltered(false);
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setLimit(limit * 4);
    parameters.setOrderByTextUnitID(true);
    parameters.setTextSearch(sourceContainsSearch(normalizedTerm));

    List<OccurrenceView> examples = new ArrayList<>();
    for (TextUnitDTO textUnit : textUnitSearcher.search(parameters)) {
      Optional<Span> span = findTermSpan(textUnit.getSource(), normalizedTerm);
      if (span.isEmpty()) {
        continue;
      }
      examples.add(toSearchedOccurrence(textUnit, span.get()));
      if (examples.size() >= limit) {
        break;
      }
    }
    return examples;
  }

  private TextUnitTextSearch sourceContainsSearch(String term) {
    TextUnitTextSearchPredicate predicate = new TextUnitTextSearchPredicate();
    predicate.setField(TextUnitTextSearchField.SOURCE);
    predicate.setSearchType(SearchType.CONTAINS);
    predicate.setValue(term);

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setPredicates(List.of(predicate));
    return textSearch;
  }

  private OccurrenceView toSearchedOccurrence(TextUnitDTO textUnit, Span span) {
    return new OccurrenceView(
        null,
        null,
        textUnit.getRepositoryName(),
        textUnit.getAssetId(),
        textUnit.getAssetPath(),
        textUnit.getTmTextUnitId(),
        textUnit.getName(),
        textUnit.getSource(),
        textUnit.getSource() == null
            ? null
            : textUnit.getSource().substring(span.start(), span.end()),
        span.start(),
        span.end(),
        SOURCE_SEARCH_METHOD,
        25);
  }

  private Optional<Span> findTermSpan(String source, String term) {
    if (source == null || term == null || term.isBlank()) {
      return Optional.empty();
    }
    String normalizedSource = source.toLowerCase(Locale.ROOT);
    String normalizedTerm = term.toLowerCase(Locale.ROOT);
    int fromIndex = 0;
    while (fromIndex < normalizedSource.length()) {
      int startIndex = normalizedSource.indexOf(normalizedTerm, fromIndex);
      if (startIndex < 0) {
        return Optional.empty();
      }
      int endIndex = startIndex + normalizedTerm.length();
      if (hasTermBoundaries(source, startIndex, endIndex)) {
        return Optional.of(new Span(startIndex, endIndex));
      }
      fromIndex = startIndex + 1;
    }
    return Optional.empty();
  }

  private boolean hasTermBoundaries(String source, int startIndex, int endIndex) {
    boolean leftBoundary =
        startIndex == 0 || !Character.isLetterOrDigit(source.codePointBefore(startIndex));
    boolean rightBoundary =
        endIndex >= source.length() || !Character.isLetterOrDigit(source.codePointAt(endIndex));
    return leftBoundary && rightBoundary;
  }

  private List<GlossaryTermService.EvidenceInput> toEvidenceInputs(
      List<AcceptSuggestionEvidenceInput> evidence) {
    return evidence.stream()
        .map(
            item ->
                new GlossaryTermService.EvidenceInput(
                    item.evidenceType(),
                    item.caption(),
                    item.imageKey(),
                    item.tmTextUnitId(),
                    item.cropX(),
                    item.cropY(),
                    item.cropWidth(),
                    item.cropHeight()))
        .toList();
  }

  private Set<Long> linkedCandidateIds(Long glossaryId, List<Long> candidateIds) {
    if (candidateIds.isEmpty()) {
      return Set.of();
    }
    return new LinkedHashSet<>(
        glossaryTermIndexLinkRepository.findLinkedTermIndexCandidateIdsByGlossaryId(
            glossaryId, candidateIds));
  }

  private Set<Long> ignoredCandidateIds(Long glossaryId, List<Long> candidateIds) {
    if (candidateIds.isEmpty()) {
      return Set.of();
    }
    return glossaryTermIndexDecisionRepository
        .findByGlossaryIdAndTermIndexCandidateIdIn(glossaryId, candidateIds)
        .stream()
        .filter(
            decision -> GlossaryTermIndexDecision.DECISION_IGNORED.equals(decision.getDecision()))
        .map(decision -> decision.getTermIndexCandidate().getId())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> existingNormalizedTermKeys(Long glossaryId) {
    return glossaryTermMetadataRepository.findByGlossaryId(glossaryId).stream()
        .map(GlossaryTermMetadata::getTmTextUnit)
        .filter(Objects::nonNull)
        .map(tmTextUnit -> normalizeCandidateKey(tmTextUnit.getContent()))
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private List<Long> resolveScopeRepositoryIds(Glossary glossary) {
    Set<Long> backingRepositoryIds =
        new LinkedHashSet<>(glossaryRepository.findBackingRepositoryIds());
    if (glossary.getBackingRepository() != null) {
      backingRepositoryIds.add(glossary.getBackingRepository().getId());
    }

    if (Glossary.SCOPE_MODE_SELECTED_REPOSITORIES.equals(glossary.getScopeMode())) {
      return glossary.getRepositories().stream()
          .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
          .filter(repository -> !Boolean.TRUE.equals(repository.getHidden()))
          .map(Repository::getId)
          .filter(id -> !backingRepositoryIds.contains(id))
          .distinct()
          .toList();
    }

    Set<Long> excludedRepositoryIds =
        glossary.getExcludedRepositories().stream()
            .map(Repository::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc().stream()
        .map(Repository::getId)
        .filter(id -> !excludedRepositoryIds.contains(id))
        .filter(id -> !backingRepositoryIds.contains(id))
        .distinct()
        .toList();
  }

  private List<TermIndexExtractedTermRepository.SearchRow> findRowsForTriage(
      TriageExtractedTermsCommand command, boolean repositoryIdsEmpty, List<Long> repositoryIds) {
    if (!command.termIndexExtractedTermIds().isEmpty()) {
      return termIndexExtractedTermRepository.findSearchRowsByIdIn(
          command.termIndexExtractedTermIds(), repositoryIdsEmpty, repositoryIds);
    }

    return termIndexExtractedTermRepository.searchEntries(
        repositoryIdsEmpty,
        repositoryIds,
        normalizeOptional(command.searchQuery()),
        normalizeOptional(command.extractionMethod()),
        command.reviewStatusFilter(),
        command.reviewAuthorityFilter(),
        command.minOccurrences(),
        command.lastOccurrenceAfter(),
        command.lastOccurrenceBefore(),
        command.reviewChangedAfter(),
        command.reviewChangedBefore(),
        "HITS",
        PageRequest.of(0, normalizeGenerationLimit(command.limit())));
  }

  private List<GlossaryAiExtractionService.AiExtractedTermReviewView> reviewExtractedTermsWithRetry(
      List<GlossaryAiExtractionService.CandidateSignal> signals) {
    RuntimeException lastException = null;
    for (int attempt = 1; attempt <= AI_REVIEW_MAX_ATTEMPTS; attempt++) {
      try {
        return glossaryAiExtractionService.reviewExtractedTerms(signals);
      } catch (RuntimeException e) {
        lastException = e;
        if (attempt >= AI_REVIEW_MAX_ATTEMPTS) {
          break;
        }
        sleepBeforeAiReviewRetry(attempt);
      }
    }
    throw lastException == null ? new IllegalStateException("AI review failed") : lastException;
  }

  private void sleepBeforeAiReviewRetry(int attempt) {
    try {
      Thread.sleep(AI_REVIEW_RETRY_BACKOFF_MILLIS * attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to retry AI review", e);
    }
  }

  private List<TriagedExtractedTermView> applyAiExtractedTermReviewsInNewTransaction(
      List<TermIndexExtractedTermRepository.SearchRow> rows,
      List<GlossaryAiExtractionService.AiExtractedTermReviewView> reviews,
      boolean overwriteHumanReview) {
    return Objects.requireNonNull(
        requiresNewTransactionTemplate.execute(
            status -> applyAiExtractedTermReviews(rows, reviews, overwriteHumanReview)));
  }

  private void addResultEntries(
      List<TriagedExtractedTermView> resultEntries, List<TriagedExtractedTermView> triagedBatch) {
    if (resultEntries.size() >= TRIAGE_RESULT_ENTRY_LIMIT || triagedBatch.isEmpty()) {
      return;
    }
    int remaining = TRIAGE_RESULT_ENTRY_LIMIT - resultEntries.size();
    resultEntries.addAll(triagedBatch.subList(0, Math.min(remaining, triagedBatch.size())));
  }

  private List<TriagedExtractedTermView> applyAiExtractedTermReviews(
      List<TermIndexExtractedTermRepository.SearchRow> rows,
      List<GlossaryAiExtractionService.AiExtractedTermReviewView> reviews,
      boolean overwriteHumanReview) {
    if (reviews.isEmpty()) {
      return List.of();
    }
    Map<Long, TermIndexExtractedTerm> termsById =
        termIndexExtractedTermRepository
            .findAllById(
                rows.stream().map(TermIndexExtractedTermRepository.SearchRow::getId).toList())
            .stream()
            .collect(Collectors.toMap(TermIndexExtractedTerm::getId, term -> term));
    List<TermIndexExtractedTerm> updatedTerms = new ArrayList<>();
    List<TriagedExtractedTermView> triagedTerms = new ArrayList<>();
    for (GlossaryAiExtractionService.AiExtractedTermReviewView review : reviews) {
      Long extractedTermId = parseLong(review.inputId());
      if (extractedTermId == null) {
        continue;
      }
      TermIndexExtractedTerm extractedTerm = termsById.get(extractedTermId);
      if (extractedTerm == null
          || (!overwriteHumanReview
              && TermIndexReview.AUTHORITY_HUMAN.equals(extractedTerm.getReviewAuthority()))) {
        continue;
      }
      String reviewStatus = normalizeCandidateReviewStatus(review.reviewStatus(), null);
      if (reviewStatus == null) {
        continue;
      }
      extractedTerm.setReviewStatus(reviewStatus);
      extractedTerm.setReviewAuthority(TermIndexReview.AUTHORITY_AI);
      extractedTerm.setReviewReason(normalizeReviewReason(review.reviewReason()));
      extractedTerm.setReviewRationale(truncate(normalizeOptional(review.reviewRationale()), 2048));
      extractedTerm.setReviewConfidence(clampConfidence(review.reviewConfidence()));
      extractedTerm.setReviewChangedAt(ZonedDateTime.now());
      extractedTerm.setReviewChangedByUser(null);
      updatedTerms.add(extractedTerm);
      triagedTerms.add(
          new TriagedExtractedTermView(
              extractedTerm.getId(),
              extractedTerm.getDisplayTerm(),
              extractedTerm.getNormalizedKey(),
              extractedTerm.getReviewStatus(),
              extractedTerm.getReviewReason(),
              extractedTerm.getReviewRationale(),
              extractedTerm.getReviewConfidence()));
    }
    if (!updatedTerms.isEmpty()) {
      termIndexExtractedTermRepository.saveAll(updatedTerms);
    }
    return triagedTerms;
  }

  private CandidateWriteResult upsertExtractionCandidate(
      TermIndexExtractedTermRepository.SearchRow row) {
    return upsertExtractionCandidate(row, GeneratedCandidateFields.defaultFields(), null);
  }

  private CandidateWriteResult upsertExtractionCandidate(
      TermIndexExtractedTermRepository.SearchRow row, GeneratedCandidateFields fields) {
    return upsertExtractionCandidate(row, fields, null);
  }

  private CandidateWriteResult upsertExtractionCandidate(
      TermIndexExtractedTermRepository.SearchRow row,
      GeneratedCandidateFields fields,
      User reviewChangedByUser) {
    CandidateFieldOverrides overrides = fields == null ? null : fields.overrides();
    String sourceLocaleTag =
        coalesce(
            normalizeOptional(row.getSourceLocaleTag()), TermIndexExtractedTerm.SOURCE_LOCALE_ROOT);
    String label = fields == null ? null : truncate(normalizeOptional(fields.label()), 512);
    String sourceExternalId =
        fields == null ? null : truncate(normalizeOptional(fields.sourceExternalId()), 255);
    String hashLabel = fields != null && fields.distinctIdentity() ? label : null;
    String hashSourceExternalId =
        fields != null && fields.distinctIdentity() ? sourceExternalId : null;
    String candidateHash =
        candidateHash(
            TermIndexCandidate.SOURCE_TYPE_EXTRACTION,
            EXTRACTION_SOURCE_NAME,
            hashSourceExternalId,
            sourceLocaleTag,
            row.getNormalizedKey(),
            row.getDisplayTerm(),
            hashLabel,
            null,
            null,
            null);
    Optional<TermIndexCandidate> existingCandidate =
        termIndexCandidateRepository.findBySourceTypeAndSourceNameAndCandidateHash(
            TermIndexCandidate.SOURCE_TYPE_EXTRACTION, EXTRACTION_SOURCE_NAME, candidateHash);
    TermIndexCandidate candidate =
        existingCandidate.orElseGet(
            () -> {
              TermIndexCandidate next = new TermIndexCandidate();
              next.setSourceType(TermIndexCandidate.SOURCE_TYPE_EXTRACTION);
              next.setSourceName(EXTRACTION_SOURCE_NAME);
              next.setCandidateHash(candidateHash);
              return next;
            });
    candidate.setTermIndexExtractedTerm(
        termIndexExtractedTermRepository.getReferenceById(row.getId()));
    candidate.setSourceLocaleTag(sourceLocaleTag);
    candidate.setNormalizedKey(row.getNormalizedKey());
    candidate.setTerm(row.getDisplayTerm());
    candidate.setLabel(coalesce(label, candidate.getLabel()));
    candidate.setSourceExternalId(coalesce(sourceExternalId, candidate.getSourceExternalId()));
    candidate.setConfidence(
        coalesce(
            overrides == null ? null : overrides.confidence(),
            coalesce(
                candidate.getConfidence(),
                heuristicConfidence(row.getOccurrenceCount(), row.getRepositoryCount()))));
    candidate.setDefinition(
        coalesce(overrides == null ? null : overrides.definition(), candidate.getDefinition()));
    candidate.setRationale(
        coalesce(overrides == null ? null : overrides.rationale(), candidate.getRationale()));
    candidate.setTermType(
        coalesce(
            overrides == null ? null : overrides.termType(),
            coalesce(candidate.getTermType(), suggestTermType(row.getDisplayTerm()))));
    candidate.setPartOfSpeech(
        coalesce(overrides == null ? null : overrides.partOfSpeech(), candidate.getPartOfSpeech()));
    candidate.setEnforcement(
        coalesce(
            overrides == null ? null : overrides.enforcement(),
            coalesce(candidate.getEnforcement(), GlossaryTermMetadata.ENFORCEMENT_SOFT)));
    candidate.setDoNotTranslate(
        coalesce(
            overrides == null ? null : overrides.doNotTranslate(),
            coalesce(candidate.getDoNotTranslate(), shouldPreserveSource(row.getDisplayTerm()))));
    applyExtractionCandidateReview(candidate, overrides, reviewChangedByUser);
    return new CandidateWriteResult(
        termIndexCandidateRepository.save(candidate), existingCandidate.isEmpty());
  }

  private void applyExtractionCandidateReview(
      TermIndexCandidate candidate, CandidateFieldOverrides overrides, User reviewChangedByUser) {
    if (overrides != null && overrides.reviewStatus() != null) {
      candidate.setReviewStatus(overrides.reviewStatus());
      candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
      candidate.setReviewReason(overrides.reviewReason());
      candidate.setReviewRationale(overrides.reviewRationale());
      candidate.setReviewConfidence(overrides.reviewConfidence());
      candidate.setReviewChangedAt(ZonedDateTime.now());
      candidate.setReviewChangedByUser(reviewChangedByUser);
      return;
    }
    applyDefaultReview(candidate);
  }

  private void applyDefaultReview(TermIndexCandidate candidate) {
    if (candidate.getReviewStatus() == null) {
      candidate.setReviewStatus(TermIndexReview.STATUS_TO_REVIEW);
    }
    if (candidate.getReviewAuthority() == null) {
      candidate.setReviewAuthority(TermIndexReview.AUTHORITY_NONE);
    }
  }

  private List<GeneratedCandidateFields> candidateFieldsForRow(
      Map<Long, List<GeneratedCandidateFields>> candidateFieldsByExtractedTermId,
      TermIndexExtractedTermRepository.SearchRow row) {
    List<GeneratedCandidateFields> fields = candidateFieldsByExtractedTermId.get(row.getId());
    return fields == null || fields.isEmpty()
        ? List.of(GeneratedCandidateFields.defaultFields())
        : fields;
  }

  private Map<Long, List<GeneratedCandidateFields>> candidateFieldsByExtractedTermId(
      List<TermIndexExtractedTermRepository.SearchRow> rows,
      boolean repositoryIdsEmpty,
      List<Long> repositoryIds,
      CandidateFieldOverrides requestedOverrides) {
    if (rows.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<GlossaryAiExtractionService.AiCandidateView>> aiCandidatesByExtractedTermId =
        aiCandidatesByExtractedTermId(rows, repositoryIdsEmpty, repositoryIds);
    Map<Long, List<GeneratedCandidateFields>> candidateFieldsByExtractedTermId =
        new LinkedHashMap<>();
    for (TermIndexExtractedTermRepository.SearchRow row : rows) {
      List<GlossaryAiExtractionService.AiCandidateView> aiCandidates =
          aiCandidatesByExtractedTermId.getOrDefault(row.getId(), List.of());
      if (aiCandidates.isEmpty()) {
        if (requestedOverrides != null) {
          candidateFieldsByExtractedTermId.put(
              row.getId(),
              List.of(new GeneratedCandidateFields(null, null, false, requestedOverrides)));
        }
        continue;
      }
      boolean splitCandidate = aiCandidates.size() > 1;
      List<GeneratedCandidateFields> fields = new ArrayList<>(aiCandidates.size());
      for (int index = 0; index < aiCandidates.size(); index++) {
        GlossaryAiExtractionService.AiCandidateView aiCandidate = aiCandidates.get(index);
        fields.add(
            new GeneratedCandidateFields(
                generatedCandidateLabel(aiCandidate, splitCandidate, index),
                generatedCandidateSourceExternalId(aiCandidate, splitCandidate, index),
                splitCandidate,
                mergeCandidateFieldOverrides(requestedOverrides, aiCandidate)));
      }
      candidateFieldsByExtractedTermId.put(row.getId(), fields);
    }
    return candidateFieldsByExtractedTermId;
  }

  private Map<Long, List<GlossaryAiExtractionService.AiCandidateView>>
      aiCandidatesByExtractedTermId(
          List<TermIndexExtractedTermRepository.SearchRow> rows,
          boolean repositoryIdsEmpty,
          List<Long> repositoryIds) {
    Map<Long, List<GlossaryAiExtractionService.AiCandidateView>> aiCandidatesByExtractedTermId =
        new LinkedHashMap<>();
    Map<String, Long> extractedTermIdByNormalizedKey =
        rows.stream()
            .collect(
                Collectors.toMap(
                    TermIndexExtractedTermRepository.SearchRow::getNormalizedKey,
                    TermIndexExtractedTermRepository.SearchRow::getId,
                    (left, ignored) -> left,
                    LinkedHashMap::new));
    for (int start = 0; start < rows.size(); start += AI_CANDIDATE_BATCH_SIZE) {
      List<TermIndexExtractedTermRepository.SearchRow> batch =
          rows.subList(start, Math.min(rows.size(), start + AI_CANDIDATE_BATCH_SIZE));
      List<GlossaryAiExtractionService.CandidateSignal> signals =
          batch.stream()
              .map(row -> toCandidateSignal(row, repositoryIdsEmpty, repositoryIds))
              .toList();
      List<GlossaryAiExtractionService.AiCandidateView> aiCandidates;
      try {
        aiCandidates = glossaryAiExtractionService.enrichCandidates(signals);
      } catch (RuntimeException ignored) {
        continue;
      }
      for (GlossaryAiExtractionService.AiCandidateView aiCandidate : aiCandidates) {
        Long extractedTermId = parseLong(aiCandidate.inputId());
        if (extractedTermId == null) {
          String key = normalizeCandidateKey(aiCandidate.term());
          extractedTermId = key == null ? null : extractedTermIdByNormalizedKey.get(key);
        }
        if (extractedTermId != null) {
          aiCandidatesByExtractedTermId
              .computeIfAbsent(extractedTermId, ignored -> new ArrayList<>())
              .add(aiCandidate);
        }
      }
    }
    return aiCandidatesByExtractedTermId;
  }

  private String generatedCandidateLabel(
      GlossaryAiExtractionService.AiCandidateView aiCandidate, boolean splitCandidate, int index) {
    String label = normalizeOptional(aiCandidate.label());
    if (label != null || !splitCandidate) {
      return label;
    }
    String termType =
        normalizeKnownValue(aiCandidate.termType(), GlossaryTermMetadata.TERM_TYPES, null);
    if (termType != null) {
      return termType.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
    String partOfSpeech = normalizeOptional(aiCandidate.partOfSpeech());
    if (partOfSpeech != null) {
      return partOfSpeech.toLowerCase(Locale.ROOT);
    }
    return "Sense " + (index + 1);
  }

  private String generatedCandidateSourceExternalId(
      GlossaryAiExtractionService.AiCandidateView aiCandidate, boolean splitCandidate, int index) {
    String sourceExternalId = normalizeOptional(aiCandidate.sourceExternalId());
    if (sourceExternalId != null || !splitCandidate) {
      return sourceExternalId;
    }
    return slugify(
        coalesce(generatedCandidateLabel(aiCandidate, true, index), "sense-" + (index + 1)));
  }

  private GlossaryAiExtractionService.CandidateSignal toCandidateSignal(
      TermIndexExtractedTermRepository.SearchRow row,
      boolean repositoryIdsEmpty,
      List<Long> repositoryIds) {
    List<TermIndexOccurrenceRepository.DetailRow> examples =
        termIndexOccurrenceRepository.findDetailsByTermIndexExtractedTermId(
            row.getId(),
            repositoryIdsEmpty,
            repositoryIds,
            null,
            PageRequest.of(0, AI_SIGNAL_EXAMPLE_LIMIT));
    List<String> repositories =
        examples.stream()
            .map(TermIndexOccurrenceRepository.DetailRow::getRepositoryName)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    List<String> sampleSources =
        examples.stream()
            .map(TermIndexOccurrenceRepository.DetailRow::getSourceText)
            .filter(Objects::nonNull)
            .map(source -> truncate(source, 320))
            .distinct()
            .toList();
    return new GlossaryAiExtractionService.CandidateSignal(
        String.valueOf(row.getId()),
        row.getDisplayTerm(),
        Math.toIntExact(Math.min(Integer.MAX_VALUE, nullToZero(row.getOccurrenceCount()))),
        Math.toIntExact(Math.min(Integer.MAX_VALUE, nullToZero(row.getRepositoryCount()))),
        repositories,
        sampleSources,
        suggestTermType(row.getDisplayTerm()));
  }

  private CandidateFieldOverrides mergeCandidateFieldOverrides(
      CandidateFieldOverrides requestedOverrides,
      GlossaryAiExtractionService.AiCandidateView aiCandidate) {
    if (requestedOverrides == null && aiCandidate == null) {
      return null;
    }
    return new CandidateFieldOverrides(
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.definition(),
            truncate(
                normalizeOptional(aiCandidate == null ? null : aiCandidate.definition()), 2048)),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.rationale(),
            truncate(
                normalizeOptional(aiCandidate == null ? null : aiCandidate.rationale()), 2048)),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.termType(),
            normalizeKnownValue(
                aiCandidate == null ? null : aiCandidate.termType(),
                GlossaryTermMetadata.TERM_TYPES,
                null)),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.partOfSpeech(),
            truncate(
                normalizeOptional(aiCandidate == null ? null : aiCandidate.partOfSpeech()), 64)),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.enforcement(),
            normalizeKnownValue(
                aiCandidate == null ? null : aiCandidate.enforcement(),
                GlossaryTermMetadata.ENFORCEMENTS,
                null)),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.doNotTranslate(),
            aiCandidate == null ? null : aiCandidate.doNotTranslate()),
        coalesce(
            requestedOverrides == null ? null : requestedOverrides.confidence(),
            clampConfidence(aiCandidate == null ? null : aiCandidate.confidence())),
        requestedOverrides == null ? null : requestedOverrides.reviewStatus(),
        requestedOverrides == null ? null : requestedOverrides.reviewReason(),
        requestedOverrides == null ? null : requestedOverrides.reviewRationale(),
        requestedOverrides == null ? null : requestedOverrides.reviewConfidence());
  }

  private void applySeedReview(
      TermIndexCandidate candidate, String sourceType, SeedTermInput term) {
    if (TermIndexCandidate.SOURCE_TYPE_HUMAN.equals(sourceType)) {
      candidate.setReviewStatus(TermIndexReview.STATUS_ACCEPTED);
      candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
      candidate.setReviewReason(null);
      candidate.setReviewRationale(null);
      candidate.setReviewConfidence(null);
      candidate.setReviewChangedAt(ZonedDateTime.now());
      candidate.setReviewChangedByUser(currentUserOrNull());
      return;
    }
    if (!TermIndexReview.AUTHORITY_HUMAN.equals(candidate.getReviewAuthority())) {
      String reviewStatus = normalizeCandidateReviewStatus(term.reviewStatus(), null);
      if (reviewStatus != null) {
        candidate.setReviewStatus(reviewStatus);
      }
      String reviewAuthority = normalizeCandidateReviewAuthority(term.reviewAuthority(), null);
      if (reviewAuthority != null) {
        candidate.setReviewAuthority(reviewAuthority);
      }
      candidate.setReviewReason(truncate(normalizeOptional(term.reviewReason()), 64));
      candidate.setReviewRationale(truncate(normalizeOptional(term.reviewRationale()), 2048));
      candidate.setReviewConfidence(clampConfidence(term.reviewConfidence()));
      candidate.setReviewChangedAt(ZonedDateTime.now());
      candidate.setReviewChangedByUser(
          TermIndexReview.AUTHORITY_HUMAN.equals(candidate.getReviewAuthority())
              ? currentUserOrNull()
              : null);
    }
    applyDefaultReview(candidate);
  }

  private SuggestionAccumulator fromCandidateSearchRow(
      TermIndexCandidateRepository.CandidateSearchRow row, long extractedTermMatchCount) {
    return new SuggestionAccumulator(
        row.getId(),
        row.getTermIndexExtractedTermId(),
        row.getNormalizedKey(),
        row.getTerm(),
        row.getLabel(),
        row.getSourceLocaleTag(),
        nullToZero(row.getOccurrenceCount()),
        Math.toIntExact(nullToZero(row.getRepositoryCount())),
        1,
        Math.toIntExact(extractedTermMatchCount),
        row.getLastOccurrenceAt() == null
            ? row.getCandidateCreatedDate()
            : row.getLastOccurrenceAt());
  }

  private Map<String, Long> findExtractedTermMatchCountsByNormalizedKey(
      List<TermIndexCandidateRepository.CandidateSearchRow> rows, List<Long> scopeRepositoryIds) {
    List<String> normalizedKeys =
        rows.stream()
            .map(TermIndexCandidateRepository.CandidateSearchRow::getNormalizedKey)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (normalizedKeys.isEmpty()) {
      return Map.of();
    }
    return termIndexExtractedTermRepository
        .countScopedMatchesByNormalizedKeyIn(
            normalizedKeys,
            scopeRepositoryIds.isEmpty(),
            repositoryIdsOrSentinel(scopeRepositoryIds))
        .stream()
        .collect(
            Collectors.toMap(
                TermIndexExtractedTermRepository.ExtractedTermMatchCountRow::getNormalizedKey,
                row -> nullToZero(row.getExtractedTermMatchCount()),
                Long::sum,
                LinkedHashMap::new));
  }

  private OccurrenceView toOccurrenceView(TermIndexOccurrenceRepository.DetailRow row) {
    return new OccurrenceView(
        row.getId(),
        row.getRepositoryId(),
        row.getRepositoryName(),
        row.getAssetId(),
        row.getAssetPath(),
        row.getTmTextUnitId(),
        row.getTextUnitName(),
        row.getSourceText(),
        row.getMatchedText(),
        row.getStartIndex(),
        row.getEndIndex(),
        row.getExtractionMethod(),
        row.getConfidence());
  }

  private CandidateSourceView toCandidateSourceView(TermIndexCandidate candidate) {
    return new CandidateSourceView(
        candidate.getId(),
        candidate.getSourceType(),
        candidate.getSourceName(),
        candidate.getSourceExternalId(),
        candidate.getConfidence(),
        candidate.getMetadataJson(),
        candidate.getCreatedDate());
  }

  private SuggestionSearchCommand normalize(SuggestionSearchCommand command) {
    if (command == null) {
      return new SuggestionSearchCommand(
          null,
          DEFAULT_SUGGESTION_LIMIT,
          true,
          false,
          REVIEW_STATUS_NEW,
          GLOSSARY_PRESENCE_FILTER_ALL);
    }
    return new SuggestionSearchCommand(
        command.searchQuery(),
        normalizeLimit(command.limit()),
        command.useAi(),
        Boolean.TRUE.equals(command.includeReviewed()),
        normalizeReviewStatusFilter(command.reviewStatusFilter(), command.includeReviewed()),
        normalizeGlossaryPresenceFilter(command.glossaryPresenceFilter()));
  }

  private String normalizeReviewStatusFilter(String reviewStatusFilter, Boolean includeReviewed) {
    String normalized = normalizeOptional(reviewStatusFilter);
    if (normalized == null) {
      return Boolean.TRUE.equals(includeReviewed) ? REVIEW_STATUS_FILTER_ALL : REVIEW_STATUS_NEW;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case REVIEW_STATUS_NEW,
              REVIEW_STATUS_IGNORED,
              REVIEW_STATUS_ACCEPTED,
              REVIEW_STATUS_FILTER_REVIEWED,
              REVIEW_STATUS_FILTER_ALL ->
          normalized;
      default -> REVIEW_STATUS_NEW;
    };
  }

  private String normalizeGlossaryPresenceFilter(String glossaryPresenceFilter) {
    String normalized = normalizeOptional(glossaryPresenceFilter);
    if (normalized == null) {
      return GLOSSARY_PRESENCE_FILTER_ALL;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case GLOSSARY_PRESENCE_FILTER_ALL,
              GLOSSARY_PRESENCE_FILTER_IN_GLOSSARY,
              GLOSSARY_PRESENCE_NOT_IN_GLOSSARY,
              GLOSSARY_PRESENCE_LINKED,
              GLOSSARY_PRESENCE_EXISTING_TERM ->
          normalized;
      default -> GLOSSARY_PRESENCE_FILTER_ALL;
    };
  }

  private String normalizeCandidateReviewStatus(String reviewStatus, String fallback) {
    String normalized = normalizeOptional(reviewStatus);
    if (normalized == null) {
      return fallback;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.STATUS_TO_REVIEW,
              TermIndexReview.STATUS_ACCEPTED,
              TermIndexReview.STATUS_REJECTED ->
          normalized;
      default -> fallback;
    };
  }

  private String normalizeTermIndexReviewStatusFilter(String reviewStatusFilter) {
    String normalized = normalizeOptional(reviewStatusFilter);
    if (normalized == null) {
      return TermIndexReview.STATUS_TO_REVIEW;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.STATUS_FILTER_ALL,
              TermIndexReview.STATUS_FILTER_NON_REJECTED,
              TermIndexReview.STATUS_TO_REVIEW,
              TermIndexReview.STATUS_ACCEPTED,
              TermIndexReview.STATUS_REJECTED ->
          normalized;
      default -> TermIndexReview.STATUS_TO_REVIEW;
    };
  }

  private String normalizeCandidateReviewAuthority(String reviewAuthority, String fallback) {
    String normalized = normalizeOptional(reviewAuthority);
    if (normalized == null) {
      return fallback;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.AUTHORITY_NONE,
              TermIndexReview.AUTHORITY_AI,
              TermIndexReview.AUTHORITY_HUMAN ->
          normalized;
      default -> fallback;
    };
  }

  private String normalizeReviewReason(String reviewReason) {
    String normalized = normalizeOptional(reviewReason);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.REASON_STOP_WORD,
              TermIndexReview.REASON_TOO_GENERIC,
              TermIndexReview.REASON_FALSE_POSITIVE,
              TermIndexReview.REASON_OUT_OF_SCOPE,
              TermIndexReview.REASON_DUPLICATE,
              TermIndexReview.REASON_OTHER ->
          normalized;
      default -> TermIndexReview.REASON_OTHER;
    };
  }

  private boolean matchesReviewStatusFilter(String reviewStatus, String reviewStatusFilter) {
    return switch (reviewStatusFilter) {
      case REVIEW_STATUS_FILTER_ALL -> true;
      case REVIEW_STATUS_FILTER_REVIEWED -> !REVIEW_STATUS_NEW.equals(reviewStatus);
      default -> Objects.equals(reviewStatus, reviewStatusFilter);
    };
  }

  private boolean matchesGlossaryPresenceFilter(
      String glossaryPresence, String glossaryPresenceFilter) {
    return switch (glossaryPresenceFilter) {
      case GLOSSARY_PRESENCE_FILTER_ALL -> true;
      case GLOSSARY_PRESENCE_FILTER_IN_GLOSSARY ->
          GLOSSARY_PRESENCE_LINKED.equals(glossaryPresence)
              || GLOSSARY_PRESENCE_EXISTING_TERM.equals(glossaryPresence);
      default -> Objects.equals(glossaryPresence, glossaryPresenceFilter);
    };
  }

  private SuggestionState suggestionState(
      SuggestionAccumulator candidate,
      Set<Long> linkedEntryIds,
      Set<Long> ignoredEntryIds,
      Set<String> existingTermKeys) {
    String glossaryPresence = glossaryPresence(candidate, linkedEntryIds, existingTermKeys);
    String reviewStatus = reviewStatus(candidate, linkedEntryIds, ignoredEntryIds);
    return new SuggestionState(reviewStatus, glossaryPresence);
  }

  private String reviewStatus(
      SuggestionAccumulator candidate, Set<Long> linkedEntryIds, Set<Long> ignoredEntryIds) {
    if (ignoredEntryIds.contains(candidate.id())) {
      return REVIEW_STATUS_IGNORED;
    }
    if (linkedEntryIds.contains(candidate.id())) {
      return REVIEW_STATUS_ACCEPTED;
    }
    return REVIEW_STATUS_NEW;
  }

  private String glossaryPresence(
      SuggestionAccumulator candidate, Set<Long> linkedEntryIds, Set<String> existingTermKeys) {
    if (linkedEntryIds.contains(candidate.id())) {
      return GLOSSARY_PRESENCE_LINKED;
    }
    if (existingTermKeys.contains(candidate.normalizedKey())) {
      return GLOSSARY_PRESENCE_EXISTING_TERM;
    }
    return GLOSSARY_PRESENCE_NOT_IN_GLOSSARY;
  }

  private SeedCommand normalize(SeedCommand command) {
    if (command == null || command.terms() == null || command.terms().isEmpty()) {
      throw new IllegalArgumentException("terms are required");
    }
    return command;
  }

  private GenerateCandidatesCommand normalize(GenerateCandidatesCommand command) {
    if (command == null) {
      return new GenerateCandidatesCommand(null, null, 1L, DEFAULT_GENERATION_LIMIT);
    }
    return new GenerateCandidatesCommand(
        command.searchQuery(),
        command.extractionMethod(),
        Math.max(1L, command.minOccurrences() == null ? 1L : command.minOccurrences()),
        normalizeGenerationLimit(command.limit()));
  }

  private GenerateCandidatesFromExtractedTermsCommand normalize(
      GenerateCandidatesFromExtractedTermsCommand command) {
    if (command == null
        || command.termIndexExtractedTermIds() == null
        || command.termIndexExtractedTermIds().isEmpty()) {
      throw new IllegalArgumentException("termIndexEntryIds are required");
    }
    List<Long> termIndexExtractedTermIds =
        command.termIndexExtractedTermIds().stream().filter(Objects::nonNull).distinct().toList();
    if (termIndexExtractedTermIds.isEmpty()) {
      throw new IllegalArgumentException("termIndexEntryIds are required");
    }
    return new GenerateCandidatesFromExtractedTermsCommand(
        termIndexExtractedTermIds,
        normalizeRepositoryIds(command.repositoryIds()),
        normalize(command.overrides()));
  }

  private TriageExtractedTermsCommand normalize(TriageExtractedTermsCommand command) {
    if (command == null) {
      return new TriageExtractedTermsCommand(
          List.of(),
          List.of(),
          null,
          null,
          TermIndexReview.STATUS_TO_REVIEW,
          TermIndexReview.AUTHORITY_NONE,
          1L,
          DEFAULT_GENERATION_LIMIT,
          null,
          null,
          null,
          null,
          false);
    }
    List<Long> termIndexExtractedTermIds =
        command.termIndexExtractedTermIds() == null
            ? List.of()
            : command.termIndexExtractedTermIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    return new TriageExtractedTermsCommand(
        termIndexExtractedTermIds,
        normalizeRepositoryIds(command.repositoryIds()),
        command.searchQuery(),
        command.extractionMethod(),
        normalizeTermIndexReviewStatusFilter(command.reviewStatusFilter()),
        normalizeCandidateReviewAuthority(
            command.reviewAuthorityFilter(), TermIndexReview.AUTHORITY_NONE),
        Math.max(1L, command.minOccurrences() == null ? 1L : command.minOccurrences()),
        normalizeGenerationLimit(command.limit()),
        command.lastOccurrenceAfter(),
        command.lastOccurrenceBefore(),
        command.reviewChangedAfter(),
        command.reviewChangedBefore(),
        Boolean.TRUE.equals(command.overwriteHumanReview()));
  }

  private CandidateFieldOverrides normalize(CandidateFieldOverrides overrides) {
    if (overrides == null) {
      return null;
    }
    return new CandidateFieldOverrides(
        truncate(normalizeOptional(overrides.definition()), 2048),
        truncate(normalizeOptional(overrides.rationale()), 2048),
        normalizeKnownValue(overrides.termType(), GlossaryTermMetadata.TERM_TYPES, null),
        truncate(normalizeOptional(overrides.partOfSpeech()), 64),
        normalizeKnownValue(overrides.enforcement(), GlossaryTermMetadata.ENFORCEMENTS, null),
        overrides.doNotTranslate(),
        clampConfidence(overrides.confidence()),
        normalizeCandidateReviewStatus(overrides.reviewStatus(), null),
        truncate(normalizeOptional(overrides.reviewReason()), 64),
        truncate(normalizeOptional(overrides.reviewRationale()), 2048),
        clampConfidence(overrides.reviewConfidence()));
  }

  private CandidateReviewCommand normalize(CandidateReviewCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("reviewStatus is required");
    }
    String reviewStatus = normalizeCandidateReviewStatus(command.reviewStatus(), null);
    if (reviewStatus == null) {
      throw new IllegalArgumentException("Unsupported reviewStatus: " + command.reviewStatus());
    }
    return new CandidateReviewCommand(
        reviewStatus,
        truncate(normalizeOptional(command.reviewReason()), 64),
        truncate(normalizeOptional(command.reviewRationale()), 2048),
        clampConfidence(command.reviewConfidence()));
  }

  private CandidateImportCommand normalize(CandidateImportCommand command) {
    if (command == null || normalizeOptional(command.content()) == null) {
      throw new IllegalArgumentException("content is required");
    }
    return new CandidateImportCommand(
        normalizeCandidateFormat(command.format()), command.content());
  }

  private CandidateExportCommand normalize(CandidateExportCommand command) {
    if (command == null) {
      return new CandidateExportCommand(
          null, DEFAULT_CANDIDATE_EXPORT_LIMIT, CANDIDATE_EXPORT_FORMAT_JSON);
    }
    return new CandidateExportCommand(
        command.searchQuery(),
        normalizeCandidateExportLimit(command.limit()),
        normalizeCandidateFormat(command.format()));
  }

  private AcceptSuggestionCommand normalize(
      AcceptSuggestionCommand command, TermIndexCandidate candidate) {
    String defaultTerm = candidate.getTerm();
    return new AcceptSuggestionCommand(
        command == null ? null : command.termKey(),
        coalesce(command == null ? null : normalizeOptional(command.source()), defaultTerm),
        coalesce(
            command == null ? null : normalizeOptional(command.definition()),
            normalizeOptional(candidate.getDefinition())),
        coalesce(
            command == null ? null : normalizeOptional(command.partOfSpeech()),
            normalizeOptional(candidate.getPartOfSpeech())),
        normalizeKnownValue(
            command == null ? null : command.termType(),
            GlossaryTermMetadata.TERM_TYPES,
            normalizeKnownValue(
                candidate.getTermType(),
                GlossaryTermMetadata.TERM_TYPES,
                suggestTermType(defaultTerm))),
        normalizeKnownValue(
            command == null ? null : command.enforcement(),
            GlossaryTermMetadata.ENFORCEMENTS,
            normalizeKnownValue(
                candidate.getEnforcement(),
                GlossaryTermMetadata.ENFORCEMENTS,
                GlossaryTermMetadata.ENFORCEMENT_SOFT)),
        normalizeKnownValue(
            command == null ? null : command.status(),
            GlossaryTermMetadata.STATUSES,
            GlossaryTermMetadata.STATUS_CANDIDATE),
        command == null ? null : command.caseSensitive(),
        command == null
            ? (candidate.getDoNotTranslate() == null
                ? shouldPreserveSource(defaultTerm)
                : candidate.getDoNotTranslate())
            : command.doNotTranslate(),
        clampConfidence(command == null ? candidate.getConfidence() : command.confidence()),
        coalesce(
            command == null ? null : normalizeOptional(command.rationale()),
            normalizeOptional(candidate.getRationale())),
        command == null ? List.of() : command.evidence());
  }

  private TermIndexCandidate getCandidate(Long termIndexCandidateId) {
    return termIndexCandidateRepository
        .findById(termIndexCandidateId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Term index candidate not found: " + termIndexCandidateId));
  }

  private Glossary getGlossary(Long glossaryId) {
    return glossaryRepository
        .findByIdWithBindings(glossaryId)
        .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));
  }

  private List<Long> repositoryIdsOrSentinel(List<Long> repositoryIds) {
    return repositoryIds.isEmpty() ? EMPTY_FILTER_SENTINEL : repositoryIds;
  }

  private List<Long> normalizeRepositoryIds(List<Long> repositoryIds) {
    if (repositoryIds == null) {
      return List.of();
    }
    return repositoryIds.stream().filter(Objects::nonNull).distinct().toList();
  }

  private int normalizeLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_SUGGESTION_LIMIT : limit;
    return Math.max(1, normalized);
  }

  private int normalizeGenerationLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_GENERATION_LIMIT : limit;
    return Math.max(1, normalized);
  }

  private int normalizeCandidateExportLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_CANDIDATE_EXPORT_LIMIT : limit;
    return Math.max(1, normalized);
  }

  private int prefetchLimit(int requestedLimit) {
    long desiredLimit = Math.max(requestedLimit * 4L, 100L);
    return desiredLimit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) desiredLimit;
  }

  private String normalizeCandidateFormat(String format) {
    String normalized = normalizeOptional(format);
    if (normalized == null || CANDIDATE_EXPORT_FORMAT_JSON.equalsIgnoreCase(normalized)) {
      return CANDIDATE_EXPORT_FORMAT_JSON;
    }
    throw new IllegalArgumentException("Unsupported candidate format: " + format);
  }

  private String normalizeRequired(String value, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String normalizeCandidateKey(String candidate) {
    String normalized = normalizeOptional(candidate);
    if (normalized == null) {
      return null;
    }
    normalized =
        normalized
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    return normalized.isBlank() ? null : normalized;
  }

  private String normalizeKnownValue(String value, Set<String> allowedValues, String defaultValue) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return defaultValue;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return allowedValues.contains(normalized) ? normalized : defaultValue;
  }

  private Integer clampConfidence(Integer value) {
    return value == null ? null : Math.max(0, Math.min(100, value));
  }

  private void applyHumanCandidateReview(
      TermIndexCandidate candidate, CandidateReviewCommand command, User currentUser) {
    candidate.setReviewStatus(command.reviewStatus());
    candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
    candidate.setReviewReason(command.reviewReason());
    candidate.setReviewRationale(command.reviewRationale());
    candidate.setReviewConfidence(command.reviewConfidence());
    candidate.setReviewChangedAt(ZonedDateTime.now());
    candidate.setReviewChangedByUser(currentUser);
  }

  private User currentUserOrNull() {
    return userService.getCurrentUser().orElse(null);
  }

  private Long currentUserIdOrNull() {
    return userService.getCurrentUser().map(User::getId).orElse(null);
  }

  private User userByIdOrNull(Long userId) {
    return userService.getUserById(userId).orElse(null);
  }

  private int ceilDiv(int dividend, int divisor) {
    if (dividend <= 0 || divisor <= 0) {
      return 0;
    }
    return (dividend + divisor - 1) / divisor;
  }

  private long nullToZero(Long value) {
    return value == null ? 0 : value;
  }

  private <T> T coalesce(T left, T right) {
    return left != null ? left : right;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private Long parseLong(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Long.valueOf(normalized);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private void updateTriageProgress(Long pollableTaskId, TriageProgressView progress) {
    if (pollableTaskId == null || progress == null) {
      return;
    }
    pollableTaskService.updateMessage(
        pollableTaskId, objectMapper.writeValueAsStringUnchecked(progress));
  }

  private Optional<TermIndexCandidate> findCandidate(
      String sourceType, String sourceName, String candidateHash) {
    return termIndexCandidateRepository.findBySourceTypeAndSourceNameAndCandidateHash(
        sourceType, sourceName, candidateHash);
  }

  private String normalizeSourceName(String sourceName) {
    return coalesce(truncate(normalizeOptional(sourceName), 255), "");
  }

  private String candidateHash(
      String sourceType,
      String sourceName,
      String sourceExternalId,
      String sourceLocaleTag,
      String normalizedKey,
      String term,
      String label,
      String definition,
      String rationale,
      String metadataJson) {
    String payload =
        String.join(
            "\n",
            sourceType,
            normalizeSourceName(sourceName),
            normalizeOptional(sourceExternalId) == null ? "" : normalizeOptional(sourceExternalId),
            normalizeOptional(sourceLocaleTag) == null ? "" : normalizeOptional(sourceLocaleTag),
            normalizeOptional(normalizedKey) == null ? "" : normalizeOptional(normalizedKey),
            normalizeOptional(term) == null ? "" : normalizeOptional(term),
            normalizeOptional(label) == null ? "" : normalizeOptional(label),
            normalizeOptional(definition) == null ? "" : normalizeOptional(definition),
            normalizeOptional(rationale) == null ? "" : normalizeOptional(rationale),
            metadataJson == null ? "" : metadataJson);
    return DigestUtils.sha256Hex(payload);
  }

  private List<SeedTermInput> scopeCandidatesToGlossary(
      Long glossaryId, List<SeedTermInput> candidates) {
    return candidates.stream()
        .map(candidate -> scopeCandidateToGlossary(glossaryId, candidate))
        .toList();
  }

  private SeedTermInput scopeCandidateToGlossary(Long glossaryId, SeedTermInput candidate) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (candidate.metadata() != null) {
      metadata.putAll(candidate.metadata());
    }
    metadata.putIfAbsent(GLOSSARY_SUBMISSION_METADATA_GLOSSARY_ID, glossaryId);
    metadata.putIfAbsent(
        GLOSSARY_SUBMISSION_METADATA_SUBMITTED_FROM, GLOSSARY_SUBMISSION_SOURCE_NAME);

    return new SeedTermInput(
        candidate.term(),
        candidate.sourceLocaleTag(),
        coalesce(normalizeOptional(candidate.sourceType()), GLOSSARY_SUBMISSION_SOURCE_TYPE),
        coalesce(normalizeOptional(candidate.sourceName()), GLOSSARY_SUBMISSION_SOURCE_NAME),
        candidate.sourceExternalId(),
        candidate.confidence(),
        candidate.label(),
        candidate.definition(),
        candidate.rationale(),
        candidate.termType(),
        candidate.partOfSpeech(),
        candidate.enforcement(),
        candidate.doNotTranslate(),
        candidate.reviewStatus(),
        candidate.reviewAuthority(),
        candidate.reviewReason(),
        candidate.reviewRationale(),
        candidate.reviewConfidence(),
        metadata);
  }

  private boolean candidateVisibleForGlossary(TermIndexCandidate candidate, Long glossaryId) {
    return candidateVisibleForGlossary(candidate.getMetadataJson(), glossaryId);
  }

  private boolean candidateVisibleForGlossary(String metadataJson, Long glossaryId) {
    Map<String, Object> metadata = parseMetadata(metadataJson);
    if (metadata == null || !metadata.containsKey(GLOSSARY_SUBMISSION_METADATA_GLOSSARY_ID)) {
      return true;
    }
    Object candidateGlossaryId = metadata.get(GLOSSARY_SUBMISSION_METADATA_GLOSSARY_ID);
    return candidateGlossaryId != null
        && String.valueOf(glossaryId).equals(String.valueOf(candidateGlossaryId));
  }

  private List<SeedTermInput> parseCandidateImportRows(CandidateImportCommand command) {
    JsonNode root = objectMapper.readValueUnchecked(command.content(), JsonNode.class);
    JsonNode candidatesNode;
    if (root.isArray()) {
      candidatesNode = root;
    } else if (root.has("candidates")) {
      candidatesNode = root.get("candidates");
    } else if (root.has("terms")) {
      candidatesNode = root.get("terms");
    } else {
      throw new IllegalArgumentException("Candidate import must contain candidates or terms");
    }
    if (candidatesNode == null || !candidatesNode.isArray()) {
      throw new IllegalArgumentException("Candidate import rows must be an array");
    }
    return objectMapper.convertValue(candidatesNode, new TypeReference<List<SeedTermInput>>() {});
  }

  private SeedTermInput toSeedTermInput(TermIndexCandidate candidate) {
    return new SeedTermInput(
        candidate.getTerm(),
        candidate.getSourceLocaleTag(),
        candidate.getSourceType(),
        candidate.getSourceName(),
        candidate.getSourceExternalId(),
        candidate.getConfidence(),
        candidate.getLabel(),
        candidate.getDefinition(),
        candidate.getRationale(),
        candidate.getTermType(),
        candidate.getPartOfSpeech(),
        candidate.getEnforcement(),
        candidate.getDoNotTranslate(),
        candidate.getReviewStatus(),
        candidate.getReviewAuthority(),
        candidate.getReviewReason(),
        candidate.getReviewRationale(),
        candidate.getReviewConfidence(),
        parseMetadata(candidate.getMetadataJson()));
  }

  private Map<String, Object> parseMetadata(String metadataJson) {
    if (normalizeOptional(metadataJson) == null) {
      return null;
    }
    try {
      return objectMapper.readValueUnchecked(
          metadataJson, new TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException ignored) {
      return Map.of("raw", metadataJson);
    }
  }

  private SeededTermView toSeededTermView(TermIndexCandidate candidate) {
    TermIndexExtractedTerm extractedTerm = candidate.getTermIndexExtractedTerm();
    return new SeededTermView(
        candidate.getId(),
        extractedTerm == null ? null : extractedTerm.getId(),
        candidate.getTerm(),
        candidate.getNormalizedKey(),
        candidate.getLabel(),
        candidate.getDefinition(),
        candidate.getRationale(),
        candidate.getTermType(),
        candidate.getPartOfSpeech(),
        candidate.getEnforcement(),
        candidate.getDoNotTranslate(),
        candidate.getConfidence());
  }

  private String slugify(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return "glossary";
    }
    normalized =
        normalized
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "-")
            .replaceAll("^-+|-+$", "");
    return normalized.isBlank() ? "glossary" : normalized;
  }

  private String heuristicDefinition(SuggestionAccumulator candidate) {
    if (candidate.occurrenceCount() > 0) {
      return "Product term observed in source strings.";
    }
    return "Externally suggested glossary term.";
  }

  private String heuristicRationale(SuggestionAccumulator candidate) {
    if (candidate.occurrenceCount() > 0) {
      return "Found "
          + candidate.occurrenceCount()
          + " time"
          + (candidate.occurrenceCount() == 1 ? "" : "s")
          + " across "
          + candidate.repositoryCount()
          + " repositor"
          + (candidate.repositoryCount() == 1 ? "y" : "ies")
          + ".";
    }
    return "Added from an external candidate source and ready for glossary review.";
  }

  private int heuristicConfidence(SuggestionAccumulator candidate) {
    return heuristicConfidence(candidate.occurrenceCount(), (long) candidate.repositoryCount());
  }

  private int heuristicConfidence(Long occurrenceCount, Long repositoryCount) {
    long normalizedOccurrenceCount = nullToZero(occurrenceCount);
    long normalizedRepositoryCount = nullToZero(repositoryCount);
    return Math.max(
        35,
        Math.min(
            70,
            35
                + (int) Math.min(normalizedOccurrenceCount * 3, 25)
                + (int) Math.min(normalizedRepositoryCount * 5, 10)));
  }

  private String suggestTermType(String term) {
    if (term == null) {
      return GlossaryTermMetadata.TERM_TYPE_GENERAL;
    }
    if (term.equals(term.toUpperCase(Locale.ROOT)) && term.length() >= 3) {
      return GlossaryTermMetadata.TERM_TYPE_BRAND;
    }
    if (term.matches(".*[a-z][A-Z].*")) {
      return GlossaryTermMetadata.TERM_TYPE_TECHNICAL;
    }
    if (term.contains(" ")) {
      return GlossaryTermMetadata.TERM_TYPE_PRODUCT;
    }
    return GlossaryTermMetadata.TERM_TYPE_GENERAL;
  }

  private boolean shouldPreserveSource(String term) {
    return term != null && term.length() >= 3 && term.equals(term.toUpperCase(Locale.ROOT));
  }

  private void requireTermManager() {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("PM or admin access is required to curate glossary terms");
    }
  }

  private record SuggestionAccumulator(
      Long id,
      Long termIndexExtractedTermId,
      String normalizedKey,
      String displayTerm,
      String label,
      String sourceLocaleTag,
      long occurrenceCount,
      int repositoryCount,
      int sourceCount,
      int extractedTermMatchCount,
      ZonedDateTime lastSignalAt) {}

  private record SuggestionCandidate(
      SuggestionAccumulator accumulator, TermIndexCandidate candidate, SuggestionState state) {}

  private record SuggestionState(String reviewStatus, String glossaryPresence) {}

  public record SuggestionSearchCommand(
      String searchQuery,
      Integer limit,
      Boolean useAi,
      Boolean includeReviewed,
      String reviewStatusFilter,
      String glossaryPresenceFilter) {}

  public record SuggestionSearchView(List<SuggestionView> suggestions, long totalCount) {}

  public record SuggestionView(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String normalizedKey,
      String term,
      String label,
      String sourceLocaleTag,
      long occurrenceCount,
      int repositoryCount,
      int sourceCount,
      int extractedTermMatchCount,
      int confidence,
      String definition,
      String rationale,
      String suggestedTermType,
      String suggestedProvenance,
      String suggestedPartOfSpeech,
      String suggestedEnforcement,
      boolean suggestedDoNotTranslate,
      List<OccurrenceView> examples,
      List<CandidateSourceView> sources,
      ZonedDateTime lastSignalAt,
      String candidateReviewStatus,
      String candidateReviewAuthority,
      String candidateReviewReason,
      String candidateReviewRationale,
      Integer candidateReviewConfidence,
      ZonedDateTime candidateReviewChangedAt,
      Long candidateReviewChangedByUserId,
      String candidateReviewChangedByUsername,
      String candidateReviewChangedByCommonName,
      String reviewStatus,
      String glossaryPresence,
      String selectionMethod) {}

  public record OccurrenceView(
      Long id,
      Long repositoryId,
      String repositoryName,
      Long assetId,
      String assetPath,
      Long tmTextUnitId,
      String textUnitName,
      String sourceText,
      String matchedText,
      Integer startIndex,
      Integer endIndex,
      String extractionMethod,
      Integer confidence) {}

  public record CandidateSourceView(
      Long id,
      String sourceType,
      String sourceName,
      String sourceExternalId,
      Integer confidence,
      String metadataJson,
      ZonedDateTime createdDate) {}

  public record AcceptSuggestionCommand(
      String termKey,
      String source,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      Boolean caseSensitive,
      Boolean doNotTranslate,
      Integer confidence,
      String rationale,
      List<AcceptSuggestionEvidenceInput> evidence) {}

  public record AcceptSuggestionEvidenceInput(
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight) {}

  public record IgnoreSuggestionCommand(String reason) {}

  public record CandidateReviewCommand(
      String reviewStatus, String reviewReason, String reviewRationale, Integer reviewConfidence) {}

  public record CandidateReviewView(
      Long termIndexCandidateId,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      ZonedDateTime reviewChangedAt,
      Long reviewChangedByUserId,
      String reviewChangedByUsername,
      String reviewChangedByCommonName) {}

  public record SeedCommand(List<SeedTermInput> terms) {}

  public record GenerateCandidatesCommand(
      String searchQuery, String extractionMethod, Long minOccurrences, Integer limit) {}

  public record GenerateCandidatesFromExtractedTermsCommand(
      List<Long> termIndexExtractedTermIds,
      List<Long> repositoryIds,
      CandidateFieldOverrides overrides) {}

  public record GenerateCandidatesJobCommand(
      Long glossaryId,
      GenerateCandidatesCommand generateCandidatesCommand,
      GenerateCandidatesFromExtractedTermsCommand generateCandidatesFromExtractedTermsCommand,
      Long requestedByUserId) {}

  public record TriageExtractedTermsCommand(
      List<Long> termIndexExtractedTermIds,
      List<Long> repositoryIds,
      String searchQuery,
      String extractionMethod,
      String reviewStatusFilter,
      String reviewAuthorityFilter,
      Long minOccurrences,
      Integer limit,
      ZonedDateTime lastOccurrenceAfter,
      ZonedDateTime lastOccurrenceBefore,
      ZonedDateTime reviewChangedAfter,
      ZonedDateTime reviewChangedBefore,
      Boolean overwriteHumanReview) {}

  public record CandidateFieldOverrides(
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}

  public record CandidateImportCommand(String format, String content) {}

  public record CandidateExportCommand(String searchQuery, Integer limit, String format) {}

  public record SeedTermInput(
      String term,
      String sourceLocaleTag,
      String sourceType,
      String sourceName,
      String sourceExternalId,
      Integer confidence,
      String label,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      Map<String, Object> metadata) {}

  public record SeedResult(
      int termCount,
      int createdCandidateCount,
      int updatedCandidateCount,
      List<SeededTermView> terms) {}

  public record GenerateCandidatesResult(
      int candidateCount,
      int createdCandidateCount,
      int updatedCandidateCount,
      List<SeededTermView> candidates) {}

  public record TriageExtractedTermsResult(
      int entryCount,
      int reviewedEntryCount,
      int updatedEntryCount,
      int acceptedCount,
      int toReviewCount,
      int rejectedCount,
      int skippedHumanReviewedCount,
      List<TriagedExtractedTermView> entries) {}

  public record TriageProgressView(
      String type,
      String status,
      int entryCount,
      int reviewableEntryCount,
      int reviewedEntryCount,
      int updatedEntryCount,
      int acceptedCount,
      int toReviewCount,
      int rejectedCount,
      int skippedHumanReviewedCount,
      int batchCount,
      int completedBatchCount) {}

  public record CandidateExportResult(
      String format, String filename, String content, int candidateCount) {}

  public record CandidateExportInfo(
      Long glossaryId, String glossaryName, String searchQuery, int candidateCount) {}

  public record CandidateExportDocument(
      CandidateExportInfo glossary, List<SeedTermInput> candidates) {}

  public record SeededTermView(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String term,
      String normalizedKey,
      String label,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence) {}

  public record TriagedExtractedTermView(
      Long termIndexExtractedTermId,
      String term,
      String normalizedKey,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}

  private record CandidateWriteResult(TermIndexCandidate candidate, boolean created) {}

  private record GeneratedCandidateFields(
      String label,
      String sourceExternalId,
      boolean distinctIdentity,
      CandidateFieldOverrides overrides) {

    private static GeneratedCandidateFields defaultFields() {
      return new GeneratedCandidateFields(null, null, false, null);
    }
  }

  private record Span(int start, int end) {}
}
