package com.box.l10n.mojito.mf2;

import java.util.List;

public record Mf2PartsResult(List<Mf2FormattedPart> parts, List<Mf2Exception> errors) {
    public Mf2PartsResult {
        parts = List.copyOf(parts);
        errors = List.copyOf(errors);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
