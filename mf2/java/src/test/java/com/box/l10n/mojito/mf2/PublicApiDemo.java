package com.box.l10n.mojito.mf2;

import java.util.List;
import java.util.Map;

public final class PublicApiDemo {
    private PublicApiDemo() {}

    public static void main(String[] args) throws Exception {
        Mf2Message message = parse("Hello {$name}");
        Map<String, Object> arguments = Map.of("name", "Mojito");
        Mf2FormatOptions options = Mf2FormatOptions.defaults();

        Mf2FormatResult staticResult =
                Mf2Formatter.formatMessage(message, arguments, options);
        assertEquals("static result value", "Hello Mojito", staticResult.value());
        assertEquals("static result ok", true, staticResult.ok());
        assertEmpty("static result errors", staticResult.errors());

        Mf2PartsResult staticParts =
                Mf2Formatter.formatMessageToParts(message, arguments, options);
        assertExpressionParts("static result parts", staticParts.parts(), "Mojito");
        assertEquals("static parts ok", true, staticParts.ok());
        assertEmpty("static parts errors", staticParts.errors());

        Mf2FormatResult instanceResult = message.format(arguments, options);
        assertEquals("instance result value", "Hello Mojito", instanceResult.value());
        assertEmpty("instance result errors", instanceResult.errors());

        Mf2PartsResult instanceParts = message.formatToParts(arguments, options);
        assertExpressionParts("instance result parts", instanceParts.parts(), "Mojito");
        assertEmpty("instance parts errors", instanceParts.errors());

        Mf2FormatResult fallback = message.format(Map.of(), options);
        assertEquals("fallback result value", "Hello {$name}", fallback.value());
        assertEquals("fallback result ok", false, fallback.ok());
        assertEquals("fallback error count", 1, fallback.errors().size());

        assertEquals(
                "null locale defaults",
                "en",
                Mf2FormatOptions.builder().locale(null).build().locale());
        assertEquals(
                "blank locale defaults",
                "en",
                Mf2FormatOptions.builder().locale(" \t").build().locale());

        Mf2FormatOptions recoveryOptions = Mf2FormatOptions.builder()
                .onMissingArgument(context -> "[missing " + context.variableName() + "]")
                .build();
        Mf2FormatResult recovered = message.format(Map.of(), recoveryOptions);
        assertEquals("callback recovery value", "Hello [missing name]", recovered.value());
        assertEquals("callback recovery error count", 1, recovered.errors().size());

        Mf2FormatOptions emptyMissingRecovery = Mf2FormatOptions.builder()
                .onMissingArgument(context -> "")
                .build();
        Mf2FormatResult emptyMissing = message.format(Map.of(), emptyMissingRecovery);
        assertEquals("empty missing recovery value", "Hello ", emptyMissing.value());
        assertEquals("empty missing recovery error count", 1, emptyMissing.errors().size());
        assertFallbackParts(
                "empty missing recovery parts",
                message.formatToParts(Map.of(), emptyMissingRecovery).parts(),
                "Hello ",
                "$name",
                "");

        Mf2FormatOptions declinedRecovery = Mf2FormatOptions.builder()
                .onMissingArgument(context -> null)
                .build();
        Mf2FormatResult declined = message.format(Map.of(), declinedRecovery);
        assertEquals("declined missing recovery value", "Hello {$name}", declined.value());
        assertFallbackParts(
                "declined missing recovery parts",
                message.formatToParts(Map.of(), declinedRecovery).parts(),
                "Hello ",
                "$name",
                null);

        Mf2Message integerMessage = parse("Hello {$name :integer}");
        Mf2FormatOptions emptyFormatErrorRecovery = Mf2FormatOptions.builder()
                .onFormatError(context -> "")
                .build();
        Mf2FormatResult emptyFormatError =
                integerMessage.format(Map.of("name", "abc"), emptyFormatErrorRecovery);
        assertEquals("empty format-error recovery value", "Hello ", emptyFormatError.value());
        assertEquals("empty format-error code", "bad-operand", emptyFormatError.errors().get(0).code());
        assertFallbackParts(
                "empty format-error recovery parts",
                integerMessage.formatToParts(Map.of("name", "abc"), emptyFormatErrorRecovery).parts(),
                "Hello ",
                "$name",
                "");

        Mf2FormatResult throwingPlaceholder =
                message.format(Map.of("name", new ThrowingStringValue()), options);
        assertEquals("throwing host placeholder value", "Hello {$name}", throwingPlaceholder.value());
        assertErrorCodes("throwing host placeholder errors", List.of("bad-operand"), throwingPlaceholder.errors());

        Mf2Message throwingStringMessage = parse("Hello {$name :string}");
        Mf2FormatResult throwingString =
                throwingStringMessage.format(Map.of("name", new ThrowingStringValue()), options);
        assertEquals("throwing host annotated value", "Hello {$name}", throwingString.value());
        assertErrorCodes("throwing host annotated errors", List.of("bad-operand"), throwingString.errors());

        Mf2Message throwingSelectorMessage = parse("""
                .input {$name :string}
                .match $name
                ok {{ok}}
                * {{fallback}}""");
        Mf2FormatResult throwingSelector =
                throwingSelectorMessage.format(Map.of("name", new ThrowingStringValue()), options);
        assertEquals("throwing host selector value", "fallback", throwingSelector.value());
        assertErrorCodes(
                "throwing host selector errors",
                List.of("bad-operand", "bad-selector"),
                throwingSelector.errors());

        Mf2Message throwingOptionMessage = parse("Hello {1 :number minimumFractionDigits=$digits}");
        Mf2FormatResult throwingNumberOption = throwingOptionMessage.format(
                Map.of("digits", new ThrowingStringValue()), options);
        assertEquals("throwing host number option value", "Hello {|1|}", throwingNumberOption.value());
        assertErrorCodes("throwing host number option errors", List.of("bad-option"), throwingNumberOption.errors());

        Mf2Message throwingOptionSelectorMessage = parse("""
                .input {$count :number minimumFractionDigits=$digits}
                .match $count
                one {{one}}
                * {{fallback}}""");
        Mf2FormatResult throwingOptionSelector = throwingOptionSelectorMessage.format(
                Map.of("count", 1, "digits", new ThrowingStringValue()), options);
        assertEquals("throwing host number option selector value", "fallback", throwingOptionSelector.value());
        List<String> throwingOptionSelectorCodes = errorCodes(throwingOptionSelector.errors());
        assertEquals("throwing host number option selector first error", "bad-option", throwingOptionSelectorCodes.get(0));
        assertContainsAll(
                "throwing host number option selector errors",
                throwingOptionSelectorCodes,
                List.of("bad-option", "bad-selector"));

        Mf2FormatOptions portableOptions = Mf2FormatOptions.builder()
                .functions(Mf2FunctionRegistry.portable())
                .build();
        Mf2Message offsetMessage = parse("Next: {$count :offset add=1}");
        Mf2FormatResult portableOffset = offsetMessage.format(Map.of("count", 41), portableOptions);
        assertEquals("portable registry offset", "Next: 42", portableOffset.value());
        assertEmpty("portable registry offset errors", portableOffset.errors());

        Mf2Message numberMessage = parse("Ratio: {$ratio :percent minimumFractionDigits=1}");
        Mf2FormatResult portableNumber =
                numberMessage.format(Map.of("ratio", 0.125), portableOptions);
        assertEquals("portable registry unlocalized percent", "Ratio: 12.5%", portableNumber.value());
        assertEmpty("portable registry unlocalized percent errors", portableNumber.errors());

        assertEquals(
                "bidi isolation from name",
                Mf2BidiIsolation.DEFAULT,
                Mf2BidiIsolation.fromName("default"));
        assertEquals(
                "bidi isolation default fallback",
                Mf2BidiIsolation.NONE,
                Mf2BidiIsolation.fromName(null));

        Mf2Message currencyMessage = parse("Total: {$amount :currency currency=USD}");
        Mf2FormatResult defaultCurrency =
                currencyMessage.format(Map.of("amount", 12.5), options);
        assertEquals("default registry formats JDK functions", true, defaultCurrency.ok());
        assertEmpty("default registry currency errors", defaultCurrency.errors());

        Mf2FormatResult portableCurrency =
                currencyMessage.format(Map.of("amount", 12.5), portableOptions);
        assertEquals("portable registry does not include currency shim", true, portableCurrency.hasErrors());

        Mf2Message selectorOnlyMessage = parse("""
                .input {$flag :raw}
                .match $flag
                raw {{raw}}
                * {{fallback}}""");
        Mf2FunctionRegistry selectorOnlyRegistry = Mf2FunctionRegistry.portable()
                .withSelector("raw", match -> {
                    if (Boolean.TRUE.equals(match.rawValue()) && match.key().equals("raw")) {
                        return 1;
                    }
                    return null;
                });
        Mf2FormatResult selectorOnlyResult = selectorOnlyMessage.format(
                Map.of("flag", true),
                Mf2FormatOptions.builder().functions(selectorOnlyRegistry).build());
        assertEquals("selector-only annotation rawValue", "raw", selectorOnlyResult.value());
        assertEmpty("selector-only annotation rawValue errors", selectorOnlyResult.errors());

        Mf2Message directedMessage = parse("Hello {$name :string u:dir=rtl}");
        Mf2PartsResult directedParts = directedMessage.formatToParts(Map.of("name", "Mojito"), options);
        Map<String, Object> directedExpression = FormattedPartJson.toMaps(directedParts.parts()).get(1);
        assertEquals("direction field part", "rtl", directedExpression.get("direction"));
        if (directedExpression.containsKey("dir")) {
            throw new AssertionError("direction field part used stale dir key: " + directedExpression);
        }

        Mf2Message falsePresenceAttribute = new Mf2Message.PatternMessage(
                List.of(),
                List.of(new Mf2Message.ExpressionPart(
                        new Mf2Message.Expression(
                                null,
                                null,
                                Map.of("private", new Mf2Message.PresentAttribute(false))))));
        assertThrowsCode(
                "false presence attribute",
                "bad-option",
                () -> falsePresenceAttribute.format(Map.of(), options));

        System.out.println("Java public API demo passed");
    }

