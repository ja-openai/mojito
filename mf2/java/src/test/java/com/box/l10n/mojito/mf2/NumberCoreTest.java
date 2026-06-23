package com.box.l10n.mojito.mf2;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NumberCoreTest {
    private NumberCoreTest() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/number-core/cases.json");
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        int formatCases = checkFormatCases(arrayOrEmpty(fixture.get("formatCases")));
        int referenceCases = checkReferenceCases(arrayOrEmpty(fixture.get("intlReferenceCases")));
        int errorCases = checkErrorCases(arrayOrEmpty(fixture.get("errorCases")));
        int registryCases = checkRegistryIntegration(arrayOrEmpty(fixture.get("registryCases")));
        int registryErrorCases = checkRegistryErrorCases(arrayOrEmpty(fixture.get("registryErrorCases")));
        checkDefaultOverload();
        System.out.printf(
                "Java number core test passed %d format cases, %d JDK reference cases, "
                        + "%d error cases, %d registry cases, %d registry error cases, and default overloads.%n",
                formatCases, referenceCases, errorCases, registryCases, registryErrorCases);
    }

    private static int checkFormatCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            String actual = Mf2NumberCore.format(
                    item.get("value"),
                    options(string(item.get("locale")), objectOrEmpty(item.get("options"))));
            String expected = string(item.get("expected"));
            if (!actual.equals(expected)) {
                throw new AssertionError(String.format(
                        "%s: expected %s, got %s",
                        string(item.get("name")), expected, actual));
            }
            checked++;
        }
        return checked;
    }

    private static int checkReferenceCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            Map<String, Object> rawOptions = objectOrEmpty(item.get("options"));
            String locale = string(item.get("locale"));
            Object value = item.get("value");
            String actual = Mf2NumberCore.format(value, options(locale, rawOptions));
            String expected = jdkFormat(locale, value, rawOptions);
            if (!actual.equals(expected)) {
                throw new AssertionError(String.format(
                        "JDK reference %s %s: expected %s, got %s",
                        locale, rawOptions, expected, actual));
            }
            checked++;
        }
        return checked;
    }

    private static int checkErrorCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            String expected = string(item.get("expectedError"));
            try {
                Mf2NumberCore.format(
                        item.get("value"),
                        options(string(item.get("locale")), objectOrEmpty(item.get("options"))));
                throw new AssertionError(String.format(
                        "%s: expected %s, got success",
                        string(item.get("name")), expected));
            } catch (Mf2Exception error) {
                if (!error.code().equals(expected)) {
                    throw new AssertionError(String.format(
                            "%s: expected %s, got %s",
                            string(item.get("name")), expected, error.code()));
                }
            }
            checked++;
        }
        return checked;
    }

    private static int checkRegistryIntegration(List<Object> registryCases) throws Exception {
        int checked = 0;
        Mf2FunctionRegistry registry = Mf2NumberCore.registry();
        String actual = registry.format(new Mf2FunctionRegistry.FunctionCall(
                "1234.5",
                1234.5,
                new Mf2Message.FunctionRef("currency", Map.of()),
                "en-US",
                (optionName, defaultValue) -> optionName.equals("currency") ? "USD" : defaultValue,
                null));
        if (!actual.equals("$1,234.50")) {
            throw new AssertionError("registry currency format expected $1,234.50, got " + actual);
        }
        checked++;
        Mf2ParseResult parsed = Mf2Parser.parseToModel(
                ".input {$count :number}\n.match $count\none {{one}}\n* {{other}}");
        Mf2FormatResult result = parsed.model().format(
                Map.of("count", 1),
                Mf2FormatOptions.builder().locale("en").functions(registry).build());
        if (!result.value().equals("one") || result.hasErrors()) {
            throw new AssertionError("registry should preserve portable number selectors, got "
                    + result.value() + " errors=" + result.errors());
        }
        checked++;
        for (Object rawCase : registryCases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult registryParsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (registryParsed.hasDiagnostics()) {
                throw new AssertionError(
                        string(item.get("name")) + ": parse diagnostics " + registryParsed.diagnostics());
            }
            Mf2FormatResult registryResult = registryParsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            if (!registryResult.value().equals(string(item.get("expected"))) || registryResult.hasErrors()) {
                throw new AssertionError(string(item.get("name")) + ": expected "
                        + string(item.get("expected")) + ", got " + registryResult.value()
                        + " errors=" + registryResult.errors());
            }
            checked++;
        }
        return checked;
    }

    private static int checkRegistryErrorCases(List<Object> registryErrorCases) throws Exception {
        int checked = 0;
        Mf2FunctionRegistry registry = Mf2NumberCore.registry();
        for (Object rawCase : registryErrorCases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult parsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (parsed.hasDiagnostics()) {
                throw new AssertionError(
                        string(item.get("name")) + ": parse diagnostics " + parsed.diagnostics());
            }
            Mf2FormatResult result = parsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            List<String> actualCodes = result.errors().stream().map(Mf2Exception::code).toList();
            if (!actualCodes.equals(stringList(item.get("expectedErrors")))) {
                throw new AssertionError(string(item.get("name")) + ": expected errors "
                        + stringList(item.get("expectedErrors")) + ", got "
                        + actualCodes + " value=" + result.value());
            }
            checked++;
        }
        return checked;
    }

    private static void checkDefaultOverload() throws Exception {
        String overloaded = Mf2NumberCore.format(1234.5);
        String explicitDefault = Mf2NumberCore.format(1234.5, null);
        if (!overloaded.equals(explicitDefault)) {
            throw new AssertionError("number core default overload expected "
                    + explicitDefault + ", got " + overloaded);
        }
        List<Mf2FormattedPart> parts = Mf2NumberCore.formatToParts(1234.5);
        List<Mf2FormattedPart> explicitParts = Mf2NumberCore.formatToParts(1234.5, null);
        List<Mf2FormattedPart> expectedParts = List.of(new Mf2FormattedPart.Text(explicitDefault));
        if (!parts.equals(expectedParts) || !explicitParts.equals(expectedParts)) {
            throw new AssertionError("number core formatToParts expected "
                    + expectedParts + ", got " + parts + " and " + explicitParts);
        }
    }

    private static Mf2NumberCore.Options options(String locale, Map<String, Object> rawOptions) {
        Mf2NumberCore.Options.Builder builder = Mf2NumberCore.options().locale(locale);
        if (rawOptions.containsKey("style")) {
            builder.style(style(string(rawOptions.get("style"))));
        }
        if (rawOptions.containsKey("currency")) {
            builder.currency(string(rawOptions.get("currency")));
        }
        if (rawOptions.containsKey("currencyDisplay")) {
            builder.currencyDisplay(currencyDisplay(string(rawOptions.get("currencyDisplay"))));
        }
        if (rawOptions.containsKey("useGrouping")) {
            builder.useGrouping(booleanValue(rawOptions.get("useGrouping")));
        }
        if (rawOptions.containsKey("minimumFractionDigits")) {
            builder.minimumFractionDigits(intValue(rawOptions.get("minimumFractionDigits")));
        }
        if (rawOptions.containsKey("maximumFractionDigits")) {
            builder.maximumFractionDigits(intValue(rawOptions.get("maximumFractionDigits")));
        }
        if (rawOptions.containsKey("signDisplay")) {
            builder.signDisplay(signDisplay(string(rawOptions.get("signDisplay"))));
        }
        return builder.build();
    }

    private static String jdkFormat(String locale, Object rawValue, Map<String, Object> rawOptions) {
        Locale tag = Locale.forLanguageTag(locale);
        String style = stringOrDefault(rawOptions.get("style"), "number");
        NumberFormat formatter = switch (style) {
            case "number" -> NumberFormat.getNumberInstance(tag);
            case "percent" -> NumberFormat.getPercentInstance(tag);
            case "currency" -> {
                NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(tag);
                currencyFormatter.setCurrency(Currency.getInstance(string(rawOptions.get("currency"))));
                yield currencyFormatter;
            }
            default -> throw new IllegalArgumentException("Unsupported reference style: " + style);
        };
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(numberValue(rawValue));
    }

    private static Mf2NumberCore.Style style(String value) {
        return switch (value) {
            case "number" -> Mf2NumberCore.Style.NUMBER;
            case "integer" -> Mf2NumberCore.Style.INTEGER;
            case "percent" -> Mf2NumberCore.Style.PERCENT;
            case "currency" -> Mf2NumberCore.Style.CURRENCY;
            default -> throw new IllegalArgumentException("Unknown number core style: " + value);
        };
    }

    private static Mf2NumberCore.CurrencyDisplay currencyDisplay(String value) {
        return switch (value) {
            case "symbol" -> Mf2NumberCore.CurrencyDisplay.SYMBOL;
            case "narrowSymbol" -> Mf2NumberCore.CurrencyDisplay.NARROW_SYMBOL;
            case "code" -> Mf2NumberCore.CurrencyDisplay.CODE;
            default -> throw new IllegalArgumentException("Unknown currency display: " + value);
        };
    }

    private static Mf2NumberCore.SignDisplay signDisplay(String value) {
        return switch (value) {
            case "auto" -> Mf2NumberCore.SignDisplay.AUTO;
            case "always" -> Mf2NumberCore.SignDisplay.ALWAYS;
            case "never" -> Mf2NumberCore.SignDisplay.NEVER;
            default -> throw new IllegalArgumentException("Unknown sign display: " + value);
        };
    }

    private static Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value == null ? Map.of() : object(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arrayOrEmpty(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static String string(Object value) {
        return (String) value;
    }

    private static List<String> stringList(Object value) {
        return arrayOrEmpty(value).stream().map(NumberCoreTest::string).toList();
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue
                ? booleanValue
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
