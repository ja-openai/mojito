// Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.
package com.box.l10n.mojito.mf2;

import java.util.Locale;
import java.util.Map;

final class CldrPluralRules {
    private CldrPluralRules() {}

    static String selectCardinal(String locale, NumberOperands operands) {
        return switch (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {
            case "r0" -> selectCardinalR0(operands);
            case "r1" -> selectCardinalR1(operands);
            case "r2" -> selectCardinalR2(operands);
            case "r3" -> selectCardinalR3(operands);
            case "r4" -> selectCardinalR4(operands);
            case "r5" -> selectCardinalR5(operands);
            case "r6" -> selectCardinalR6(operands);
            case "r7" -> selectCardinalR7(operands);
            case "r8" -> selectCardinalR8(operands);
            case "r9" -> selectCardinalR9(operands);
            case "r10" -> selectCardinalR10(operands);
            case "r11" -> selectCardinalR11(operands);
            case "r12" -> selectCardinalR12(operands);
            case "r13" -> selectCardinalR13(operands);
            case "r14" -> selectCardinalR14(operands);
            case "r15" -> selectCardinalR15(operands);
            case "r16" -> selectCardinalR16(operands);
            case "r17" -> selectCardinalR17(operands);
            case "r18" -> selectCardinalR18(operands);
            case "r19" -> selectCardinalR19(operands);
            case "r20" -> selectCardinalR20(operands);
            case "r21" -> selectCardinalR21(operands);
            case "r22" -> selectCardinalR22(operands);
            case "r23" -> selectCardinalR23(operands);
            case "r24" -> selectCardinalR24(operands);
            case "r25" -> selectCardinalR25(operands);
            case "r26" -> selectCardinalR26(operands);
            case "r27" -> selectCardinalR27(operands);
            case "r28" -> selectCardinalR28(operands);
            case "r29" -> selectCardinalR29(operands);
            case "r30" -> selectCardinalR30(operands);
            case "r31" -> selectCardinalR31(operands);
            case "r32" -> selectCardinalR32(operands);
            case "r33" -> selectCardinalR33(operands);
            case "r34" -> selectCardinalR34(operands);
            case "r35" -> selectCardinalR35(operands);
            case "r36" -> selectCardinalR36(operands);
            case "r37" -> selectCardinalR37(operands);
            case "r38" -> selectCardinalR38(operands);
            case "r39" -> selectCardinalR39(operands);
            default -> "other";
        };
    }

    static String selectOrdinal(String locale, NumberOperands operands) {
        return switch (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {
            case "r0" -> selectOrdinalR0(operands);
            case "r1" -> selectOrdinalR1(operands);
            case "r2" -> selectOrdinalR2(operands);
            case "r3" -> selectOrdinalR3(operands);
            case "r4" -> selectOrdinalR4(operands);
            case "r5" -> selectOrdinalR5(operands);
            case "r6" -> selectOrdinalR6(operands);
            case "r7" -> selectOrdinalR7(operands);
            case "r8" -> selectOrdinalR8(operands);
            case "r9" -> selectOrdinalR9(operands);
            case "r10" -> selectOrdinalR10(operands);
            case "r11" -> selectOrdinalR11(operands);
            case "r12" -> selectOrdinalR12(operands);
            case "r13" -> selectOrdinalR13(operands);
            case "r14" -> selectOrdinalR14(operands);
            case "r15" -> selectOrdinalR15(operands);
            case "r16" -> selectOrdinalR16(operands);
            case "r17" -> selectOrdinalR17(operands);
            case "r18" -> selectOrdinalR18(operands);
            case "r19" -> selectOrdinalR19(operands);
            case "r20" -> selectOrdinalR20(operands);
            case "r21" -> selectOrdinalR21(operands);
            case "r22" -> selectOrdinalR22(operands);
            case "r23" -> selectOrdinalR23(operands);
            case "r24" -> selectOrdinalR24(operands);
            default -> "other";
        };
    }

    private static final Map<String, String> CARDINAL_LOCALES = Map.ofEntries(
            Map.entry("af", "r0"),
            Map.entry("ak", "r1"),
            Map.entry("am", "r2"),
            Map.entry("an", "r0"),
            Map.entry("ar", "r3"),
            Map.entry("ars", "r3"),
            Map.entry("as", "r2"),
            Map.entry("asa", "r0"),
            Map.entry("ast", "r4"),
            Map.entry("az", "r0"),
            Map.entry("bal", "r0"),
            Map.entry("be", "r5"),
            Map.entry("bem", "r0"),
            Map.entry("bez", "r0"),
            Map.entry("bg", "r0"),
            Map.entry("bho", "r1"),
            Map.entry("blo", "r6"),
            Map.entry("bm", "r7"),
            Map.entry("bn", "r2"),
            Map.entry("bo", "r7"),
            Map.entry("br", "r8"),
            Map.entry("brx", "r0"),
            Map.entry("bs", "r9"),
            Map.entry("ca", "r10"),
            Map.entry("ce", "r0"),
            Map.entry("ceb", "r11"),
            Map.entry("cgg", "r0"),
            Map.entry("chr", "r0"),
            Map.entry("ckb", "r0"),
            Map.entry("cs", "r12"),
            Map.entry("csw", "r1"),
            Map.entry("cv", "r6"),
            Map.entry("cy", "r13"),
            Map.entry("da", "r14"),
            Map.entry("de", "r4"),
            Map.entry("doi", "r2"),
            Map.entry("dsb", "r15"),
            Map.entry("dv", "r0"),
            Map.entry("dz", "r7"),
            Map.entry("ee", "r0"),
            Map.entry("el", "r0"),
            Map.entry("en", "r4"),
            Map.entry("eo", "r0"),
            Map.entry("es", "r16"),
            Map.entry("et", "r4"),
            Map.entry("eu", "r0"),
            Map.entry("fa", "r2"),
            Map.entry("ff", "r17"),
            Map.entry("fi", "r4"),
            Map.entry("fil", "r11"),
            Map.entry("fo", "r0"),
            Map.entry("fr", "r18"),
            Map.entry("fur", "r0"),
            Map.entry("fy", "r4"),
            Map.entry("ga", "r19"),
            Map.entry("gd", "r20"),
            Map.entry("gl", "r4"),
            Map.entry("gsw", "r0"),
            Map.entry("gu", "r2"),
            Map.entry("guw", "r1"),
            Map.entry("gv", "r21"),
            Map.entry("ha", "r0"),
            Map.entry("haw", "r0"),
            Map.entry("he", "r22"),
            Map.entry("hi", "r2"),
            Map.entry("hnj", "r7"),
            Map.entry("hr", "r9"),
            Map.entry("hsb", "r15"),
            Map.entry("hu", "r0"),
            Map.entry("hy", "r17"),
            Map.entry("ia", "r4"),
            Map.entry("id", "r7"),
            Map.entry("ie", "r4"),
            Map.entry("ig", "r7"),
            Map.entry("ii", "r7"),
            Map.entry("io", "r4"),
            Map.entry("is", "r23"),
            Map.entry("it", "r10"),
            Map.entry("iu", "r24"),
            Map.entry("ja", "r7"),
            Map.entry("jbo", "r7"),
            Map.entry("jgo", "r0"),
            Map.entry("jmc", "r0"),
            Map.entry("jv", "r7"),
            Map.entry("jw", "r7"),
            Map.entry("ka", "r0"),
            Map.entry("kab", "r17"),
            Map.entry("kaj", "r0"),
            Map.entry("kcg", "r0"),
            Map.entry("kde", "r7"),
            Map.entry("kea", "r7"),
            Map.entry("kk", "r0"),
            Map.entry("kkj", "r0"),
            Map.entry("kl", "r0"),
            Map.entry("km", "r7"),
            Map.entry("kn", "r2"),
            Map.entry("ko", "r7"),
            Map.entry("kok", "r2"),
            Map.entry("kok-Latn", "r2"),
            Map.entry("ks", "r0"),
            Map.entry("ksb", "r0"),
            Map.entry("ksh", "r6"),
            Map.entry("ku", "r0"),
            Map.entry("kw", "r25"),
            Map.entry("ky", "r0"),
            Map.entry("lag", "r26"),
            Map.entry("lb", "r0"),
            Map.entry("lg", "r0"),
            Map.entry("lij", "r4"),
            Map.entry("lkt", "r7"),
            Map.entry("lld", "r10"),
            Map.entry("ln", "r1"),
            Map.entry("lo", "r7"),
            Map.entry("lt", "r27"),
            Map.entry("lv", "r28"),
            Map.entry("mas", "r0"),
            Map.entry("mg", "r1"),
            Map.entry("mgo", "r0"),
            Map.entry("mk", "r29"),
            Map.entry("ml", "r0"),
            Map.entry("mn", "r0"),
            Map.entry("mo", "r30"),
            Map.entry("mr", "r0"),
            Map.entry("ms", "r7"),
            Map.entry("mt", "r31"),
            Map.entry("my", "r7"),
            Map.entry("nah", "r0"),
            Map.entry("naq", "r24"),
            Map.entry("nb", "r0"),
            Map.entry("nd", "r0"),
            Map.entry("ne", "r0"),
            Map.entry("nl", "r4"),
            Map.entry("nn", "r0"),
            Map.entry("nnh", "r0"),
            Map.entry("no", "r0"),
            Map.entry("nqo", "r7"),
            Map.entry("nr", "r0"),
            Map.entry("nso", "r1"),
            Map.entry("ny", "r0"),
            Map.entry("nyn", "r0"),
            Map.entry("om", "r0"),
            Map.entry("or", "r0"),
            Map.entry("os", "r0"),
            Map.entry("osa", "r7"),
            Map.entry("pa", "r1"),
            Map.entry("pap", "r0"),
            Map.entry("pcm", "r2"),
            Map.entry("pl", "r32"),
            Map.entry("prg", "r28"),
            Map.entry("ps", "r0"),
            Map.entry("pt", "r33"),
            Map.entry("pt-PT", "r10"),
            Map.entry("rm", "r0"),
            Map.entry("ro", "r30"),
            Map.entry("rof", "r0"),
            Map.entry("ru", "r34"),
            Map.entry("rwk", "r0"),
            Map.entry("sah", "r7"),
            Map.entry("saq", "r0"),
            Map.entry("sat", "r24"),
            Map.entry("sc", "r4"),
            Map.entry("scn", "r10"),
            Map.entry("sd", "r0"),
            Map.entry("sdh", "r0"),
            Map.entry("se", "r24"),
            Map.entry("seh", "r0"),
            Map.entry("ses", "r7"),
            Map.entry("sg", "r7"),
            Map.entry("sgs", "r35"),
            Map.entry("sh", "r9"),
            Map.entry("shi", "r36"),
            Map.entry("si", "r37"),
            Map.entry("sk", "r12"),
            Map.entry("sl", "r38"),
            Map.entry("sma", "r24"),
            Map.entry("smi", "r24"),
            Map.entry("smj", "r24"),
            Map.entry("smn", "r24"),
            Map.entry("sms", "r24"),
            Map.entry("sn", "r0"),
            Map.entry("so", "r0"),
            Map.entry("sq", "r0"),
            Map.entry("sr", "r9"),
            Map.entry("ss", "r0"),
            Map.entry("ssy", "r0"),
            Map.entry("st", "r0"),
            Map.entry("su", "r7"),
            Map.entry("sv", "r4"),
            Map.entry("sw", "r4"),
            Map.entry("syr", "r0"),
            Map.entry("ta", "r0"),
            Map.entry("te", "r0"),
            Map.entry("teo", "r0"),
            Map.entry("th", "r7"),
            Map.entry("ti", "r1"),
            Map.entry("tig", "r0"),
            Map.entry("tk", "r0"),
            Map.entry("tl", "r11"),
            Map.entry("tn", "r0"),
            Map.entry("to", "r7"),
            Map.entry("tpi", "r7"),
            Map.entry("tr", "r0"),
            Map.entry("ts", "r0"),
            Map.entry("tzm", "r39"),
            Map.entry("ug", "r0"),
            Map.entry("uk", "r34"),
            Map.entry("und", "r7"),
            Map.entry("ur", "r4"),
            Map.entry("uz", "r0"),
            Map.entry("ve", "r0"),
            Map.entry("vec", "r10"),
            Map.entry("vi", "r7"),
            Map.entry("vo", "r0"),
            Map.entry("vun", "r0"),
            Map.entry("wa", "r1"),
            Map.entry("wae", "r0"),
            Map.entry("wo", "r7"),
            Map.entry("xh", "r0"),
            Map.entry("xog", "r0"),
            Map.entry("yi", "r4"),
            Map.entry("yo", "r7"),
            Map.entry("yue", "r7"),
            Map.entry("zh", "r7"),
            Map.entry("zu", "r2")
    );

    private static final Map<String, String> ORDINAL_LOCALES = Map.ofEntries(
            Map.entry("af", "r0"),
            Map.entry("am", "r0"),
            Map.entry("an", "r0"),
            Map.entry("ar", "r0"),
            Map.entry("as", "r1"),
            Map.entry("ast", "r0"),
            Map.entry("az", "r2"),
            Map.entry("bal", "r3"),
            Map.entry("be", "r4"),
            Map.entry("bg", "r0"),
            Map.entry("blo", "r5"),
            Map.entry("bn", "r1"),
            Map.entry("bs", "r0"),
            Map.entry("ca", "r6"),
            Map.entry("ce", "r0"),
            Map.entry("cs", "r0"),
            Map.entry("cv", "r0"),
            Map.entry("cy", "r7"),
            Map.entry("da", "r0"),
            Map.entry("de", "r0"),
            Map.entry("dsb", "r0"),
            Map.entry("el", "r0"),
            Map.entry("en", "r8"),
            Map.entry("es", "r0"),
            Map.entry("et", "r0"),
            Map.entry("eu", "r0"),
            Map.entry("fa", "r0"),
            Map.entry("fi", "r0"),
            Map.entry("fil", "r3"),
            Map.entry("fr", "r3"),
            Map.entry("fy", "r0"),
            Map.entry("ga", "r3"),
            Map.entry("gd", "r9"),
            Map.entry("gl", "r0"),
            Map.entry("gsw", "r0"),
            Map.entry("gu", "r10"),
            Map.entry("he", "r0"),
            Map.entry("hi", "r10"),
            Map.entry("hr", "r0"),
            Map.entry("hsb", "r0"),
            Map.entry("hu", "r11"),
            Map.entry("hy", "r3"),
            Map.entry("ia", "r0"),
            Map.entry("id", "r0"),
            Map.entry("ie", "r0"),
            Map.entry("is", "r0"),
            Map.entry("it", "r12"),
            Map.entry("ja", "r0"),
            Map.entry("ka", "r13"),
            Map.entry("kk", "r14"),
            Map.entry("km", "r0"),
            Map.entry("kn", "r0"),
            Map.entry("ko", "r0"),
            Map.entry("kok", "r15"),
            Map.entry("kok-Latn", "r15"),
            Map.entry("kw", "r16"),
            Map.entry("ky", "r0"),
            Map.entry("lij", "r17"),
            Map.entry("lld", "r12"),
            Map.entry("lo", "r3"),
            Map.entry("lt", "r0"),
            Map.entry("lv", "r0"),
            Map.entry("mk", "r18"),
            Map.entry("ml", "r0"),
            Map.entry("mn", "r0"),
            Map.entry("mo", "r3"),
            Map.entry("mr", "r15"),
            Map.entry("ms", "r3"),
            Map.entry("my", "r0"),
            Map.entry("nb", "r0"),
            Map.entry("ne", "r19"),
            Map.entry("nl", "r0"),
            Map.entry("no", "r0"),
            Map.entry("or", "r20"),
            Map.entry("pa", "r0"),
            Map.entry("pl", "r0"),
            Map.entry("prg", "r0"),
            Map.entry("ps", "r0"),
            Map.entry("pt", "r0"),
            Map.entry("ro", "r3"),
            Map.entry("ru", "r0"),
            Map.entry("sc", "r12"),
            Map.entry("scn", "r17"),
            Map.entry("sd", "r0"),
            Map.entry("sh", "r0"),
            Map.entry("si", "r0"),
            Map.entry("sk", "r0"),
            Map.entry("sl", "r0"),
            Map.entry("sq", "r21"),
            Map.entry("sr", "r0"),
            Map.entry("sv", "r22"),
            Map.entry("sw", "r0"),
            Map.entry("ta", "r0"),
            Map.entry("te", "r0"),
            Map.entry("th", "r0"),
            Map.entry("tk", "r23"),
            Map.entry("tl", "r3"),
            Map.entry("tpi", "r0"),
            Map.entry("tr", "r0"),
            Map.entry("uk", "r24"),
            Map.entry("und", "r0"),
            Map.entry("ur", "r0"),
            Map.entry("uz", "r0"),
            Map.entry("vec", "r12"),
            Map.entry("vi", "r3"),
            Map.entry("yue", "r0"),
            Map.entry("zh", "r0"),
            Map.entry("zu", "r0")
    );

    private static final Map<String, String> CARDINAL_PARENTS = Map.of();

    private static final Map<String, String> ORDINAL_PARENTS = Map.of();

    private static String lookupRuleId(
            Map<String, String> locales, Map<String, String> parents, String locale) {
        for (String candidate : LocaleKey.pluralLookupChain(locale, parents)) {
            String rule = locales.get(candidate);
            if (rule != null) {
                return rule;
            }
        }
        return null;
    }

    private static boolean isInteger(double value) {
        return Math.rint(value) == value;
    }

    static final class NumberOperands {
        private static final int MAX_OPERAND_LENGTH = 256;

        private final double n;
        private final long i;
        private final long v;
        private final long w;
        private final long f;
        private final long t;
        private final long e;
        private final long c;

        private NumberOperands(double n, long i, long v, long w, long f, long t) {
            this.n = n;
            this.i = i;
            this.v = v;
            this.w = w;
            this.f = f;
            this.t = t;
            this.e = 0;
            this.c = 0;
        }

        static NumberOperands fromString(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.length() > MAX_OPERAND_LENGTH) {
                return null;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(trimmed);
            } catch (NumberFormatException error) {
                return null;
            }
            if (!Double.isFinite(parsed)) {
                return null;
            }
            double n = Math.abs(parsed);
            String normalized = trimmed;
            while (normalized.startsWith("-") || normalized.startsWith("+")) {
                normalized = normalized.substring(1);
            }
            normalized = normalized.toLowerCase(Locale.ROOT);
            int exponentIndex = normalized.indexOf('e');
            String base = exponentIndex >= 0 ? normalized.substring(0, exponentIndex) : normalized;
            int dotIndex = base.indexOf('.');
            String fraction = dotIndex >= 0 ? base.substring(dotIndex + 1) : "";
            String trimmedFraction = trimTrailingZeros(fraction);
            Long f = parseLong(fraction);
            Long t = parseLong(trimmedFraction);
            if (f == null || t == null) {
                return null;
            }
            return new NumberOperands(
                    n, (long) n, fraction.length(), trimmedFraction.length(), f, t);
        }

        private static Long parseLong(String value) {
            if (value.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException error) {
                return null;
            }
        }

        private static String trimTrailingZeros(String value) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '0') {
                end--;
            }
            return value.substring(0, end);
        }

