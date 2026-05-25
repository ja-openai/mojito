package com.box.l10n.mojito.mf2;

import java.util.List;

public record Mf2ParseResult(Mf2Message model, List<Mf2ParseDiagnostic> diagnostics) {
    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }
}
