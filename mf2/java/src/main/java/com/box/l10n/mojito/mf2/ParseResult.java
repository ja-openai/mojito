package com.box.l10n.mojito.mf2;

import java.util.List;

record ParseResult(Mf2Message model, List<Diagnostic> diagnostics) {
    boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }
}
