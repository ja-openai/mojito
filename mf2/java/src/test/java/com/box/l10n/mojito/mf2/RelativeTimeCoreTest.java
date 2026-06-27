package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RelativeTimeCoreTest {
    private RelativeTimeCoreTest() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/functions/relative-time-duration-v0.json");
        Mf2RelativeTimeCore.Data data = relativeTimeData();
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        int formatCases = checkFormatCases(data, arrayOrEmpty(fixture.get("cases")));
        int errorCases = checkErrorCases(data, arrayOrEmpty(fixture.get("errorCases")));
        checkDirectApi(data);
        int referenceCases = checkIntlReferenceCases(data);
        System.out.printf(
                "Java relative-time core test passed %d format cases, %d error cases, "
                        + "%d Intl reference cases, and direct API checks.%n",
                formatCases, errorCases, referenceCases);
    }

    private static int checkFormatCases(
            Mf2RelativeTimeCore.Data data, List<Object> cases) throws Exception {
        Mf2FunctionRegistry registry = Mf2RelativeTimeCore.registry(data);
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult parsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (parsed.hasDiagnostics()) {
                throw new AssertionError(string(item.get("label")) + ": parse diagnostics "
                        + parsed.diagnostics());
            }
            Mf2FormatResult actual = parsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            String expected = string(item.get("expected"));
            if (!actual.value().equals(expected) || actual.hasErrors()) {
                throw new AssertionError(String.format(
                        "%s: expected %s, got %s errors=%s",
                        string(item.get("label")), expected, actual.value(), actual.errors()));
            }
            checked++;
        }
        return checked;
    }

    private static int checkErrorCases(
            Mf2RelativeTimeCore.Data data, List<Object> cases) throws Exception {
        Mf2FunctionRegistry registry = Mf2RelativeTimeCore.registry(data);
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult parsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (parsed.hasDiagnostics()) {
                throw new AssertionError(string(item.get("label")) + ": parse diagnostics "
                        + parsed.diagnostics());
            }
            Mf2FormatResult actual = parsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            String expected = string(object(item.get("expectedError")).get("code"));
            if (!errorCodes(actual.errors()).equals(List.of(expected))) {
                throw new AssertionError(String.format(
                        "%s: expected errors %s, got %s",
                        string(item.get("label")), List.of(expected), actual.errors()));
            }
            checked++;
        }
        return checked;
    }

    private static void checkDirectApi(Mf2RelativeTimeCore.Data data) throws Exception {
        String direct = Mf2RelativeTimeCore.format(
                3_600,
                data,
                Mf2RelativeTimeCore.Options.builder()
                        .locale("en")
                        .style(Mf2RelativeTimeCore.Style.NARROW)
                        .numeric(Mf2RelativeTimeCore.Numeric.ALWAYS)
                        .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                        .unit(Mf2RelativeTimeCore.Unit.AUTO)
                        .build());
        if (!direct.equals("in 1h")) {
            throw new AssertionError("direct relative-time format expected in 1h, got " + direct);
        }
        String emptyLocale = Mf2RelativeTimeCore.format(
                3_600,
                data,
                Mf2RelativeTimeCore.Options.builder()
                        .locale("")
                        .style(Mf2RelativeTimeCore.Style.NARROW)
                        .numeric(Mf2RelativeTimeCore.Numeric.ALWAYS)
                        .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                        .unit(Mf2RelativeTimeCore.Unit.AUTO)
                        .build());
        if (!emptyLocale.equals("in 1h")) {
            throw new AssertionError(
                    "direct relative-time empty locale expected in 1h, got " + emptyLocale);
        }
        String negativeZero = Mf2RelativeTimeCore.format(
                -0.0d,
                data,
                Mf2RelativeTimeCore.Options.builder()
                        .locale("en")
                        .style(Mf2RelativeTimeCore.Style.LONG)
                        .numeric(Mf2RelativeTimeCore.Numeric.ALWAYS)
                        .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                        .unit(Mf2RelativeTimeCore.Unit.SECOND)
                        .build());
        if (!negativeZero.equals("0 seconds ago")) {
            throw new AssertionError(
                    "direct relative-time negative zero expected 0 seconds ago, got " + negativeZero);
        }
        String afterTomorrow = Mf2RelativeTimeCore.format(
                172_800,
                data,
                Mf2RelativeTimeCore.Options.builder()
                        .locale("fr")
                        .style(Mf2RelativeTimeCore.Style.LONG)
                        .numeric(Mf2RelativeTimeCore.Numeric.AUTO)
                        .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                        .unit(Mf2RelativeTimeCore.Unit.DAY)
                        .build());
        if (!afterTomorrow.equals("après-demain")) {
            throw new AssertionError(
                    "direct relative-time after tomorrow expected après-demain, got " + afterTomorrow);
        }

        List<Mf2FormattedPart> parts = Mf2RelativeTimeCore.formatToParts(
                -86_400,
                data,
                Mf2RelativeTimeCore.Options.builder()
                        .locale("en")
                        .style(Mf2RelativeTimeCore.Style.LONG)
                        .numeric(Mf2RelativeTimeCore.Numeric.AUTO)
                        .unit(Mf2RelativeTimeCore.Unit.DAY)
                        .build());
        if (!parts.equals(List.of(new Mf2FormattedPart.Text("yesterday")))) {
            throw new AssertionError("relative-time parts expected text yesterday, got " + parts);
        }
        Mf2RelativeTimeCore formatter = Mf2RelativeTimeCore.create(data);
        assertSameDefault(
                "instance format",
                formatter.format(60),
                Mf2RelativeTimeCore.format(60, data, null));
        assertSameDefault(
                "static format",
                Mf2RelativeTimeCore.format(60, data),
                Mf2RelativeTimeCore.format(60, data, null));
        assertSameDefaultParts(
                "instance formatToParts",
                formatter.formatToParts(60),
                Mf2RelativeTimeCore.formatToParts(60, data, null));
        assertSameDefaultParts(
                "static formatToParts",
                Mf2RelativeTimeCore.formatToParts(60, data),
                Mf2RelativeTimeCore.formatToParts(60, data, null));

        try {
            Mf2RelativeTimeCore.format(
                    "1e30",
                    data,
                    Mf2RelativeTimeCore.Options.builder()
                            .locale("en")
                            .style(Mf2RelativeTimeCore.Style.NARROW)
                            .numeric(Mf2RelativeTimeCore.Numeric.ALWAYS)
                            .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                            .unit(Mf2RelativeTimeCore.Unit.AUTO)
                            .build());
            throw new AssertionError("huge relative-time quantity expected bad-operand");
        } catch (Mf2Exception error) {
            if (!error.code().equals("bad-operand")) {
                throw new AssertionError(
                        "huge relative-time quantity expected bad-operand, got " + error.code());
            }
        }
        Object throwingOperand = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("relative-time operand coercion failed");
            }
        };
        try {
            Mf2RelativeTimeCore.format(throwingOperand, data);
            throw new AssertionError("throwing relative-time operand expected bad-operand");
        } catch (Mf2Exception error) {
            if (!error.code().equals("bad-operand")) {
                throw new AssertionError(
                        "throwing relative-time operand expected bad-operand, got " + error.code());
            }
        }
    }

    private static void assertSameDefault(String label, String overloaded, String explicitDefault) {
        if (!overloaded.equals(explicitDefault)) {
            throw new AssertionError("relative-time core " + label + " default overload expected "
                    + explicitDefault + ", got " + overloaded);
        }
    }

    private static void assertSameDefaultParts(
            String label, List<Mf2FormattedPart> overloaded, List<Mf2FormattedPart> explicitDefault) {
        if (!overloaded.equals(explicitDefault)) {
            throw new AssertionError("relative-time core " + label + " default overload expected "
                    + explicitDefault + ", got " + overloaded);
        }
    }

    private static int checkIntlReferenceCases(Mf2RelativeTimeCore.Data data) throws Exception {
        Mf2RelativeTimeCore formatter = Mf2RelativeTimeCore.create(data);
        List<ReferenceCase> cases = List.of(
                new ReferenceCase("en", "long", "auto", "day", -1, -86_400),
                new ReferenceCase("en", "long", "always", "day", 1, 86_400),
                new ReferenceCase("ja", "narrow", "always", "minute", 3, 180),
                new ReferenceCase("en", "narrow", "always", "minute", -1, -60),
                new ReferenceCase("en", "long", "always", "second", -0.0d, -0.0d),
                new ReferenceCase("fr", "long", "auto", "day", 2, 172_800));
        List<Object> references = nodeIntlRelativeTimeOutputs();
        for (int index = 0; index < cases.size(); index++) {
            ReferenceCase item = cases.get(index);
            String actual = formatter.format(
                    item.seconds(),
                    Mf2RelativeTimeCore.Options.builder()
                            .locale(item.locale())
                            .style(Mf2RelativeTimeCore.Style.fromName(item.style()))
                            .numeric(Mf2RelativeTimeCore.Numeric.fromName(item.numeric()))
                            .policy(Mf2RelativeTimeCore.Policy.PRECISE)
                            .unit(Mf2RelativeTimeCore.Unit.fromName(item.unit()))
                            .build());
            String expected = string(references.get(index));
            if (!actual.equals(expected)) {
                throw new AssertionError(String.format(
                        "Intl relative-time reference %d: expected %s, got %s",
                        index, expected, actual));
            }
        }
        return cases.size();
    }

    private static Mf2RelativeTimeCore.Data relativeTimeData() throws Exception {
        return Mf2RelativeTimeCore.dataFromJson(object(
                JsonParser.parse(Path.of("../cldr/generated/relative-time/all/relative_time.json"))));
    }

    private static List<Object> nodeIntlRelativeTimeOutputs() throws Exception {
        String script = """
                const cases = [
                  {locale:"en", style:"long", numeric:"auto", unit:"day", value:-1},
                  {locale:"en", style:"long", numeric:"always", unit:"day", value:1},
                  {locale:"ja", style:"narrow", numeric:"always", unit:"minute", value:3},
                  {locale:"en", style:"narrow", numeric:"always", unit:"minute", value:-1},
                  {locale:"en", style:"long", numeric:"always", unit:"second", value:-0},
                  {locale:"fr", style:"long", numeric:"auto", unit:"day", value:2},
                ];
                process.stdout.write(JSON.stringify(cases.map((item) =>
                  new Intl.RelativeTimeFormat(item.locale, { style: item.style, numeric: item.numeric }).format(item.value, item.unit)
                )));
                """;
        Process process = new ProcessBuilder("node", "-e", script)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Node Intl relative-time reference failed: " + output);
        }
        return arrayOrEmpty(JsonParser.parse(output));
    }

    private static List<String> errorCodes(List<Mf2Exception> errors) {
        return errors.stream().map(Mf2Exception::code).toList();
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

    private record ReferenceCase(
            String locale,
            String style,
            String numeric,
            String unit,
            double value,
            double seconds) {}
}
