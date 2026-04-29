package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.VirtualAsset;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleCreationException;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryNameAlreadyUsedException;
import com.box.l10n.mojito.service.repository.RepositoryService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryStorageService {

  public static final String DEFAULT_ASSET_PATH = "glossary";
  private static final String BACKING_REPOSITORY_PREFIX = "glossary-";
  private static final int BACKING_REPOSITORY_CREATE_ATTEMPTS = 5;

  private final RepositoryService repositoryService;
  private final AssetRepository assetRepository;
  private final VirtualAssetService virtualAssetService;
  private final LocaleService localeService;
  private final RepositoryLocaleRepository repositoryLocaleRepository;

  public GlossaryStorageService(
      RepositoryService repositoryService,
      AssetRepository assetRepository,
      VirtualAssetService virtualAssetService,
      LocaleService localeService,
      RepositoryLocaleRepository repositoryLocaleRepository) {
    this.repositoryService = repositoryService;
    this.assetRepository = assetRepository;
    this.virtualAssetService = virtualAssetService;
    this.localeService = localeService;
    this.repositoryLocaleRepository = repositoryLocaleRepository;
  }

  @Transactional
  public Repository createManagedBackingRepository(String glossaryName) {
    String baseName = buildBackingRepositoryName(glossaryName);
    RepositoryNameAlreadyUsedException lastException = null;

    for (int attempt = 0; attempt < BACKING_REPOSITORY_CREATE_ATTEMPTS; attempt++) {
      String candidateName = attempt == 0 ? baseName : appendUniqueSuffix(baseName, randomSuffix());
      try {
        return repositoryService.createRepository(
            candidateName,
            "Managed glossary backing repository for " + glossaryName,
            localeService.getDefaultLocale(),
            false);
      } catch (RepositoryNameAlreadyUsedException ex) {
        lastException = ex;
      }
    }

    throw new IllegalStateException(
        "Failed to create managed glossary backing repository", lastException);
  }

  @Transactional
  public Asset ensureCanonicalAsset(Glossary glossary) {
    String assetPath = normalizeAssetPath(glossary.getAssetPath());
    glossary.setAssetPath(assetPath);

    Asset asset =
        assetRepository.findByPathAndRepositoryId(
            assetPath, glossary.getBackingRepository().getId());
    if (asset != null) {
      return asset;
    }

    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(glossary.getBackingRepository().getId());
    virtualAsset.setPath(assetPath);
    virtualAsset.setDeleted(false);
    try {
      VirtualAsset saved = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);
      return assetRepository.findById(saved.getId()).orElseThrow();
    } catch (VirtualAssetBadRequestException ex) {
      throw new IllegalStateException(
          "Failed to create canonical glossary asset for glossary " + glossary.getId(), ex);
    }
  }

  @Transactional
  public RepositoryLocale ensureLocale(Glossary glossary, String bcp47Tag) {
    Repository backingRepository = glossary.getBackingRepository();
    RepositoryLocale repositoryLocale =
        repositoryLocaleRepository.findByRepositoryAndLocale_Bcp47Tag(backingRepository, bcp47Tag);
    if (repositoryLocale != null) {
      return repositoryLocale;
    }

    try {
      return repositoryService.addRepositoryLocale(backingRepository, bcp47Tag);
    } catch (RepositoryLocaleCreationException ex) {
      throw new IllegalArgumentException(
          "Could not enable locale " + bcp47Tag + " for glossary " + glossary.getName(), ex);
    }
  }

  @Transactional
  public void replaceLocales(Glossary glossary, List<String> localeTags) {
    Repository backingRepository = glossary.getBackingRepository();
    RepositoryLocale rootLocale =
        repositoryLocaleRepository.findByRepositoryAndParentLocaleIsNull(backingRepository);
    if (rootLocale == null) {
      throw new IllegalStateException(
          "Backing repository is missing a root locale for glossary " + glossary.getName());
    }

    Set<String> normalizedLocaleTags = new LinkedHashSet<>();
    String rootLocaleTag = rootLocale.getLocale().getBcp47Tag();
    for (String localeTag : localeTags == null ? List.<String>of() : localeTags) {
      if (localeTag == null || localeTag.isBlank()) {
        continue;
      }

      com.box.l10n.mojito.entity.Locale locale = localeService.findByBcp47Tag(localeTag.trim());
      if (locale == null) {
        throw new IllegalArgumentException("Unknown locale: " + localeTag.trim());
      }
      if (locale.getBcp47Tag().equalsIgnoreCase(rootLocaleTag)) {
        continue;
      }

      normalizedLocaleTags.add(locale.getBcp47Tag());
    }

    Set<RepositoryLocale> repositoryLocales = new LinkedHashSet<>();
    for (String localeTag : normalizedLocaleTags) {
      com.box.l10n.mojito.entity.Locale locale = localeService.findByBcp47Tag(localeTag);
      if (locale == null) {
        throw new IllegalArgumentException("Unknown locale: " + localeTag);
      }
      repositoryLocales.add(new RepositoryLocale(backingRepository, locale, true, null));
    }

    retainOnlyRootLocaleInMemory(backingRepository, rootLocale);

    if (repositoryLocales.isEmpty()) {
      repositoryLocaleRepository.deleteByRepositoryAndParentLocaleIsNotNull(backingRepository);
      return;
    }

    try {
      repositoryService.updateRepositoryLocales(backingRepository, repositoryLocales);
    } catch (RepositoryLocaleCreationException ex) {
      throw new IllegalArgumentException(
          "Could not update locales for glossary " + glossary.getName(), ex);
    }
  }

  @Transactional
  public void deleteManagedBackingRepository(Glossary glossary) {
    Repository backingRepository = glossary.getBackingRepository();
    if (backingRepository == null || Boolean.TRUE.equals(backingRepository.getDeleted())) {
      return;
    }
    repositoryService.deleteRepository(backingRepository);
  }

  @Transactional
  public void renameManagedBackingRepository(Glossary glossary, String repositoryName) {
    Repository backingRepository = glossary.getBackingRepository();
    if (backingRepository == null || Boolean.TRUE.equals(backingRepository.getDeleted())) {
      throw new IllegalStateException(
          "Backing repository is missing for glossary " + glossary.getName());
    }

    String normalizedName = normalizeBackingRepositoryName(repositoryName);
    if (normalizedName.equals(backingRepository.getName())) {
      return;
    }

    try {
      repositoryService.renameRepository(backingRepository, normalizedName);
    } catch (RepositoryNameAlreadyUsedException ex) {
      throw new IllegalArgumentException("Repository already exists: " + normalizedName, ex);
    }
  }

  private void retainOnlyRootLocaleInMemory(
      Repository backingRepository, RepositoryLocale rootLocale) {
    backingRepository.getRepositoryLocales().clear();
    backingRepository.getRepositoryLocales().add(rootLocale);
  }

  private String buildBackingRepositoryName(String glossaryName) {
    String slug =
        glossaryName == null
            ? "glossary"
            : glossaryName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    if (slug.isBlank()) {
      slug = "glossary";
    }
    return StringUtils.left(BACKING_REPOSITORY_PREFIX + slug, Repository.NAME_MAX_LENGTH);
  }

  private String appendUniqueSuffix(String baseName, String suffix) {
    int maxBaseLength = Repository.NAME_MAX_LENGTH - suffix.length() - 1;
    String truncatedBase = StringUtils.left(baseName, Math.max(1, maxBaseLength));
    truncatedBase = truncatedBase.replaceAll("-+$", "");
    return truncatedBase + "-" + suffix;
  }

  private String normalizeBackingRepositoryName(String repositoryName) {
    String normalized = repositoryName == null ? "" : repositoryName.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Backing repository name is required");
    }
    if (normalized.length() > Repository.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Backing repository name must be at most " + Repository.NAME_MAX_LENGTH + " characters");
    }
    return normalized;
  }

  private String randomSuffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private String normalizeAssetPath(String assetPath) {
    String normalized = assetPath == null ? "" : assetPath.trim();
    if (normalized.isEmpty()) {
      return DEFAULT_ASSET_PATH;
    }
    return StringUtils.abbreviate(normalized, Glossary.ASSET_PATH_MAX_LENGTH);
  }
}