    private static Mf2Message parse(String source) throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model();
    }

    private static void assertExpressionParts(
            String label, List<Mf2FormattedPart> parts, String expressionValue) {
        if (parts.size() != 2
                || !(parts.get(0) instanceof Mf2FormattedPart.Text text)
                || !text.value().equals("Hello ")
                || !(parts.get(1) instanceof Mf2FormattedPart.Expression expression)
                || !expression.value().equals(expressionValue)) {
            throw new AssertionError(label + " got unexpected parts: " + parts);
        }
    }

    private static void assertFallbackParts(
            String label, List<Mf2FormattedPart> parts, String textValue, String source, String value) {
        if (parts.size() != 2
                || !(parts.get(0) instanceof Mf2FormattedPart.Text text)
                || !text.value().equals(textValue)
                || !(parts.get(1) instanceof Mf2FormattedPart.Fallback fallback)
                || !fallback.source().equals(source)
                || (value == null
                        ? fallback.value() != null
                        : !value.equals(fallback.value()))) {
            throw new AssertionError(label + " got unexpected parts: " + parts);
        }
    }

    private static void assertEmpty(String label, List<?> values) {
        if (!values.isEmpty()) {
            throw new AssertionError(label + " expected empty list, got " + values);
        }
    }

    private static void assertErrorCodes(String label, List<String> expected, List<Mf2Exception> errors) {
        List<String> actual = errorCodes(errors);
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected " + expected + ", got " + actual);
        }
    }

    private static List<String> errorCodes(List<Mf2Exception> errors) {
        return errors.stream().map(Mf2Exception::code).toList();
    }

    private static void assertContainsAll(String label, List<String> actual, List<String> expected) {
        if (!actual.containsAll(expected)) {
            throw new AssertionError(label + " expected " + expected + " to be present in " + actual);
        }
    }

    private static void assertThrowsCode(String label, String expected, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (Mf2Exception error) {
            assertEquals(label + " code", expected, error.code());
            return;
        }
        throw new AssertionError(label + " expected " + expected);
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected " + expected + ", got " + actual);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ThrowingStringValue {
        @Override
        public String toString() {
            throw new IllegalStateException("host string conversion failed");
        }
    }
}
