package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import java.util.Objects;

/** Excludes pull-run tracking until queue workers can fence business lineage writes. */
public final class AssetLocalizeAsyncJobEligibility {

  private AssetLocalizeAsyncJobEligibility() {}

  public static boolean isEligible(LocalizedAssetBody input) {
    // TMService tracks every non-null name, including empty and whitespace-only names.
    return Objects.requireNonNull(input).getPullRunName() == null;
  }

  public static void requireEligible(LocalizedAssetBody input) {
    if (!isEligible(input)) {
      throw new IllegalStateException(
          "Asset localize async queue does not support pull-run tracking");
    }
  }
}