        long operandI64(String name) {
            return switch (name) {
                case "i" -> i;
                case "v" -> v;
                case "w" -> w;
                case "f" -> f;
                case "t" -> t;
                case "e" -> e;
                case "c" -> c;
                case "n" -> (long) n;
                default -> 0;
            };
        }

        double operandDouble(String name) {
            return name.equals("n") ? n : operandI64(name);
        }
    }

    private static String selectCardinalR0(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        return "other";
    }

    private static String selectCardinalR1(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        return "other";
    }

    private static String selectCardinalR2(NumberOperands operands) {
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) { return "one"; }
        return "other";
    }

    private static String selectCardinalR3(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) { return "zero"; }
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if (isInteger((operands.operandDouble("n") % 100.0)) && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 10.0) { return "few"; }
        if (isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 99.0) { return "many"; }
        return "other";
    }

    private static String selectCardinalR4(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "one"; }
        return "other";
    }

    private static String selectCardinalR5(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) { return "one"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) { return "few"; }
        if ((isInteger((operands.operandDouble("n") % 10.0)) && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 5.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR6(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) { return "zero"; }
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        return "other";
    }

    private static String selectCardinalR7(NumberOperands ignored) {
        return "other";
    }

    private static String selectCardinalR8(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(((isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 71.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 71.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 91.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 91.0)))) { return "one"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0 && !(((isInteger((operands.operandDouble("n") % 100.0)) && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 72.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 72.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 92.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 92.0)))) { return "two"; }
        if (((isInteger((operands.operandDouble("n") % 10.0)) && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0)) && !(((isInteger((operands.operandDouble("n") % 100.0)) && 10.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 70.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 79.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 90.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 99.0)))) { return "few"; }
        if (!(isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) && isInteger((operands.operandDouble("n") % 1000000.0)) && 0.0 <= (operands.operandDouble("n") % 1000000.0) && (operands.operandDouble("n") % 1000000.0) <= 0.0) { return "many"; }
        return "other";
    }

    private static String selectCardinalR9(NumberOperands operands) {
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1 && !(11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 11)) || (1 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 1 && !(11 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 11))) { return "one"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 4 && !(12 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 14)) || (2 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 4 && !(12 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 14))) { return "few"; }
        return "other";
    }

    private static String selectCardinalR10(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "one"; }
        if ((0 <= operands.operandI64("e") && operands.operandI64("e") <= 0 && !(0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) && 0 <= (operands.operandI64("i") % 1000000) && (operands.operandI64("i") % 1000000) <= 0 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) || (!(0 <= operands.operandI64("e") && operands.operandI64("e") <= 5))) { return "many"; }
        return "other";
    }

    private static String selectCardinalR11(NumberOperands operands) {
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && ((1 <= operands.operandI64("i") && operands.operandI64("i") <= 1) || (2 <= operands.operandI64("i") && operands.operandI64("i") <= 2) || (3 <= operands.operandI64("i") && operands.operandI64("i") <= 3))) || (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && !(((4 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 4) || (6 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 6) || (9 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 9)))) || (!(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) && !(((4 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 4) || (6 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 6) || (9 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 9))))) { return "one"; }
        return "other";
    }

    private static String selectCardinalR12(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "one"; }
        if (2 <= operands.operandI64("i") && operands.operandI64("i") <= 4 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "few"; }
        if (!(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR13(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) { return "zero"; }
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) { return "few"; }
        if (isInteger(operands.operandDouble("n")) && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) { return "many"; }
        return "other";
    }

    private static String selectCardinalR14(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (!(0 <= operands.operandI64("t") && operands.operandI64("t") <= 0) && ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1)))) { return "one"; }
        return "other";
    }

    private static String selectCardinalR15(NumberOperands operands) {
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 1) || (1 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 1)) { return "one"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 2) || (2 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 2)) { return "two"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 3 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 4) || (3 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 4)) { return "few"; }
        return "other";
    }

    private static String selectCardinalR16(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if ((0 <= operands.operandI64("e") && operands.operandI64("e") <= 0 && !(0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) && 0 <= (operands.operandI64("i") % 1000000) && (operands.operandI64("i") % 1000000) <= 0 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) || (!(0 <= operands.operandI64("e") && operands.operandI64("e") <= 5))) { return "many"; }
        return "other";
    }

    private static String selectCardinalR17(NumberOperands operands) {
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1)) { return "one"; }
        return "other";
    }

    private static String selectCardinalR18(NumberOperands operands) {
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1)) { return "one"; }
        if ((0 <= operands.operandI64("e") && operands.operandI64("e") <= 0 && !(0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) && 0 <= (operands.operandI64("i") % 1000000) && (operands.operandI64("i") % 1000000) <= 0 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) || (!(0 <= operands.operandI64("e") && operands.operandI64("e") <= 5))) { return "many"; }
        return "other";
    }

    private static String selectCardinalR19(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) { return "few"; }
        if (isInteger(operands.operandDouble("n")) && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) { return "many"; }
        return "other";
    }

    private static String selectCardinalR20(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0)) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0)) { return "two"; }
        if ((isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) || (isInteger(operands.operandDouble("n")) && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 19.0)) { return "few"; }
        return "other";
    }

    private static String selectCardinalR21(NumberOperands operands) {
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1) { return "one"; }
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 2) { return "two"; }
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && ((0 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 0) || (20 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 20) || (40 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 40) || (60 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 60) || (80 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 80))) { return "few"; }
        if (!(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR22(NumberOperands operands) {
        if ((1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) || (0 <= operands.operandI64("i") && operands.operandI64("i") <= 0 && !(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0))) { return "one"; }
        if (2 <= operands.operandI64("i") && operands.operandI64("i") <= 2 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "two"; }
        return "other";
    }

    private static String selectCardinalR23(NumberOperands operands) {
        if ((0 <= operands.operandI64("t") && operands.operandI64("t") <= 0 && 1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1 && !(11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 11)) || (1 <= (operands.operandI64("t") % 10) && (operands.operandI64("t") % 10) <= 1 && !(11 <= (operands.operandI64("t") % 100) && (operands.operandI64("t") % 100) <= 11))) { return "one"; }
        return "other";
    }

    private static String selectCardinalR24(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        return "other";
    }

    private static String selectCardinalR25(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) { return "zero"; }
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (((isInteger((operands.operandDouble("n") % 100.0)) && 2.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 2.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 22.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 22.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 42.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 42.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 62.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 62.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 82.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 82.0)) || (isInteger((operands.operandDouble("n") % 1000.0)) && 0.0 <= (operands.operandDouble("n") % 1000.0) && (operands.operandDouble("n") % 1000.0) <= 0.0 && ((isInteger((operands.operandDouble("n") % 100000.0)) && 1000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 20000.0) || (isInteger((operands.operandDouble("n") % 100000.0)) && 40000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 40000.0) || (isInteger((operands.operandDouble("n") % 100000.0)) && 60000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 60000.0) || (isInteger((operands.operandDouble("n") % 100000.0)) && 80000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 80000.0))) || (!(isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) && isInteger((operands.operandDouble("n") % 1000000.0)) && 100000.0 <= (operands.operandDouble("n") % 1000000.0) && (operands.operandDouble("n") % 1000000.0) <= 100000.0)) { return "two"; }
        if ((isInteger((operands.operandDouble("n") % 100.0)) && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 3.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 23.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 23.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 43.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 43.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 63.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 63.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 83.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 83.0)) { return "few"; }
        if (!(isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) && ((isInteger((operands.operandDouble("n") % 100.0)) && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 1.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 21.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 21.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 41.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 41.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 61.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 61.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 81.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 81.0))) { return "many"; }
        return "other";
    }

    private static String selectCardinalR26(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) { return "zero"; }
        if (((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1)) && !(isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0)) { return "one"; }
        return "other";
    }

    private static String selectCardinalR27(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) { return "one"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) { return "few"; }
        if (!(0 <= operands.operandI64("f") && operands.operandI64("f") <= 0)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR28(NumberOperands operands) {
        if ((isInteger((operands.operandDouble("n") % 10.0)) && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) || (2 <= operands.operandI64("v") && operands.operandI64("v") <= 2 && 11 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 19)) { return "zero"; }
        if ((isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) || (2 <= operands.operandI64("v") && operands.operandI64("v") <= 2 && 1 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 1 && !(11 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 11)) || (!(2 <= operands.operandI64("v") && operands.operandI64("v") <= 2) && 1 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 1)) { return "one"; }
        return "other";
    }

    private static String selectCardinalR29(NumberOperands operands) {
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1 && !(11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 11)) || (1 <= (operands.operandI64("f") % 10) && (operands.operandI64("f") % 10) <= 1 && !(11 <= (operands.operandI64("f") % 100) && (operands.operandI64("f") % 100) <= 11))) { return "one"; }
        return "other";
    }

    private static String selectCardinalR30(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "one"; }
        if ((!(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0)) || (isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (!(isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) && isInteger((operands.operandDouble("n") % 100.0)) && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) { return "few"; }
        return "other";
    }

    private static String selectCardinalR31(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if ((isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 10.0)) { return "few"; }
        if (isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) { return "many"; }
        return "other";
    }

    private static String selectCardinalR32(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) { return "one"; }
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 4 && !(12 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 14)) { return "few"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && !(1 <= operands.operandI64("i") && operands.operandI64("i") <= 1) && 0 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1) || (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 5 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 9) || (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 12 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 14)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR33(NumberOperands operands) {
        if (0 <= operands.operandI64("i") && operands.operandI64("i") <= 1) { return "one"; }
        if ((0 <= operands.operandI64("e") && operands.operandI64("e") <= 0 && !(0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) && 0 <= (operands.operandI64("i") % 1000000) && (operands.operandI64("i") % 1000000) <= 0 && 0 <= operands.operandI64("v") && operands.operandI64("v") <= 0) || (!(0 <= operands.operandI64("e") && operands.operandI64("e") <= 5))) { return "many"; }
        return "other";
    }

    private static String selectCardinalR34(NumberOperands operands) {
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1 && !(11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 11)) { return "one"; }
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 4 && !(12 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 14)) { return "few"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 0 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 0) || (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 5 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 9) || (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 14)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR35(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if (!(isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) && isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) { return "few"; }
        if (!(0 <= operands.operandI64("f") && operands.operandI64("f") <= 0)) { return "many"; }
        return "other";
    }

    private static String selectCardinalR36(NumberOperands operands) {
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) { return "few"; }
        return "other";
    }

    private static String selectCardinalR37(NumberOperands operands) {
        if (((isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) || (0 <= operands.operandI64("i") && operands.operandI64("i") <= 0 && 1 <= operands.operandI64("f") && operands.operandI64("f") <= 1)) { return "one"; }
        return "other";
    }

    private static String selectCardinalR38(NumberOperands operands) {
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 1 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 1) { return "one"; }
        if (0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 2 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 2) { return "two"; }
        if ((0 <= operands.operandI64("v") && operands.operandI64("v") <= 0 && 3 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 4) || (!(0 <= operands.operandI64("v") && operands.operandI64("v") <= 0))) { return "few"; }
        return "other";
    }

    private static String selectCardinalR39(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 99.0)) { return "one"; }
        return "other";
    }

    private static String selectOrdinalR0(NumberOperands ignored) {
        return "other";
    }

    private static String selectOrdinalR1(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (isInteger(operands.operandDouble("n")) && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (isInteger(operands.operandDouble("n")) && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (isInteger(operands.operandDouble("n")) && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0) || (isInteger(operands.operandDouble("n")) && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0)) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "few"; }
        if (isInteger(operands.operandDouble("n")) && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR2(NumberOperands operands) {
        if (((1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1) || (2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 2) || (5 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 5) || (7 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 7) || (8 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 8)) || ((20 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 20) || (50 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 50) || (70 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 70) || (80 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 80))) { return "one"; }
        if (((3 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 3) || (4 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 4)) || ((100 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 100) || (200 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 200) || (300 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 300) || (400 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 400) || (500 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 500) || (600 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 600) || (700 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 700) || (800 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 800) || (900 <= (operands.operandI64("i") % 1000) && (operands.operandI64("i") % 1000) <= 900))) { return "few"; }
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || (6 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 6) || ((40 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 40) || (60 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 60) || (90 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 90))) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR3(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        return "other";
    }

    private static String selectOrdinalR4(NumberOperands operands) {
        if (((isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0)) && !(((isInteger((operands.operandDouble("n") % 100.0)) && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)))) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR5(NumberOperands operands) {
        if (0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) { return "zero"; }
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1) { return "one"; }
        if ((2 <= operands.operandI64("i") && operands.operandI64("i") <= 2) || (3 <= operands.operandI64("i") && operands.operandI64("i") <= 3) || (4 <= operands.operandI64("i") && operands.operandI64("i") <= 4) || (5 <= operands.operandI64("i") && operands.operandI64("i") <= 5) || (6 <= operands.operandI64("i") && operands.operandI64("i") <= 6)) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR6(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR7(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (isInteger(operands.operandDouble("n")) && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (isInteger(operands.operandDouble("n")) && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (isInteger(operands.operandDouble("n")) && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0)) { return "zero"; }
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) { return "two"; }
        if ((isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0)) { return "few"; }
        if ((isInteger(operands.operandDouble("n")) && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (isInteger(operands.operandDouble("n")) && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0)) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR8(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) { return "one"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0)) { return "two"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR9(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0)) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0)) { return "two"; }
        if ((isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (isInteger(operands.operandDouble("n")) && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 13.0)) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR10(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "few"; }
        if (isInteger(operands.operandDouble("n")) && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR11(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0)) { return "one"; }
        return "other";
    }

    private static String selectOrdinalR12(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (isInteger(operands.operandDouble("n")) && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (isInteger(operands.operandDouble("n")) && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 80.0) || (isInteger(operands.operandDouble("n")) && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 800.0)) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR13(NumberOperands operands) {
        if (1 <= operands.operandI64("i") && operands.operandI64("i") <= 1) { return "one"; }
        if ((0 <= operands.operandI64("i") && operands.operandI64("i") <= 0) || ((2 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 20) || (40 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 40) || (60 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 60) || (80 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 80))) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR14(NumberOperands operands) {
        if ((isInteger((operands.operandDouble("n") % 10.0)) && 6.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 6.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0 && !(isInteger(operands.operandDouble("n")) && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR15(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR16(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) || ((isInteger((operands.operandDouble("n") % 100.0)) && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 4.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 21.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 24.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 41.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 44.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 61.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 64.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 81.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 84.0))) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 5.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 5.0)) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR17(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (isInteger(operands.operandDouble("n")) && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (isInteger(operands.operandDouble("n")) && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 89.0) || (isInteger(operands.operandDouble("n")) && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 899.0)) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR18(NumberOperands operands) {
        if (1 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 1 && !(11 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 11)) { return "one"; }
        if (2 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 2 && !(12 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 12)) { return "two"; }
        if (((7 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 7) || (8 <= (operands.operandI64("i") % 10) && (operands.operandI64("i") % 10) <= 8)) && !(((17 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 17) || (18 <= (operands.operandI64("i") % 100) && (operands.operandI64("i") % 100) <= 18)))) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR19(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "one"; }
        return "other";
    }

    private static String selectOrdinalR20(NumberOperands operands) {
        if ((isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (isInteger(operands.operandDouble("n")) && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (isInteger(operands.operandDouble("n")) && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0)) { return "one"; }
        if ((isInteger(operands.operandDouble("n")) && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (isInteger(operands.operandDouble("n")) && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) { return "two"; }
        if (isInteger(operands.operandDouble("n")) && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) { return "few"; }
        if (isInteger(operands.operandDouble("n")) && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR21(NumberOperands operands) {
        if (isInteger(operands.operandDouble("n")) && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) { return "one"; }
        if (isInteger((operands.operandDouble("n") % 10.0)) && 4.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 14.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) { return "many"; }
        return "other";
    }

    private static String selectOrdinalR22(NumberOperands operands) {
        if (((isInteger((operands.operandDouble("n") % 10.0)) && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0)) && !(((isInteger((operands.operandDouble("n") % 100.0)) && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0) || (isInteger((operands.operandDouble("n") % 100.0)) && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0)))) { return "one"; }
        return "other";
    }

    private static String selectOrdinalR23(NumberOperands operands) {
        if (((isInteger((operands.operandDouble("n") % 10.0)) && 6.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 6.0) || (isInteger((operands.operandDouble("n") % 10.0)) && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0)) || (isInteger(operands.operandDouble("n")) && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0)) { return "few"; }
        return "other";
    }

    private static String selectOrdinalR24(NumberOperands operands) {
        if (isInteger((operands.operandDouble("n") % 10.0)) && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0 && !(isInteger((operands.operandDouble("n") % 100.0)) && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)) { return "few"; }
        return "other";
    }

}
