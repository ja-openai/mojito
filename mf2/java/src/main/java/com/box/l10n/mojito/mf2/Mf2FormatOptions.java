package com.box.l10n.mojito.mf2;

import java.util.Objects;

public record Mf2FormatOptions(
        String locale,
        Mf2FunctionRegistry functions,
        Mf2BidiIsolation bidiIsolation,
        Mf2RecoveryHandler onMissingArgument,
        Mf2RecoveryHandler onFormatError) {
    private static final Mf2RecoveryHandler DEFAULT_RECOVERY = context -> context.fallbackValue();

    public Mf2FormatOptions {
        locale = Objects.requireNonNull(locale, "locale");
        if (locale.isBlank()) {
            throw new IllegalArgumentException("locale must not be blank");
        }
        functions = Objects.requireNonNullElseGet(functions, Mf2FunctionRegistry::defaults);
        bidiIsolation = Objects.requireNonNullElse(bidiIsolation, Mf2BidiIsolation.NONE);
        onMissingArgument = Objects.requireNonNullElse(onMissingArgument, DEFAULT_RECOVERY);
        onFormatError = Objects.requireNonNullElse(onFormatError, DEFAULT_RECOVERY);
    }

    public static Mf2FormatOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String locale = "en";
        private Mf2FunctionRegistry functions;
        private Mf2BidiIsolation bidiIsolation = Mf2BidiIsolation.NONE;
        private Mf2RecoveryHandler onMissingArgument = DEFAULT_RECOVERY;
        private Mf2RecoveryHandler onFormatError = DEFAULT_RECOVERY;

        public Builder locale(String locale) {
            this.locale = locale;
            return this;
        }

        public Builder functions(Mf2FunctionRegistry functions) {
            this.functions = functions;
            return this;
        }

        public Builder bidiIsolation(Mf2BidiIsolation bidiIsolation) {
            this.bidiIsolation = bidiIsolation;
            return this;
        }

        public Builder onMissingArgument(Mf2RecoveryHandler onMissingArgument) {
            this.onMissingArgument = onMissingArgument;
            return this;
        }

        public Builder onFormatError(Mf2RecoveryHandler onFormatError) {
            this.onFormatError = onFormatError;
            return this;
        }

        public Mf2FormatOptions build() {
            return new Mf2FormatOptions(
                    locale, functions, bidiIsolation, onMissingArgument, onFormatError);
        }
    }
}
