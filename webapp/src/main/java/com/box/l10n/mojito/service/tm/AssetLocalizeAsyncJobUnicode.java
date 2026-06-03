package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import java.util.List;

/** Checks decoded strings too: JSON escapes can carry unpaired surrogates in valid UTF-8. */
final class AssetLocalizeAsyncJobUnicode {

  private AssetLocalizeAsyncJobUnicode() {}

  static boolean isWellFormed(LocalizedAssetBody body) {
    return body != null
        && isWellFormed(body.getContent())
        && isWellFormed(body.getBcp47Tag())
        && isWellFormed(body.getOutputBcp47tag())
        && isWellFormed(body.getPullRunName())
        && isWellFormed(body.getFilterOptions())
        && isWellFormed(body.getPullWithNoSourceBranches());
  }

  private static boolean isWellFormed(List<String> values) {
    if (values != null) {
      for (String value : values) {
        if (!isWellFormed(value)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isWellFormed(String value) {
    if (value != null) {
      for (int i = 0; i < value.length(); i++) {
        char character = value.charAt(i);
        if (Character.isHighSurrogate(character)) {
          if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
            return false;
          }
        } else if (Character.isLowSurrogate(character)) {
          return false;
        }
      }
    }
    return true;
  }
}
