package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TextUnitSourceCreatedBy;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryTermServiceTest {

  @Mock GlossaryRepository glossaryRepository;
  @Mock GlossaryStorageService glossaryStorageService;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  @Mock GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository;
  @Mock GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository;
  @Mock TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  @Mock TermIndexCandidateRepository termIndexCandidateRepository;
  @Mock GlossaryAiExtractionService glossaryAiExtractionService;
  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;
  @Mock TextUnitSearcher textUnitSearcher;
  @Mock VirtualAssetService virtualAssetService;
  @Mock VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService;
  @Mock TextUnitBatchImporterService textUnitBatchImporterService;
  @Mock TMTextUnitRepository tmTextUnitRepository;
  @Mock com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  @Mock LocaleService localeService;
  @Mock UserService userService;

  GlossaryTermService glossaryTermService;

  @Before
  public void setUp() {
    glossaryTermService =
        new GlossaryTermService(
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            glossaryTermIndexLinkRepository,
            glossaryTermTranslationProposalRepository,
            termIndexExtractedTermRepository,
            termIndexCandidateRepository,
            glossaryAiExtractionService,
            pollableTaskBlobStorage,
            textUnitSearcher,
            virtualAssetService,
            virtualTextUnitBatchUpdaterService,
            textUnitBatchImporterService,
            tmTextUnitRepository,
            repositoryRepository,
            localeService,
            userService);
  }

  @Test
  public void upsertTermBackfillsTermIndexLinkFromGlossarySourceLocale() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    TextUnitDTO sourceTextUnit = sourceTextUnit(3L, "new_chat", "New chat");
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(sourceTextUnit.getTmTextUnitId());
    TermIndexExtractedTerm extractedTerm = extractedTerm(4L, "en", "new chat", "New chat");
    AtomicReference<GlossaryTermIndexLink> savedLink = new AtomicReference<>();

    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(List.of(sourceTextUnit));
    when(tmTextUnitRepository.findById(sourceTextUnit.getTmTextUnitId()))
        .thenReturn(Optional.of(tmTextUnit));
    when(tmTextUnitRepository.findSourceCreatedByByIdIn(List.of(sourceTextUnit.getTmTextUnitId())))
        .thenReturn(List.of(sourceCreator(sourceTextUnit.getTmTextUnitId(), 101L)));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            glossary.getId(), sourceTextUnit.getTmTextUnitId()))
        .thenReturn(Optional.empty());
    when(glossaryTermMetadataRepository.save(any(GlossaryTermMetadata.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermMetadata metadata = invocation.getArgument(0);
              metadata.setId(5L);
              return metadata;
            });
    when(termIndexExtractedTermRepository.findBySourceLocaleTagAndNormalizedKey("en", "new chat"))
        .thenReturn(Optional.of(extractedTerm));
    when(termIndexCandidateRepository.findBySourceTypeAndSourceNameAndCandidateHash(
            anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(termIndexCandidateRepository.save(any(TermIndexCandidate.class)))
        .thenAnswer(
            invocation -> {
              TermIndexCandidate candidate = invocation.getArgument(0);
              candidate.setId(6L);
              return candidate;
            });
    when(glossaryTermIndexLinkRepository.findByGlossaryTermMetadataId(anyLong()))
        .thenReturn(List.of());
    when(glossaryTermIndexLinkRepository.save(any(GlossaryTermIndexLink.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermIndexLink link = invocation.getArgument(0);
              link.setId(7L);
              savedLink.set(link);
              return link;
            });
    when(glossaryTermIndexLinkRepository.findByGlossaryTermMetadataIdInAndRelationType(
            any(), eq(GlossaryTermIndexLink.RELATION_TYPE_PRIMARY)))
        .thenAnswer(invocation -> savedLink.get() == null ? List.of() : List.of(savedLink.get()));

    GlossaryTermService.TermView view =
        glossaryTermService.upsertTerm(
            glossary.getId(),
            null,
            new GlossaryTermService.TermUpsertCommand(
                sourceTextUnit.getName(),
                sourceTextUnit.getSource(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    verify(termIndexExtractedTermRepository)
        .findBySourceLocaleTagAndNormalizedKey("en", "new chat");
    verifyNoMoreInteractions(termIndexExtractedTermRepository);
    assertThat(view.termIndexExtractedTermId()).isEqualTo(extractedTerm.getId());
    assertThat(view.sourceCreatedBy().id()).isEqualTo(101L);
    assertThat(savedLink.get().getTermIndexCandidate().getSourceType())
        .isEqualTo(TermIndexCandidate.SOURCE_TYPE_EXTRACTION);
    assertThat(savedLink.get().getTermIndexCandidate().getSourceLocaleTag()).isEqualTo("en");
    assertThat(savedLink.get().getTermIndexCandidate().getTermIndexExtractedTerm())
        .isSameAs(extractedTerm);
  }

  @Test
  public void searchTermsSurfacesExtractedTermMatchWithoutCandidateLink() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    TextUnitDTO sourceTextUnit = sourceTextUnit(3L, "about_this_ad", "About this ad");
    TermIndexExtractedTerm extractedTerm =
        extractedTerm(4L, "en", "about this ad", "About this ad");

    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(List.of(sourceTextUnit));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(
            glossary.getId(), List.of(sourceTextUnit.getTmTextUnitId())))
        .thenReturn(List.of());
    when(termIndexExtractedTermRepository.findBySourceLocaleTagAndNormalizedKeyIn(
            "en", List.of("about this ad")))
        .thenReturn(List.of(extractedTerm));

    GlossaryTermService.SearchTermsView view =
        glossaryTermService.searchTerms(glossary.getId(), null, List.of(), 50);

    assertThat(view.terms()).hasSize(1);
    GlossaryTermService.TermView term = view.terms().get(0);
    assertThat(term.termIndexCandidateId()).isNull();
    assertThat(term.termIndexExtractedTermId()).isEqualTo(extractedTerm.getId());
    assertThat(term.termIndexOccurrenceCount()).isEqualTo(12L);
    assertThat(term.termIndexRepositoryCount()).isEqualTo(2);
  }

  @Test
  public void searchTermsCanScopeSearchToSourceOrDefinition() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    TextUnitDTO sourceMatch = sourceTextUnit(3L, "launch_plan", "Launch plan");
    TextUnitDTO definitionMatch = sourceTextUnit(4L, "billing", "Billing");
    definitionMatch.setComment("Launch plan terminology");

    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(List.of(sourceMatch, definitionMatch));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(
            glossary.getId(),
            List.of(sourceMatch.getTmTextUnitId(), definitionMatch.getTmTextUnitId())))
        .thenReturn(List.of());
    when(termIndexExtractedTermRepository.findBySourceLocaleTagAndNormalizedKeyIn(eq("en"), any()))
        .thenReturn(List.of());

    GlossaryTermService.SearchTermsView sourceView =
        glossaryTermService.searchTerms(
            glossary.getId(), "launch plan", GlossaryTermService.SearchField.SOURCE, List.of(), 50);
    GlossaryTermService.SearchTermsView definitionView =
        glossaryTermService.searchTerms(
            glossary.getId(),
            "launch plan",
            GlossaryTermService.SearchField.DEFINITION,
            List.of(),
            50);

    assertThat(sourceView.terms())
        .extracting(GlossaryTermService.TermView::source)
        .containsExactly("Launch plan");
    assertThat(definitionView.terms())
        .extracting(GlossaryTermService.TermView::source)
        .containsExactly("Billing");
  }

  @Test
  public void searchTermsLoadsSourceCreatorsInOneBatchWithoutGlossaryMetadata() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    List<TextUnitDTO> sources =
        List.of(
            sourceTextUnit(3L, "alpha", "Alpha"),
            sourceTextUnit(4L, "beta", "Beta"),
            sourceTextUnit(5L, "gamma", "Gamma"));
    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L)).thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(sources);
    when(tmTextUnitRepository.findSourceCreatedByByIdIn(List.of(3L, 4L, 5L)))
        .thenReturn(List.of(sourceCreator(3L, 101L), sourceCreator(4L, 102L)));

    List<GlossaryTermService.TermView> terms =
        glossaryTermService.searchTerms(1L, null, List.of(), 50).terms();

    assertThat(terms).hasSize(3);
    assertThat(terms.get(0).metadataId()).isNull();
    assertThat(terms.get(0).sourceCreatedBy())
        .isEqualTo(
            new GlossaryTermService.SourceCreatedByView(
                101L, "author101", "Alex", "Writer", "Alex Writer"));
    assertThat(terms.get(1).sourceCreatedBy().id()).isEqualTo(102L);
    assertThat(terms.get(2).sourceCreatedBy()).isNull();
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode serializedTerm = objectMapper.valueToTree(terms.get(0));
    assertThat(serializedTerm.get("sourceCreatedBy"))
        .isEqualTo(
            objectMapper.valueToTree(
                Map.of(
                    "id", 101L,
                    "username", "author101",
                    "givenName", "Alex",
                    "surname", "Writer",
                    "commonName", "Alex Writer")));
    JsonNode serializedUnknown = objectMapper.valueToTree(terms.get(2));
    assertThat(serializedUnknown.get("sourceCreatedBy").isNull()).isTrue();
    verify(tmTextUnitRepository).findSourceCreatedByByIdIn(List.of(3L, 4L, 5L));
    verifyNoMoreInteractions(tmTextUnitRepository);
  }

  @Test
  public void getTermReturnsCurrentSourceCreatorOrNullWithoutGlossaryMetadata() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L)).thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any()))
        .thenReturn(
            List.of(sourceTextUnit(3L, "alpha", "Alpha"), sourceTextUnit(4L, "beta", "Beta")));
    when(tmTextUnitRepository.findSourceCreatedByByIdIn(List.of(3L)))
        .thenReturn(List.of(sourceCreator(3L, 101L)));

    assertThat(glossaryTermService.getTerm(1L, 3L, List.of()).sourceCreatedBy().id())
        .isEqualTo(101L);
    assertThat(glossaryTermService.getTerm(1L, 4L, List.of()).sourceCreatedBy()).isNull();
  }

  @Test
  public void replacementReturnsCreatorOfReplacementSourceEvenWhenMetadataIsReused() {
    Glossary glossary = glossary(1L, "en");
    Asset asset = asset(2L, glossary.getBackingRepository());
    TextUnitDTO original = sourceTextUnit(3L, "workspace", "Workspace");
    TextUnitDTO replacement = sourceTextUnit(4L, "workspace", "Project workspace");
    TMTextUnit replacementTextUnit = new TMTextUnit();
    replacementTextUnit.setId(4L);
    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setId(5L);
    metadata.setGlossary(glossary);
    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L)).thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(List.of(original), List.of(replacement));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(1L, 3L))
        .thenReturn(Optional.of(metadata));
    when(glossaryTermMetadataRepository.save(metadata)).thenReturn(metadata);
    when(tmTextUnitRepository.findById(4L)).thenReturn(Optional.of(replacementTextUnit));
    when(tmTextUnitRepository.findSourceCreatedByByIdIn(List.of(4L)))
        .thenReturn(List.of(sourceCreator(4L, 102L)));

    GlossaryTermService.TermView term =
        glossaryTermService.upsertTerm(
            1L,
            3L,
            new GlossaryTermService.TermUpsertCommand(
                "workspace",
                "Project workspace",
                null,
                null,
                null,
                null,
                null,
                null,
                GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
                null,
                null,
                true,
                false,
                null,
                null,
                List.of()));

    assertThat(term.metadataId()).isEqualTo(5L);
    assertThat(term.tmTextUnitId()).isEqualTo(4L);
    assertThat(term.sourceCreatedBy().id()).isEqualTo(102L);
    assertThat(metadata.getTmTextUnit()).isSameAs(replacementTextUnit);
    verify(tmTextUnitRepository).findSourceCreatedByByIdIn(List.of(4L));
  }

  private TextUnitSourceCreatedBy sourceCreator(Long tmTextUnitId, Long userId) {
    return new TextUnitSourceCreatedBy(
        tmTextUnitId, userId, "author" + userId, "Alex", "Writer", "Alex Writer");
  }

  private Glossary glossary(Long id, String sourceLocaleTag) {
    Locale sourceLocale = new Locale();
    sourceLocale.setBcp47Tag(sourceLocaleTag);
    Repository backingRepository = new Repository();
    backingRepository.setId(10L);
    backingRepository.setName("glossary-backing");
    backingRepository.setSourceLocale(sourceLocale);
    Glossary glossary = new Glossary();
    glossary.setId(id);
    glossary.setName("Product UI");
    glossary.setBackingRepository(backingRepository);
    return glossary;
  }

  private Asset asset(Long id, Repository repository) {
    Asset asset = new Asset();
    asset.setId(id);
    asset.setRepository(repository);
    asset.setPath("glossary");
    asset.setVirtual(true);
    return asset;
  }

  private TextUnitDTO sourceTextUnit(Long tmTextUnitId, String name, String source) {
    TextUnitDTO sourceTextUnit = new TextUnitDTO();
    sourceTextUnit.setTmTextUnitId(tmTextUnitId);
    sourceTextUnit.setName(name);
    sourceTextUnit.setSource(source);
    return sourceTextUnit;
  }

  private TermIndexExtractedTerm extractedTerm(
      Long id, String sourceLocaleTag, String normalizedKey, String displayTerm) {
    TermIndexExtractedTerm extractedTerm = new TermIndexExtractedTerm();
    extractedTerm.setId(id);
    extractedTerm.setSourceLocaleTag(sourceLocaleTag);
    extractedTerm.setNormalizedKey(normalizedKey);
    extractedTerm.setDisplayTerm(displayTerm);
    extractedTerm.setOccurrenceCount(12L);
    extractedTerm.setRepositoryCount(2);
    return extractedTerm;
  }
}
