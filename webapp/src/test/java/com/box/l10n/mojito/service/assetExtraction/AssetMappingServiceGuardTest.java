package com.box.l10n.mojito.service.assetExtraction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.CmsManagedVirtualAssetGuard;
import java.util.Optional;
import org.junit.Test;

public class AssetMappingServiceGuardTest {

  @Test
  public void guardsAssetExtractionOwnerBeforeDuplicateAssetParameter() {
    AssetMappingService service = new AssetMappingService();
    service.assetExtractionRepository = mock(AssetExtractionRepository.class);
    service.assetRepository = mock(AssetRepository.class);
    service.cmsManagedVirtualAssetGuard = mock(CmsManagedVirtualAssetGuard.class);

    Asset extractionAsset = new Asset();
    Asset duplicateAssetParameter = new Asset();
    AssetExtraction assetExtraction = new AssetExtraction();
    assetExtraction.setAsset(extractionAsset);
    when(service.assetExtractionRepository.findById(1L)).thenReturn(Optional.of(assetExtraction));
    when(service.assetRepository.findById(2L)).thenReturn(Optional.of(duplicateAssetParameter));

    service.requireGenericMutationAllowed(1L, 2L);

    verify(service.cmsManagedVirtualAssetGuard).requireGenericMutationAllowed(extractionAsset);
    verify(service.cmsManagedVirtualAssetGuard)
        .requireGenericMutationAllowed(duplicateAssetParameter);
  }
}
