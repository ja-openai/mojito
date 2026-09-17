package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.fileformat.LocalizationFileConverters;
import com.box.l10n.mojito.fileformat.LocalizationFileFormat;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.fileformat.MdxDocument;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.messageformat.Mf2TranslationIntegrityEvaluator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Fill missing targets only, preserving human work under concurrent imports and editor saves. */
@Service
public class ContentTranslationCandidateService {
  private final UserService users;
  private final RepositoryLocaleRepository locales;
  private final EntityManager entityManager;
  private final TMTextUnitCurrentVariantRepository currents;
  private final ContentTranslationCandidateQueries queries;
  private final TMTextUnitIntegrityCheckService integrity;
  private final TMService tm;

  public ContentTranslationCandidateService(
      UserService users,
      RepositoryLocaleRepository locales,
      EntityManager entityManager,
      TMTextUnitCurrentVariantRepository currents,
      ContentTranslationCandidateQueries queries,
      TMTextUnitIntegrityCheckService integrity,
      TMService tm) {
    this.users = users;
    this.locales = locales;
    this.entityManager = entityManager;
    this.currents = currents;
    this.queries = queries;
    this.integrity = integrity;
    this.tm = tm;
  }

  @Transactional(readOnly = true)
  public ContentTranslationCandidate.Source source(Long repositoryId, Long branchId, Long assetId) {
    if (!users.isCurrentUserAdminOrPm())
      throw new AccessDeniedException(
          "Candidate source lookup requires an administrator or project manager");
    if (!positive(repositoryId) || !positive(branchId) || !positive(assetId))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Select a repository, branch, and content asset");
    var source = queries.findCurrentSource(repositoryId, branchId, assetId);
    if (source == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Content asset has no successful extraction on this branch");
    return source;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ContentTranslationCandidate.Result create(
      Long repositoryId, ContentTranslationCandidate request) {
    if (!users.isCurrentUserAdminOrPm())
      throw new AccessDeniedException(
          "Candidate import requires an administrator or project manager");
    validateRequest(repositoryId, request);
    users.checkUserCanEditLocale(request.localeId());
    var locale = locales.findByRepositoryIdAndLocaleId(repositoryId, request.localeId());
    if (locale == null || locale.getParentLocale() == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Select a configured target locale");

    // Same parent-before-current order as Workbench feedback. Variant/current inserts also
    // acquire a parent lock through their foreign keys, closing the absent-current-row race.
    var unit =
        entityManager.find(
            TMTextUnit.class, request.tmTextUnitId(), LockModeType.PESSIMISTIC_WRITE);
    if (unit == null) throw conflict("Source text unit no longer exists");
    entityManager.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    var asset = unit.getAsset();
    if (asset == null
        || !Objects.equals(asset.getId(), request.assetId())
        || !Objects.equals(asset.getRepository().getId(), repositoryId)
        || Boolean.TRUE.equals(asset.getDeleted())
        || Boolean.TRUE.equals(asset.getRepository().getDeleted())
        || Objects.equals(asset.getRepository().getSourceLocale().getId(), request.localeId())
        || !Objects.equals(unit.getName(), request.name())
        || !Objects.equals(unit.getContent(), request.expectedSource()))
      throw conflict("Source identity changed; refresh candidates");
    var current =
        currents.findForUpdateByLocaleIdAndTmTextUnitId(request.localeId(), request.tmTextUnitId());
    if (current != null) entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
    if (current != null || queries.hasTargetHistory(request.tmTextUnitId(), request.localeId()))
      throw conflict("A translation or translation history already exists; review it instead");
    var extraction =
        queries.lockCurrentExtraction(repositoryId, request.branchId(), request.assetId());
    if (extraction == null
        || !Objects.equals(extraction.id(), request.expectedAssetExtractionId())
        || !Objects.equals(extraction.contentMd5(), request.expectedAssetContentMd5())
        || !queries.containsUnit(extraction.id(), request.assetId(), request.tmTextUnitId()))
      throw conflict("The branch extraction changed; refresh candidates");

    String target = NormalizationUtils.normalize(request.target());
    validateContent(unit, target, locale.getLocale().getBcp47Tag());
    try {
      // Unlike interactive manager overrides, generated candidates always run configured checks.
      integrity.checkTMTextUnitIntegrity(unit.getId(), target, request.localeId());
    } catch (IntegrityCheckException exception) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
    }
    var saved =
        tm.addTMTextUnitCurrentVariant(
            unit.getId(),
            request.localeId(),
            target,
            "Generated translation candidate",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true);
    entityManager.flush();
    var variant = saved.getTmTextUnitVariant();
    return new ContentTranslationCandidate.Result(
        unit.getId(), variant.getId(), variant.getStatus(), variant.getContent());
  }

  private static void validateRequest(Long repositoryId, ContentTranslationCandidate request) {
    if (request == null
        || !positive(repositoryId)
        || !positive(request.branchId())
        || !positive(request.assetId())
        || !positive(request.tmTextUnitId())
        || !positive(request.localeId())
        || !positive(request.expectedAssetExtractionId())
        || request.expectedAssetContentMd5() == null
        || !request.expectedAssetContentMd5().matches("[0-9a-f]{32}")
        || request.expectedVariantId() != null
        || request.name() == null
        || request.name().isBlank()
        || request.name().length() > 1024
        || request.expectedSource() == null
        || request.expectedSource().length() > 10000
        || request.target() == null
        || request.target().isBlank()
        || request.target().length() > 10000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Provide a bounded candidate with exact source identity and an absent variant baseline");
  }

  static void validateContent(TMTextUnit unit, String target, String localeTag) {
    String path = unit.getAsset().getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".mf2.json")) {
      var evaluation =
          Mf2TranslationIntegrityEvaluator.evaluate(unit.getContent(), target, localeTag);
      if (evaluation.disposition() != TranslationIntegrityDisposition.PASS
          || evaluation.diagnostics().stream()
              .anyMatch(d -> d.code().equals("mf2-rendered-expression-missing")))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Invalid MF2 candidate: " + evaluation.diagnostics());
    } else if (path.endsWith(".mdx")) {
      // Without a document payload, single-line inputs retain their line boundary. This
      // conservatively preserves heading/list constraints as well as paragraph structure.
      if (!unit.getContent().contains("\n")
          && !unit.getContent().contains("\r")
          && (target.contains("\n") || target.contains("\r")))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "A single-line MDX source requires a single-line candidate");
      // Validate the fragment with the same source-preserving MDX insertion rules as pull.
      String fragment = "{/* mojito-id: candidate */}\n" + unit.getContent() + "\n";
      try {
        var blocks =
            MdxDocument.parse(fragment.getBytes(StandardCharsets.UTF_8)).blocks().stream()
                .filter(MdxDocument.Block::translatable)
                .toList();
        if (blocks.size() != 1 || !blocks.getFirst().source().equals(unit.getContent()))
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported MDX candidate fragment");
        var skeleton =
            LocalizationFileConverters.extractSkeleton(
                LocalizationFileFormat.MDX, fragment.getBytes(StandardCharsets.UTF_8));
        LocalizationFileConverters.renderSkeleton(skeleton, Map.of("candidate", target));
      } catch (LocalizationParseException exception) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
      }
    } else {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Candidate import supports MDX and MF2 JSON content assets");
    }
  }

  private static boolean positive(Long value) {
    return value != null && value > 0;
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
