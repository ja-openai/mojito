package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.Map;

public final class TranslateDemo {
    private TranslateDemo() {}

    public static void main(String[] args) throws Exception {
        Catalog catalog = Catalog.load(Path.of("../examples/catalog.json"));
        System.out.println("welcome[fr] -> \"" + catalog.translate("welcome", "fr", Map.of("name", "Mojito")) + "\"");
        System.out.println("welcome[fr-CA] -> \"" + catalog.translate("welcome", "fr-CA", Map.of("name", "Mojito")) + "\"");
        System.out.println("cart.items[en] -> \"" + catalog.translate("cart.items", "en", Map.of("count", 1)) + "\"");
        System.out.println("cart.items[en] -> \"" + catalog.translate("cart.items", "en", Map.of("count", 5)) + "\"");
        System.out.println("cart.items[ru] -> \"" + catalog.translate("cart.items", "ru", Map.of("count", 2)) + "\"");
        System.out.println("cart.items[ru] -> \"" + catalog.translate("cart.items", "ru", Map.of("count", 5)) + "\"");
    }

    private record Catalog(Map<String, Object> messages) {
        static Catalog load(Path path) throws Exception {
            return new Catalog(object(object(JsonParser.parse(path)).get("messages")));
        }

        String translate(String id, String locale, Map<String, ?> arguments) throws Mf2Exception {
            Map<String, Object> localized = object(messages.get(id));
            String current = locale;
            while (!current.isEmpty()) {
                Object model = localized.get(current);
                if (model != null) {
                    return Mf2Message.fromJson(model).format(arguments, current);
                }
                current = parent(current);
            }
            return Mf2Message.fromJson(localized.get("en")).format(arguments, "en");
        }

        private static String parent(String locale) {
            int index = locale.lastIndexOf('-');
            return index < 0 ? "" : locale.substring(0, index);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
