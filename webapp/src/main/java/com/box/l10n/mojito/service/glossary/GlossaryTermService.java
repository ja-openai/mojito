package com.box.l10n.mojito.service.glossary;

import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.APPROVED;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.GlossaryTermTranslationProposal;
import com.box.l10n.mojito.service.asset.VirtualAssetRequiredException;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.InjectCurrentTask;
import com.box.l10n.mojito.service.pollableTask.Pollable;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryTermService {

  private static final int DEFAULT_TERM_LIMIT = 200;
  private static final int MAX_TERM_LIMIT = 500;
  private static final int SEARCH_SCAN_LIMIT = 2_000;
  private static final int WORKSPACE_SUMMARY_SCAN_LIMIT = 10_000;
  private static final int DEFAULT_EXTRACTION_LIMIT = 50;
  private static final int MAX_EXTRACTION_LIMIT = 200;
  private static final int DEFAULT_EXTRACTION_SCAN_LIMIT = 5_000;
  private static final Pattern TITLE_PHRASE_PATTERN =
      Pattern.compile("\\b[A-Z][a-z0-9]+(?:[ -][A-Z][a-z0-9]+){0,3}\\b");
  private static final Pattern UPPER_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Z]{2,}(?:[A-Z0-9_-]{0,30})\\b");
  private static final Pattern CAMEL_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Za-z]+(?:[A-Z][a-z0-9]+)+\\b");
  private static final Set<String> EXTRACTION_STOP_WORDS =
      Set.of(
          "a",
          "an",
          "and",
          "app",
          "button",
          "cancel",
          "click",
          "continue",
          "create",
          "delete",
          "edit",
          "for",
          "from",
          "go",
          "help",
          "in",
          "learn",
          "more",
          "new",
          "of",
          "on",
          "open",
          "please",
          "remove",
          "review",
          "save",
          "select",
          "settings",
          "start",
          "the",
          "this",
          "to",
          "update",
          "view",
          "with",
          "your");

  private final GlossaryRepository glossaryRepository;
  private final GlossaryStorageService glossaryStorageService;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  private final GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository;
  private final GlossaryAiExtractionService glossaryAiExtractionService;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final TextUnitSearcher textUnitSearcher;
  private final VirtualAssetService virtualAssetService;
  private final VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService;
  private final TextUnitBatchImporterService textUnitBatchImporterService;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  private final LocaleService localeService;
  private final UserService userService;

  public GlossaryTermService(
      GlossaryRepository glossaryRepository,
      GlossaryStorageService glossaryStorageService,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermEvidenceRepository glossaryTermEvidenceRepository,
      GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository,
      GlossaryAiExtractionService glossaryAiExtractionService,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      TextUnitSearcher textUnitSearcher,
      VirtualAssetService virtualAssetService,
      VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService,
      TextUnitBatchImporterService textUnitBatchImporterService,
      TMTextUnitRepository tmTextUnitRepository,
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      LocaleService localeService,
      UserService userService) {
    this.glossaryRepository = glossaryRepository;
    this.glossaryStorageService = glossaryStorageService;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.glossaryTermEvidenceRepository = glossaryTermEvidenceRepository;
    this.glossaryTermTranslationProposalRepository = glossaryTermTranslationProposalRepository;
    this.glossaryAiExtractionService = glossaryAiExtractionService;
    this.pollableTaskBlobStorage = pollableTaskBlobStorage;
    this.textUnitSearcher = textUnitSearcher;
    this.virtualAssetService = virtualAssetService;
    this.virtualTextUnitBatchUpdaterService = virtualTextUnitBatchUpdaterService;
    this.textUnitBatchImporterService = textUnitBatchImporterService;
    this.tmTextUnitRepository = tmTextUnitRepository;
    this.repositoryRepository = repositoryRepository;
    this.localeService = localeService;
    this.userService = userService;
  }

  @Transactional(readOnly = true)
  public SearchTermsView searchTerms(
      Long glossaryId, String searchQuery, List<String> localeTags, Integer limit) {
    requireGlossaryReader();

    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    int resolvedLimit = normalizeLimit(limit, DEFAULT_TERM_LIMIT, MAX_TERM_LIMIT);
    List<TextUnitDTO> sourceTextUnits = searchAssetTextUnits(asset, null, true, SEARCH_SCAN_LIMIT);
    if (sourceTextUnits.isEmpty()) {
      List<String> resolvedLocaleTags = resolveRequestedLocaleTags(glossary, localeTags);
      return new SearchTermsView(List.of(), 0, resolvedLocaleTags);
    }

    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        getMetadataByTmTextUnitId(
            glossary.getId(), sourceTextUnits.stream().map(TextUnitDTO::getTmTextUnitId).toList());
    Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId =
        getEvidenceByMetadataId(metadataByTmTextUnitId.values());
    List<String> resolvedLocaleTags = resolveRequestedLocaleTags(glossary, localeTags);
    Map<String, List<TextUnitDTO>> localizedByTermKey =
        loadLocalizedTextUnits(asset, resolvedLocaleTags);

    String normalizedSearchQuery = normalizeSearchQuery(searchQuery);
    List<TermView> termViews =
        sourceTextUnits.stream()
            .map(
                textUnit ->
                    toTermView(
                        textUnit,
                        metadataByTmTextUnitId.get(textUnit.getTmTextUnitId()),
                        localizedByTermKey.getOrDefault(textUnit.getName(), List.of()),
                        evidenceByMetadataId))
            .filter(term -> matchesSearch(term, normalizedSearchQuery))
            .sorted(
                Comparator.comparing(TermView::source, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TermView::termKey, String.CASE_INSENSITIVE_ORDER))
            .toList();

    return new SearchTermsView(
        termViews.stream().limit(resolvedLimit).toList(), termViews.size(), resolvedLocaleTags);
  }

  @Transactional(readOnly = true)
  public WorkspaceSummaryView getWorkspaceSummary(Long glossaryId) {
    requireGlossaryReader();

    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    List<String> localeTags = getAvailableLocaleTags(glossary);
    List<TextUnitDTO> sourceTextUnits =
        searchAssetTextUnits(asset, null, true, WORKSPACE_SUMMARY_SCAN_LIMIT);

    if (sourceTextUnits.isEmpty()) {
      return new WorkspaceSummaryView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        getMetadataByTmTextUnitId(
            glossaryId, sourceTextUnits.stream().map(TextUnitDTO::getTmTextUnitId).toList());
    Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId =
        getEvidenceByMetadataId(metadataByTmTextUnitId.values());
    Map<String, List<TextUnitDTO>> localizedByTermKey = loadLocalizedTextUnits(asset, localeTags);

    long approvedTermCount = 0;
    long candidateTermCount = 0;
    long deprecatedTermCount = 0;
    long rejectedTermCount = 0;
    long doNotTranslateTermCount = 0;
    long termsWithEvidenceCount = 0;
    long termsMissingAnyTranslationCount = 0;
    long missingTranslationCount = 0;
    long fullyTranslatedTermCount = 0;
    long publishReadyTermCount = 0;

    for (TextUnitDTO sourceTextUnit : sourceTextUnits) {
      GlossaryTermMetadata metadata = metadataByTmTextUnitId.get(sourceTextUnit.getTmTextUnitId());
      String status =
          metadata == null || metadata.getStatus() == null
              ? GlossaryTermMetadata.STATUS_CANDIDATE
              : metadata.getStatus();

      switch (status) {
        case GlossaryTermMetadata.STATUS_APPROVED -> approvedTermCount++;
        case GlossaryTermMetadata.STATUS_DEPRECATED -> deprecatedTermCount++;
        case GlossaryTermMetadata.STATUS_REJECTED -> rejectedTermCount++;
        default -> candidateTermCount++;
      }

      boolean doNotTranslate =
          metadata != null
              ? Boolean.TRUE.equals(metadata.getDoNotTranslate())
              : sourceTextUnit.isDoNotTranslate();
      if (doNotTranslate) {
        doNotTranslateTermCount++;
      }

      if (metadata != null
          && !evidenceByMetadataId.getOrDefault(metadata.getId(), List.of()).isEmpty()) {
        termsWithEvidenceCount++;
      }

      Map<String, TextUnitDTO> localizedByLocaleTag = new LinkedHashMap<>();
      for (TextUnitDTO localizedTextUnit :
          localizedByTermKey.getOrDefault(sourceTextUnit.getName(), List.of())) {
        if (localizedTextUnit.getTargetLocale() != null) {
          localizedByLocaleTag.putIfAbsent(localizedTextUnit.getTargetLocale(), localizedTextUnit);
        }
      }

      boolean missingAnyTranslation = false;
      boolean allApproved = !localeTags.isEmpty();
      for (String localeTag : localeTags) {
        TextUnitDTO translation = localizedByLocaleTag.get(localeTag);
        if (translation == null || translation.hasEmptyTranslation()) {
          missingAnyTranslation = true;
          missingTranslationCount++;
          allApproved = false;
          continue;
        }
        if (translation.getStatus() != APPROVED) {
          allApproved = false;
        }
      }

      if (missingAnyTranslation) {
        termsMissingAnyTranslationCount++;
      } else if (!localeTags.isEmpty()) {
        fullyTranslatedTermCount++;
      }

      if (!missingAnyTranslation && allApproved) {
        publishReadyTermCount++;
      }
    }

    boolean truncated = sourceTextUnits.size() >= WORKSPACE_SUMMARY_SCAN_LIMIT;

    return new WorkspaceSummaryView(
        sourceTextUnits.size(),
        approvedTermCount,
        candidateTermCount,
        deprecatedTermCount,
        rejectedTermCount,
        doNotTranslateTermCount,
        termsWithEvidenceCount,
        termsMissingAnyTranslationCount,
        missingTranslationCount,
        fullyTranslatedTermCount,
        publishReadyTermCount,
        truncated);
  }

  @Transactional
  public TermView upsertTerm(Long glossaryId, Long tmTextUnitId, TermUpsertCommand command) {
    boolean canManageTerms = userService.isCurrentUserAdminOrPm();
    if (canManageTerms) {
      requireTermManager();
    } else {
      requireGlossaryReader();
      if (tmTextUnitId != null) {
        return updateReaderTermTranslations(
            glossaryId, tmTextUnitId, command != null ? command.translations() : List.of());
      }
    }
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    TermUpsertCommand effectiveCommand =
        canManageTerms ? command : sanitizeReaderProposalCommand(command);
    String normalizedSource = normalizeRequired(effectiveCommand.source(), "source");

    TextUnitDTO existingSource =
        tmTextUnitId == null ? null : getSourceTextUnit(asset, tmTextUnitId);
    String termKey =
        existingSource != null
            ? existingSource.getName()
            : normalizeOptional(effectiveCommand.termKey()) != null
                ? normalizeOptional(effectiveCommand.termKey())
                : generateTermKey(normalizedSource);

    VirtualAssetTextUnit virtualAssetTextUnit = new VirtualAssetTextUnit();
    virtualAssetTextUnit.setName(termKey);
    virtualAssetTextUnit.setContent(normalizedSource);
    virtualAssetTextUnit.setComment(normalizeOptional(effectiveCommand.sourceComment()));
    virtualAssetTextUnit.setDoNotTranslate(
        resolveDoNotTranslate(effectiveCommand.doNotTranslate(), null));
    updateVirtualAssetTerms(asset, List.of(virtualAssetTextUnit));

    TextUnitDTO refreshedSource = getSourceTextUnitByKey(asset, termKey);
    GlossaryTermMetadata metadata =
        glossaryTermMetadataRepository
            .findByGlossaryIdAndTmTextUnitId(glossaryId, refreshedSource.getTmTextUnitId())
            .orElseGet(
                () -> {
                  GlossaryTermMetadata next = new GlossaryTermMetadata();
                  next.setGlossary(glossary);
                  next.setTmTextUnit(getTmTextUnit(refreshedSource.getTmTextUnitId()));
                  return next;
                });

    applyMetadata(metadata, effectiveCommand, refreshedSource);
    metadata = glossaryTermMetadataRepository.save(metadata);

    replaceEvidence(metadata, effectiveCommand.evidence());
    importTranslations(glossary, termKey, effectiveCommand.translations());

    Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId =
        getEvidenceByMetadataId(List.of(metadata));
    List<TextUnitDTO> localizedTextUnits =
        loadLocalizedTextUnits(
                asset,
                effectiveCommand.translations() == null
                    ? getAvailableLocaleTags(glossary)
                    : effectiveCommand.translations().stream()
                        .map(TranslationInput::localeTag)
                        .filter(Objects::nonNull)
                        .toList())
            .getOrDefault(termKey, List.of());

    return toTermView(refreshedSource, metadata, localizedTextUnits, evidenceByMetadataId);
  }

  @Transactional
  public void deleteTerm(Long glossaryId, Long tmTextUnitId) {
    requireTermManager();

    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    TextUnitDTO sourceTextUnit = getSourceTextUnit(asset, tmTextUnitId);

    glossaryTermMetadataRepository
        .findByGlossaryIdAndTmTextUnitId(glossaryId, tmTextUnitId)
        .ifPresent(
            metadata -> {
              glossaryTermEvidenceRepository.deleteByGlossaryTermMetadataId(metadata.getId());
              glossaryTermMetadataRepository.delete(metadata);
            });
    glossaryTermTranslationProposalRepository.deleteByGlossaryIdAndTmTextUnitId(
        glossaryId, tmTextUnitId);
    virtualAssetService.deleteTextUnit(asset.getId(), sourceTextUnit.getName());
  }

  private TermView updateReaderTermTranslations(
      Long glossaryId, Long tmTextUnitId, List<TranslationInput> translations) {
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    TextUnitDTO sourceTextUnit = getSourceTextUnit(asset, tmTextUnitId);
    List<TranslationInput> allowedTranslations = validateReaderTranslations(translations);

    importTranslations(glossary, sourceTextUnit.getName(), allowedTranslations);

    GlossaryTermMetadata metadata =
        glossaryTermMetadataRepository
            .findByGlossaryIdAndTmTextUnitId(glossaryId, tmTextUnitId)
            .orElse(null);
    Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId =
        metadata == null ? Map.of() : getEvidenceByMetadataId(List.of(metadata));
    List<TextUnitDTO> localizedTextUnits =
        loadLocalizedTextUnits(
                asset, allowedTranslations.stream().map(TranslationInput::localeTag).toList())
            .getOrDefault(sourceTextUnit.getName(), List.of());

    return toTermView(sourceTextUnit, metadata, localizedTextUnits, evidenceByMetadataId);
  }

  private TermUpsertCommand sanitizeReaderProposalCommand(TermUpsertCommand command) {
    return new TermUpsertCommand(
        null,
        command.source(),
        command.sourceComment(),
        command.definition(),
        command.partOfSpeech(),
        command.termType(),
        null,
        GlossaryTermMetadata.STATUS_CANDIDATE,
        GlossaryTermMetadata.PROVENANCE_MANUAL,
        command.caseSensitive(),
        command.doNotTranslate(),
        validateReaderTranslations(command.translations()),
        command.evidence() == null ? List.of() : command.evidence());
  }

  private List<TranslationInput> validateReaderTranslations(List<TranslationInput> translations) {
    if (translations == null || translations.isEmpty()) {
      return List.of();
    }

    List<TranslationInput> allowedTranslations = new ArrayList<>();
    for (TranslationInput translation : translations) {
      if (translation == null) {
        continue;
      }
      String localeTag = normalizeOptional(translation.localeTag());
      String target = normalizeOptional(translation.target());
      if (localeTag == null || target == null) {
        continue;
      }
      com.box.l10n.mojito.entity.Locale locale = localeService.findByBcp47Tag(localeTag);
      if (locale == null) {
        throw new IllegalArgumentException("Unknown locale: " + localeTag);
      }
      userService.checkUserCanEditLocale(locale.getId());
      allowedTranslations.add(
          new TranslationInput(localeTag, target, normalizeOptional(translation.targetComment())));
    }
    return allowedTranslations;
  }

  @Transactional
  public BatchUpdateResult batchUpdateTerms(Long glossaryId, BatchUpdateCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    List<Long> tmTextUnitIds =
        normalizeIds(command.tmTextUnitIds(), "Glossary term ids are required for batch update");
    Map<Long, TextUnitDTO> sourceByTmTextUnitId = getSourceTextUnits(asset, tmTextUnitIds);
    if (sourceByTmTextUnitId.size() != tmTextUnitIds.size()) {
      Set<Long> found = sourceByTmTextUnitId.keySet();
      List<Long> missing = tmTextUnitIds.stream().filter(id -> !found.contains(id)).toList();
      throw new IllegalArgumentException("Unknown glossary terms: " + missing);
    }

    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        getMetadataByTmTextUnitId(glossaryId, tmTextUnitIds);
    List<GlossaryTermMetadata> toSave = new ArrayList<>();
    List<VirtualAssetTextUnit> sourceUpdates = new ArrayList<>();

    for (Long tmTextUnitId : tmTextUnitIds) {
      TextUnitDTO sourceTextUnit = sourceByTmTextUnitId.get(tmTextUnitId);
      GlossaryTermMetadata metadata = metadataByTmTextUnitId.get(tmTextUnitId);
      if (metadata == null) {
        metadata = new GlossaryTermMetadata();
        metadata.setGlossary(glossary);
        metadata.setTmTextUnit(getTmTextUnit(tmTextUnitId));
      }

      if (command.partOfSpeech() != null) {
        metadata.setPartOfSpeech(normalizeOptional(command.partOfSpeech()));
      }
      if (command.termType() != null) {
        metadata.setTermType(normalizeTermType(command.termType()));
      }
      if (command.enforcement() != null) {
        metadata.setEnforcement(normalizeEnforcement(command.enforcement()));
      }
      if (command.status() != null) {
        metadata.setStatus(normalizeStatus(command.status()));
      }
      if (command.provenance() != null) {
        metadata.setProvenance(normalizeProvenance(command.provenance()));
      }
      if (command.caseSensitive() != null) {
        metadata.setCaseSensitive(command.caseSensitive());
      }
      if (command.doNotTranslate() != null) {
        metadata.setDoNotTranslate(command.doNotTranslate());
        VirtualAssetTextUnit sourceUpdate = new VirtualAssetTextUnit();
        sourceUpdate.setName(sourceTextUnit.getName());
        sourceUpdate.setContent(sourceTextUnit.getSource());
        sourceUpdate.setComment(sourceTextUnit.getComment());
        sourceUpdate.setDoNotTranslate(command.doNotTranslate());
        sourceUpdates.add(sourceUpdate);
      }

      toSave.add(metadata);
    }

    if (!sourceUpdates.isEmpty()) {
      updateVirtualAssetTerms(asset, sourceUpdates);
    }
    glossaryTermMetadataRepository.saveAll(toSave);
    return new BatchUpdateResult(toSave.size());
  }

  @Transactional(readOnly = true)
  public ExtractionView extractCandidates(Long glossaryId, ExtractionCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    Set<String> existingTerms =
        searchAssetTextUnits(
                glossaryStorageService.ensureCanonicalAsset(glossary),
                null,
                true,
                SEARCH_SCAN_LIMIT)
            .stream()
            .map(TextUnitDTO::getSource)
            .filter(Objects::nonNull)
            .map(this::normalizeCandidateKey)
            .collect(java.util.stream.Collectors.toSet());

    List<Long> repositoryIds =
        normalizeIds(
            command.repositoryIds(), "Repository ids are required to extract glossary terms");
    List<Repository> repositories =
        repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(repositoryIds);
    if (repositories.size() != repositoryIds.size()) {
      Set<Long> foundIds =
          repositories.stream().map(Repository::getId).collect(java.util.stream.Collectors.toSet());
      List<Long> missingIds = repositoryIds.stream().filter(id -> !foundIds.contains(id)).toList();
      throw new IllegalArgumentException("Unknown repositories: " + missingIds);
    }

    int resolvedLimit =
        normalizeLimit(command.limit(), DEFAULT_EXTRACTION_LIMIT, MAX_EXTRACTION_LIMIT);
    int scanLimit =
        normalizeLimit(
            command.scanLimit(), DEFAULT_EXTRACTION_SCAN_LIMIT, DEFAULT_EXTRACTION_SCAN_LIMIT * 2);
    int minOccurrences =
        Math.max(1, command.minOccurrences() == null ? 2 : command.minOccurrences());

    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setRepositoryIds(repositoryIds);
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setForRootLocale(true);
    parameters.setRootLocaleExcluded(false);
    parameters.setLimit(scanLimit);

    Map<String, CandidateAccumulator> candidatesByKey = new LinkedHashMap<>();
    for (TextUnitDTO textUnitDTO : textUnitSearcher.search(parameters)) {
      for (String candidate : extractCandidatePhrases(textUnitDTO.getSource())) {
        String normalizedCandidateKey = normalizeCandidateKey(candidate);
        if (normalizedCandidateKey == null) {
          continue;
        }
        CandidateAccumulator accumulator =
            candidatesByKey.computeIfAbsent(
                normalizedCandidateKey, ignored -> new CandidateAccumulator(candidate));
        accumulator.add(textUnitDTO);
      }
    }

    List<ExtractedCandidateView> heuristicCandidates =
        candidatesByKey.values().stream()
            .filter(candidate -> candidate.occurrenceCount >= minOccurrences)
            .map(
                candidate ->
                    candidate.toView(existingTerms.contains(normalizeCandidateKey(candidate.term))))
            .filter(candidate -> !candidate.existingInGlossary())
            .sorted(
                Comparator.comparingInt(ExtractedCandidateView::repositoryCount)
                    .reversed()
                    .thenComparing(
                        Comparator.comparingInt(ExtractedCandidateView::occurrenceCount).reversed())
                    .thenComparing(ExtractedCandidateView::term, String.CASE_INSENSITIVE_ORDER))
            .toList();

    List<ExtractedCandidateView> candidates =
        refineCandidatesWithAi(heuristicCandidates, resolvedLimit);

    return new ExtractionView(candidates);
  }

  @Pollable(async = true, message = "Extract glossary candidates")
  public PollableFuture<Void> extractCandidatesAsync(
      Long glossaryId,
      ExtractionCommand command,
      @InjectCurrentTask com.box.l10n.mojito.entity.PollableTask currentTask) {
    if (currentTask == null || currentTask.getId() == null) {
      throw new IllegalStateException("Current pollable task is missing");
    }

    pollableTaskBlobStorage.saveInput(
        currentTask.getId(), new ExtractionTaskInput(glossaryId, command));
    ExtractionView extractionView = extractCandidates(glossaryId, command);
    pollableTaskBlobStorage.saveOutput(currentTask.getId(), extractionView);
    return new PollableFutureTaskResult<>();
  }

  @Transactional
  public TranslationProposalView submitTranslationProposal(
      Long glossaryId, Long tmTextUnitId, TranslationProposalCommand command) {
    requireGlossaryReader();
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    TextUnitDTO sourceTextUnit = getSourceTextUnit(asset, tmTextUnitId);

    String localeTag = normalizeRequired(command.localeTag(), "localeTag");
    if (localeService.findByBcp47Tag(localeTag) == null) {
      throw new IllegalArgumentException("Unknown locale: " + localeTag);
    }
    if (userService.isCurrentUserTranslator()) {
      userService.checkUserCanEditLocale(localeService.findByBcp47Tag(localeTag).getId());
    }

    GlossaryTermTranslationProposal proposal = new GlossaryTermTranslationProposal();
    proposal.setGlossary(glossary);
    proposal.setTmTextUnit(getTmTextUnit(tmTextUnitId));
    proposal.setLocaleTag(localeTag);
    proposal.setProposedTarget(normalizeRequired(command.target(), "target"));
    proposal.setProposedTargetComment(normalizeOptional(command.targetComment()));
    proposal.setNote(normalizeOptional(command.note()));
    proposal.setStatus(GlossaryTermTranslationProposal.STATUS_PENDING);

    proposal = glossaryTermTranslationProposalRepository.save(proposal);
    return toTranslationProposalView(proposal, sourceTextUnit.getSource());
  }

  @Transactional(readOnly = true)
  public ProposalSearchView searchTranslationProposals(
      Long glossaryId, String status, Integer limit) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    int resolvedLimit = normalizeLimit(limit, DEFAULT_TERM_LIMIT, MAX_TERM_LIMIT);
    String normalizedStatus = normalizeProposalStatus(status);

    List<GlossaryTermTranslationProposal> proposals =
        normalizedStatus == null
            ? glossaryTermTranslationProposalRepository.findByGlossaryIdOrderByCreatedDateDesc(
                glossary.getId())
            : glossaryTermTranslationProposalRepository
                .findByGlossaryIdAndStatusOrderByCreatedDateAsc(glossary.getId(), normalizedStatus);

    Map<Long, TextUnitDTO> sourceByTmTextUnitId =
        getSourceTextUnits(
            asset,
            proposals.stream()
                .map(proposal -> proposal.getTmTextUnit().getId())
                .distinct()
                .toList());

    List<TranslationProposalView> views =
        proposals.stream()
            .limit(resolvedLimit)
            .map(
                proposal ->
                    toTranslationProposalView(
                        proposal,
                        sourceByTmTextUnitId.containsKey(proposal.getTmTextUnit().getId())
                            ? sourceByTmTextUnitId.get(proposal.getTmTextUnit().getId()).getSource()
                            : null))
            .toList();
    return new ProposalSearchView(views, proposals.size());
  }

  @Transactional
  public TranslationProposalView decideTranslationProposal(
      Long glossaryId, Long proposalId, TranslationProposalDecisionCommand command) {
    requireTermManager();
    Glossary glossary = getGlossary(glossaryId);
    GlossaryTermTranslationProposal proposal =
        glossaryTermTranslationProposalRepository
            .findById(proposalId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Glossary translation proposal not found: " + proposalId));

    if (!Objects.equals(proposal.getGlossary().getId(), glossary.getId())) {
      throw new IllegalArgumentException(
          "Glossary translation proposal does not belong to glossary: " + proposalId);
    }

    String decision = normalizeProposalStatus(command.status());
    if (!GlossaryTermTranslationProposal.STATUS_ACCEPTED.equals(decision)
        && !GlossaryTermTranslationProposal.STATUS_REJECTED.equals(decision)) {
      throw new IllegalArgumentException("Unsupported proposal decision: " + command.status());
    }
    if (!GlossaryTermTranslationProposal.STATUS_PENDING.equals(proposal.getStatus())) {
      throw new IllegalArgumentException("Only pending proposals can be decided");
    }

    if (GlossaryTermTranslationProposal.STATUS_ACCEPTED.equals(decision)) {
      importTranslations(
          glossary,
          getSourceTextUnit(
                  glossaryStorageService.ensureCanonicalAsset(glossary),
                  proposal.getTmTextUnit().getId())
              .getName(),
          List.of(
              new TranslationInput(
                  proposal.getLocaleTag(),
                  proposal.getProposedTarget(),
                  proposal.getProposedTargetComment())));
    }

    proposal.setStatus(decision);
    proposal.setReviewerNote(normalizeOptional(command.reviewerNote()));
    proposal = glossaryTermTranslationProposalRepository.save(proposal);

    String source =
        getSourceTextUnit(
                glossaryStorageService.ensureCanonicalAsset(glossary),
                proposal.getTmTextUnit().getId())
            .getSource();
    return toTranslationProposalView(proposal, source);
  }

  private List<ExtractedCandidateView> refineCandidatesWithAi(
      List<ExtractedCandidateView> heuristicCandidates, int resolvedLimit) {
    if (heuristicCandidates.isEmpty()) {
      return List.of();
    }

    List<GlossaryAiExtractionService.CandidateSignal> aiSignals =
        heuristicCandidates.stream()
            .limit(Math.max(resolvedLimit * 3, resolvedLimit))
            .map(
                candidate ->
                    new GlossaryAiExtractionService.CandidateSignal(
                        candidate.term(),
                        candidate.occurrenceCount(),
                        candidate.repositoryCount(),
                        candidate.repositories(),
                        candidate.sampleSources(),
                        candidate.suggestedTermType()))
            .toList();

    List<GlossaryAiExtractionService.AiCandidateView> aiCandidates =
        glossaryAiExtractionService.refineCandidates(aiSignals);
    if (aiCandidates.isEmpty()) {
      return heuristicCandidates.stream()
          .limit(resolvedLimit)
          .map(
              candidate ->
                  new ExtractedCandidateView(
                      candidate.term(),
                      candidate.occurrenceCount(),
                      candidate.repositoryCount(),
                      candidate.repositories(),
                      candidate.sampleSources(),
                      candidate.suggestedTermType(),
                      candidate.suggestedProvenance(),
                      candidate.existingInGlossary(),
                      40,
                      null,
                      null,
                      GlossaryTermMetadata.ENFORCEMENT_SOFT,
                      false,
                      "HEURISTIC_FALLBACK"))
          .toList();
    }

    Map<String, ExtractedCandidateView> heuristicByKey = new LinkedHashMap<>();
    for (ExtractedCandidateView heuristicCandidate : heuristicCandidates) {
      String key = normalizeCandidateKey(heuristicCandidate.term());
      if (key != null) {
        heuristicByKey.put(key, heuristicCandidate);
      }
    }

    return aiCandidates.stream()
        .filter(candidate -> normalizeCandidateKey(candidate.term()) != null)
        .map(
            candidate -> {
              ExtractedCandidateView heuristic =
                  heuristicByKey.get(normalizeCandidateKey(candidate.term()));
              if (heuristic == null) {
                return null;
              }
              return new ExtractedCandidateView(
                  heuristic.term(),
                  heuristic.occurrenceCount(),
                  heuristic.repositoryCount(),
                  heuristic.repositories(),
                  heuristic.sampleSources(),
                  tryNormalizeTermType(candidate.termType(), heuristic.suggestedTermType()),
                  GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
                  heuristic.existingInGlossary(),
                  candidate.confidence() == null
                      ? 75
                      : Math.max(0, Math.min(100, candidate.confidence())),
                  normalizeOptional(candidate.rationale()),
                  normalizeOptional(candidate.partOfSpeech()),
                  tryNormalizeEnforcement(
                      candidate.enforcement(), GlossaryTermMetadata.ENFORCEMENT_SOFT),
                  Boolean.TRUE.equals(candidate.doNotTranslate()),
                  "AI_REVIEW");
            })
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparingInt(ExtractedCandidateView::confidence)
                .reversed()
                .thenComparing(
                    Comparator.comparingInt(ExtractedCandidateView::repositoryCount).reversed())
                .thenComparing(
                    Comparator.comparingInt(ExtractedCandidateView::occurrenceCount).reversed())
                .thenComparing(ExtractedCandidateView::term, String.CASE_INSENSITIVE_ORDER))
        .limit(resolvedLimit)
        .toList();
  }

  private TranslationProposalView toTranslationProposalView(
      GlossaryTermTranslationProposal proposal, String source) {
    return new TranslationProposalView(
        proposal.getId(),
        proposal.getCreatedDate(),
        proposal.getLastModifiedDate(),
        proposal.getTmTextUnit().getId(),
        source,
        proposal.getLocaleTag(),
        proposal.getProposedTarget(),
        proposal.getProposedTargetComment(),
        proposal.getNote(),
        proposal.getStatus(),
        proposal.getReviewerNote());
  }

  private Glossary getGlossary(Long glossaryId) {
    return glossaryRepository
        .findByIdWithBindings(glossaryId)
        .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));
  }

  private void applyMetadata(
      GlossaryTermMetadata metadata, TermUpsertCommand command, TextUnitDTO sourceTextUnit) {
    metadata.setDefinition(normalizeOptional(command.definition()));
    metadata.setPartOfSpeech(normalizeOptional(command.partOfSpeech()));
    metadata.setTermType(normalizeTermType(command.termType()));
    metadata.setEnforcement(normalizeEnforcement(command.enforcement()));
    metadata.setStatus(normalizeStatus(command.status()));
    metadata.setProvenance(normalizeProvenance(command.provenance()));
    metadata.setCaseSensitive(resolveCaseSensitive(command.caseSensitive(), null));
    metadata.setDoNotTranslate(resolveDoNotTranslate(command.doNotTranslate(), sourceTextUnit));
  }

  private void replaceEvidence(GlossaryTermMetadata metadata, List<EvidenceInput> evidenceInputs) {
    glossaryTermEvidenceRepository.deleteByGlossaryTermMetadataId(metadata.getId());
    if (evidenceInputs == null || evidenceInputs.isEmpty()) {
      return;
    }

    List<GlossaryTermEvidence> evidence = new ArrayList<>();
    int sortOrder = 0;
    for (EvidenceInput evidenceInput : evidenceInputs) {
      String evidenceType = normalizeEvidenceType(evidenceInput.evidenceType());
      GlossaryTermEvidence next = new GlossaryTermEvidence();
      next.setGlossaryTermMetadata(metadata);
      next.setEvidenceType(evidenceType);
      next.setImageKey(normalizeOptional(evidenceInput.imageKey()));
      next.setCaption(normalizeOptional(evidenceInput.caption()));
      next.setCropX(evidenceInput.cropX());
      next.setCropY(evidenceInput.cropY());
      next.setCropWidth(evidenceInput.cropWidth());
      next.setCropHeight(evidenceInput.cropHeight());
      next.setSortOrder(sortOrder++);

      Long referencedTmTextUnitId = evidenceInput.tmTextUnitId();
      if (referencedTmTextUnitId != null) {
        next.setTmTextUnit(getTmTextUnit(referencedTmTextUnitId));
      }
      evidence.add(next);
    }

    glossaryTermEvidenceRepository.saveAll(evidence);
  }

  private void importTranslations(
      Glossary glossary, String termKey, List<TranslationInput> translationInputs) {
    if (translationInputs == null || translationInputs.isEmpty()) {
      return;
    }

    List<TextUnitBatchImporterService.TextUnitDTOWithVariantComment> imports = new ArrayList<>();
    for (TranslationInput translationInput : translationInputs) {
      String localeTag = normalizeOptional(translationInput.localeTag());
      String target = normalizeOptional(translationInput.target());
      if (localeTag == null || target == null) {
        continue;
      }
      glossaryStorageService.ensureLocale(glossary, localeTag);
      TextUnitDTO textUnitDTO = new TextUnitDTO();
      textUnitDTO.setRepositoryName(glossary.getBackingRepository().getName());
      textUnitDTO.setAssetPath(glossary.getAssetPath());
      textUnitDTO.setTargetLocale(localeTag);
      textUnitDTO.setName(termKey);
      textUnitDTO.setTarget(target);
      textUnitDTO.setTargetComment(normalizeOptional(translationInput.targetComment()));
      textUnitDTO.setStatus(APPROVED);
      textUnitDTO.setIncludedInLocalizedFile(true);
      imports.add(
          new TextUnitBatchImporterService.TextUnitDTOWithVariantComment(textUnitDTO, null));
    }

    if (!imports.isEmpty()) {
      textUnitBatchImporterService.importTextUnitsWithVariantComment(
          imports,
          TextUnitBatchImporterService.IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
          TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    }
  }

  private Map<Long, GlossaryTermMetadata> getMetadataByTmTextUnitId(
      Long glossaryId, Collection<Long> tmTextUnitIds) {
    if (tmTextUnitIds == null || tmTextUnitIds.isEmpty()) {
      return Map.of();
    }
    return glossaryTermMetadataRepository
        .findByGlossaryIdAndTmTextUnitIdIn(glossaryId, new ArrayList<>(tmTextUnitIds))
        .stream()
        .collect(
            java.util.stream.Collectors.toMap(
                metadata -> metadata.getTmTextUnit().getId(), metadata -> metadata));
  }

  private Map<Long, List<GlossaryTermEvidence>> getEvidenceByMetadataId(
      Collection<GlossaryTermMetadata> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId = new LinkedHashMap<>();
    List<Long> metadataIds =
        metadata.stream().map(GlossaryTermMetadata::getId).filter(Objects::nonNull).toList();
    for (GlossaryTermEvidence evidence :
        glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(
            metadataIds)) {
      evidenceByMetadataId
          .computeIfAbsent(evidence.getGlossaryTermMetadata().getId(), ignored -> new ArrayList<>())
          .add(evidence);
    }
    return evidenceByMetadataId;
  }

  private TermView toTermView(
      TextUnitDTO sourceTextUnit,
      GlossaryTermMetadata metadata,
      List<TextUnitDTO> localizedTextUnits,
      Map<Long, List<GlossaryTermEvidence>> evidenceByMetadataId) {
    List<TermTranslationView> translations =
        localizedTextUnits.stream()
            .sorted(
                Comparator.comparing(
                    translation ->
                        translation.getTargetLocale() == null ? "" : translation.getTargetLocale(),
                    String.CASE_INSENSITIVE_ORDER))
            .map(
                translation ->
                    new TermTranslationView(
                        translation.getTargetLocale(),
                        translation.getTarget(),
                        translation.getTargetComment(),
                        translation.getStatus() == null ? null : translation.getStatus().name()))
            .toList();

    List<TermEvidenceView> evidence =
        metadata == null
            ? List.of()
            : evidenceByMetadataId.getOrDefault(metadata.getId(), List.of()).stream()
                .map(
                    item ->
                        new TermEvidenceView(
                            item.getId(),
                            item.getEvidenceType(),
                            item.getCaption(),
                            item.getImageKey(),
                            item.getTmTextUnit() == null ? null : item.getTmTextUnit().getId(),
                            item.getCropX(),
                            item.getCropY(),
                            item.getCropWidth(),
                            item.getCropHeight(),
                            item.getSortOrder()))
                .toList();

    return new TermView(
        metadata == null ? null : metadata.getId(),
        sourceTextUnit.getTmTextUnitId(),
        sourceTextUnit.getName(),
        sourceTextUnit.getSource(),
        sourceTextUnit.getComment(),
        metadata == null ? null : metadata.getDefinition(),
        metadata == null ? null : metadata.getPartOfSpeech(),
        metadata == null ? null : metadata.getTermType(),
        metadata == null ? null : metadata.getEnforcement(),
        metadata == null ? null : metadata.getStatus(),
        metadata == null ? null : metadata.getProvenance(),
        metadata != null && Boolean.TRUE.equals(metadata.getCaseSensitive()),
        metadata != null
            ? Boolean.TRUE.equals(metadata.getDoNotTranslate())
            : sourceTextUnit.isDoNotTranslate(),
        translations,
        evidence);
  }

  private Map<String, List<TextUnitDTO>> loadLocalizedTextUnits(
      Asset asset, List<String> localeTags) {
    Map<String, List<TextUnitDTO>> localizedByKey = new LinkedHashMap<>();
    for (String localeTag : localeTags) {
      for (TextUnitDTO textUnitDTO :
          searchAssetTextUnits(asset, localeTag, false, SEARCH_SCAN_LIMIT)) {
        localizedByKey
            .computeIfAbsent(textUnitDTO.getName(), ignored -> new ArrayList<>())
            .add(textUnitDTO);
      }
    }
    return localizedByKey;
  }

  private List<TextUnitDTO> searchAssetTextUnits(
      Asset asset, String localeTag, boolean rootLocale, int limit) {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setAssetId(asset.getId());
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setLimit(limit);
    if (rootLocale) {
      parameters.setForRootLocale(true);
      parameters.setRootLocaleExcluded(false);
    } else if (localeTag != null) {
      parameters.setLocaleTags(List.of(localeTag));
      parameters.setRootLocaleExcluded(false);
    }
    return textUnitSearcher.search(parameters);
  }

  private TextUnitDTO getSourceTextUnit(Asset asset, Long tmTextUnitId) {
    return searchAssetTextUnits(asset, null, true, SEARCH_SCAN_LIMIT).stream()
        .filter(textUnitDTO -> Objects.equals(textUnitDTO.getTmTextUnitId(), tmTextUnitId))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Glossary term not found: " + tmTextUnitId));
  }

  private TextUnitDTO getSourceTextUnitByKey(Asset asset, String termKey) {
    return searchAssetTextUnits(asset, null, true, SEARCH_SCAN_LIMIT).stream()
        .filter(textUnitDTO -> Objects.equals(textUnitDTO.getName(), termKey))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Glossary term was not persisted: " + termKey));
  }

  private Map<Long, TextUnitDTO> getSourceTextUnits(Asset asset, List<Long> tmTextUnitIds) {
    Map<Long, TextUnitDTO> sourceByTmTextUnitId = new LinkedHashMap<>();
    for (TextUnitDTO textUnitDTO : searchAssetTextUnits(asset, null, true, SEARCH_SCAN_LIMIT)) {
      if (tmTextUnitIds.contains(textUnitDTO.getTmTextUnitId())) {
        sourceByTmTextUnitId.put(textUnitDTO.getTmTextUnitId(), textUnitDTO);
      }
    }
    return sourceByTmTextUnitId;
  }

  private TMTextUnit getTmTextUnit(Long tmTextUnitId) {
    return tmTextUnitRepository
        .findById(tmTextUnitId)
        .orElseThrow(() -> new IllegalArgumentException("TM text unit not found: " + tmTextUnitId));
  }

  private void updateVirtualAssetTerms(Asset asset, List<VirtualAssetTextUnit> textUnits) {
    try {
      virtualTextUnitBatchUpdaterService.updateTextUnits(asset, textUnits, false);
    } catch (VirtualAssetRequiredException ex) {
      throw new IllegalStateException(
          "Canonical glossary asset is not a virtual asset for glossary backing repository "
              + asset.getRepository().getId(),
          ex);
    }
  }

  private boolean matchesSearch(TermView term, String normalizedSearchQuery) {
    if (normalizedSearchQuery == null) {
      return true;
    }
    if (contains(term.source(), normalizedSearchQuery)
        || contains(term.termKey(), normalizedSearchQuery)
        || contains(term.sourceComment(), normalizedSearchQuery)
        || contains(term.definition(), normalizedSearchQuery)
        || contains(term.partOfSpeech(), normalizedSearchQuery)
        || contains(term.termType(), normalizedSearchQuery)
        || contains(term.status(), normalizedSearchQuery)
        || contains(term.enforcement(), normalizedSearchQuery)) {
      return true;
    }
    return term.translations().stream()
            .anyMatch(
                translation ->
                    contains(translation.localeTag(), normalizedSearchQuery)
                        || contains(translation.target(), normalizedSearchQuery)
                        || contains(translation.targetComment(), normalizedSearchQuery))
        || term.evidence().stream()
            .anyMatch(evidence -> contains(evidence.caption(), normalizedSearchQuery));
  }

  private boolean contains(String value, String normalizedSearchQuery) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedSearchQuery);
  }

  private List<String> resolveRequestedLocaleTags(
      Glossary glossary, List<String> requestedLocaleTags) {
    LinkedHashMap<String, String> normalizedRequested = new LinkedHashMap<>();
    for (String requestedLocaleTag :
        requestedLocaleTags == null ? List.<String>of() : requestedLocaleTags) {
      String normalized = normalizeOptional(requestedLocaleTag);
      if (normalized == null) {
        continue;
      }
      normalizedRequested.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
    }
    if (!normalizedRequested.isEmpty()) {
      return new ArrayList<>(normalizedRequested.values());
    }
    return List.of();
  }

  private List<String> getAvailableLocaleTags(Glossary glossary) {
    return glossary.getBackingRepository().getRepositoryLocales().stream()
        .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
        .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
        .filter(Objects::nonNull)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  private List<Long> normalizeIds(List<Long> ids, String errorMessage) {
    List<Long> normalized =
        (ids == null ? List.<Long>of() : ids)
            .stream().filter(id -> id != null && id > 0).distinct().toList();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(errorMessage);
    }
    return normalized;
  }

  private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
    if (limit == null) {
      return defaultLimit;
    }
    return Math.max(1, Math.min(maxLimit, limit));
  }

  private String normalizeSearchQuery(String searchQuery) {
    String normalized = normalizeOptional(searchQuery);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private String normalizeRequired(String value, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      throw new IllegalArgumentException("Glossary field is required: " + fieldName);
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

  private String normalizeTermType(String value) {
    return normalizeTermType(value, GlossaryTermMetadata.TERM_TYPE_GENERAL);
  }

  private String normalizeTermType(String value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!GlossaryTermMetadata.TERM_TYPES.contains(normalized)) {
      throw new IllegalArgumentException("Unknown glossary term type: " + value);
    }
    return normalized;
  }

  private String normalizeEnforcement(String value) {
    return normalizeEnforcement(value, GlossaryTermMetadata.ENFORCEMENT_SOFT);
  }

  private String normalizeEnforcement(String value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!GlossaryTermMetadata.ENFORCEMENTS.contains(normalized)) {
      throw new IllegalArgumentException("Unknown glossary enforcement: " + value);
    }
    return normalized;
  }

  private String tryNormalizeTermType(String value, String defaultValue) {
    try {
      return normalizeTermType(value, defaultValue);
    } catch (IllegalArgumentException ignored) {
      return defaultValue;
    }
  }

  private String tryNormalizeEnforcement(String value, String defaultValue) {
    try {
      return normalizeEnforcement(value, defaultValue);
    } catch (IllegalArgumentException ignored) {
      return defaultValue;
    }
  }

  private String normalizeStatus(String value) {
    if (value == null) {
      return GlossaryTermMetadata.STATUS_CANDIDATE;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!GlossaryTermMetadata.STATUSES.contains(normalized)) {
      throw new IllegalArgumentException("Unknown glossary status: " + value);
    }
    return normalized;
  }

  private String normalizeProvenance(String value) {
    if (value == null) {
      return GlossaryTermMetadata.PROVENANCE_MANUAL;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!GlossaryTermMetadata.PROVENANCES.contains(normalized)) {
      throw new IllegalArgumentException("Unknown glossary provenance: " + value);
    }
    return normalized;
  }

  private String normalizeEvidenceType(String value) {
    String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    if (GlossaryTermEvidence.EVIDENCE_TYPE_SCREENSHOT.equals(normalized)
        || GlossaryTermEvidence.EVIDENCE_TYPE_STRING_USAGE.equals(normalized)
        || GlossaryTermEvidence.EVIDENCE_TYPE_CODE_REF.equals(normalized)
        || GlossaryTermEvidence.EVIDENCE_TYPE_NOTE.equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("Unknown glossary evidence type: " + value);
  }

  private String normalizeProposalStatus(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    if (GlossaryTermTranslationProposal.STATUS_PENDING.equals(normalized)
        || GlossaryTermTranslationProposal.STATUS_ACCEPTED.equals(normalized)
        || GlossaryTermTranslationProposal.STATUS_REJECTED.equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("Unknown glossary proposal status: " + value);
  }

  private boolean resolveCaseSensitive(Boolean value, TextUnitDTO sourceTextUnit) {
    if (value != null) {
      return value;
    }
    return sourceTextUnit != null
        && sourceTextUnit.getComment() != null
        && sourceTextUnit.getComment().contains("CAS");
  }

  private boolean resolveDoNotTranslate(Boolean value, TextUnitDTO sourceTextUnit) {
    if (value != null) {
      return value;
    }
    return sourceTextUnit != null && sourceTextUnit.isDoNotTranslate();
  }

  private String generateTermKey(String source) {
    String normalized =
        source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_+|_+$)", "");
    if (normalized.isBlank()) {
      normalized = "term";
    }
    String abbreviated =
        normalized.length() > 48 ? normalized.substring(0, 48).replaceAll("_+$", "") : normalized;
    return abbreviated + "_" + DigestUtils.md5Hex(source).substring(0, 8);
  }

  private List<String> extractCandidatePhrases(String source) {
    if (source == null || source.isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    collectCandidates(candidates, TITLE_PHRASE_PATTERN, source);
    collectCandidates(candidates, UPPER_TOKEN_PATTERN, source);
    collectCandidates(candidates, CAMEL_TOKEN_PATTERN, source);
    return candidates.stream().filter(this::isUsableCandidate).toList();
  }

  private void collectCandidates(LinkedHashSet<String> candidates, Pattern pattern, String source) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      String candidate = normalizeOptional(matcher.group());
      if (candidate != null) {
        candidates.add(candidate);
      }
    }
  }

  private boolean isUsableCandidate(String candidate) {
    if (candidate == null || candidate.length() < 2 || candidate.length() > 80) {
      return false;
    }
    String normalized = normalizeCandidateKey(candidate);
    if (normalized == null) {
      return false;
    }
    List<String> tokens = List.of(normalized.split("[\\s_-]+"));
    if (tokens.stream().allMatch(EXTRACTION_STOP_WORDS::contains)) {
      return false;
    }
    return tokens.stream()
        .anyMatch(token -> token.length() > 2 && !EXTRACTION_STOP_WORDS.contains(token));
  }

  private String normalizeCandidateKey(String candidate) {
    String normalized = normalizeOptional(candidate);
    if (normalized == null) {
      return null;
    }
    return normalized
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim()
        .replaceAll("\\s+", " ");
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

  private void requireGlossaryReader() {
    if (!userService.isCurrentUserTranslationRole()) {
      throw new AccessDeniedException("Translation role required to access glossary terms");
    }
  }

  private void requireTermManager() {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("PM or admin access is required to manage glossary terms");
    }
  }

  public record SearchTermsView(List<TermView> terms, long totalCount, List<String> localeTags) {}

  public record WorkspaceSummaryView(
      long totalTerms,
      long approvedTermCount,
      long candidateTermCount,
      long deprecatedTermCount,
      long rejectedTermCount,
      long doNotTranslateTermCount,
      long termsWithEvidenceCount,
      long termsMissingAnyTranslationCount,
      long missingTranslationCount,
      long fullyTranslatedTermCount,
      long publishReadyTermCount,
      boolean truncated) {}

  public record TermView(
      Long metadataId,
      Long tmTextUnitId,
      String termKey,
      String source,
      String sourceComment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      boolean caseSensitive,
      boolean doNotTranslate,
      List<TermTranslationView> translations,
      List<TermEvidenceView> evidence) {}

  public record TermTranslationView(
      String localeTag, String target, String targetComment, String status) {}

  public record TermEvidenceView(
      Long id,
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight,
      Integer sortOrder) {}

  public record TermUpsertCommand(
      String termKey,
      String source,
      String sourceComment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      Boolean caseSensitive,
      Boolean doNotTranslate,
      List<TranslationInput> translations,
      List<EvidenceInput> evidence) {}

  public record TranslationInput(String localeTag, String target, String targetComment) {}

  public record EvidenceInput(
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight) {}

  public record BatchUpdateCommand(
      List<Long> tmTextUnitIds,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      Boolean caseSensitive,
      Boolean doNotTranslate) {}

  public record BatchUpdateResult(int updatedTermCount) {}

  public record TranslationProposalCommand(
      String localeTag, String target, String targetComment, String note) {}

  public record ProposalSearchView(List<TranslationProposalView> proposals, long totalCount) {}

  public record TranslationProposalView(
      Long id,
      java.time.ZonedDateTime createdDate,
      java.time.ZonedDateTime lastModifiedDate,
      Long tmTextUnitId,
      String source,
      String localeTag,
      String proposedTarget,
      String proposedTargetComment,
      String note,
      String status,
      String reviewerNote) {}

  public record TranslationProposalDecisionCommand(String status, String reviewerNote) {}

  public record ExtractionCommand(
      List<Long> repositoryIds, Integer limit, Integer minOccurrences, Integer scanLimit) {}

  public record ExtractionTaskInput(Long glossaryId, ExtractionCommand command) {}

  public record ExtractionView(List<ExtractedCandidateView> candidates) {}

  public record ExtractedCandidateView(
      String term,
      int occurrenceCount,
      int repositoryCount,
      List<String> repositories,
      List<String> sampleSources,
      String suggestedTermType,
      String suggestedProvenance,
      boolean existingInGlossary,
      int confidence,
      String rationale,
      String suggestedPartOfSpeech,
      String suggestedEnforcement,
      boolean suggestedDoNotTranslate,
      String extractionMethod) {}

  private final class CandidateAccumulator {
    private final String term;
    private final Set<String> repositoryNames = new LinkedHashSet<>();
    private final List<String> sampleSources = new ArrayList<>();
    private int occurrenceCount = 0;

    private CandidateAccumulator(String term) {
      this.term = term;
    }

    private void add(TextUnitDTO textUnitDTO) {
      occurrenceCount++;
      if (textUnitDTO.getRepositoryName() != null) {
        repositoryNames.add(textUnitDTO.getRepositoryName());
      }
      if (sampleSources.size() < 3
          && textUnitDTO.getSource() != null
          && sampleSources.stream().noneMatch(textUnitDTO.getSource()::equals)) {
        sampleSources.add(textUnitDTO.getSource());
      }
    }

    private ExtractedCandidateView toView(boolean existingInGlossary) {
      return new ExtractedCandidateView(
          term,
          occurrenceCount,
          repositoryNames.size(),
          new ArrayList<>(repositoryNames),
          List.copyOf(sampleSources),
          suggestTermType(term),
          GlossaryTermMetadata.PROVENANCE_AUTOMATED,
          existingInGlossary,
          40,
          null,
          null,
          GlossaryTermMetadata.ENFORCEMENT_SOFT,
          false,
          "HEURISTIC");
    }
  }
}
