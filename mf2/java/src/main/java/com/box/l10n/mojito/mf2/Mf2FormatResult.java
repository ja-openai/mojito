package com.box.l10n.mojito.mf2;

import java.util.List;

public record Mf2FormatResult(String value, List<Mf2Exception> errors) {
    public Mf2FormatResult {
        errors = List.copyOf(errors);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
