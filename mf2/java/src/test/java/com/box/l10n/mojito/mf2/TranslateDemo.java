package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.Map;

public final class TranslateDemo {
    private TranslateDemo() {}

    public static void main(String[] args) throws Exception {
        Catalog catalog = Catalog.load(Path.of("../examples/catalog.json"), DemoFunctions.registry());
        System.out.println("welcome[fr] -> \"" + catalog.translate("welcome", "fr", Map.of("name", "Mojito")) + "\"");
        System.out.println("welcome[fr-CA] -> \"" + catalog.translate("welcome", "fr-CA", Map.of("name", "Mojito")) + "\"");
        System.out.println("checkout.total[en] -> \""
                + catalog.translate("checkout.total", "en", Map.of("amount", 1234.5)) + "\"");
        System.out.println("checkout.total[fr] -> \""
                + catalog.translate("checkout.total", "fr", Map.of("amount", 1234.5)) + "\"");
        String fileSaved = catalog.translate(
                "file.saved",
                "en",
                Map.of("fileName", "שלום.txt"),
                Mf2BidiIsolation.DEFAULT);
        System.out.println("file.saved[en] -> \"" + fileSaved + "\"");
        System.out.println("file.saved[en].escaped -> \"" + escapedNonAscii(fileSaved) + "\"");
        System.out.println("cart.items[en] -> \"" + catalog.translate("cart.items", "en", Map.of("count", 1)) + "\"");
        System.out.println("cart.items[en] -> \"" + catalog.translate("cart.items", "en", Map.of("count", 5)) + "\"");
        System.out.println("cart.items[ru] -> \"" + catalog.translate("cart.items", "ru", Map.of("count", 2)) + "\"");
        System.out.println("cart.items[ru] -> \"" + catalog.translate("cart.items", "ru", Map.of("count", 5)) + "\"");
        System.out.println("assignee.files[en] -> \""
                + catalog.translate("assignee.files", "en", Map.of("gender", "male", "count", 1)) + "\"");
        System.out.println("assignee.files[en] -> \""
                + catalog.translate("assignee.files", "en", Map.of("gender", "female", "count", 3)) + "\"");
        System.out.println("assignee.files[en] -> \""
                + catalog.translate("assignee.files", "en", Map.of("gender", "unknown", "count", 2)) + "\"");
    }

    private record Catalog(Map<String, Object> messages, Mf2FunctionRegistry functions) {
        static Catalog load(Path path, Mf2FunctionRegistry functions) throws Exception {
            return new Catalog(object(object(JsonParser.parse(path)).get("messages")), functions);
        }

        String translate(String id, String locale, Map<String, ?> arguments) throws Mf2Exception {
            return translate(id, locale, arguments, Mf2BidiIsolation.NONE);
        }

        String translate(
                String id, String locale, Map<String, ?> arguments, Mf2BidiIsolation bidiIsolation)
                throws Mf2Exception {
            Map<String, Object> localized = object(messages.get(id));
            Object model = LocaleKey.lookup(localized, locale);
            return Mf2Message.fromJson(model).format(arguments, locale, functions, bidiIsolation);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static String escapedNonAscii(String value) {
        StringBuilder output = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint >= 0x20 && codePoint <= 0x7e) {
                output.appendCodePoint(codePoint);
            } else {
                output.append(String.format("\\u%04X", codePoint));
            }
            offset += Character.charCount(codePoint);
        }
        return output.toString();
    }
}
