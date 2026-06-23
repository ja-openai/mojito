package com.box.l10n.mojito.mf2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Mf2RelativeTimeCore {
    private static final String DEFAULT_LOCALE = "en";
    private static final int MAX_OPTION_LENGTH = 256;
    private static final int MAX_OPERAND_LENGTH = 256;
    private static final int MAX_RELATIVE_TIME_QUANTITY = 1_000_000_000;
    private static final Pattern DECIMAL_NUMBER_PATTERN =
            Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    private static final List<PolicyStep> PRECISE_POLICY = List.of(
            new PolicyStep(60, Unit.SECOND),
            new PolicyStep(3_600, Unit.MINUTE),
            new PolicyStep(86_400, Unit.HOUR),
            new PolicyStep(604_800, Unit.DAY),
            new PolicyStep(2_592_000, Unit.WEEK),
            new PolicyStep(31_536_000, Unit.MONTH),
            new PolicyStep(Double.POSITIVE_INFINITY, Unit.YEAR));
    private static final List<PolicyStep> COMPACT_POLICY = List.of(
            new PolicyStep(60, Unit.SECOND),
            new PolicyStep(3_600, Unit.MINUTE),
            new PolicyStep(86_400, Unit.HOUR),
            new PolicyStep(Double.POSITIVE_INFINITY, Unit.DAY));
    private static final List<PolicyStep> CHAT_POLICY = List.of(
            new PolicyStep(45, Unit.SECOND),
            new PolicyStep(2_700, Unit.MINUTE),
            new PolicyStep(79_200, Unit.HOUR),
            new PolicyStep(604_800, Unit.DAY),
            new PolicyStep(Double.POSITIVE_INFINITY, Unit.WEEK));

    private final Map<String, String> localeMap;
    private final Map<String, PatternSet> patternSets;

    private Mf2RelativeTimeCore(Data data) throws Mf2Exception {
        if (data == null || data.localeMap().isEmpty() || data.patternSets().isEmpty()) {
            throw missingLocaleData("Relative-time core data has an unsupported shape.");
        }
        this.localeMap = data.localeMap();
        Map<String, PatternSet> nextPatternSets = new LinkedHashMap<>();
        for (PatternSet patternSet : data.patternSets()) {
            if (patternSet.id() != null && !patternSet.id().isEmpty() && !patternSet.data().isEmpty()) {
                nextPatternSets.put(patternSet.id(), patternSet);
            }
        }
        if (nextPatternSets.isEmpty()) {
            throw missingLocaleData("Relative-time core data has an unsupported shape.");
        }
        this.patternSets = Map.copyOf(nextPatternSets);
    }

    public static Mf2RelativeTimeCore create(Data data) throws Mf2Exception {
        return new Mf2RelativeTimeCore(data);
    }

    public static Data dataFromJson(Map<String, ?> rawData) throws Mf2Exception {
        if (rawData == null) {
            throw missingLocaleData("Relative-time core data has an unsupported shape.");
        }
        try {
            Map<String, String> localeMap = stringMap(rawData.get("localeMap"));
            List<PatternSet> patternSets = list(rawData.get("patternSets")).stream()
                    .map(Mf2RelativeTimeCore::patternSetFromJson)
                    .toList();
            return new Data(localeMap, patternSets);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw missingLocaleData("Relative-time core data has an unsupported shape.");
        }
    }

    public static Mf2FunctionRegistry registry(Data data) throws Mf2Exception {
        return create(data).registry();
    }

    public Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.portable()
                .withFunction("relativeTime", this::formatCall);
    }

    public static String format(Object value, Data data, Options options) throws Mf2Exception {
        return create(data).format(value, options);
    }

    public static String format(Object value, Data data) throws Mf2Exception {
        return format(value, data, null);
    }

    public static List<Mf2FormattedPart> formatToParts(Object value, Data data, Options options)
            throws Mf2Exception {
        return create(data).formatToParts(value, options);
    }

    public static List<Mf2FormattedPart> formatToParts(Object value, Data data)
            throws Mf2Exception {
        return formatToParts(value, data, null);
    }

    public String format(Object value) throws Mf2Exception {
        return format(value, (Options) null);
    }

    public String format(Object value, Options options) throws Mf2Exception {
        Options effectiveOptions = options == null ? Options.builder().build() : options;
        double seconds = parseFiniteNumber(value);
        Unit unit = effectiveOptions.unit() == Unit.AUTO
                ? selectUnit(seconds, effectiveOptions.policy())
                : effectiveOptions.unit();
        int quantity = quantity(seconds, unit);
        String locale = LocaleKey.option(effectiveOptions.locale(), DEFAULT_LOCALE);

        if (useRelativeZero(effectiveOptions.policy(), effectiveOptions.numeric(), seconds)) {
            String relative = relativeTerm(locale, effectiveOptions.style(), unit, "0");
            if (relative != null) {
                return relative;
            }
        }
        if (effectiveOptions.numeric() == Numeric.AUTO) {
            String offset = relativeOffset(seconds, unit, quantity);
            if (offset != null) {
                String relative = relativeTerm(locale, effectiveOptions.style(), unit, offset);
                if (relative != null) {
                    return relative;
                }
            }
        }

        String direction = isNegativeRelativeTime(seconds) ? "past" : "future";
        UnitData unitData = unitData(locale, effectiveOptions.style(), unit);
        CldrPluralRules.NumberOperands operands =
                CldrPluralRules.NumberOperands.fromString(Integer.toString(quantity));
        String category = CldrPluralRules.selectCardinal(locale, operands);
        String pattern = relativeTimePattern(
                unitData, locale, effectiveOptions.style(), unit, direction, category);
        return pattern.replace("{0}", Integer.toString(quantity));
    }

    public List<Mf2FormattedPart> formatToParts(Object value) throws Mf2Exception {
        return formatToParts(value, (Options) null);
    }

    public List<Mf2FormattedPart> formatToParts(Object value, Options options) throws Mf2Exception {
        return List.of(new Mf2FormattedPart.Text(format(value, options)));
    }

    private String formatCall(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        Options options = Options.builder()
                .locale(call.locale())
                .style(Style.fromName(call.optionValue("style", "short")))
                .numeric(Numeric.fromName(call.optionValue("numeric", "always")))
                .policy(Policy.fromName(call.optionValue("policy", "precise")))
                .unit(Unit.fromName(call.optionValue("unit", "auto")))
                .build();
        Object value = call.rawValue() != null ? call.rawValue() : call.value();
        try {
            return format(value, options);
        } catch (Mf2Exception error) {
            String sourceValue = relativeTimeSourceValue(call.inheritedSource());
            if (!"bad-operand".equals(error.code()) || sourceValue == null) {
                throw error;
            }
            return format(sourceValue, options);
        }
    }

    private static String relativeTimeSourceValue(Mf2FunctionRegistry.FunctionSourceRef source) {
        for (Mf2FunctionRegistry.FunctionSourceRef current = source;
                current != null;
                current = current.inheritedSource()) {
            if (Mf2FunctionSupport.isDecimalSourceFunction(current.function())) {
                return current.value();
            }
        }
        return null;
    }

    private String relativeTerm(String locale, Style style, Unit unit, String offset)
            throws Mf2Exception {
        return unitData(locale, style, unit).relative().get(offset);
    }

    private String relativeTimePattern(
            UnitData unitData,
            String locale,
            Style style,
            Unit unit,
            String direction,
            String category)
            throws Mf2Exception {
        Map<String, String> patterns = direction.equals("past")
                ? unitData.past()
                : unitData.future();
        String pattern = patterns.get(category);
        if (pattern == null) {
            pattern = patterns.get("other");
        }
        if (pattern == null) {
            throw missingLocaleData(
                    "Missing relative-time pattern for %s/%s/%s/%s."
                            .formatted(locale, style.key(), unit.key(), direction));
        }
        return pattern;
    }

    private UnitData unitData(String locale, Style style, Unit unit) throws Mf2Exception {
        PatternSet patternSet = patternSetFor(locale);
        Map<String, UnitData> styleData = patternSet.data().get(style.key());
        UnitData unitData = styleData == null ? null : styleData.get(unit.key());
        if (unitData == null) {
            throw missingLocaleData(
                    "Missing relative-time unit data for %s/%s/%s."
                            .formatted(locale, style.key(), unit.key()));
        }
        return unitData;
    }

    private PatternSet patternSetFor(String locale) throws Mf2Exception {
        for (String candidate : LocaleKey.lookupChain(locale)) {
            String setId = localeMap.get(candidate);
            if (setId == null) {
                continue;
            }
            PatternSet patternSet = patternSets.get(setId);
            if (patternSet == null) {
                throw missingLocaleData("Missing relative-time pattern set %s.".formatted(setId));
            }
            return patternSet;
        }
        throw missingLocaleData("Missing relative-time locale data for %s.".formatted(locale));
    }

    private static PatternSet patternSetFromJson(Object rawValue) {
        Map<String, ?> rawSet = object(rawValue);
        return new PatternSet(
                string(rawSet.get("id")),
                patternData(rawSet.get("data")));
    }

    private static Map<String, Map<String, UnitData>> patternData(Object rawValue) {
        Map<String, ?> rawData = object(rawValue);
        Map<String, Map<String, UnitData>> output = new LinkedHashMap<>();
        for (Map.Entry<String, ?> styleEntry : rawData.entrySet()) {
            Map<String, UnitData> units = new LinkedHashMap<>();
            for (Map.Entry<String, ?> unitEntry : object(styleEntry.getValue()).entrySet()) {
                units.put(unitEntry.getKey(), unitDataFromJson(unitEntry.getValue()));
            }
            output.put(styleEntry.getKey(), units);
        }
        return output;
    }

    private static UnitData unitDataFromJson(Object rawValue) {
        Map<String, ?> rawUnit = object(rawValue);
        return new UnitData(
                stringMapOrEmpty(rawUnit.get("future")),
                stringMapOrEmpty(rawUnit.get("past")),
                stringMapOrEmpty(rawUnit.get("relative")));
    }

    private static double parseFiniteNumber(Object value) throws Mf2Exception {
        if (value == null || value instanceof Boolean) {
            throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
        }
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
            }
            if (text.length() > MAX_OPERAND_LENGTH) {
                throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
            }
            if (!DECIMAL_NUMBER_PATTERN.matcher(text).matches()) {
                throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
            }
            try {
                parsed = Double.parseDouble(text);
            } catch (NumberFormatException error) {
                throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
            }
        }
        if (!Double.isFinite(parsed)) {
            throw Mf2Exception.badOperand("Relative-time core requires a finite numeric value.");
        }
        return parsed;
    }

    private static Unit selectUnit(double seconds, Policy policy) {
        double absolute = Math.abs(seconds);
        for (PolicyStep step : policy.steps()) {
            if (absolute < step.upper()) {
                return step.unit();
            }
        }
        return Unit.YEAR;
    }

    private static int quantity(double seconds, Unit unit) throws Mf2Exception {
        double absolute = Math.abs(seconds);
        if (absolute == 0) {
            return 0;
        }
        double quantity = Math.max(1, Math.floor(absolute / unit.seconds() + 0.5));
        if (quantity > MAX_RELATIVE_TIME_QUANTITY) {
            throw Mf2Exception.badOperand("Relative-time core quantity is outside the supported range.");
        }
        return (int) quantity;
    }

    private static boolean useRelativeZero(Policy policy, Numeric numeric, double seconds) {
        return policy == Policy.CHAT && numeric == Numeric.AUTO && Math.abs(seconds) < 45;
    }

    private static String relativeOffset(double seconds, Unit unit, int quantity) {
        if (quantity == 0) {
            return "0";
        }
        if (Math.abs(seconds) != quantity * unit.seconds()) {
            return null;
        }
        return isNegativeRelativeTime(seconds) ? "-" + quantity : Integer.toString(quantity);
    }

    private static boolean isNegativeRelativeTime(double seconds) {
        return seconds < 0 || Double.doubleToRawLongBits(seconds) == Double.doubleToRawLongBits(-0.0d);
    }

    private static Map<String, String> stringMapOrEmpty(Object value) {
        return value == null ? Map.of() : stringMap(value);
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, ?> raw = object(value);
        Map<String, String> output = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            output.put(entry.getKey(), string(entry.getValue()));
        }
        return output;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected JSON object.");
        }
        return (Map<String, ?>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException("Expected JSON array.");
        }
        return (List<Object>) raw;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Expected JSON string.");
        }
        return text;
    }

    private static Mf2Exception missingLocaleData(String message) {
        return new Mf2Exception("missing-locale-data", message);
    }

    private static String optionName(String value, String name) throws Mf2Exception {
        if (value.length() > MAX_OPTION_LENGTH) {
            throw Mf2FunctionSupport.badOption(name + " must not exceed 256 characters.");
        }
        return value;
    }

    public enum Style {
        LONG("long"),
        SHORT("short"),
        NARROW("narrow");

        private final String key;

        Style(String key) {
            this.key = key;
        }

        public static Style fromName(String value) throws Mf2Exception {
            return switch (optionName(value, "style")) {
                case "long" -> LONG;
                case "short" -> SHORT;
                case "narrow" -> NARROW;
                default -> throw Mf2FunctionSupport.badOption("style must be one of long, short, narrow.");
            };
        }

        String key() {
            return key;
        }
    }

    public enum Numeric {
        ALWAYS,
        AUTO;

        public static Numeric fromName(String value) throws Mf2Exception {
            return switch (optionName(value, "numeric")) {
                case "always" -> ALWAYS;
                case "auto" -> AUTO;
                default -> throw Mf2FunctionSupport.badOption("numeric must be one of always, auto.");
            };
        }
    }

    public enum Policy {
        PRECISE(PRECISE_POLICY),
        COMPACT(COMPACT_POLICY),
        CHAT(CHAT_POLICY);

        private final List<PolicyStep> steps;

        Policy(List<PolicyStep> steps) {
            this.steps = steps;
        }

        public static Policy fromName(String value) throws Mf2Exception {
            return switch (optionName(value, "policy")) {
                case "precise" -> PRECISE;
                case "compact" -> COMPACT;
                case "chat" -> CHAT;
                default -> throw Mf2FunctionSupport.badOption("policy must be one of precise, compact, chat.");
            };
        }

        private List<PolicyStep> steps() {
            return steps;
        }
    }

    public enum Unit {
        AUTO("auto", 1),
        SECOND("second", 1),
        MINUTE("minute", 60),
        HOUR("hour", 3_600),
        DAY("day", 86_400),
        WEEK("week", 604_800),
        MONTH("month", 2_592_000),
        QUARTER("quarter", 7_776_000),
        YEAR("year", 31_536_000);

        private final String key;
        private final double seconds;

        Unit(String key, double seconds) {
            this.key = key;
            this.seconds = seconds;
        }

        public static Unit fromName(String value) throws Mf2Exception {
            return switch (optionName(value, "unit")) {
                case "auto" -> AUTO;
                case "second" -> SECOND;
                case "minute" -> MINUTE;
                case "hour" -> HOUR;
                case "day" -> DAY;
                case "week" -> WEEK;
                case "month" -> MONTH;
                case "quarter" -> QUARTER;
                case "year" -> YEAR;
                default -> throw Mf2FunctionSupport.badOption(
                        "unit must be one of auto, second, minute, hour, day, week, month, quarter, year.");
            };
        }

        String key() {
            return key;
        }

        double seconds() {
            return seconds;
        }
    }

    public record Options(
            String locale,
            Style style,
            Numeric numeric,
            Policy policy,
            Unit unit) {
        public Options {
            locale = locale == null || locale.isEmpty() ? DEFAULT_LOCALE : locale;
            style = style == null ? Style.SHORT : style;
            numeric = numeric == null ? Numeric.ALWAYS : numeric;
            policy = policy == null ? Policy.PRECISE : policy;
            unit = unit == null ? Unit.AUTO : unit;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String locale = DEFAULT_LOCALE;
            private Style style = Style.SHORT;
            private Numeric numeric = Numeric.ALWAYS;
            private Policy policy = Policy.PRECISE;
            private Unit unit = Unit.AUTO;

            public Builder locale(String locale) {
                this.locale = locale;
                return this;
            }

            public Builder style(Style style) {
                this.style = style;
                return this;
            }

            public Builder numeric(Numeric numeric) {
                this.numeric = numeric;
                return this;
            }

            public Builder policy(Policy policy) {
                this.policy = policy;
                return this;
            }

            public Builder unit(Unit unit) {
                this.unit = unit;
                return this;
            }

            public Options build() {
                return new Options(locale, style, numeric, policy, unit);
            }
        }
    }

    public record Data(Map<String, String> localeMap, List<PatternSet> patternSets) {
        public Data {
            localeMap = Map.copyOf(localeMap);
            patternSets = List.copyOf(patternSets);
        }
    }

    public record PatternSet(String id, Map<String, Map<String, UnitData>> data) {
        public PatternSet {
            Map<String, Map<String, UnitData>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, UnitData>> entry : data.entrySet()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            data = Map.copyOf(copy);
        }
    }

    public record UnitData(
            Map<String, String> future,
            Map<String, String> past,
            Map<String, String> relative) {
        public UnitData {
            future = future == null ? Map.of() : Map.copyOf(future);
            past = past == null ? Map.of() : Map.copyOf(past);
            relative = relative == null ? Map.of() : Map.copyOf(relative);
        }
    }

    private record PolicyStep(double upper, Unit unit) {}
}
