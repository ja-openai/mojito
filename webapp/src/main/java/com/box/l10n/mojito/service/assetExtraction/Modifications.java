package com.box.l10n.mojito.service.assetExtraction;

import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.google.common.collect.ImmutableSet;

public record Modifications(
    ImmutableSet<BranchStateTextUnit> added,
    ImmutableSet<BranchStateTextUnit> removed,
    ImmutableSet<BranchStateTextUnit> updated) {}
