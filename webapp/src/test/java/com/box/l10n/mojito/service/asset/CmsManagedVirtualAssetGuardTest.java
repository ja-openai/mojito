package com.box.l10n.mojito.service.asset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.entity.Asset;
import org.junit.Test;

public class CmsManagedVirtualAssetGuardTest {

  @Test
  public void allowsCmsManagedMutationOnlyInsideScopedCallback() throws Exception {
    CmsManagedVirtualAssetGuard guard = new CmsManagedVirtualAssetGuard();
    guard.assetRepository = mock(AssetRepository.class);
    Asset asset = new Asset();
    asset.setId(1L);
    asset.setCmsManaged(Boolean.TRUE);
    Asset otherAsset = new Asset();
    otherAsset.setId(2L);
    otherAsset.setCmsManaged(Boolean.TRUE);

    assertThatThrownBy(() -> guard.requireGenericMutationAllowed(asset))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class);

    guard.runCmsManagedMutation(asset, () -> guard.requireGenericMutationAllowed(asset));
    assertThatThrownBy(
            () ->
                guard.runCmsManagedMutation(
                    asset, () -> guard.requireGenericMutationAllowed(otherAsset)))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class);

    assertThatThrownBy(() -> guard.requireGenericMutationAllowed(asset))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class);
  }

  @Test
  public void clearsCmsManagedMutationScopeAfterFailure() throws Exception {
    CmsManagedVirtualAssetGuard guard = new CmsManagedVirtualAssetGuard();
    guard.assetRepository = mock(AssetRepository.class);
    Asset asset = new Asset();
    asset.setId(1L);
    asset.setCmsManaged(Boolean.TRUE);

    assertThatThrownBy(
            () ->
                guard.runCmsManagedMutation(
                    asset,
                    () -> {
                      throw new VirtualAssetRequiredException("failed CMS mutation");
                    }))
        .isInstanceOf(VirtualAssetRequiredException.class);

    assertThatThrownBy(() -> guard.requireGenericMutationAllowed(asset))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class);
  }
}
