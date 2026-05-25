package com.box.l10n.mojito.mf2;

final class ConformanceFunctionRegistry {
    private ConformanceFunctionRegistry() {}

    static Mf2FunctionRegistry registry() {
        return FixtureFormatterStubs.registry()
                .withFunction("test:function", ConformanceFunctionRegistry::testFunction)
                .withFunction("test:select", ConformanceFunctionRegistry::testSelectResolver)
                .withFunction("test:format", ConformanceFunctionRegistry::testFormatResolver)
                .withSelector("test:function", ConformanceFunctionRegistry::testSelector)
                .withSelector("test:select", ConformanceFunctionRegistry::testSelector)
                .withSelector("test:format", ConformanceFunctionRegistry::testFormatSelector);
    }

    private static String testFunction(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        TestFunctionState state = testState(call);
        if (state.failsFormat) {
            throw new Mf2Exception(
                    "bad-option",
                    ":test function fails=format requested a format failure.");
        }
        return state.formatValue();
    }

    private static String testSelectResolver(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return testState(call).formatValue();
    }

    private static String testFormatResolver(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return testState(call).formatValue();
    }

    private static Integer testSelector(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        TestFunctionState state = testState(match);
        if (state.failsSelect) {
            throw new Mf2Exception("bad-selector", ":test function fails selection.");
        }
        if ((long) state.input != 1L) {
            return null;
        }
        if (state.decimalPlaces == 1 && match.key().equals("1.0")) {
            return 2;
        }
        if (match.key().equals("1")) {
            return 1;
        }
        return null;
    }

    private static Integer testFormatSelector(Mf2FunctionRegistry.FunctionMatch ignored)
            throws Mf2Exception {
        throw new Mf2Exception("bad-selector", ":test:format cannot be used for selection.");
    }

    private static TestFunctionState testState(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return testState(call.value(), call.inheritedSource(), call::optionValue);
    }

    private static TestFunctionState testState(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        return testState(match.value(), match.inheritedSource(), match::optionValue);
    }

    private static TestFunctionState testState(
            String value,
            Mf2FunctionRegistry.FunctionSourceRef inheritedSource,
            OptionLookup options)
            throws Mf2Exception {
        TestFunctionState state = inheritedSource == null
                ? testStateFromValue(value)
                : testStateFromSource(inheritedSource);
        applyTestOptions(state, options);
        return state;
    }

    private static TestFunctionState testStateFromSource(
            Mf2FunctionRegistry.FunctionSourceRef source) throws Mf2Exception {
        TestFunctionState state = source.inheritedSource() == null
                ? testStateFromValue(source.value())
                : testStateFromSource(source.inheritedSource());
        if (isTestFunction(source.function().name())) {
            applyTestOptions(state, source::optionValue);
        }
        return state;
    }

    private static TestFunctionState testStateFromValue(String value) throws Mf2Exception {
        try {
            return TestFunctionState.from(value);
        } catch (NumberFormatException error) {
            throw Mf2Exception.badOperand("Unicode test function requires a numeric operand.");
        }
    }

    private static void applyTestOptions(TestFunctionState state, OptionLookup options)
            throws Mf2Exception {
        String decimalPlaces = options.value("decimalPlaces", null);
        if (decimalPlaces != null) {
            switch (decimalPlaces) {
                case "0" -> state.decimalPlaces = 0;
                case "1" -> state.decimalPlaces = 1;
                default -> throw new Mf2Exception(
                        "bad-option",
                        ":test function decimalPlaces must be 0 or 1.");
            }
        }
        switch (String.valueOf(options.value("fails", ""))) {
            case "always" -> {
                state.failsFormat = true;
                state.failsSelect = true;
            }
            case "format" -> state.failsFormat = true;
            case "select" -> state.failsSelect = true;
            default -> {}
        }
    }

    private static boolean isTestFunction(String name) {
        return name.equals("test:function") || name.equals("test:select") || name.equals("test:format");
    }

    @FunctionalInterface
    private interface OptionLookup {
        String value(String name, String fallback) throws Mf2Exception;
    }

    private static final class TestFunctionState {
        private final double input;
        private int decimalPlaces;
        private boolean failsFormat;
        private boolean failsSelect;

        private TestFunctionState(double input) {
            this.input = input;
        }

        static TestFunctionState from(String value) {
            return new TestFunctionState(Double.parseDouble(value));
        }

        String formatValue() {
            String sign = input < 0 ? "-" : "";
            double absolute = Math.abs(input);
            long integer = (long) Math.floor(absolute);
            if (decimalPlaces == 1) {
                long digit = (long) Math.floor((absolute - integer) * 10.0);
                return sign + integer + "." + digit;
            }
            return sign + integer;
        }
    }
}
