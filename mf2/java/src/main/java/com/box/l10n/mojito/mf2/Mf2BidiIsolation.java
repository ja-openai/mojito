package com.box.l10n.mojito.mf2;

public enum Mf2BidiIsolation {
    NONE,
    DEFAULT;

    static Mf2BidiIsolation fromName(String value) {
        return "default".equals(value) ? DEFAULT : NONE;
    }
}
