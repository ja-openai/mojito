package com.box.l10n.mojito.service.branch;

public record BranchTextUnitStatisticWithCounts(
    long id,
    long branchStatisticId,
    long tmTextUnitId,
    long forTranslationCount,
    long totalCount) {}
