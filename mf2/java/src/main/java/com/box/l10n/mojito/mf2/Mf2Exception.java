package com.box.l10n.mojito.mf2;

public final class Mf2Exception extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;

    public Mf2Exception(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    static Mf2Exception missingArgument(String name) {
        return new Mf2Exception("missing-argument", "Missing argument $" + name + ".");
    }

    static Mf2Exception missingSelectVariant() {
        return new Mf2Exception(
                "missing-select-variant",
                "No select variant matched and no catch-all variant is present.");
    }

    static Mf2Exception unsupportedFunction(String name) {
        return new Mf2Exception(
                "unsupported-function-format",
                "Function :" + name + " is not supported by this prototype formatter.");
    }
}
