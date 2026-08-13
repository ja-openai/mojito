package com.box.l10n.mojito.fileformat;

import java.util.List;

/** Implementation-neutral comparison between canonical descriptors and existing extracted units. */
public record LocalizationShadowReport(
    String sourceFormat,
    int canonicalUnits,
    int legacyUnits,
    String outcome,
    List<LocalizationShadowDifference> differences) {}
