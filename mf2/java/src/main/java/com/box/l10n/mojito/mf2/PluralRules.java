package com.box.l10n.mojito.mf2;

final class PluralRules {
    private PluralRules() {}

    static String selectCardinalPluralCategory(String locale, Object value) {
        return selectPluralCategory(locale, value, NumberSelect.PLURAL);
    }

    static String selectPluralCategory(String locale, Object value, NumberSelect numberSelect) {
        if (value == null || value instanceof Boolean || numberSelect == NumberSelect.EXACT) {
            return null;
        }

        GeneratedPluralRules.NumberOperands operands =
                GeneratedPluralRules.NumberOperands.fromString(Mf2Formatter.valueToString(value));
        if (operands == null) {
            return null;
        }

        return switch (numberSelect) {
            case PLURAL -> GeneratedPluralRules.selectCardinal(locale, operands);
            case ORDINAL -> GeneratedPluralRules.selectOrdinal(locale, operands);
            case EXACT -> null;
        };
    }
}
