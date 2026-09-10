package com.box.l10n.mojito.mf2;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;

public final class RuntimeRegressionTests {
    private RuntimeRegressionTests() {}
    public static void main(String[] args) throws Exception { run(); }

    static void run() throws Exception {
        bidiIsolation();
        for (var registry : new Mf2FunctionRegistry[]{Mf2FunctionRegistry.portable(), Mf2FunctionRegistry.defaults()}) {
            var options = Mf2FormatOptions.builder().functions(registry).build();
            for (double operand : new double[]{1e19, -1e19, 0x1.0p63, Math.nextDown(-0x1.0p63)}) {
                var result = render("{$x :integer}", Map.of("x", operand), options);
                check(result.errors().stream().anyMatch(error -> error.code().equals("bad-operand")), "integer conversion bound: " + operand);
            }
            for (double operand : new double[]{Math.nextDown(0x1.0p63), -0x1.0p63, Math.nextUp(-0x1.0p63)}) {
                check(!render("{$x :integer}", Map.of("x", operand), options).hasErrors(), "valid integer boundary: " + operand);
            }
            check(render("{$x}", Map.of("x", 1e19), options).value().equals("10000000000000000000"), "native numeric conversion must not narrow to long");
        }
        check(Mf2FunctionSupport.truncateInteger(-0x1.0p63) == Long.MIN_VALUE, "integer lower endpoint");
        var copiedSourceRegistry = Mf2FunctionRegistry.portable().withFunction("number", call -> {
            var inherited = call.inheritedSource();
            if (inherited == null) return call.value();
            Mf2FunctionSupport.numericSourceOperand(inherited);
            var copied = new Mf2FunctionRegistry.FunctionSourceRef("2", inherited.function(), inherited.options(), null);
            return Mf2FunctionSupport.numericSourceOperand(copied);
        });
        check(render(".local $n = {1 :number}\n{{{$n :number}}}", Map.of(), Mf2FormatOptions.builder().functions(copiedSourceRegistry).build()).value().equals("2"), "caller-created source cannot reuse a different source cache");
        var portable = Mf2FormatOptions.builder().functions(Mf2FunctionRegistry.portable()).build();
        var digits = render("{1 :number minimumFractionDigits=1000}", Map.of(), portable);
        check(!digits.hasErrors() && digits.value().equals("1." + "0".repeat(1000)), "fraction boundary");
        var exponent = render("{1e-100000 :offset add=1}", Map.of(), portable);
        check(exponent.errors().stream().anyMatch(error -> error.code().equals("bad-operand")), "offset expansion bound");
        StringBuilder chain = new StringBuilder(".local $v0 = {1 :number}\n");
        for (int index = 1; index < 7000; index++) chain.append(".local $v").append(index).append(" = {$v").append(index - 1).append(" :number}\n");
        chain.append("{{{$v6999 :number}}}");
        var chained = render(chain.toString(), Map.of(), portable);
        check(!chained.hasErrors() && chained.value().equals("1"), "deep declaration chain");
        ZonedDateTime instant = ZonedDateTime.parse("2026-05-21T14:30:15Z");
        for (String tag : new String[]{"en-US", "fr-FR"}) {
            for (String zone : new String[]{"UTC", "America/Los_Angeles"}) {
                var options = Mf2FormatOptions.builder().locale(tag).build();
                for (FormatStyle style : FormatStyle.values()) {
                    String name = style.name().toLowerCase(Locale.ROOT);
                    var temporal = instant.withZoneSameInstant(ZoneId.of(zone));
                    String expectedTime = DateTimeFormatter.ofLocalizedTime(style).withLocale(Locale.forLanguageTag(tag)).format(temporal);
                    String expectedDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, style).withLocale(Locale.forLanguageTag(tag)).format(temporal);
                    var time = render("{$x :time timeStyle=" + name + " timeZone=|" + zone + "|}", Map.of("x", instant), options);
                    var dateTime = render("{$x :datetime dateStyle=short timeStyle=" + name + " timeZone=|" + zone + "|}", Map.of("x", instant), options);
                    check(!time.hasErrors() && time.value().equals(expectedTime), "time style " + tag + zone + name);
                    check(!dateTime.hasErrors() && dateTime.value().equals(expectedDateTime), "datetime style " + tag + zone + name);
                }
            }
        }
    }

    private static void bidiIsolation() throws Exception {
        var registry = Mf2FunctionRegistry.portable();
        var hiddenDirection = registry.withFunction("probe", call -> call.optionValue("u:dir", "removed"));
        check(render("{:probe u:dir=$direction}", Map.of("direction", "rtl"), Mf2FormatOptions.builder().functions(hiddenDirection).build()).value().equals("removed"), "u:dir removed from resolved callback options");
        for (String locale : new String[]{"en", "ar", "ar-Latn", "en-Arab", "en-Qaaa", "zz", "und", "az-IR", "sd-IN", "en-u-nu-arab"}) {
            boolean ltr = java.util.Set.of("en", "ar-Latn", "sd-IN", "en-u-nu-arab").contains(locale);
            var options = Mf2FormatOptions.builder().locale(locale).functions(registry).bidiIsolation(Mf2BidiIsolation.DEFAULT).build();
            var result = render("{1 :number}", Map.of(), options);
            check(result.value().equals(ltr ? "1" : "\u20681\u2069"), "numeric direction " + locale);
        }
        var options = Mf2FormatOptions.builder().functions(registry).bidiIsolation(Mf2BidiIsolation.DEFAULT).build();
        check(render(".local $n = {1 :number u:dir=rtl}\n{{{$n}}}", Map.of(), options).value().equals("\u20671\u2069"), "inherited explicit direction");
        var custom = Mf2FormatOptions.builder().functions(registry.withFunction("number", call -> "custom")).bidiIsolation(Mf2BidiIsolation.DEFAULT).build();
        check(render("{1 :number}", Map.of(), custom).value().equals("\u2068custom\u2069"), "custom override isolates");
        var selector = Mf2FormatOptions.builder().functions(registry.withSelector("number", match -> null)).bidiIsolation(Mf2BidiIsolation.DEFAULT).build();
        check(render("{1 :number}", Map.of(), selector).value().equals("1"), "selector override retains direction");
    }

    private static Mf2FormatResult render(String source, Map<String, ?> args, Mf2FormatOptions options) throws Exception {
        var parsed = Mf2Parser.parseToModel(source);
        check(!parsed.hasDiagnostics(), "regression source parses: " + parsed.diagnostics());
        return parsed.model().format(args, options);
    }
    private static void check(boolean success, String label) { if (!success) throw new AssertionError(label); }
}
