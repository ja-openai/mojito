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
        Mf2FunctionRegistry functions = DemoFunctions.registry();
        Case[] cases = new Case[] {
            new Case(
                    "date.literal",
                    "Due {|2026-05-21| :date}",
                    Map.of(),
                    "Due 2026-05-21"),
            new Case(
                    "date.localDate",
                    "Due {$due :date}",
                    "en",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Due 2026-05-21"),
            new Case(
                    "date.localized.en",
                    "Due {$due :date style=full}",
                    "en-US",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Due Thursday, May 21, 2026"),
            new Case(
                    "date.localized.fr",
                    "Livraison {$due :date style=full}",
                    "fr-FR",
                    Map.of("due", LocalDate.of(2026, 5, 21)),
                    "Livraison jeudi 21 mai 2026"),
            new Case(
                    "time.localTime",
                    "Start {$start :time}",
                    "en",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Start 14:30:00"),
            new Case(
                    "time.localized.en",
                    "Start {$start :time style=short}",
                    "en-US",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Start 2:30\u202fPM"),
            new Case(
                    "time.localized.fr",
                    "Début {$start :time style=short}",
                    "fr-FR",
                    Map.of("start", LocalTime.of(14, 30)),
                    "Début 14:30"),
            new Case(
                    "datetime.localDateTime",
                    "Created {$created :datetime}",
                    "en",
                    Map.of("created", LocalDateTime.of(2026, 5, 21, 14, 30)),
                    "Created 2026-05-21T14:30:00"),
            new Case(
                    "datetime.localized.en",
                    "Created {$created :datetime style=medium}",
                    "en-US",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Created May 21, 2026, 2:30:00\u202fPM"),
            new Case(
                    "datetime.localized.fr",
                    "Créé {$created :datetime style=medium}",
                    "fr-FR",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Créé 21 mai 2026, 14:30:00"),
            new Case(
                    "datetime.instant",
                    "Created {$created :datetime}",
                    "en",
                    Map.of("created", Instant.parse("2026-05-21T14:30:00Z")),
                    "Created 2026-05-21T14:30:00Z"),
            new Case(
                    "date.legacyDate",
                    "Epoch {$epoch :date}",
                    "en",
                    Map.of("epoch", Date.from(Instant.EPOCH)),
                    "Epoch 1970-01-01"),
        };

        for (Case demoCase : cases) {
            String actual = format(
                    demoCase.source(), demoCase.locale(), demoCase.arguments(), functions);
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
        ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model().format(arguments, locale, functions);
    }

    private record Case(
            String label, String source, String locale, Map<String, ?> arguments, String expected) {
        Case(String label, String source, Map<String, ?> arguments, String expected) {
            this(label, source, "en", arguments, expected);
        }
    }
}
