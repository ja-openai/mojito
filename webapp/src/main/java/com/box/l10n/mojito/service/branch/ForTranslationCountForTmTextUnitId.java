package com.box.l10n.mojito.service.branch;

import com.box.l10n.mojito.localtm.merger.Branch;

public record ForTranslationCountForTmTextUnitId(
    long tmTextUnitId, long forTranslationCount, long totalCount, Branch branch) {}
