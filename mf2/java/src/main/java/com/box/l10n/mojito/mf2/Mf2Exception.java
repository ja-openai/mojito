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

    static Mf2Exception duplicateDeclaration(String name) {
        return new Mf2Exception(
                "duplicate-declaration",
                "Declaration $" + name + " is defined more than once.");
    }

    static Mf2Exception variantKeyCountMismatch() {
        return new Mf2Exception(
                "variant-key-count-mismatch",
                "Variant key count must match selector count.");
    }

    static Mf2Exception duplicateVariant() {
        return new Mf2Exception(
                "duplicate-variant",
                "Select variants must have unique key tuples.");
    }

    static Mf2Exception missingFallbackVariant() {
        return new Mf2Exception(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.");
    }

    static Mf2Exception missingSelectorAnnotation(String name) {
        return new Mf2Exception(
                "missing-selector-annotation",
                "Selector $" + name + " must reference a declaration with a function.");
    }
}
