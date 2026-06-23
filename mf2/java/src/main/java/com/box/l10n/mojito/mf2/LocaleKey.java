package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LocaleKey {
    private static final int MAX_LOCALE_OPTION_LENGTH = 256;

    private LocaleKey() {}

    static String option(String locale, String fallback) throws Mf2Exception {
        String value = locale == null || locale.isEmpty() ? fallback : locale;
        if (value.length() > MAX_LOCALE_OPTION_LENGTH) {
            throw Mf2FunctionSupport.badOption("locale must not exceed 256 characters.");
        }
        return value;
    }

    static String canonicalKey(String locale) {
        if (locale != null && locale.length() > MAX_LOCALE_OPTION_LENGTH) {
            return "";
        }
        return String.join("-", parts(locale));
    }

    static List<String> lookupChain(String locale) {
        return structuralLookupChain(canonicalKey(locale));
    }

    static List<String> pluralLookupChain(String locale, Map<String, String> parents) {
        return featureLookupChain(locale, parents);
    }

    static List<String> featureLookupChain(String locale, Map<String, String> parents) {
        List<String> chain = new ArrayList<>();
        appendFeatureLookupChain(canonicalKey(locale), parents, chain);
        return chain;
    }

    private static void appendFeatureLookupChain(
            String locale, Map<String, String> parents, List<String> chain) {
        String current = locale;
        while (!current.isEmpty()) {
            if (current.length() > MAX_LOCALE_OPTION_LENGTH) {
                return;
            }
            if (chain.contains(current)) {
                return;
            }
            chain.add(current);
            String parent = parents.get(current);
            if (parent != null) {
                appendFeatureLookupChain(parent, parents, chain);
            }
            current = structuralParent(current);
        }
    }

    private static List<String> structuralLookupChain(String locale) {
        List<String> parts = List.of(locale.split("-")).stream()
                .filter(part -> !part.isEmpty())
                .toList();
        List<String> chain = new ArrayList<>(parts.size());
        for (int length = parts.size(); length > 0; length--) {
            chain.add(String.join("-", parts.subList(0, length)));
        }
        return chain;
    }

    static <T> T lookup(Map<String, T> values, String locale) {
        return lookup(values, locale, "en");
    }

    static <T> T lookup(Map<String, T> values, String locale, String fallback) {
        Map<String, T> canonicalValues = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            canonicalValues.put(canonicalKey(entry.getKey()), entry.getValue());
        }
        for (String candidate : lookupChain(locale)) {
            T value = canonicalValues.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return canonicalValues.get(canonicalKey(fallback));
    }

    private static List<String> parts(String locale) {
        String normalized = locale == null ? "" : locale.trim().replace('_', '-');
        List<String> parts = new ArrayList<>();
        int index = 0;
        for (String part : normalized.split("-")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() == 1) {
                break;
            }
            parts.add(canonicalSubtag(index, part));
            index++;
        }
        return parts;
    }

    private static String canonicalSubtag(int index, String part) {
        if (index == 0) {
            return part.toLowerCase(Locale.ROOT);
        }
        if (part.length() == 4 && part.chars().allMatch(LocaleKey::isAsciiAlpha)) {
            return part.substring(0, 1).toUpperCase(Locale.ROOT)
                    + part.substring(1).toLowerCase(Locale.ROOT);
        }
        if ((part.length() == 2 && part.chars().allMatch(LocaleKey::isAsciiAlpha))
                || (part.length() == 3 && part.chars().allMatch(LocaleKey::isAsciiDigit))) {
            return part.toUpperCase(Locale.ROOT);
        }
        return part.toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiAlpha(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isAsciiDigit(int ch) {
        return ch >= '0' && ch <= '9';
    }

    private static String structuralParent(String locale) {
        int index = locale.lastIndexOf('-');
        return index < 0 ? "" : locale.substring(0, index);
    }
}
