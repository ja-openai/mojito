package com.box.l10n.mojito.service.stringauthoring;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.box.l10n.mojito.service.branch.BranchService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class StringAuthoringService {

  public static final String AUTHORING_BRANCH_PREFIX = "authoring/";

  private static final int DEFAULT_ASSET_LIMIT = 100;
  private static final int MAX_ASSET_LIMIT = 500;
  private static final int BRANCH_LIMIT = 500;
  private static final int DEFAULT_AUTHORING_BRANCH_CLEANUP_DAYS = 7;

  private final RepositoryRepository repositoryRepository;
  private final AssetRepository assetRepository;
  private final BranchRepository branchRepository;
  private final BranchService branchService;
  private final AssetService assetService;
  private final TextUnitSearcher textUnitSearcher;
  private final ObjectMapper objectMapper;

  public StringAuthoringService(
      RepositoryRepository repositoryRepository,
      AssetRepository assetRepository,
      BranchRepository branchRepository,
      BranchService branchService,
      AssetService assetService,
      TextUnitSearcher textUnitSearcher,
      ObjectMapper objectMapper) {
    this.repositoryRepository = repositoryRepository;
    this.assetRepository = assetRepository;
    this.branchRepository = branchRepository;
    this.branchService = branchService;
    this.assetService = assetService;
    this.textUnitSearcher = textUnitSearcher;
    this.objectMapper = objectMapper;
  }

  public List<StringAuthoringAsset> getAssets(Long repositoryId, String search, Integer limit) {
    requireRepository(repositoryId);
    String normalizedSearch = StringUtils.trimToEmpty(search);
    int normalizedLimit = normalizeLimit(limit);
    Pageable page = PageRequest.of(0, normalizedLimit);
    List<Asset> assets =
        normalizedSearch.isEmpty()
            ? assetRepository.findByRepositoryIdAndDeletedFalseAndVirtualFalseOrderByPathAsc(
                repositoryId, page)
            : assetRepository
                .findByRepositoryIdAndDeletedFalseAndVirtualFalseAndPathContainingIgnoreCaseOrderByPathAsc(
                    repositoryId, normalizedSearch, page);

    return assets.stream()
        .limit(normalizedLimit)
        .map(asset -> new StringAuthoringAsset(asset.getId(), asset.getPath()))
        .toList();
  }

  public List<StringAuthoringBranch> getBranches(Long repositoryId, BranchScope scope) {
    requireRepository(repositoryId);
    BranchScope normalizedScope = Objects.requireNonNullElse(scope, BranchScope.AUTHORING);
    Pageable branchPage = PageRequest.of(0, BRANCH_LIMIT);
    List<Branch> branches =
        normalizedScope == BranchScope.ALL
            ? branchRepository.findByRepositoryIdAndDeletedFalseOrderByCreatedDateDescNameAsc(
                repositoryId, branchPage)
            : branchRepository
                .findByRepositoryIdAndDeletedFalseAndNameStartingWithOrderByCreatedDateDescNameAsc(
                    repositoryId, AUTHORING_BRANCH_PREFIX, branchPage);

    return branches.stream()
        .map(
            branch ->
                new StringAuthoringBranch(
                    branch.getId(),
                    branch.getName(),
                    isAuthoring(branch),
                    branch.getCreatedDate(),
                    branch.getCleanupDate()))
        .toList();
  }

  public List<StringAuthoringStringRow> getStrings(
      Long repositoryId,
      String branchName,
      String assetPath,
      StringAuthoringUsedFilter usedFilter,
      Integer limit) {
    Repository repository = requireRepository(repositoryId);
    String normalizedBranchName = normalizeBranchName(branchName);
    String normalizedAssetPath = NormalizationUtils.normalize(StringUtils.trimToNull(assetPath));
    StringAuthoringUsedFilter normalizedUsedFilter =
        Objects.requireNonNullElse(usedFilter, StringAuthoringUsedFilter.USED);
    Branch branch = branchRepository.findByNameAndRepository(normalizedBranchName, repository);
    if (branch == null) {
      return List.of();
    }

    TextUnitSearcherParameters searchParameters = new TextUnitSearcherParameters();
    searchParameters.setRepositoryIds(repositoryId);
    searchParameters.setBranchId(branch.getId());
    searchParameters.setUsedFilter(toSearcherUsedFilter(normalizedUsedFilter));
    searchParameters.setForRootLocale(true);
    searchParameters.setPluralFormsFiltered(false);
    searchParameters.setLimit(normalizeLimit(limit));
    if (normalizedAssetPath != null) {
      searchParameters.setAssetPath(normalizedAssetPath);
      searchParameters.setSearchType(SearchType.EXACT);
    }

    return textUnitSearcher.search(searchParameters).stream().map(this::toStringRow).toList();
  }

  public StringAuthoringSaveResult saveStrings(
      Long repositoryId, StringAuthoringSaveRequest request)
      throws ExecutionException, InterruptedException, UnsupportedAssetFilterTypeException {
    Repository repository = requireRepository(repositoryId);
    StringAuthoringSaveRequest normalizedRequest = normalizeSaveRequest(request);
    List<AssetExtractorTextUnit> extractedTextUnits =
        normalizedRequest.strings().stream().map(this::toAssetExtractorTextUnit).toList();
    String extractedContent = objectMapper.writeValueAsStringUnchecked(extractedTextUnits);

    PollableFuture<Asset> assetFuture =
        assetService.addOrUpdateAssetAndProcessIfNeeded(
            repositoryId,
            normalizedRequest.assetPath(),
            extractedContent,
            true,
            normalizedRequest.branchName(),
            null,
            null,
            null,
            null,
            List.of());
    ZonedDateTime cleanupDate =
        updateAuthoringBranchCleanupDate(
            repository, normalizedRequest.branchName(), normalizedRequest.cleanupDate());

    return new StringAuthoringSaveResult(
        normalizedRequest.assetPath(),
        normalizedRequest.branchName(),
        extractedTextUnits.size(),
        cleanupDate,
        assetFuture.getPollableTask());
  }

  public StringAuthoringDeleteBranchResult deleteBranch(Long repositoryId, Long branchId) {
    requireRepository(repositoryId);
    if (branchId == null) {
      throw new IllegalArgumentException("A branch id must be provided");
    }
    Branch branch =
        branchRepository
            .findByIdAndRepositoryIdAndDeletedFalse(branchId, repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
    if (!isAuthoring(branch)) {
      throw new IllegalArgumentException("Only authoring branches can be deleted from this page");
    }
    PollableFuture<Void> branchDeleteFuture =
        branchService.asyncDeleteBranch(repositoryId, branchId);
    return new StringAuthoringDeleteBranchResult(
        branch.getId(), branch.getName(), branchDeleteFuture.getPollableTask());
  }

  private Repository requireRepository(Long repositoryId) {
    if (repositoryId == null) {
      throw new IllegalArgumentException("A repository id must be provided");
    }
    return repositoryRepository
        .findById(repositoryId)
        .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
  }

  private StringAuthoringSaveRequest normalizeSaveRequest(StringAuthoringSaveRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("A request body must be provided");
    }
    String branchName = normalizeBranchName(request.branchName());

    List<StringAuthoringString> requestedStrings =
        request.strings() == null ? List.of() : request.strings();
    List<StringAuthoringString> strings =
        requestedStrings.stream().map(this::normalizeString).toList();

    String assetPath = NormalizationUtils.normalize(StringUtils.trimToNull(request.assetPath()));
    if (assetPath == null) {
      assetPath = defaultAssetPath(branchName);
    }

    return new StringAuthoringSaveRequest(assetPath, branchName, strings, request.cleanupDate());
  }

  StringAuthoringString normalizeString(StringAuthoringString string) {
    if (string == null) {
      throw new IllegalArgumentException("String entries cannot be null");
    }
    String name = NormalizationUtils.normalize(StringUtils.trimToNull(string.name()));
    String source = NormalizationUtils.normalize(Objects.requireNonNullElse(string.source(), ""));
    String comment = NormalizationUtils.normalize(string.comment());
    boolean shouldGenerateId = Boolean.TRUE.equals(string.generateId());
    if (name == null) {
      if (!shouldGenerateId) {
        throw new IllegalArgumentException(
            "A string id must be provided unless md5 id generation is explicitly enabled");
      }
      if (StringUtils.isBlank(source)) {
        throw new IllegalArgumentException("Source text must be provided to generate an md5 id");
      }
      name = generateStringId(source, comment);
    } else if (shouldGenerateId) {
      throw new IllegalArgumentException(
          "md5 id generation cannot be enabled when a string id is provided");
    }
    return new StringAuthoringString(
        name,
        source,
        comment,
        NormalizationUtils.normalize(string.pluralForm()),
        NormalizationUtils.normalize(string.pluralFormOther()),
        string.usages(),
        false);
  }

  static String generateStringId(String source, String comment) {
    return DigestUtils.md5Hex(
        Objects.requireNonNullElse(source, "") + Objects.requireNonNullElse(comment, ""));
  }

  private String normalizeBranchName(String branchName) {
    String normalizedBranchName = NormalizationUtils.normalize(StringUtils.trimToNull(branchName));
    if (normalizedBranchName == null) {
      throw new IllegalArgumentException("An authoring branch name must be provided");
    }
    if (!isAuthoringBranchName(normalizedBranchName)) {
      throw new IllegalArgumentException(
          "Authoring branch names must start with "
              + AUTHORING_BRANCH_PREFIX
              + " and include a name");
    }
    return normalizedBranchName;
  }

  private StringAuthoringStringRow toStringRow(TextUnitDTO textUnit) {
    return new StringAuthoringStringRow(
        textUnit.getTmTextUnitId(),
        textUnit.getAssetId(),
        textUnit.getAssetPath(),
        textUnit.getName(),
        textUnit.getSource(),
        textUnit.getComment(),
        textUnit.getPluralForm(),
        textUnit.getPluralFormOther(),
        textUnit.isUsed(),
        textUnit.getTmTextUnitCreatedDate());
  }

  private AssetExtractorTextUnit toAssetExtractorTextUnit(StringAuthoringString string) {
    AssetExtractorTextUnit textUnit = new AssetExtractorTextUnit();
    textUnit.setName(string.name());
    textUnit.setSource(string.source());
    textUnit.setComments(string.comment());
    textUnit.setPluralForm(string.pluralForm());
    textUnit.setPluralFormOther(string.pluralFormOther());
    textUnit.setUsages(string.usages());
    return textUnit;
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_ASSET_LIMIT;
    }
    return Math.min(limit, MAX_ASSET_LIMIT);
  }

  private boolean isAuthoring(Branch branch) {
    return isAuthoringBranchName(branch.getName());
  }

  private boolean isAuthoringBranchName(String branchName) {
    return branchName != null
        && branchName.startsWith(AUTHORING_BRANCH_PREFIX)
        && branchName.length() > AUTHORING_BRANCH_PREFIX.length();
  }

  private UsedFilter toSearcherUsedFilter(StringAuthoringUsedFilter usedFilter) {
    return switch (usedFilter) {
      case USED -> UsedFilter.USED;
      case UNUSED -> UsedFilter.UNUSED;
      case ALL -> null;
    };
  }

  private ZonedDateTime updateAuthoringBranchCleanupDate(
      Repository repository, String branchName, ZonedDateTime requestedCleanupDate) {
    Branch branch = branchRepository.findByNameAndRepository(branchName, repository);
    if (branch == null || !isAuthoring(branch)) {
      return requestedCleanupDate;
    }

    ZonedDateTime cleanupDate =
        Objects.requireNonNullElseGet(requestedCleanupDate, branch::getCleanupDate);
    if (cleanupDate == null) {
      cleanupDate = ZonedDateTime.now().plusDays(DEFAULT_AUTHORING_BRANCH_CLEANUP_DAYS);
    }
    if (!Objects.equals(branch.getCleanupDate(), cleanupDate)) {
      branch.setCleanupDate(cleanupDate);
      branchRepository.save(branch);
    }
    return cleanupDate;
  }

  private String defaultAssetPath(String branchName) {
    String slug = branchName.substring(AUTHORING_BRANCH_PREFIX.length());
    return AUTHORING_BRANCH_PREFIX + slug + "/strings.json";
  }

  public enum BranchScope {
    AUTHORING,
    ALL
  }

  public enum StringAuthoringUsedFilter {
    USED,
    UNUSED,
    ALL
  }

  public record StringAuthoringAsset(Long id, String path) {}

  public record StringAuthoringBranch(
      Long id,
      String name,
      boolean authoring,
      ZonedDateTime createdDate,
      ZonedDateTime cleanupDate) {}

  public record StringAuthoringString(
      String name,
      String source,
      String comment,
      String pluralForm,
      String pluralFormOther,
      Set<String> usages,
      Boolean generateId) {}

  public record StringAuthoringStringRow(
      Long tmTextUnitId,
      Long assetId,
      String assetPath,
      String name,
      String source,
      String comment,
      String pluralForm,
      String pluralFormOther,
      boolean used,
      ZonedDateTime createdDate) {}

  public record StringAuthoringSaveRequest(
      String assetPath,
      String branchName,
      List<StringAuthoringString> strings,
      ZonedDateTime cleanupDate) {}

  public record StringAuthoringSaveResult(
      String assetPath,
      String branchName,
      int stringCount,
      ZonedDateTime cleanupDate,
      PollableTask pollableTask) {}

  public record StringAuthoringDeleteBranchResult(
      Long id, String name, PollableTask pollableTask) {}
}
