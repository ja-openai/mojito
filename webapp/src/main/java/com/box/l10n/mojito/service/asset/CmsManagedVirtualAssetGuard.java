package com.box.l10n.mojito.service.asset;

import com.box.l10n.mojito.entity.Asset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CmsManagedVirtualAssetGuard {

  @Autowired AssetRepository assetRepository;

  // CMS mappings reuse generic virtual text-unit plumbing only for their owned asset write path.
  private final ThreadLocal<Deque<Long>> cmsManagedMutationAssetIds = new ThreadLocal<>();

  public void requireGenericMutationAllowed(Asset asset) {
    if (asset != null
        && (Boolean.TRUE.equals(asset.getCmsManaged())
            || (asset.getId() != null
                && assetRepository.countCmsContentProjectsByAssetId(asset.getId()) > 0))
        && !isCmsManagedMutationAllowed(asset)) {
      throw new CmsManagedVirtualAssetMutationException(
          "CMS content project virtual assets must be edited through /api/content-cms endpoints");
    }
  }

  public void runCmsManagedMutation(Asset asset, CmsManagedMutation mutation)
      throws VirtualAssetRequiredException {
    Deque<Long> scopedAssetIds = cmsManagedMutationAssetIds.get();
    if (scopedAssetIds == null) {
      scopedAssetIds = new ArrayDeque<>();
      cmsManagedMutationAssetIds.set(scopedAssetIds);
    }
    Asset cmsManagedAsset = Objects.requireNonNull(asset, "CMS mutation asset");
    scopedAssetIds.push(Objects.requireNonNull(cmsManagedAsset.getId(), "CMS mutation asset id"));
    try {
      mutation.run();
    } finally {
      scopedAssetIds.pop();
      if (scopedAssetIds.isEmpty()) {
        cmsManagedMutationAssetIds.remove();
      }
    }
  }

  private boolean isCmsManagedMutationAllowed(Asset asset) {
    Deque<Long> scopedAssetIds = cmsManagedMutationAssetIds.get();
    return scopedAssetIds != null
        && asset.getId() != null
        && scopedAssetIds.contains(asset.getId());
  }

  @FunctionalInterface
  public interface CmsManagedMutation {
    void run() throws VirtualAssetRequiredException;
  }
}
