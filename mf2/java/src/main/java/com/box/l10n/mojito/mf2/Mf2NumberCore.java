package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Mf2NumberCore {
    private static final String DEFAULT_LOCALE = "en-US";
    private static final String ABSENT_OPTION = "\u0000__mojito_mf2_absent__";
    private static final int MAX_OPTION_LENGTH = 256;
    private static final int MAX_OPERAND_LENGTH = 256;
    private static final BigDecimal MAX_ABSOLUTE_FORMAT_VALUE = new BigDecimal("1e21");
    private static final Pattern DECIMAL_TEXT =
            Pattern.compile("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$");
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Za-z]{3}$");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[#0,.]+");

    private Mf2NumberCore() {}

    public static Options.Builder options() {
        return Options.builder();
    }

    public static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.portable()
                .withFunction("number", call -> formatCall(call, Style.NUMBER))
                .withFunction("integer", call -> formatCall(call, Style.INTEGER))
                .withFunction("percent", call -> formatCall(call, Style.PERCENT))
                .withFunction("currency", call -> formatCall(call, Style.CURRENCY));
    }

    public static String format(Object value) throws Mf2Exception {
        return format(value, null);
    }

    public static String format(Object value, Options options) throws Mf2Exception {
        Options effectiveOptions = options == null ? Options.builder().build() : options;
        String locale = LocaleKey.option(effectiveOptions.locale(), DEFAULT_LOCALE);
        CldrNumberData.LocaleData localeData = resolveLocaleData(locale);
        ParsedNumber parsed = parseFiniteNumber(value);
        if (parsed == null) {
            throw Mf2Exception.badOperand("Number core requires a finite numeric value.");
        }

        String currency = effectiveOptions.style() == Style.CURRENCY
                ? parseCurrency(effectiveOptions.currency())
                : null;
        String pattern = patternForStyle(localeData, effectiveOptions.style());
        FractionOptions fraction = fractionOptions(
                effectiveOptions.style(),
                currency,
                effectiveOptions,
                pattern);
        ParsedNumber normalized = effectiveOptions.style() == Style.INTEGER
                ? parsed.withValue(new BigDecimal(parsed.value().toBigInteger()))
                : parsed;
        ParsedNumber scaled = effectiveOptions.style() == Style.PERCENT
                ? normalized.multiply(BigDecimal.valueOf(100))
                : normalized;
        ensureSupportedMagnitude(scaled.value());
        String formatted = formatDecimal(scaled.value().abs(), localeData, pattern, fraction, effectiveOptions);

        if (effectiveOptions.style() == Style.PERCENT) {
            return applySignedPattern(
                    pattern,
                    formatted,
                    scaled.isNegative(),
                    localeData.symbols(),
                    effectiveOptions.signDisplay(),
                    localeData.symbols().get("percentSign"),
                    null);
        }
        if (effectiveOptions.style() == Style.CURRENCY) {
            return applySignedPattern(
                    pattern,
                    formatted,
                    scaled.isNegative(),
                    localeData.symbols(),
                    effectiveOptions.signDisplay(),
                    null,
                    currencyDisplay(localeData, currency, effectiveOptions.currencyDisplay()));
        }
        return applySign(formatted, scaled.isNegative(), localeData.symbols(), effectiveOptions.signDisplay());
    }

    public static List<Mf2FormattedPart> formatToParts(Object value) throws Mf2Exception {
        return formatToParts(value, null);
    }

    public static List<Mf2FormattedPart> formatToParts(Object value, Options options) throws Mf2Exception {
        return List.of(new Mf2FormattedPart.Text(format(value, options)));
    }

    private static String formatCall(Mf2FunctionRegistry.FunctionCall call, Style style)
            throws Mf2Exception {
        return format(
                callNumberValue(call, style),
                Options.builder()
                        .locale(call.locale())
                        .style(style)
                        .currency(currencyOption(call, style))
                        .currencyDisplay(currencyDisplayOption(call.optionValue("currencyDisplay", "symbol")))
                        .minimumFractionDigits(integerOption(call.optionValue("minimumFractionDigits", null)))
                        .maximumFractionDigits(integerOption(call.optionValue("maximumFractionDigits", null)))
                        .signDisplay(signDisplayOption(call.optionValue("signDisplay", "auto")))
                        .useGrouping(booleanOption(call.optionValue("useGrouping", "true"), "useGrouping"))
                        .build());
    }

    private static String currencyOption(Mf2FunctionRegistry.FunctionCall call, Style style)
            throws Mf2Exception {
        return style == Style.CURRENCY
                ? inheritedOptionValue(call, "currency", null)
                : call.optionValue("currency", null);
    }

    private static String inheritedOptionValue(
            Mf2FunctionRegistry.FunctionCall call, String name, String fallback)
            throws Mf2Exception {
        String value = call.optionValue(name, ABSENT_OPTION);
        if (!ABSENT_OPTION.equals(value)) {
            return value;
        }
        Mf2FunctionRegistry.FunctionSourceRef source = call.inheritedSource();
        while (source != null) {
            value = source.optionValue(name, ABSENT_OPTION);
            if (!ABSENT_OPTION.equals(value)) {
                return value;
            }
            source = source.inheritedSource();
        }
        return fallback;
    }

    private static Object callNumberValue(Mf2FunctionRegistry.FunctionCall call, Style style) {
        Mf2FunctionRegistry.FunctionSourceRef source = call.inheritedSource();
        if (source != null) {
            if (style == Style.NUMBER && "integer".equals(source.function().name())) {
                ParsedNumber parsed = parseFiniteNumber(source.value());
                if (parsed != null) {
                    return parsed.withValue(new BigDecimal(parsed.value().toBigInteger()));
                }
            }
            return source.value();
        }
        return call.rawValue() != null ? call.rawValue() : call.value();
    }

    private static CldrNumberData.LocaleData resolveLocaleData(String locale) {
        for (String candidate : LocaleKey.lookupChain(locale)) {
            CldrNumberData.LocaleData exact = CldrNumberData.LOCALES.get(candidate);
            if (exact != null) {
                return exact;
            }
            for (CldrNumberData.LocaleData localeData : CldrNumberData.LOCALES.values()) {
                if (localeData.numbersSourceLocale().equals(candidate)) {
                    return localeData;
                }
            }
        }
        return CldrNumberData.LOCALES.get(DEFAULT_LOCALE);
    }

    private static String patternForStyle(CldrNumberData.LocaleData localeData, Style style) {
        return switch (style) {
            case PERCENT -> localeData.percentPattern();
            case CURRENCY -> localeData.currencyPattern();
            case NUMBER, INTEGER -> localeData.decimalPattern();
        };
    }

    private static FractionOptions fractionOptions(
            Style style,
            String currency,
            Options options,
            String pattern) throws Mf2Exception {
        FractionOptions defaults = fractionDefaultsFromPattern(pattern);
        if (style == Style.INTEGER) {
            defaults = new FractionOptions(0, 0);
        }
        if (style == Style.CURRENCY) {
            CldrNumberData.CurrencyFraction currencyDefaults = CldrNumberData.CURRENCY_FRACTIONS
                    .getOrDefault(currency, CldrNumberData.CURRENCY_FRACTIONS.get("DEFAULT"));
            defaults = new FractionOptions(currencyDefaults.digits(), currencyDefaults.digits());
        }
        int minimum = options.minimumFractionDigits() == null
                ? defaults.minimum()
                : options.minimumFractionDigits();
        int maximum = options.maximumFractionDigits() == null
                ? defaults.maximum()
                : options.maximumFractionDigits();
        if (minimum < 0 || minimum > Mf2FunctionSupport.MAX_FRACTION_DIGITS) {
            throw Mf2FunctionSupport.badOption("minimumFractionDigits must be a non-negative integer.");
        }
        if (maximum < 0 || maximum > Mf2FunctionSupport.MAX_FRACTION_DIGITS) {
            throw Mf2FunctionSupport.badOption("maximumFractionDigits must be a non-negative integer.");
        }
        if (options.minimumFractionDigits() != null
                && options.maximumFractionDigits() == null
                && maximum < minimum) {
            maximum = minimum;
        }
        if (options.maximumFractionDigits() != null
                && options.minimumFractionDigits() == null
                && maximum < minimum) {
            minimum = maximum;
        }
        if (maximum < minimum) {
            throw Mf2FunctionSupport.badOption(
                    "maximumFractionDigits must be greater than or equal to minimumFractionDigits.");
        }
        return new FractionOptions(minimum, maximum);
    }

    private static FractionOptions fractionDefaultsFromPattern(String pattern) {
        String numberPattern = numberPattern(pattern);
        int dot = numberPattern.indexOf('.');
        if (dot < 0) {
            return new FractionOptions(0, 0);
        }
        String fraction = numberPattern.substring(dot + 1);
        return new FractionOptions(countChars(fraction, '0'), fraction.length());
    }

    private static String formatDecimal(
            BigDecimal value,
            CldrNumberData.LocaleData localeData,
            String pattern,
            FractionOptions fraction,
            Options options) {
        String rounded = value.setScale(fraction.maximum(), RoundingMode.HALF_UP).toPlainString();
        int dot = rounded.indexOf('.');
        String integer = dot < 0 ? rounded : rounded.substring(0, dot);
        String decimal = dot < 0 ? "" : rounded.substring(dot + 1);
        while (decimal.length() > fraction.minimum() && decimal.endsWith("0")) {
            decimal = decimal.substring(0, decimal.length() - 1);
        }
        while (decimal.length() < fraction.minimum()) {
            decimal += "0";
        }

        GroupingInfo grouping = groupingInfo(pattern);
        if (options.useGrouping()
                && shouldGroup(integer, grouping, localeData.minimumGroupingDigits())) {
            integer = groupInteger(integer, grouping, localeData.symbols().get("group"));
        }
        integer = localizeDigits(integer, localeData.numberingSystemDigits());
        if (!decimal.isEmpty()) {
            return integer + localeData.symbols().get("decimal")
                    + localizeDigits(decimal, localeData.numberingSystemDigits());
        }
        return integer;
    }

    private static GroupingInfo groupingInfo(String pattern) {
        String integerPattern = numberPattern(pattern).split("\\.", 2)[0];
        String[] groups = integerPattern.split(",");
        if (groups.length == 1) {
            return new GroupingInfo(0, 0);
        }
        int primary = placeholderCount(groups[groups.length - 1]);
        int secondary = groups.length > 2
                ? placeholderCount(groups[groups.length - 2])
                : primary;
        return new GroupingInfo(primary, secondary);
    }

    private static boolean shouldGroup(String integer, GroupingInfo grouping, int minimumGroupingDigits) {
        if (grouping.primary() <= 0) {
            return false;
        }
        return integer.length() >= grouping.primary() + minimumGroupingDigits;
    }

    private static String groupInteger(String integer, GroupingInfo grouping, String separator) {
        StringBuilder output = new StringBuilder();
        int end = integer.length();
        int size = grouping.primary();
        while (end > 0) {
            int start = Math.max(0, end - size);
            if (!output.isEmpty()) {
                output.insert(0, separator);
            }
            output.insert(0, integer.substring(start, end));
            end = start;
            size = grouping.secondary() == 0 ? grouping.primary() : grouping.secondary();
        }
        return output.toString();
    }

    private static String applySign(
            String formatted,
            boolean negative,
            Map<String, String> symbols,
            SignDisplay signDisplay) {
        if (signDisplay == SignDisplay.NEVER) {
            return formatted;
        }
        if (negative) {
            return symbols.get("minusSign") + formatted;
        }
        if (signDisplay == SignDisplay.ALWAYS) {
            return symbols.get("plusSign") + formatted;
        }
        return formatted;
    }

    private static String applyPattern(
            String pattern,
            String formatted,
            String percentSign,
            String currency) {
        String output = NUMBER_PATTERN.matcher(pattern).replaceFirst(formatted);
        if (percentSign != null) {
            output = output.replace("%", percentSign);
        }
        if (currency != null) {
            output = output.replace("¤", currency);
        }
        return output;
    }

    private static String applySignedPattern(
            String pattern,
            String formatted,
            boolean negative,
            Map<String, String> symbols,
            SignDisplay signDisplay,
            String percentSign,
            String currency) {
        int separator = pattern.indexOf(';');
        String positivePattern = separator < 0 ? pattern : pattern.substring(0, separator);
        if (negative && signDisplay != SignDisplay.NEVER) {
            if (separator >= 0) {
                return applyPattern(pattern.substring(separator + 1), formatted, percentSign, currency);
            }
            return symbols.get("minusSign") + applyPattern(positivePattern, formatted, percentSign, currency);
        }
        String output = applyPattern(positivePattern, formatted, percentSign, currency);
        if (signDisplay == SignDisplay.ALWAYS) {
            return symbols.get("plusSign") + output;
        }
        return output;
    }

    private static String currencyDisplay(
            CldrNumberData.LocaleData localeData,
            String currency,
            CurrencyDisplay display) {
        if (display == CurrencyDisplay.CODE) {
            return currencyCodeDisplay(localeData, currency);
        }
        CldrNumberData.CurrencyData data = localeData.currencies().get(currency);
        if (data == null) {
            return currency;
        }
        if (display == CurrencyDisplay.NARROW_SYMBOL && data.narrowSymbol() != null) {
            return data.narrowSymbol();
        }
        return data.symbol() == null ? currency : data.symbol();
    }

    private static String currencyCodeDisplay(
            CldrNumberData.LocaleData localeData,
            String currency) {
        String positivePattern = localeData.currencyPattern().split(";", 2)[0];
        String before = positivePattern.contains("#\u00a4") || positivePattern.contains("0\u00a4")
                ? localeData.currencySpacing().beforeCurrency()
                : "";
        String after = positivePattern.contains("\u00a4#") || positivePattern.contains("\u00a40")
                ? localeData.currencySpacing().afterCurrency()
                : "";
        return before + currency + after;
    }

    private static ParsedNumber parseFiniteNumber(Object value) {
        if (value instanceof ParsedNumber parsed) {
            return parsed;
        }
        if (value instanceof BigDecimal decimal) {
            return new ParsedNumber(decimal, decimal.signum() < 0);
        }
        if (value instanceof BigInteger integer) {
            return new ParsedNumber(new BigDecimal(integer), integer.signum() < 0);
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue)) {
                return null;
            }
            return new ParsedNumber(BigDecimal.valueOf(doubleValue), Double.compare(doubleValue, 0.0d) < 0);
        }
        String text = String.valueOf(value == null ? "" : value).trim();
        if (text.length() > MAX_OPERAND_LENGTH) {
            return null;
        }
        if (!DECIMAL_TEXT.matcher(text).matches()) {
            return null;
        }
        BigDecimal decimal = new BigDecimal(text);
        return new ParsedNumber(decimal, decimal.signum() < 0 || (decimal.signum() == 0 && text.startsWith("-")));
    }

    private static void ensureSupportedMagnitude(BigDecimal value) throws Mf2Exception {
        if (value.abs().compareTo(MAX_ABSOLUTE_FORMAT_VALUE) >= 0) {
            throw Mf2Exception.badOperand("Number core numeric value is outside the supported magnitude.");
        }
    }

    private static String parseCurrency(String value) throws Mf2Exception {
        if (value == null) {
            throw Mf2FunctionSupport.badOption("currency must be a three-letter ISO 4217 code.");
        }
        value = optionName(value, "currency");
        if (!CURRENCY_CODE.matcher(value).matches()) {
            throw Mf2FunctionSupport.badOption("currency must be a three-letter ISO 4217 code.");
        }
        return value.toUpperCase();
    }

    private static Integer integerOption(String value) throws Mf2Exception {
        if (value == null) {
            return null;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(value, "Option must be a non-negative integer.");
    }

    private static SignDisplay signDisplayOption(String value) throws Mf2Exception {
        return switch (optionName(value, "signDisplay")) {
            case "auto" -> SignDisplay.AUTO;
            case "always" -> SignDisplay.ALWAYS;
            case "never" -> SignDisplay.NEVER;
            default -> throw Mf2FunctionSupport.badOption("signDisplay must be auto, always, or never.");
        };
    }

    private static CurrencyDisplay currencyDisplayOption(String value) throws Mf2Exception {
        return switch (optionName(value, "currencyDisplay")) {
            case "symbol" -> CurrencyDisplay.SYMBOL;
            case "narrowSymbol" -> CurrencyDisplay.NARROW_SYMBOL;
            case "code" -> CurrencyDisplay.CODE;
            default -> throw Mf2FunctionSupport.badOption("currencyDisplay must be symbol, narrowSymbol, or code.");
        };
    }

    private static boolean booleanOption(String value, String name) throws Mf2Exception {
        return switch (optionName(value, name)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw Mf2FunctionSupport.badOption(name + " must be true or false.");
        };
    }

    private static String optionName(String value, String name) throws Mf2Exception {
        if (value.length() > MAX_OPTION_LENGTH) {
            throw Mf2FunctionSupport.badOption(name + " must not exceed 256 characters.");
        }
        return value;
    }

    private static String numberPattern(String pattern) {
        java.util.regex.Matcher matcher = NUMBER_PATTERN.matcher(pattern);
        return matcher.find() ? matcher.group() : "";
    }

    private static int placeholderCount(String pattern) {
        return countChars(pattern, '#') + countChars(pattern, '0');
    }

    private static int countChars(String value, char needle) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                count++;
            }
        }
        return count;
    }

    private static String localizeDigits(String value, String digits) {
        if (digits == null || digits.equals("0123456789")) {
            return value;
        }
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch >= '0' && ch <= '9') {
                output.append(digits.charAt(ch - '0'));
            } else {
                output.append(ch);
            }
        }
        return output.toString();
    }

    public enum Style {
        NUMBER,
        INTEGER,
        PERCENT,
        CURRENCY
    }

    public enum CurrencyDisplay {
        SYMBOL,
        NARROW_SYMBOL,
        CODE
    }

    public enum SignDisplay {
        AUTO,
        ALWAYS,
        NEVER
    }

    private record ParsedNumber(BigDecimal value, boolean negative) {
        ParsedNumber withValue(BigDecimal nextValue) {
            return new ParsedNumber(nextValue, nextValue.signum() < 0 || (nextValue.signum() == 0 && negative));
        }

        ParsedNumber multiply(BigDecimal factor) {
            return withValue(value.multiply(factor));
        }

        boolean isNegative() {
            return value.signum() < 0 || (value.signum() == 0 && negative);
        }
    }

    public record Options(
            String locale,
            Style style,
            String currency,
            CurrencyDisplay currencyDisplay,
            boolean useGrouping,
            Integer minimumFractionDigits,
            Integer maximumFractionDigits,
            SignDisplay signDisplay) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String locale = DEFAULT_LOCALE;
            private Style style = Style.NUMBER;
            private String currency;
            private CurrencyDisplay currencyDisplay = CurrencyDisplay.SYMBOL;
            private boolean useGrouping = true;
            private Integer minimumFractionDigits;
            private Integer maximumFractionDigits;
            private SignDisplay signDisplay = SignDisplay.AUTO;

            public Builder locale(String locale) {
                this.locale = locale == null ? DEFAULT_LOCALE : locale;
                return this;
            }

            public Builder style(Style style) {
                this.style = style == null ? Style.NUMBER : style;
                return this;
            }

            public Builder currency(String currency) {
                this.currency = currency;
                return this;
            }

            public Builder currencyDisplay(CurrencyDisplay currencyDisplay) {
                this.currencyDisplay = currencyDisplay == null ? CurrencyDisplay.SYMBOL : currencyDisplay;
                return this;
            }

            public Builder useGrouping(boolean useGrouping) {
                this.useGrouping = useGrouping;
                return this;
            }

            public Builder minimumFractionDigits(Integer minimumFractionDigits) {
                this.minimumFractionDigits = minimumFractionDigits;
                return this;
            }

            public Builder maximumFractionDigits(Integer maximumFractionDigits) {
                this.maximumFractionDigits = maximumFractionDigits;
                return this;
            }

            public Builder signDisplay(SignDisplay signDisplay) {
                this.signDisplay = signDisplay == null ? SignDisplay.AUTO : signDisplay;
                return this;
            }

            public Options build() {
                if (minimumFractionDigits != null && minimumFractionDigits < 0) {
                    throw new IllegalArgumentException("minimumFractionDigits must be non-negative.");
                }
                if (maximumFractionDigits != null && maximumFractionDigits < 0) {
                    throw new IllegalArgumentException("maximumFractionDigits must be non-negative.");
                }
                return new Options(
                        locale,
                        style,
                        currency,
                        currencyDisplay,
                        useGrouping,
                        minimumFractionDigits,
                        maximumFractionDigits,
                        signDisplay);
            }
        }
    }

    private record FractionOptions(int minimum, int maximum) {}

    private record GroupingInfo(int primary, int secondary) {}
}
