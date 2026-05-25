package com.box.l10n.mojito.mf2;

public enum Mf2BidiIsolation {
    NONE,
    DEFAULT;

    public static Mf2BidiIsolation fromName(String value) {
        return "default".equalsIgnoreCase(String.valueOf(value)) ? DEFAULT : NONE;
    }
}
