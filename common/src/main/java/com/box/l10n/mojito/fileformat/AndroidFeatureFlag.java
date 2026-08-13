package com.box.l10n.mojito.fileformat;

/** One ordered AAPT2 feature-flag declaration, including mutable and unset flag values. */
public record AndroidFeatureFlag(String name, boolean readOnly, Boolean value) {}
