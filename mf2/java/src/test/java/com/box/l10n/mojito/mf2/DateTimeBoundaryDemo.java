package com.box.l10n.mojito.mf2;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.Map;

public final class DateTimeBoundaryDemo {
    private DateTimeBoundaryDemo() {}

    public static void main(String[] args) throws Exception {
        Mf2FunctionRegistry fixtureFunctions = SampleCatalogFunctions.registry();
        Mf2FunctionRegistry platformFunctions = Mf2FunctionRegistry.defaults();
        Case[] cases = new Case[] {
            new Case(
                    "date.literal",
                    "Due {|2026-05-21| :date}",
                    fixtureFunctions,
                    Map.of(),
                    "Due 2026-05-21"),
            new Case(
                    "date.localDate",
                    "Due {$due :date}",
                    fixtureFunctions,
                    "en",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Due 2026-05-21"),
            new Case(
                    "date.localized.en",
                    "Due {$due :date style=full}",
                    platformFunctions,
                    "en-US",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Due Thursday, May 21, 2026"),
            new Case(
                    "date.localized.fr",
                    "Livraison {$due :date style=full}",
                    platformFunctions,
                    "fr-FR",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Livraison jeudi 21 mai 2026"),
            new Case(
                    "time.localTime",
                    "Start {$start :time}",
                    fixtureFunctions,
                    "en",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Start 14:30:00"),
            new Case(
                    "time.localized.en",
                    "Start {$start :time precision=medium}",
                    platformFunctions,
                    "en-US",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Start 2:30:00\u202fPM"),
            new Case(
                    "time.localized.fr",
                    "Début {$start :time precision=medium}",
                    platformFunctions,
                    "fr-FR",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Début 14:30:00"),
            new Case(
                    "datetime.localDateTime",
                    "Created {$created :datetime}",
                    fixtureFunctions,
                    "en",
                    Map.of("created", LocalDateTime.of(2026, 5, 21, 14, 30)),
                    "Created 2026-05-21T14:30:00"),
            new Case(
                    "datetime.localized.en",
                    "Created {$created :datetime style=medium}",
                    platformFunctions,
                    "en-US",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Created May 21, 2026, 2:30:00\u202fPM"),
            new Case(
                    "datetime.localized.fr",
                    "Créé {$created :datetime style=medium}",
                    platformFunctions,
                    "fr-FR",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Créé 21 mai 2026, 14:30:00"),
            new Case(
                    "datetime.instant",
                    "Created {$created :datetime}",
                    fixtureFunctions,
                    "en",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Created 2026-05-21T14:30:00Z"),
            new Case(
                    "date.legacyDate",
                    "Epoch {$epoch :date}",
                    fixtureFunctions,
                    "en",
                    Map.of("epoch", Date.from(Instant.EPOCH)),
                    "Epoch 1970-01-01"),
        };

        for (Case demoCase : cases) {
            String actual = format(
                    demoCase.source(), demoCase.locale(), demoCase.arguments(), demoCase.functions());
            if (!actual.equals(demoCase.expected())) {
                throw new AssertionError(demoCase.label()
                        + " expected \""
                        + demoCase.expected()
                        + "\", got \""
                        + actual
                        + "\"");
            }
            System.out.println(demoCase.label() + " -> \"" + actual + "\"");
        }

        String legacyToString = Date.from(Instant.EPOCH).toString();
        System.out.println("date.legacyDate.rawToStringWouldLeak -> \"" + legacyToString + "\"");
    }

    private static String format(
            String source, String locale, Map<String, ?> arguments, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        Mf2FormatResult formatted = result.model().format(
                arguments,
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(functions)
                        .build());
        if (formatted.hasErrors()) {
            throw new Mf2Exception("format-error", formatted.errors().toString());
        }
        return formatted.value();
    }

    private record Case(
            String label,
            String source,
            Mf2FunctionRegistry functions,
            String locale,
            Map<String, ?> arguments,
            String expected) {
        Case(
                String label,
                String source,
                Mf2FunctionRegistry functions,
                Map<String, ?> arguments,
                String expected) {
            this(label, source, functions, "en", arguments, expected);
        }
    }
}
