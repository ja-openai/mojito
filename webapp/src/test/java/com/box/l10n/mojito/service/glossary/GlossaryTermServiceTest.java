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
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
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
