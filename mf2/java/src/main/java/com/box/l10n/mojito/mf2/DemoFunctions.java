package com.box.l10n.mojito.mf2;

final class DemoFunctions {
    private DemoFunctions() {}

    static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.defaults().withFunction("currency", DemoFunctions::formatCurrency);
    }

    private static String formatCurrency(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String currency = call.optionValue("currency", "USD");
        return formatCurrencyValue(call.value(), currency, call.locale());
    }

    private static String formatCurrencyValue(String value, String currency, String locale)
            throws Mf2Exception {
        double amount;
        try {
            amount = Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw Mf2Exception.badOperand("Currency value must be numeric, got " + value + ".");
        }
        if (!Double.isFinite(amount)) {
            throw Mf2Exception.badOperand("Currency value must be finite.");
        }

        String normalizedCurrency = currency.toUpperCase(java.util.Locale.ROOT);
        int fractionDigits = currencyFractionDigits(normalizedCurrency);
        long scale = (long) Math.pow(10, fractionDigits);
        long rounded = Math.round(Math.abs(amount) * scale);
        long major = rounded / scale;
        long fraction = rounded % scale;
        boolean french = canonicalLocalePrefix(locale).equals("fr");
        String grouped = groupDigits(Long.toString(major), french ? "\u202f" : ",");
        String number = fractionDigits == 0
                ? grouped
                : grouped
                        + (french ? "," : ".")
                        + String.format(java.util.Locale.ROOT, "%0" + fractionDigits + "d", fraction);
        String symbol = currencySymbol(normalizedCurrency, french);
        String negative = amount < 0 ? "-" : "";
        if (french) {
            return negative + number + " " + symbol;
        }
        if (symbol.length() == 3) {
            return negative + symbol + " " + number;
        }
        return negative + symbol + number;
    }

    private static int currencyFractionDigits(String currency) {
        return switch (currency) {
            case "JPY", "KRW" -> 0;
            default -> 2;
        };
    }

    private static String currencySymbol(String currency, boolean french) {
        return switch (currency) {
            case "USD" -> french ? "$US" : "$";
            case "EUR" -> "€";
            case "JPY" -> "¥";
            case "GBP" -> "£";
            default -> currency;
        };
    }

    private static String canonicalLocalePrefix(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en";
        }
        int hyphen = locale.indexOf('-');
        int underscore = locale.indexOf('_');
        int end;
        if (hyphen < 0) {
            end = underscore < 0 ? locale.length() : underscore;
        } else if (underscore < 0) {
            end = hyphen;
        } else {
            end = Math.min(hyphen, underscore);
        }
        return locale.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static String groupDigits(String digits, String separator) {
        StringBuilder output = new StringBuilder();
        int firstGroup = digits.length() % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }
        output.append(digits, 0, firstGroup);
        for (int index = firstGroup; index < digits.length(); index += 3) {
            output.append(separator).append(digits, index, index + 3);
        }
        return output.toString();
    }
}
