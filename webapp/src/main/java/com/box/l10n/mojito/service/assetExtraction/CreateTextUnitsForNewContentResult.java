package com.box.l10n.mojito.service.assetExtraction;

import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.localtm.merger.MultiBranchState;
import com.google.common.collect.ImmutableList;
import java.util.Objects;

public record CreateTextUnitsForNewContentResult(
    MultiBranchState updatedState,
    ImmutableList<BranchStateTextUnit> createdTextUnits,
    ImmutableList<TextUnitDTOMatch> leveragingMatches) {

  public CreateTextUnitsForNewContentResult {
    Objects.requireNonNull(updatedState);
    Objects.requireNonNull(createdTextUnits);
    Objects.requireNonNull(leveragingMatches);
  }
}
