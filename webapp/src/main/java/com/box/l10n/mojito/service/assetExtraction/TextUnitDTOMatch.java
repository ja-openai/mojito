package com.box.l10n.mojito.service.assetExtraction;

import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.Objects;

public record TextUnitDTOMatch(
    BranchStateTextUnit source,
    TextUnitDTO match,
    boolean uniqueMatch,
    boolean translationNeededIfUniqueMatch) {

  public TextUnitDTOMatch {
    Objects.requireNonNull(source);
    Objects.requireNonNull(match);
  }
}
