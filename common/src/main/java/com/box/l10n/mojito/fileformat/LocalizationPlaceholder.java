package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Original platform placeholder retained beside its normalized ICU/FormatJS argument name. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalizationPlaceholder(
    String name, String source, String kind, Integer position, String example) {}
