package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexDecision;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
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
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryTermIndexCurationService {

  private static final int DEFAULT_SUGGESTION_LIMIT = 50;
  private static final int MAX_SUGGESTION_LIMIT = 200;
  private static final int MAX_PREFETCH_LIMIT = 800;
  private static final int EXAMPLE_LIMIT = 4;
  private static final int LIVE_SEARCH_EXAMPLE_LIMIT = 3;
  private static final int MAX_LIVE_SEARCH_SUGGESTIONS = 50;
  private static final List<Long> EMPTY_FILTER_SENTINEL = List.of(-1L);
  private static final String EXTRACTION_SOURCE_NAME = "term-index";
  private static final String SOURCE_SEARCH_METHOD = "SOURCE_SEARCH";
  private static final String REVIEW_STATE_NEW = "NEW";
  private static final String REVIEW_STATE_IGNORED = "IGNORED";
  private static final String REVIEW_STATE_LINKED = "LINKED";
  private static final String REVIEW_STATE_EXISTING_TERM = "EXISTING_TERM";
  private static final String REVIEW_STATE_FILTER_ALL = "ALL";
  private static final String REVIEW_STATE_FILTER_REVIEWED = "REVIEWED";

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
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
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
  }

  @Transactional
  public SuggestionSearchView searchSuggestions(Long glossaryId, SuggestionSearchCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    SuggestionSearchCommand normalized = normalize(command);
    List<Long> scopeRepositoryIds = resolveScopeRepositoryIds(glossary);
    int prefetchLimit = Math.min(MAX_PREFETCH_LIMIT, Math.max(normalized.limit() * 4, 100));
    String searchQuery = normalizeOptional(normalized.searchQuery());

    LinkedHashMap<Long, SuggestionAccumulator> candidates = new LinkedHashMap<>();
    if (!scopeRepositoryIds.isEmpty()) {
      List<Long> repositoryFilter = repositoryIdsOrSentinel(scopeRepositoryIds);
      termIndexExtractedTermRepository
          .searchEntries(
              false, repositoryFilter, searchQuery, null, 1L, PageRequest.of(0, prefetchLimit))
          .forEach(
              row -> {
                TermIndexCandidate candidate = ensureExtractionCandidate(row);
                candidates.putIfAbsent(candidate.getId(), fromOccurrenceRow(row, candidate));
              });
    }

    termIndexCandidateRepository
        .searchUnattachedCandidates(searchQuery, PageRequest.of(0, prefetchLimit))
        .forEach(row -> candidates.putIfAbsent(row.getId(), fromUnattachedCandidateRow(row)));

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

    List<SuggestionView> heuristicSuggestions = new ArrayList<>();
    for (SuggestionAccumulator accumulator : candidates.values()) {
      TermIndexCandidate candidate = candidatesById.get(accumulator.id());
      if (candidate == null) {
        continue;
      }
      String reviewState =
          reviewState(accumulator, linkedCandidateIds, ignoredCandidateIds, existingTermKeys);
      if (!matchesReviewStateFilter(reviewState, normalized.reviewStateFilter())) {
        continue;
      }
      heuristicSuggestions.add(
          toSuggestion(accumulator, candidate, scopeRepositoryIds, reviewState));
      if (heuristicSuggestions.size() >= prefetchLimit) {
        break;
      }
    }

    List<SuggestionView> suggestions =
        Boolean.TRUE.equals(normalized.useAi())
            ? refineWithAi(heuristicSuggestions, normalized.limit())
            : heuristicSuggestions.stream().limit(normalized.limit()).toList();
    if (suggestions.isEmpty() && !heuristicSuggestions.isEmpty()) {
      suggestions = heuristicSuggestions.stream().limit(normalized.limit()).toList();
    }
    suggestions = addLiveSearchExamplesIfSmall(suggestions, scopeRepositoryIds);
    return new SuggestionSearchView(suggestions, heuristicSuggestions.size());
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
  public SeedResult seedTerms(SeedCommand command) {
    requireTermManager();
    SeedCommand normalized = normalize(command);
    return seedTermsInternal(normalized);
  }

  @Transactional
  public SeedResult seedTermsForGlossary(Long glossaryId, SeedCommand command) {
    requireTermManager();
    getGlossary(glossaryId);
    SeedCommand normalized = normalize(command);
    return seedTermsInternal(normalized);
  }

  private SeedResult seedTermsInternal(SeedCommand normalized) {
    List<SeededTermView> seededTerms = new ArrayList<>(normalized.terms().size());

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
      TermIndexCandidate candidate =
          findCandidate(sourceType, sourceName, candidateHash)
              .orElseGet(
                  () -> {
                    TermIndexCandidate next = new TermIndexCandidate();
                    next.setSourceType(sourceType);
                    next.setSourceName(sourceName);
                    next.setCandidateHash(candidateHash);
                    return next;
                  });
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
      termIndexCandidateRepository.save(candidate);

      seededTerms.add(
          new SeededTermView(
              candidate.getId(),
              extractedTerm == null ? null : extractedTerm.getId(),
              candidate.getTerm(),
              normalizedKey));
    }

    return new SeedResult(seededTerms.size(), seededTerms);
  }

  private SuggestionView toSuggestion(
      SuggestionAccumulator accumulator,
      TermIndexCandidate candidate,
      List<Long> scopeRepositoryIds,
      String reviewState) {
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
        reviewState,
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
                  heuristic.reviewState(),
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

  private List<SuggestionView> addLiveSearchExamplesIfSmall(
      List<SuggestionView> suggestions, List<Long> scopeRepositoryIds) {
    if (suggestions.isEmpty() || suggestions.size() > MAX_LIVE_SEARCH_SUGGESTIONS) {
      return suggestions;
    }

    List<SuggestionView> enriched = new ArrayList<>(suggestions.size());
    for (SuggestionView suggestion : suggestions) {
      if (suggestion.examples().size() >= EXAMPLE_LIMIT) {
        enriched.add(suggestion);
        continue;
      }
      List<OccurrenceView> searchedExamples =
          searchSourceExamples(
              suggestion.term(), scopeRepositoryIds, EXAMPLE_LIMIT - suggestion.examples().size());
      if (searchedExamples.isEmpty()) {
        enriched.add(suggestion);
        continue;
      }
      List<OccurrenceView> examples = new ArrayList<>(suggestion.examples());
      Set<Long> exampleTextUnitIds =
          examples.stream()
              .map(OccurrenceView::tmTextUnitId)
              .filter(Objects::nonNull)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      for (OccurrenceView searchedExample : searchedExamples) {
        if (searchedExample.tmTextUnitId() != null
            && !exampleTextUnitIds.add(searchedExample.tmTextUnitId())) {
          continue;
        }
        examples.add(searchedExample);
        if (examples.size() >= EXAMPLE_LIMIT) {
          break;
        }
      }
      enriched.add(copyWithExamples(suggestion, examples));
    }
    return enriched;
  }

  private SuggestionView copyWithExamples(
      SuggestionView suggestion, List<OccurrenceView> examples) {
    return new SuggestionView(
        suggestion.termIndexCandidateId(),
        suggestion.termIndexExtractedTermId(),
        suggestion.normalizedKey(),
        suggestion.term(),
        suggestion.label(),
        suggestion.sourceLocaleTag(),
        suggestion.occurrenceCount(),
        suggestion.repositoryCount(),
        suggestion.sourceCount(),
        suggestion.confidence(),
        suggestion.definition(),
        suggestion.rationale(),
        suggestion.suggestedTermType(),
        suggestion.suggestedProvenance(),
        suggestion.suggestedPartOfSpeech(),
        suggestion.suggestedEnforcement(),
        suggestion.suggestedDoNotTranslate(),
        examples,
        suggestion.sources(),
        suggestion.lastSignalAt(),
        suggestion.reviewState(),
        suggestion.selectionMethod());
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

  private TermIndexCandidate ensureExtractionCandidate(
      TermIndexExtractedTermRepository.SearchRow row) {
    String sourceLocaleTag =
        coalesce(
            normalizeOptional(row.getSourceLocaleTag()), TermIndexExtractedTerm.SOURCE_LOCALE_ROOT);
    String candidateHash =
        candidateHash(
            TermIndexCandidate.SOURCE_TYPE_EXTRACTION,
            EXTRACTION_SOURCE_NAME,
            null,
            sourceLocaleTag,
            row.getNormalizedKey(),
            row.getDisplayTerm(),
            null,
            null,
            null,
            null);
    TermIndexCandidate candidate =
        termIndexCandidateRepository
            .findBySourceTypeAndSourceNameAndCandidateHash(
                TermIndexCandidate.SOURCE_TYPE_EXTRACTION, EXTRACTION_SOURCE_NAME, candidateHash)
            .orElseGet(
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
    candidate.setConfidence(
        heuristicConfidence(row.getOccurrenceCount(), row.getRepositoryCount()));
    candidate.setDefinition(null);
    candidate.setRationale(null);
    candidate.setTermType(suggestTermType(row.getDisplayTerm()));
    candidate.setEnforcement(GlossaryTermMetadata.ENFORCEMENT_SOFT);
    candidate.setDoNotTranslate(shouldPreserveSource(row.getDisplayTerm()));
    return termIndexCandidateRepository.save(candidate);
  }

  private SuggestionAccumulator fromOccurrenceRow(
      TermIndexExtractedTermRepository.SearchRow row, TermIndexCandidate candidate) {
    return new SuggestionAccumulator(
        candidate.getId(),
        row.getId(),
        row.getNormalizedKey(),
        row.getDisplayTerm(),
        candidate.getLabel(),
        row.getSourceLocaleTag(),
        nullToZero(row.getOccurrenceCount()),
        Math.toIntExact(nullToZero(row.getRepositoryCount())),
        1,
        row.getLastOccurrenceAt());
  }

  private SuggestionAccumulator fromUnattachedCandidateRow(
      TermIndexCandidateRepository.UnattachedCandidateRow row) {
    return new SuggestionAccumulator(
        row.getId(),
        null,
        row.getNormalizedKey(),
        row.getTerm(),
        row.getLabel(),
        row.getSourceLocaleTag(),
        0,
        0,
        1,
        row.getLastSignalAt());
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
          null, DEFAULT_SUGGESTION_LIMIT, true, false, REVIEW_STATE_NEW);
    }
    return new SuggestionSearchCommand(
        command.searchQuery(),
        normalizeLimit(command.limit()),
        command.useAi(),
        Boolean.TRUE.equals(command.includeReviewed()),
        normalizeReviewStateFilter(command.reviewStateFilter(), command.includeReviewed()));
  }

  private String normalizeReviewStateFilter(String reviewStateFilter, Boolean includeReviewed) {
    String normalized = normalizeOptional(reviewStateFilter);
    if (normalized == null) {
      return Boolean.TRUE.equals(includeReviewed) ? REVIEW_STATE_FILTER_ALL : REVIEW_STATE_NEW;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case REVIEW_STATE_NEW,
              REVIEW_STATE_IGNORED,
              REVIEW_STATE_LINKED,
              REVIEW_STATE_EXISTING_TERM,
              REVIEW_STATE_FILTER_REVIEWED,
              REVIEW_STATE_FILTER_ALL ->
          normalized;
      default -> REVIEW_STATE_NEW;
    };
  }

  private boolean matchesReviewStateFilter(String reviewState, String reviewStateFilter) {
    return switch (reviewStateFilter) {
      case REVIEW_STATE_FILTER_ALL -> true;
      case REVIEW_STATE_FILTER_REVIEWED -> !REVIEW_STATE_NEW.equals(reviewState);
      default -> Objects.equals(reviewState, reviewStateFilter);
    };
  }

  private String reviewState(
      SuggestionAccumulator candidate,
      Set<Long> linkedEntryIds,
      Set<Long> ignoredEntryIds,
      Set<String> existingTermKeys) {
    if (linkedEntryIds.contains(candidate.id())) {
      return REVIEW_STATE_LINKED;
    }
    if (existingTermKeys.contains(candidate.normalizedKey())) {
      return REVIEW_STATE_EXISTING_TERM;
    }
    if (ignoredEntryIds.contains(candidate.id())) {
      return REVIEW_STATE_IGNORED;
    }
    return REVIEW_STATE_NEW;
  }

  private SeedCommand normalize(SeedCommand command) {
    if (command == null || command.terms() == null || command.terms().isEmpty()) {
      throw new IllegalArgumentException("terms are required");
    }
    return command;
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

  private int normalizeLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_SUGGESTION_LIMIT : limit;
    return Math.min(Math.max(1, normalized), MAX_SUGGESTION_LIMIT);
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

  private long nullToZero(Long value) {
    return value == null ? 0 : value;
  }

  private String coalesce(String left, String right) {
    return left != null ? left : right;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
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
      ZonedDateTime lastSignalAt) {}

  public record SuggestionSearchCommand(
      String searchQuery,
      Integer limit,
      Boolean useAi,
      Boolean includeReviewed,
      String reviewStateFilter) {}

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
      String reviewState,
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

  public record SeedCommand(List<SeedTermInput> terms) {}

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
      Map<String, Object> metadata) {}

  public record SeedResult(int termCount, List<SeededTermView> terms) {}

  public record SeededTermView(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String term,
      String normalizedKey) {}

  private record Span(int start, int end) {}
}
