package com.box.l10n.mojito.mf2.reference.icu4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.message2.MessageFormatter;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.DisplayContext;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.text.RelativeDateTimeFormatter;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedCharacterIterator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class Icu4jReference {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String SEMANTIC_SKELETON_PREFIX = "semantic:";
  private static final List<String> SEMANTIC_DATE_FIELD_ORDER =
      List.of(
          "era",
          "year",
          "quarter",
          "month",
          "weekofmonth",
          "day",
          "dayofyear",
          "dayofweekinmonth",
          "modifiedjulianday",
          "weekday",
          "weekofyear");
  private static final List<String> SEMANTIC_TIME_FIELD_ORDER =
      List.of("hour", "minute", "second", "fractionalsecond", "millisecondsinday");
  private static final List<String> SEMANTIC_STYLE_OPTION_KEYS =
      List.of(
          "yearstyle",
          "erastyle",
          "monthstyle",
          "quarterstyle",
          "daystyle",
          "weekdaystyle",
          "dayperiodstyle",
          "hourstyle",
          "minutestyle",
          "secondstyle");
  private static final List<String> SEMANTIC_DIRECT_STYLE_OPTION_KEYS =
      List.of("fields", "length", "timestyle");
  private static final Set<DateFormat.Field> HOUR_FORMAT_FIELDS =
      Set.of(
          DateFormat.Field.HOUR0,
          DateFormat.Field.HOUR1,
          DateFormat.Field.HOUR_OF_DAY0,
          DateFormat.Field.HOUR_OF_DAY1);
  private static final Set<DateFormat.Field> DAY_OF_WEEK_FORMAT_FIELDS =
      Set.of(DateFormat.Field.DAY_OF_WEEK);
  private static final Set<DateFormat.Field> TIME_ZONE_FORMAT_FIELDS =
      Set.of(DateFormat.Field.TIME_ZONE);
  // Mirrors generated CLDR timeData.allowed and short time formats for probe locales.
  private static final Map<String, String> CLDR_ALLOWED_HOUR_FORMATS =
      Map.of(
          "ar-EG", "h hB hb H",
          "de-DE", "H hB",
          "en-US", "h hb H hB",
          "fr-FR", "H hB",
          "ja-JP", "H K h");
  private static final Map<String, String> CLDR_SHORT_TIME_FORMATS =
      Map.of(
          "ar-EG", "h:mm a",
          "de-DE", "HH:mm",
          "en-US", "h:mm\u202fa",
          "fr-FR", "HH:mm",
          "ja-JP", "H:mm");
  // Mirrors generated CLDR dateTimeFormats for the checked-in probe locales.
  private static final Map<String, Map<String, String>> CLDR_DATE_TIME_FORMATS =
      Map.of(
          "ar-EG",
              Map.of(
                  "full", "{1}\u060c {0}",
                  "long", "{1}\u060c {0}",
                  "medium", "{1}\u060c {0}",
                  "short", "{1}\u060c {0}"),
          "de-DE", Map.of("full", "{1}, {0}", "long", "{1}, {0}", "medium", "{1}, {0}", "short", "{1}, {0}"),
          "en-US", Map.of("full", "{1}, {0}", "long", "{1}, {0}", "medium", "{1}, {0}", "short", "{1}, {0}"),
          "fr-FR", Map.of("full", "{1}, {0}", "long", "{1}, {0}", "medium", "{1}, {0}", "short", "{1} {0}"),
          "ja-JP", Map.of("full", "{1} {0}", "long", "{1} {0}", "medium", "{1} {0}", "short", "{1} {0}"));
  private static final Map<String, Map<String, String>> CLDR_DATE_TIME_STYLE_JOIN_FORMATS =
      Map.of(
          "ar-EG", Map.of("full", "{1} '\u0641\u064a' {0}", "long", "{1} '\u0641\u064a' {0}"),
          "de-DE", Map.of("full", "{1} 'um' {0}", "long", "{1} 'um' {0}"),
          "en-US", Map.of("full", "{1} 'at' {0}", "long", "{1} 'at' {0}"),
          "fr-FR", Map.of("full", "{1} '\u00e0' {0}", "long", "{1} '\u00e0' {0}"));
  private static final Map<String, WeekMetadata> CLDR_WEEK_METADATA =
      Map.of(
          "ar-EG", new WeekMetadata(6, 1),
          "de-DE", new WeekMetadata(1, 4),
          "en-US", new WeekMetadata(0, 1),
          "fr-FR", new WeekMetadata(1, 4),
          "ja-JP", new WeekMetadata(0, 1));
  private static final Map<String, String> CLDR_WEEK_YEAR_PATTERNS =
      Map.of(
          "ar-EG",
          "\u0627\u0644\u0623\u0633\u0628\u0648\u0639 w \u0645\u0646 \u0633\u0646\u0629 Y",
          "de-DE",
          "'Woche' w 'des' 'Jahres' Y",
          "en-US",
          "'week' w 'of' Y",
          "fr-FR",
          "'semaine' w 'de' Y",
          "ja-JP",
          "Y\u5e74\u7b2cw\u9031");
  private static final Map<String, String> CLDR_WEEK_OF_MONTH_PATTERNS =
      Map.of(
          "ar-EG",
          "\u0627\u0644\u0623\u0633\u0628\u0648\u0639 W \u0645\u0646 MMMM",
          "de-DE",
          "'Woche' W 'im' MMMM",
          "en-US",
          "'week' W 'of' MMMM",
          "fr-FR",
          "'semaine' W (MMMM)",
          "ja-JP",
          "M\u6708\u7b2cW\u9031");
  private static final Map<String, String> CLDR_YEAR_MONTH_WEEK_OF_MONTH_PATTERNS =
      Map.of(
          "en-US", "'week' W 'of' MMMM y",
          "fr-FR", "MMM y G ('semaine': W)");
  private static final Map<String, Map<String, String>> CLDR_EXACT_DAY_PERIOD_NAMES =
      Map.of(
          "en-US", Map.of("midnight", "midnight", "noon", "noon", "am", "AM", "pm", "PM"),
          "fr-FR", Map.of("midnight", "minuit", "noon", "midi", "am", "AM", "pm", "PM"),
          "ja-JP", Map.of("midnight", "\u771f\u591c\u4e2d", "noon", "\u6b63\u5348", "am", "\u5348\u524d", "pm", "\u5348\u5f8c"));
  private static final Map<String, Map<String, String>> CLDR_EXACT_DAY_PERIOD_TIME_PATTERNS =
      Map.of(
          "en-US", Map.of("Bh", "h B", "Bhm", "h:mm B", "Bhms", "h:mm:ss B"),
          "fr-FR", Map.of("Bh", "h B", "Bhm", "h:mm B", "Bhms", "h:mm:ss B"),
          "ja-JP", Map.of("Bh", "BK\u6642", "Bhm", "BK:mm", "Bhms", "BK:mm:ss"));
  private static final Map<String, String> CLDR_ZERO_TIME_ZONE_LABELS =
      Map.of(
          "ar-EG", "\u063a\u0631\u064a\u0646\u062a\u0634",
          "de-DE", "GMT",
          "en-US", "GMT",
          "fr-FR", "UTC",
          "ja-JP", "GMT");
  private static final Map<String, String> CLDR_ZERO_DIGITS =
      Map.of("ar-EG", "\u0660");
  private static final Map<String, Map<Integer, String>> CLDR_SHORT_LOCAL_WEEKDAY_LABELS =
      Map.of(
          "de-DE",
              Map.of(
                  Calendar.SUNDAY, "So.",
                  Calendar.MONDAY, "Mo.",
                  Calendar.TUESDAY, "Di.",
                  Calendar.WEDNESDAY, "Mi.",
                  Calendar.THURSDAY, "Do.",
                  Calendar.FRIDAY, "Fr.",
                  Calendar.SATURDAY, "Sa."));
  private static volatile int sink;

  public static void main(String[] args) throws Exception {
    String command = args.length > 0 ? args[0] : "compare";
    Path fixtureDirectory =
        Path.of(args.length > 1 ? args[1] : "../../conformance/fixtures/source-to-model");

    switch (command) {
      case "compare" -> compare(fixtureDirectory);
      case "plural-categories" -> pluralCategories(fixtureDirectory);
      case "relative-time" ->
          relativeTime(
              Path.of(
                  args.length > 1
                      ? args[1]
                      : "../../conformance/fixtures/functions/relative-time-duration-v0.json"));
      case "datetime-skeletons" ->
          dateTimeSkeletons(
              Path.of(
                  args.length > 1
                      ? args[1]
                      : "../../conformance/fixtures/date-time-core/cases.json"),
              false);
      case "datetime-skeletons-strict" ->
          dateTimeSkeletons(
              Path.of(
                  args.length > 1
                      ? args[1]
                      : "../../conformance/fixtures/date-time-core/cases.json"),
              true);
      case "datetime-styles" ->
          dateTimeStyles(
              Path.of(
                  args.length > 1
                      ? args[1]
                      : "../../conformance/fixtures/date-time-core/cases.json"),
              false);
      case "datetime-styles-strict" ->
          dateTimeStyles(
              Path.of(
                  args.length > 1
                      ? args[1]
                      : "../../conformance/fixtures/date-time-core/cases.json"),
              true);
      case "bench" -> {
        int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 100_000;
        int warmupIterations = args.length > 3 ? Integer.parseInt(args[3]) : 10_000;
        bench(fixtureDirectory, iterations, warmupIterations);
      }
      default -> {
        System.err.println(
            "Usage: Icu4jReference [compare|bench|plural-categories|relative-time|datetime-skeletons|datetime-skeletons-strict|datetime-styles|datetime-styles-strict] [fixture-dir-or-json] [iterations] [warmup-iterations]");
        System.exit(2);
      }
    }
  }

  private static void dateTimeStyles(Path fixturePath, boolean failOnUnsupported)
      throws IOException {
    JsonNode fixture = OBJECT_MAPPER.readTree(fixturePath.toFile());
    int total = 0;
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (JsonNode formatCase : fixture.path("formatCases")) {
      JsonNode options = formatCase.path("options");
      if (options.has("skeleton")
          || (!options.has("dateStyle") && !options.has("timeStyle"))) {
        continue;
      }
      total++;

      String name = formatCase.path("name").asText("date-time style");
      String unsupportedReason = dateTimeStyleUnsupportedReason(formatCase, options);
      if (unsupportedReason != null) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", name, unsupportedReason);
        continue;
      }

      try {
        String actual = formatIcu4jStyle(formatCase, options);
        String expected = formatCase.path("expected").asText();
        if (expected.equals(actual)) {
          passed++;
        } else {
          failed++;
          System.out.printf(
              "MISMATCH %s:%n  expected: %s%n  actual:   %s%n",
              name, expected, actual);
        }
      } catch (RuntimeException e) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", name, e.getMessage());
      }
    }

    System.out.printf(
        "icu4j datetime-styles total=%d passed=%d failed=%d unsupported=%d%n",
        total, passed, failed, unsupported);
    if (failed > 0 || (failOnUnsupported && unsupported > 0)) {
      System.exit(1);
    }
  }

  private static String dateTimeStyleUnsupportedReason(JsonNode formatCase, JsonNode options) {
    String kind = formatCase.path("kind").asText();
    if ("date".equals(kind) && !options.has("dateStyle")) {
      return "date style reference requires dateStyle";
    }
    if ("time".equals(kind) && !options.has("timeStyle")) {
      return "time style reference requires timeStyle";
    }
    if ("datetime".equals(kind) && (!options.has("dateStyle") || !options.has("timeStyle"))) {
      return "datetime style reference requires dateStyle and timeStyle";
    }
    String calendar = options.path("calendar").asText(null);
    if (calendar != null && !("gregory".equals(calendar) || "gregorian".equals(calendar))) {
      return "only Gregorian calendar style references are supported";
    }
    return null;
  }

  private static String formatIcu4jStyle(JsonNode formatCase, JsonNode options) {
    String kind = formatCase.path("kind").asText();
    ULocale uLocale = styleLocale(formatCase, options);
    Date date = Date.from(Instant.parse(formatCase.path("value").asText()));
    DateFormat formatter =
        switch (kind) {
          case "date" -> DateFormat.getDateInstance(
              dateFormatStyle(options.path("dateStyle").asText()), uLocale);
          case "time" -> DateFormat.getTimeInstance(
              dateFormatStyle(options.path("timeStyle").asText()), uLocale);
          case "datetime" -> DateFormat.getDateTimeInstance(
              dateFormatStyle(options.path("dateStyle").asText()),
              dateFormatStyle(options.path("timeStyle").asText()),
              uLocale);
          default -> throw new IllegalArgumentException("Unsupported date/time style kind: " + kind);
        };
    TimeZone timeZone = icuTimeZone(options.path("timeZone").asText("UTC"));
    formatter.setTimeZone(timeZone);
    String hourCycle = options.path("hourCycle").asText(null);
    if (hourCycle != null && !"date".equals(kind) && formatter instanceof SimpleDateFormat simpleDateFormat) {
      String pattern = explicitHourCycleStylePattern(simpleDateFormat.toPattern(), uLocale, hourCycle);
      if (pattern != null) {
        SimpleDateFormat adjusted = new SimpleDateFormat(pattern, uLocale);
        adjusted.setTimeZone(timeZone);
        return adjustHourCycleRangeBoundary(
            adjusted.format(date), adjusted, date, timeZone, uLocale, pattern);
      }
    }
    return formatter.format(date);
  }

  private static String explicitHourCycleStylePattern(
      String pattern, ULocale locale, String hourCycle) {
    char hourSymbol = preferredHourSymbol(locale, hourCycle);
    Character patternHourSymbol = firstPatternHourSymbol(pattern);
    if (patternHourSymbol == null) {
      return null;
    }
    if (!isHour12Field(hourSymbol)) {
      return stripDayPeriodPatternFields(replacePatternHourSymbol(pattern, hourSymbol));
    }
    if (isHour12Field(patternHourSymbol)) {
      return replacePatternHourSymbol(pattern, hourSymbol);
    }
    if (!hasDatePatternFields(pattern)) {
      String skeleton = timeStylePatternSkeleton(pattern, locale, hourCycle);
      if (skeleton != null) {
        return applyPatternFieldWidthOverrides(
            DateTimePatternGenerator.getInstance(locale).getBestPattern(skeleton),
            Map.of(hourSymbol, firstPatternHourWidth(pattern)));
      }
    }
    return replacePatternHourSymbol(pattern, hourSymbol);
  }

  private static Character firstPatternHourSymbol(String pattern) {
    for (FieldRun run : patternFieldRuns(pattern)) {
      if (isHourField(run.symbol())) {
        return run.symbol();
      }
    }
    return null;
  }

  private static int firstPatternHourWidth(String pattern) {
    for (FieldRun run : patternFieldRuns(pattern)) {
      if (isHourField(run.symbol())) {
        return run.width();
      }
    }
    return 1;
  }

  private static boolean hasDatePatternFields(String pattern) {
    for (FieldRun run : patternFieldRuns(pattern)) {
      if ("GyYuUrQqMLlwWdDFEc".indexOf(run.symbol()) >= 0) {
        return true;
      }
    }
    return false;
  }

  private static String timeStylePatternSkeleton(
      String pattern, ULocale locale, String hourCycle) {
    Map<Character, Integer> widths = new LinkedHashMap<>();
    char hourSymbol = preferredHourSymbol(locale, hourCycle);
    boolean hasHour = false;
    for (FieldRun run : patternFieldRuns(pattern)) {
      char symbol = run.symbol();
      if (isHourField(symbol)) {
        widths.merge(hourSymbol, run.width(), Math::max);
        hasHour = true;
      } else if (!isDayPeriodField(symbol) && "msSzZOvVXx".indexOf(symbol) >= 0) {
        widths.merge(symbol, run.width(), Math::max);
      }
    }
    if (!hasHour) {
      return null;
    }
    StringBuilder skeleton = new StringBuilder();
    for (int index = 0; index < "hHkKmsSzZOvVXx".length(); index++) {
      char symbol = "hHkKmsSzZOvVXx".charAt(index);
      Integer width = widths.get(symbol);
      if (width != null) {
        skeleton.append(Character.toString(symbol).repeat(width));
      }
    }
    return skeleton.toString();
  }

  private static boolean isHourField(char symbol) {
    return symbol == 'h' || symbol == 'H' || symbol == 'k' || symbol == 'K';
  }

  private static ULocale styleLocale(JsonNode formatCase, JsonNode options) {
    String locale = formatCase.path("locale").asText("en").replace('_', '-');
    String calendar = options.path("calendar").asText(null);
    if (calendar == null) {
      return ULocale.forLanguageTag(locale);
    }
    return new ULocale.Builder()
        .setLanguageTag(locale)
        .setUnicodeLocaleKeyword("ca", "gregory")
        .build();
  }

  private static int dateFormatStyle(String style) {
    return switch (style) {
      case "full" -> DateFormat.FULL;
      case "long" -> DateFormat.LONG;
      case "medium" -> DateFormat.MEDIUM;
      case "short" -> DateFormat.SHORT;
      default -> throw new IllegalArgumentException("Unsupported date/time style: " + style);
    };
  }

  private static void dateTimeSkeletons(Path fixturePath, boolean failOnUnsupported)
      throws IOException {
    JsonNode fixture = OBJECT_MAPPER.readTree(fixturePath.toFile());
    int total = 0;
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (JsonNode formatCase : fixture.path("formatCases")) {
      JsonNode options = formatCase.path("options");
      String skeleton = options.path("skeleton").asText(null);
      if (skeleton == null) {
        continue;
      }
      total++;

      String name = formatCase.path("name").asText("date-time skeleton");
      ReferenceSkeleton referenceSkeleton = referenceSkeleton(formatCase, skeleton, options);
      String unsupportedReason = referenceSkeleton.unsupportedReason();
      if (unsupportedReason != null) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", name, unsupportedReason);
        continue;
      }

      try {
        String actual = formatIcu4jSkeleton(formatCase, referenceSkeleton);
        String expected = formatCase.path("expected").asText();
        if (expected.equals(actual)) {
          passed++;
        } else {
          failed++;
          String referenceLabel = referenceSkeleton.pattern() == null ? "skeleton" : "pattern";
          String referenceValue =
              referenceSkeleton.pattern() == null
                  ? referenceSkeleton.skeleton()
                  : referenceSkeleton.pattern();
          System.out.printf(
              "MISMATCH %s:%n  %s: %s%n  expected: %s%n  actual:   %s%n",
              name, referenceLabel, referenceValue, expected, actual);
        }
      } catch (RuntimeException e) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", name, e.getMessage());
      }
    }

    System.out.printf(
        "icu4j datetime-skeletons total=%d passed=%d failed=%d unsupported=%d%n",
        total, passed, failed, unsupported);
    if (failed > 0 || (failOnUnsupported && unsupported > 0)) {
      System.exit(1);
    }
  }

  private static ReferenceSkeleton referenceSkeleton(
      JsonNode formatCase, String skeleton, JsonNode options) {
    if (skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
      return semanticReferenceSkeleton(
          formatCase, skeleton.substring(SEMANTIC_SKELETON_PREFIX.length()));
    }
    ULocale uLocale =
        ULocale.forLanguageTag(formatCase.path("locale").asText("en").replace('_', '-'));
    String hourCycleOption =
        options.path("hourCycle").asText(localeHourCycle(uLocale));
    String sourceSkeleton = skeleton;
    skeleton = normalizeDirectSkeletonForIcu4j(skeleton);
    HourSkeleton hourSkeleton =
        hourCycleReferenceSkeleton(skeleton, uLocale, hourCycleOption);
    String referenceSkeleton = hourSkeleton.skeleton();
    boolean loweredHourCycleOption = !referenceSkeleton.equals(skeleton);
    String unsupportedReason =
        directIcu4jSkeletonUnsupportedReason(referenceSkeleton, options, loweredHourCycleOption);
    if (referenceSkeleton.indexOf('b') >= 0) {
      String pattern = exactDayPeriodPattern(formatCase, referenceSkeleton, uLocale);
      if (pattern != null) {
        return new ReferenceSkeleton(
            referenceSkeleton, pattern, Map.of(), null, hourSkeleton.suppressDayPeriod());
      }
    }
    if (unsupportedReason == null && isBareMinuteSecondPatternSkeleton(referenceSkeleton)) {
      return new ReferenceSkeleton(
          null, referenceSkeleton, Map.of(), null, hourSkeleton.suppressDayPeriod());
    }
    if (unsupportedReason == null && hasExplicitAmPmTimeSkeleton(referenceSkeleton)) {
      DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(uLocale);
      String pattern = normalizeExplicitAmPmPatternSpacing(generator.getBestPattern(referenceSkeleton));
      return new ReferenceSkeleton(
          referenceSkeleton, pattern, Map.of(), null, hourSkeleton.suppressDayPeriod());
    }
    if (unsupportedReason == null) {
      String dayPeriodPattern =
          explicitTwentyFourHourDayPeriodPattern(referenceSkeleton, uLocale, "medium", null);
      if (dayPeriodPattern != null) {
        return new ReferenceSkeleton(
            referenceSkeleton,
            dayPeriodPattern,
            Map.of(),
            null,
            hourSkeleton.suppressDayPeriod());
      }
    }
    if (unsupportedReason == null && sourceSkeleton.indexOf('l') >= 0) {
      DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(uLocale);
      String pattern =
          standaloneMonthPattern(
              generator.getBestPattern(referenceSkeleton), skeletonFieldWidth(sourceSkeleton, 'l'));
      return new ReferenceSkeleton(
          referenceSkeleton, pattern, Map.of(), null, hourSkeleton.suppressDayPeriod());
    }
    if (unsupportedReason == null
        && hasDateAndTimeSkeletonFields(referenceSkeleton)
        && containsAny(referenceSkeleton, "GQq")) {
      String pattern = composedDateTimePattern(referenceSkeleton, uLocale, "medium", null);
      if (pattern != null) {
        return new ReferenceSkeleton(
            null, pattern, Map.of(), null, hourSkeleton.suppressDayPeriod());
      }
    }
    return new ReferenceSkeleton(
        referenceSkeleton,
        null,
        Map.of(),
        unsupportedReason,
        hourSkeleton.suppressDayPeriod());
  }

  private static String standaloneMonthPattern(String pattern, int width) {
    StringBuilder output = new StringBuilder(pattern.length());
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append("''");
          index += 2;
        } else {
          inQuote = !inQuote;
          output.append(ch);
          index++;
        }
      } else if (!inQuote && (ch == 'M' || ch == 'L')) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        output.append("L".repeat(width));
        index = end;
      } else {
        output.append(ch);
        index++;
      }
    }
    return output.toString();
  }

  private static String formatIcu4jSkeleton(JsonNode formatCase, ReferenceSkeleton referenceSkeleton) {
    String locale = formatCase.path("locale").asText("en");
    ULocale uLocale = ULocale.forLanguageTag(locale.replace('_', '-'));
    TimeZone timeZone = icuTimeZone(formatCase.path("options").path("timeZone").asText("UTC"));
    Date date = Date.from(Instant.parse(formatCase.path("value").asText()));
    String synthetic =
        formatSyntheticNumericSkeleton(referenceSkeleton.skeleton(), uLocale, timeZone, date);
    if (synthetic != null) {
      return synthetic;
    }
    synthetic = formatSyntheticWeekSkeleton(referenceSkeleton.skeleton(), uLocale, timeZone, date);
    if (synthetic != null) {
      return synthetic;
    }
    String pattern = referenceSkeleton.pattern();
    if (pattern == null) {
      DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(uLocale);
      pattern = generator.getBestPattern(referenceSkeleton.skeleton());
    }
    Map<Character, Integer> fieldWidthOverrides =
        skeletonHourFieldWidthOverrides(referenceSkeleton.skeleton());
    fieldWidthOverrides.putAll(referenceSkeleton.fieldWidthOverrides());
    if (!fieldWidthOverrides.isEmpty()) {
      pattern = applyPatternFieldWidthOverrides(pattern, fieldWidthOverrides);
    }
    if (referenceSkeleton.suppressDayPeriod()) {
      pattern = stripDayPeriodPatternFields(pattern);
    }
    SimpleDateFormat formatter = new SimpleDateFormat(pattern, uLocale);
    formatter.setTimeZone(timeZone);
    String formatted = formatter.format(date);
    String zoneAdjusted =
        adjustZeroOffsetTimeZoneLabel(
            formatted,
            formatter,
            date,
            uLocale,
            referenceSkeleton.skeleton(),
            formatCase.path("options").path("timeZone").asText("UTC"));
    String weekdayAdjusted =
        adjustDirectLocalWeekdayLabel(
            zoneAdjusted, formatter, date, timeZone, uLocale, referenceSkeleton.skeleton());
    return adjustHourCycleRangeBoundary(
        weekdayAdjusted, formatter, date, timeZone, uLocale, referenceSkeleton.skeleton());
  }

  private static String normalizeDirectSkeletonForIcu4j(String skeleton) {
    return skeleton.indexOf('l') < 0 ? skeleton : skeleton.replace('l', 'L');
  }

  private static String formatSyntheticNumericSkeleton(
      String skeleton, ULocale locale, TimeZone timeZone, Date date) {
    if (skeleton == null || skeleton.isEmpty()) {
      return null;
    }
    if (skeleton.chars().allMatch(ch -> ch == 'g')) {
      return formatInteger(locale, modifiedJulianDay(timeZone, locale, date), skeleton.length());
    }
    if (skeleton.chars().allMatch(ch -> ch == 'A')) {
      return formatInteger(locale, millisecondsInDay(timeZone, locale, date), skeleton.length());
    }
    return null;
  }

  private static String formatSyntheticWeekSkeleton(
      String skeleton, ULocale locale, TimeZone timeZone, Date date) {
    if (skeleton == null || skeleton.isEmpty()) {
      return null;
    }
    String baseLocale = baseLocaleTag(locale);
    if (skeleton.indexOf('w') >= 0
        && containsAny(skeleton, "yY")
        && removeSkeletonFields(skeleton, "yYw").isEmpty()) {
      String pattern = CLDR_WEEK_YEAR_PATTERNS.get(baseLocale);
      return pattern == null
          ? null
          : formatSyntheticWeekPattern(
              pattern, locale, timeZone, date, skeletonFieldWidth(skeleton, 'w'));
    }
    if (skeleton.indexOf('W') >= 0
        && containsAny(skeleton, "ML")
        && removeSkeletonFields(skeleton, "GyYMLW").isEmpty()) {
      String pattern =
          containsAny(skeleton, "yY")
              ? CLDR_YEAR_MONTH_WEEK_OF_MONTH_PATTERNS.get(baseLocale)
              : CLDR_WEEK_OF_MONTH_PATTERNS.get(baseLocale);
      return pattern == null
          ? null
          : formatSyntheticWeekPattern(
              pattern, locale, timeZone, date, skeletonFieldWidth(skeleton, 'W'));
    }
    return null;
  }

  private static String formatSyntheticWeekPattern(
      String pattern, ULocale locale, TimeZone timeZone, Date date, int weekWidthOverride) {
    WeekMetadata metadata = CLDR_WEEK_METADATA.get(baseLocaleTag(locale));
    if (metadata == null) {
      return null;
    }
    LocalDate localDate = localDate(timeZone, locale, date);
    WeekYearInfo weekYearInfo = weekYearInfo(localDate, metadata);
    StringBuilder output = new StringBuilder(pattern.length());
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append('\'');
          index += 2;
        } else {
          inQuote = !inQuote;
          index++;
        }
      } else if (!inQuote && "GYywWML".indexOf(ch) >= 0) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        int width = end - index;
        output.append(
            syntheticWeekPatternField(
                ch,
                width,
                weekWidthOverride,
                locale,
                timeZone,
                date,
                localDate,
                metadata,
                weekYearInfo));
        index = end;
      } else {
        output.append(ch);
        index++;
      }
    }
    return output.toString();
  }

  private static String syntheticWeekPatternField(
      char symbol,
      int patternWidth,
      int weekWidthOverride,
      ULocale locale,
      TimeZone timeZone,
      Date date,
      LocalDate localDate,
      WeekMetadata metadata,
      WeekYearInfo weekYearInfo) {
    return switch (symbol) {
      case 'G' -> formatEraField(locale, timeZone, date, patternWidth);
      case 'Y' -> formatYear(locale, weekYearInfo.year(), patternWidth);
      case 'y' -> formatYear(locale, localDate.getYear(), patternWidth);
      case 'w' ->
          formatInteger(locale, weekYearInfo.week(), Math.max(patternWidth, weekWidthOverride));
      case 'W' ->
          formatInteger(
              locale, weekOfMonth(localDate, metadata), Math.max(patternWidth, weekWidthOverride));
      case 'M', 'L' -> formatMonthField(locale, timeZone, date, symbol, patternWidth);
      default -> throw new IllegalArgumentException("Unsupported synthetic week field: " + symbol);
    };
  }

  private static String formatYear(ULocale locale, int year, int width) {
    if (width == 2) {
      return formatInteger(locale, Math.floorMod(year, 100), 2);
    }
    return formatInteger(locale, year, 1);
  }

  private static String formatEraField(
      ULocale locale, TimeZone timeZone, Date date, int width) {
    SimpleDateFormat formatter =
        new SimpleDateFormat("G".repeat(width), locale);
    formatter.setTimeZone(timeZone);
    return formatter.format(date);
  }

  private static String formatMonthField(
      ULocale locale, TimeZone timeZone, Date date, char symbol, int width) {
    Calendar calendar = Calendar.getInstance(timeZone, locale);
    calendar.setTime(date);
    if (width <= 2) {
      return formatInteger(locale, calendar.get(Calendar.MONTH) + 1, width);
    }
    SimpleDateFormat formatter =
        new SimpleDateFormat(Character.toString(symbol).repeat(width), locale);
    formatter.setTimeZone(timeZone);
    return formatter.format(date);
  }

  private static int modifiedJulianDay(TimeZone timeZone, ULocale locale, Date date) {
    return Math.toIntExact(localDate(timeZone, locale, date).toEpochDay() + 40_587);
  }

  private static int millisecondsInDay(TimeZone timeZone, ULocale locale, Date date) {
    Calendar calendar = Calendar.getInstance(timeZone, locale);
    calendar.setTime(date);
    return ((calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)) * 60
                + calendar.get(Calendar.SECOND))
            * 1000
        + calendar.get(Calendar.MILLISECOND);
  }

  private static LocalDate localDate(TimeZone timeZone, ULocale locale, Date date) {
    Calendar calendar = Calendar.getInstance(timeZone, locale);
    calendar.setTime(date);
    return LocalDate.of(
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH));
  }

  private static WeekYearInfo weekYearInfo(LocalDate date, WeekMetadata metadata) {
    long epochDay = date.toEpochDay();
    int calendarYear = date.getYear();
    long weekStart = startOfWeek(epochDay, metadata.firstDayOfWeek());
    long currentYearStart = firstWeekStartOfYear(calendarYear, metadata);
    if (weekStart < currentYearStart) {
      long previousYearStart = firstWeekStartOfYear(calendarYear - 1, metadata);
      return new WeekYearInfo(
          calendarYear - 1, Math.toIntExact(Math.floorDiv(weekStart - previousYearStart, 7) + 1));
    }
    long nextYearStart = firstWeekStartOfYear(calendarYear + 1, metadata);
    if (weekStart >= nextYearStart) {
      return new WeekYearInfo(
          calendarYear + 1, Math.toIntExact(Math.floorDiv(weekStart - nextYearStart, 7) + 1));
    }
    return new WeekYearInfo(
        calendarYear, Math.toIntExact(Math.floorDiv(weekStart - currentYearStart, 7) + 1));
  }

  private static int weekOfMonth(LocalDate date, WeekMetadata metadata) {
    long monthStart = LocalDate.of(date.getYear(), date.getMonth(), 1).toEpochDay();
    long firstWeekStart = firstWeekStart(monthStart, metadata);
    long weekStart = startOfWeek(date.toEpochDay(), metadata.firstDayOfWeek());
    return Math.toIntExact(Math.floorDiv(weekStart - firstWeekStart, 7) + 1);
  }

  private static long firstWeekStartOfYear(int year, WeekMetadata metadata) {
    return firstWeekStart(LocalDate.of(year, 1, 1).toEpochDay(), metadata);
  }

  private static long firstWeekStart(long periodStart, WeekMetadata metadata) {
    long weekStart = startOfWeek(periodStart, metadata.firstDayOfWeek());
    long daysInPeriod = weekStart + 7 - periodStart;
    return daysInPeriod >= metadata.minDaysInFirstWeek() ? weekStart : weekStart + 7;
  }

  private static long startOfWeek(long epochDay, int firstDayOfWeek) {
    return epochDay - Math.floorMod(dayOfWeek(epochDay) - firstDayOfWeek, 7);
  }

  private static int dayOfWeek(long epochDay) {
    return Math.floorMod(epochDay + 4, 7);
  }

  private static String adjustZeroOffsetTimeZoneLabel(
      String formatted,
      SimpleDateFormat formatter,
      Date date,
      ULocale locale,
      String skeleton,
      String timeZoneOption) {
    if (skeleton == null
        || !isZeroOffsetTimeZone(timeZoneOption)
        || !needsZeroOffsetTimeZoneCompaction(skeleton)) {
      return formatted;
    }
    String replacement = CLDR_ZERO_TIME_ZONE_LABELS.get(baseLocaleTag(locale));
    if (replacement == null) {
      return formatted;
    }
    AttributedCharacterIterator iterator = formatter.formatToCharacterIterator(date);
    for (char ch = iterator.first();
        ch != AttributedCharacterIterator.DONE;
        ch = iterator.setIndex(iterator.getRunLimit())) {
      int start = iterator.getRunStart();
      int end = iterator.getRunLimit();
      if (iterator.getAttributes().keySet().stream().anyMatch(TIME_ZONE_FORMAT_FIELDS::contains)) {
        return formatted.substring(0, start) + replacement + formatted.substring(end);
      }
    }
    return formatted;
  }

  private static boolean needsZeroOffsetTimeZoneCompaction(String skeleton) {
    return skeleton.indexOf('O') >= 0
        || skeleton.indexOf('v') >= 0
        || skeleton.indexOf('V') >= 0
        || containsRepeatedFieldWidth(skeleton, 'Z', 4);
  }

  private static String adjustDirectLocalWeekdayLabel(
      String formatted,
      SimpleDateFormat formatter,
      Date date,
      TimeZone timeZone,
      ULocale locale,
      String skeleton) {
    if (skeleton == null || !containsAny(skeleton, "ce")) {
      return formatted;
    }
    if (containsFieldWidth(skeleton, 'e', 2) || containsFieldWidth(skeleton, 'c', 2)) {
      return padFirstAttributedField(
          formatted, formatter, date, DAY_OF_WEEK_FORMAT_FIELDS, localizedZeroDigit(locale), 2);
    }
    if (containsFieldWidth(skeleton, 'e', 3)) {
      String replacement = cldrShortLocalWeekdayLabel(locale, timeZone, date);
      if (replacement != null) {
        return replaceFirstAttributedField(
            formatted, formatter, date, DAY_OF_WEEK_FORMAT_FIELDS, replacement);
      }
    }
    return formatted;
  }

  private static String padFirstAttributedField(
      String formatted,
      SimpleDateFormat formatter,
      Date date,
      Set<DateFormat.Field> fields,
      String pad,
      int width) {
    AttributedCharacterIterator iterator = formatter.formatToCharacterIterator(date);
    for (char ch = iterator.first();
        ch != AttributedCharacterIterator.DONE;
        ch = iterator.setIndex(iterator.getRunLimit())) {
      int start = iterator.getRunStart();
      int end = iterator.getRunLimit();
      if (iterator.getAttributes().keySet().stream().anyMatch(fields::contains)) {
        String value = formatted.substring(start, end);
        int missing = width - value.codePointCount(0, value.length());
        return missing <= 0
            ? formatted
            : formatted.substring(0, start) + pad.repeat(missing) + value + formatted.substring(end);
      }
    }
    return formatted;
  }

  private static String replaceFirstAttributedField(
      String formatted,
      SimpleDateFormat formatter,
      Date date,
      Set<DateFormat.Field> fields,
      String replacement) {
    AttributedCharacterIterator iterator = formatter.formatToCharacterIterator(date);
    for (char ch = iterator.first();
        ch != AttributedCharacterIterator.DONE;
        ch = iterator.setIndex(iterator.getRunLimit())) {
      int start = iterator.getRunStart();
      int end = iterator.getRunLimit();
      if (iterator.getAttributes().keySet().stream().anyMatch(fields::contains)) {
        return formatted.substring(0, start) + replacement + formatted.substring(end);
      }
    }
    return formatted;
  }

  private static String localizedZeroDigit(ULocale locale) {
    return CLDR_ZERO_DIGITS.getOrDefault(baseLocaleTag(locale), "0");
  }

  private static String cldrShortLocalWeekdayLabel(
      ULocale locale, TimeZone timeZone, Date date) {
    Map<Integer, String> labels = CLDR_SHORT_LOCAL_WEEKDAY_LABELS.get(baseLocaleTag(locale));
    if (labels == null) {
      return null;
    }
    Calendar calendar = Calendar.getInstance(locale);
    calendar.setTimeZone(timeZone);
    calendar.setTime(date);
    return labels.get(calendar.get(Calendar.DAY_OF_WEEK));
  }

  private static String adjustHourCycleRangeBoundary(
      String formatted,
      SimpleDateFormat formatter,
      Date date,
      TimeZone timeZone,
      ULocale locale,
      String skeleton) {
    if (skeleton == null || (!skeleton.contains("K") && !skeleton.contains("k"))) {
      return formatted;
    }
    Calendar calendar = Calendar.getInstance(timeZone, locale);
    calendar.setTime(date);
    int hour = calendar.get(Calendar.HOUR_OF_DAY);
    char symbol = skeleton.contains("K") ? 'K' : 'k';
    int replacementValue = switch (symbol) {
      case 'K' -> hour % 12 == 0 ? 0 : -1;
      case 'k' -> hour == 0 ? 24 : -1;
      default -> -1;
    };
    if (replacementValue < 0) {
      return formatted;
    }
    String replacement = formatInteger(locale, replacementValue, skeletonFieldWidth(skeleton, symbol));
    AttributedCharacterIterator iterator = formatter.formatToCharacterIterator(date);
    for (char ch = iterator.first();
        ch != AttributedCharacterIterator.DONE;
        ch = iterator.setIndex(iterator.getRunLimit())) {
      int start = iterator.getRunStart();
      int end = iterator.getRunLimit();
      if (iterator.getAttributes().keySet().stream().anyMatch(HOUR_FORMAT_FIELDS::contains)) {
        return formatted.substring(0, start) + replacement + formatted.substring(end);
      }
    }
    return formatted;
  }

  private static String formatInteger(ULocale locale, int value, int minimumDigits) {
    NumberFormat numberFormat = NumberFormat.getIntegerInstance(locale);
    numberFormat.setGroupingUsed(false);
    numberFormat.setMinimumIntegerDigits(minimumDigits);
    numberFormat.setMaximumFractionDigits(0);
    return numberFormat.format(value);
  }

  private static int skeletonFieldWidth(String skeleton, char symbol) {
    int width = 1;
    for (int index = 0; index < skeleton.length(); ) {
      char current = skeleton.charAt(index);
      int end = index + 1;
      while (end < skeleton.length() && skeleton.charAt(end) == current) {
        end++;
      }
      if (current == symbol) {
        width = Math.max(width, end - index);
      }
      index = end;
    }
    return width;
  }

  private static Map<Character, Integer> skeletonHourFieldWidthOverrides(String skeleton) {
    Map<Character, Integer> overrides = new LinkedHashMap<>();
    if (skeleton == null) {
      return overrides;
    }
    for (char symbol : new char[] {'h', 'H', 'k', 'K'}) {
      int width = skeletonFieldWidth(skeleton, symbol);
      if (width > 1) {
        overrides.put(symbol, width);
      }
    }
    return overrides;
  }

  private static TimeZone icuTimeZone(String value) {
    if ("UTC".equals(value) || "Z".equals(value)) {
      return TimeZone.getTimeZone("UTC");
    }
    if (value.matches("[+-]\\d{2}:\\d{2}")) {
      return TimeZone.getTimeZone("GMT" + value);
    }
    return TimeZone.getTimeZone(value);
  }

  private static String applyPatternFieldWidthOverrides(
      String pattern, Map<Character, Integer> fieldWidthOverrides) {
    StringBuilder output = new StringBuilder(pattern.length());
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append("''");
          index += 2;
        } else {
          inQuote = !inQuote;
          output.append(ch);
          index++;
        }
      } else if (!inQuote && fieldWidthOverrides.containsKey(ch)) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        output.append(Character.toString(ch).repeat(fieldWidthOverrides.get(ch)));
        index = end;
      } else {
        output.append(ch);
        index++;
      }
    }
    return output.toString();
  }

  private static String stripDayPeriodPatternFields(String pattern) {
    StringBuilder output = new StringBuilder(pattern.length());
    StringBuilder pendingWhitespace = new StringBuilder();
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append(pendingWhitespace).append("''");
          pendingWhitespace.setLength(0);
          index += 2;
        } else {
          inQuote = !inQuote;
          output.append(pendingWhitespace).append(ch);
          pendingWhitespace.setLength(0);
          index++;
        }
      } else if (!inQuote && isAsciiLetter(ch)) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        if (isDayPeriodField(ch)) {
          pendingWhitespace.setLength(0);
        } else {
          output.append(pendingWhitespace).append(pattern, index, end);
          pendingWhitespace.setLength(0);
        }
        index = end;
      } else if (!inQuote && isPatternWhitespace(ch)) {
        pendingWhitespace.append(ch);
        index++;
      } else {
        output.append(pendingWhitespace).append(ch);
        pendingWhitespace.setLength(0);
        index++;
      }
    }
    output.append(pendingWhitespace);
    return trimPatternWhitespace(output.toString());
  }

  private static String trimPatternWhitespace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isPatternWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && isPatternWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  private static boolean isPatternWhitespace(char value) {
    return value == ' ' || value == '\u00a0' || value == '\u202f' || Character.isWhitespace(value);
  }

  private static boolean isDayPeriodField(char value) {
    return value == 'a' || value == 'b' || value == 'B';
  }

  private static boolean isAsciiLetter(char value) {
    return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
  }

  private static ReferenceSkeleton semanticReferenceSkeleton(JsonNode formatCase, String body) {
    Map<String, String> options = parseSemanticSkeletonOptions(body);
    Set<String> fields = parseSemanticSkeletonFields(options);
    String timeKey = semanticFieldSetKey(fields, SEMANTIC_TIME_FIELD_ORDER);
    boolean hasExplicitTime = !timeKey.isEmpty();
    boolean hasTimeField = fields.contains("time");
    boolean hasTime = hasTimeField || hasExplicitTime;
    boolean hasZone = fields.contains("zone");
    String dateKey = semanticFieldSetKey(fields, SEMANTIC_DATE_FIELD_ORDER);

    String locale = formatCase.path("locale").asText("en");
    ULocale uLocale = ULocale.forLanguageTag(locale.replace('_', '-'));
    String length = semanticOption(options, "length", "medium");
    String timeStyle = semanticOption(options, "timestyle", "auto");
    ReferenceSkeleton directStyleReference =
        semanticDirectStyleReferenceSkeleton(
            uLocale, options, fields, dateKey, timeKey, length, timeStyle, hasTimeField, hasZone);
    if (directStyleReference != null) {
      return directStyleReference;
    }
    String alignment = semanticOption(options, "alignment", "inline");
    String yearStyle = semanticOption(options, "yearstyle", "auto");
    String eraStyle = semanticOption(options, "erastyle", "auto");
    String monthStyle = semanticOption(options, "monthstyle", "auto");
    String quarterStyle = semanticOption(options, "quarterstyle", "auto");
    String dayStyle = semanticOption(options, "daystyle", "auto");
    String weekdayStyle = semanticOption(options, "weekdaystyle", "auto");
    String dayPeriodStyle = semanticOption(options, "dayperiodstyle", "auto");
    String timePrecision = semanticOption(options, "timeprecision", "second");
    String effectiveTimePrecision = semanticTimeStylePrecision(timeStyle, timePrecision);
    String hourCycle =
        semanticOption(
            options,
            "hourcycle",
            semanticFormatOption(
                formatCase.path("options"),
                "hourCycle",
                "hourcycle",
                localeHourCycle(uLocale) == null ? "auto" : localeHourCycle(uLocale)));
    String zoneStyle = semanticOption(options, "zonestyle", "auto");
    String effectiveZoneStyle = semanticTimeStyleZoneStyle(timeStyle, zoneStyle);
    boolean effectiveZoneStandalone = fields.size() == 1 || "full".equals(timeStyle);
    String effectiveZoneLength = Set.of("long", "full").contains(timeStyle) ? timeStyle : length;
    String zoneSkeleton =
        hasZone ? semanticZoneSkeleton(effectiveZoneStyle, effectiveZoneStandalone, effectiveZoneLength) : "";
    String timezoneUnsupportedReason = semanticTimezoneUnsupportedReason(zoneSkeleton);
    if (timezoneUnsupportedReason != null) {
      return unsupportedSemanticSkeleton(timezoneUnsupportedReason);
    }
    Date date = Date.from(Instant.parse(formatCase.path("value").asText()));
    Map<Character, Integer> dateWidths = semanticDateFieldWidths(uLocale, length);
    StringBuilder standard = new StringBuilder();

    String explicitTimeWithoutHourUnsupportedReason =
        semanticExplicitTimeWithoutHourUnsupportedReason(fields, alignment, options);
    if (explicitTimeWithoutHourUnsupportedReason != null) {
      return unsupportedSemanticSkeleton(explicitTimeWithoutHourUnsupportedReason);
    }

    if (fields.contains("era")) {
      standard.append(semanticEraSkeleton(dateWidths, length, eraStyle));
    }
    if (fields.contains("year") && !fields.contains("weekofyear")) {
      standard.append(semanticYearSkeleton(dateWidths, yearStyle, !fields.contains("era")));
    }
    if (fields.contains("quarter")) {
      standard.append(semanticQuarterSkeleton(fields, length, alignment, quarterStyle));
    }
    if (fields.contains("month")) {
      standard.append(semanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle));
    }
    if (fields.contains("day")) {
      standard.append(semanticDaySkeleton(dateWidths, alignment, dayStyle));
    }
    if (fields.contains("dayofyear")) {
      standard.append("D".repeat("column".equals(alignment) ? 3 : 1));
    }
    if (fields.contains("dayofweekinmonth")) {
      standard.append("F".repeat("column".equals(alignment) ? 2 : 1));
    }
    if (fields.contains("modifiedjulianday")) {
      standard.append("g".repeat("column".equals(alignment) ? 6 : 1));
    }
    if (fields.contains("weekday")) {
      standard.append(semanticWeekdaySkeleton(fields, length, weekdayStyle));
    }
    if (fields.contains("weekofyear")) {
      standard.append('Y');
      standard.append("w".repeat("column".equals(alignment) ? 2 : 1));
    }
    if (fields.contains("weekofmonth")) {
      standard.append('W');
    }
    if (fields.contains("dayperiod")) {
      standard.append(semanticDayPeriodSkeleton(length, dayPeriodStyle));
    }
    if (hasExplicitTime) {
      String explicitTimeSkeleton = semanticExplicitTimeSkeleton(fields, hourCycle, alignment, options);
      if (explicitTimeSkeleton == null) {
        return unsupportedSemanticSkeleton("semantic fractional seconds require an explicit width");
      }
      standard.append(explicitTimeSkeleton);
    }
    if (hasTimeField) {
      String timeSkeleton = semanticTimeSkeleton(effectiveTimePrecision, hourCycle, alignment, date, options);
      if (timeSkeleton == null) {
        return unsupportedSemanticSkeleton("semantic fractional seconds require an explicit width");
      }
      standard.append(timeSkeleton);
    }
    if (hasZone) {
      standard.append(zoneSkeleton);
    }

    Map<Character, Integer> fieldWidthOverrides = semanticFieldWidthOverrides(options);
    HourSkeleton hourSkeleton = hourCycleReferenceSkeleton(standard.toString(), uLocale, hourCycle);
    String referenceStandard = hourSkeleton.skeleton();
    if (isBareMinuteSecondPatternSkeleton(referenceStandard)) {
      return new ReferenceSkeleton(
          null, referenceStandard, fieldWidthOverrides, null, hourSkeleton.suppressDayPeriod());
    }
    if (hasTime && fields.contains("dayperiod")) {
      String dayPeriodPattern =
          explicitTwentyFourHourDayPeriodPattern(referenceStandard, uLocale, length, dateKey);
      if (dayPeriodPattern != null) {
        return new ReferenceSkeleton(
            referenceStandard,
            dayPeriodPattern,
            fieldWidthOverrides,
            null,
            hourSkeleton.suppressDayPeriod());
      }
    }
    if (hasTime && fields.contains("weekday")) {
      String composedPattern =
          composedDateTimePattern(referenceStandard, uLocale, length, dateKey);
      if (composedPattern != null) {
        return new ReferenceSkeleton(
            null, composedPattern, fieldWidthOverrides, null, hourSkeleton.suppressDayPeriod());
      }
    }
    return new ReferenceSkeleton(
        referenceStandard, null, fieldWidthOverrides, null, hourSkeleton.suppressDayPeriod());
  }

  private static ReferenceSkeleton unsupportedSemanticSkeleton(String reason) {
    return new ReferenceSkeleton(null, null, Map.of(), reason, false);
  }

  private static String composedDateTimePattern(
      String skeleton, ULocale locale, String length, String dateKey) {
    String[] parts = splitDateTimeSkeleton(skeleton);
    if (parts[0].isEmpty() || parts[1].isEmpty()) {
      return null;
    }
    DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(locale);
    String datePattern =
        "full".equals(length) && "year,month,day,weekday".equals(dateKey)
            ? semanticDateStylePattern(locale, length)
            : generator.getBestPattern(parts[0]);
    String timePattern = generator.getBestPattern(parts[1]);
    if (datePattern == null || timePattern == null) {
      return null;
    }
    String joinPattern =
        CLDR_DATE_TIME_FORMATS
            .getOrDefault(locale.toLanguageTag(), Map.of())
            .getOrDefault(length, "{1} {0}");
    return joinPattern.replace("{1}", datePattern).replace("{0}", timePattern);
  }

  private static String explicitTwentyFourHourDayPeriodPattern(
      String skeleton, ULocale locale, String length, String dateKey) {
    String[] parts = splitDateTimeSkeleton(skeleton);
    String timeSkeleton = parts[1];
    if (timeSkeleton.isEmpty()
        || timeSkeleton.indexOf('B') < 0
        || !containsAny(timeSkeleton, "Hk")) {
      return null;
    }
    String dayPeriodRun = longestSkeletonRun(timeSkeleton, 'B');
    String timeWithoutDayPeriod = removeSkeletonFields(timeSkeleton, "abB");
    if (timeWithoutDayPeriod.isEmpty()) {
      return null;
    }
    DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(locale);
    String timePattern = generator.getBestPattern(timeWithoutDayPeriod) + " " + dayPeriodRun;
    if (parts[0].isEmpty()) {
      return timePattern;
    }
    String datePattern =
        "full".equals(length) && "year,month,day,weekday".equals(dateKey)
            ? semanticDateStylePattern(locale, length)
            : generator.getBestPattern(parts[0]);
    String joinPattern =
        CLDR_DATE_TIME_FORMATS
            .getOrDefault(locale.toLanguageTag(), Map.of())
            .getOrDefault(length, "{1} {0}");
    return joinPattern.replace("{1}", datePattern).replace("{0}", timePattern);
  }

  private static String exactDayPeriodPattern(
      JsonNode formatCase, String skeleton, ULocale locale) {
    String periodKey = exactDayPeriodKey(formatCase, locale);
    String periodName =
        CLDR_EXACT_DAY_PERIOD_NAMES
            .getOrDefault(baseLocaleTag(locale), Map.of())
            .get(periodKey);
    if (periodName == null) {
      return null;
    }
    String[] parts = splitDateTimeSkeleton(skeleton);
    if (!parts[0].isEmpty()) {
      return null;
    }
    if (removeSkeletonFields(parts[1], "abB").isEmpty()) {
      return quotePatternLiteral(periodName);
    }
    String key = dayPeriodTimePatternKey(parts[1]);
    String pattern =
        CLDR_EXACT_DAY_PERIOD_TIME_PATTERNS
            .getOrDefault(baseLocaleTag(locale), Map.of())
            .get(key);
    if (pattern == null) {
      return null;
    }
    return replaceDayPeriodFieldsWithLiteral(
        replacePatternHourSymbol(pattern, firstHourSymbol(parts[1])), periodName);
  }

  private static String exactDayPeriodKey(JsonNode formatCase, ULocale locale) {
    TimeZone timeZone = icuTimeZone(formatCase.path("options").path("timeZone").asText("UTC"));
    Calendar calendar = Calendar.getInstance(timeZone, locale);
    calendar.setTime(Date.from(Instant.parse(formatCase.path("value").asText())));
    int hour = calendar.get(Calendar.HOUR_OF_DAY);
    boolean exactMinute =
        calendar.get(Calendar.MINUTE) == 0
            && calendar.get(Calendar.SECOND) == 0
            && calendar.get(Calendar.MILLISECOND) == 0;
    if (exactMinute && hour == 0) {
      return "midnight";
    }
    if (exactMinute && hour == 12) {
      return "noon";
    }
    return hour < 12 ? "am" : "pm";
  }

  private static String dayPeriodTimePatternKey(String timeSkeleton) {
    StringBuilder key = new StringBuilder("B");
    if (containsAny(timeSkeleton, "hHkK")) {
      key.append('h');
    }
    if (timeSkeleton.indexOf('m') >= 0) {
      key.append('m');
    }
    if (timeSkeleton.indexOf('s') >= 0) {
      key.append('s');
    }
    return key.toString();
  }

  private static String quotePatternLiteral(String literal) {
    return "'" + literal.replace("'", "''") + "'";
  }

  private static String replaceDayPeriodFieldsWithLiteral(String pattern, String literal) {
    StringBuilder output = new StringBuilder(pattern.length() + literal.length());
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append("''");
          index += 2;
        } else {
          inQuote = !inQuote;
          output.append(ch);
          index++;
        }
      } else if (!inQuote && isDayPeriodField(ch)) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        output.append(quotePatternLiteral(literal));
        index = end;
      } else {
        output.append(ch);
        index++;
      }
    }
    return output.toString();
  }

  private static String replacePatternHourSymbol(String pattern, char hourSymbol) {
    if (hourSymbol == '\0') {
      return pattern;
    }
    StringBuilder output = new StringBuilder(pattern.length());
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          output.append("''");
          index += 2;
        } else {
          inQuote = !inQuote;
          output.append(ch);
          index++;
        }
      } else if (!inQuote && "hHkK".indexOf(ch) >= 0) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        output.append(Character.toString(hourSymbol).repeat(end - index));
        index = end;
      } else {
        output.append(ch);
        index++;
      }
    }
    return output.toString();
  }

  private static char firstHourSymbol(String skeleton) {
    for (int index = 0; index < skeleton.length(); index++) {
      char symbol = skeleton.charAt(index);
      if ("hHkK".indexOf(symbol) >= 0) {
        return symbol;
      }
    }
    return '\0';
  }

  private static String normalizeExplicitAmPmPatternSpacing(String pattern) {
    return pattern.replace('\u00a0', ' ').replace('\u202f', ' ');
  }

  private static boolean hasExplicitAmPmTimeSkeleton(String skeleton) {
    return skeleton.indexOf('a') >= 0 && containsAny(skeleton, "hHkKmsS");
  }

  private static String removeSkeletonFields(String skeleton, String fields) {
    StringBuilder output = new StringBuilder(skeleton.length());
    for (int index = 0; index < skeleton.length(); index++) {
      char symbol = skeleton.charAt(index);
      if (fields.indexOf(symbol) < 0) {
        output.append(symbol);
      }
    }
    return output.toString();
  }

  private static String longestSkeletonRun(String skeleton, char symbol) {
    int width = skeletonFieldWidth(skeleton, symbol);
    return Character.toString(symbol).repeat(width);
  }

  private static String[] splitDateTimeSkeleton(String skeleton) {
    StringBuilder dateSkeleton = new StringBuilder();
    StringBuilder timeSkeleton = new StringBuilder();
    for (int index = 0; index < skeleton.length(); index++) {
      char symbol = skeleton.charAt(index);
      if ("hHkKjJmsSaAbBzZOvVXx".indexOf(symbol) >= 0) {
        timeSkeleton.append(symbol);
      } else {
        dateSkeleton.append(symbol);
      }
    }
    return new String[] {dateSkeleton.toString(), timeSkeleton.toString()};
  }

  private static Map<String, String> parseSemanticSkeletonOptions(String body) {
    Map<String, String> options = new LinkedHashMap<>();
    String[] parts = body.split(";");
    String implicitDateStyle = null;
    boolean implicitTimeFields = false;
    for (int index = 0; index < parts.length; index++) {
      String part = parts[index].trim();
      if (part.isEmpty()) {
        continue;
      }
      int equals = part.indexOf('=');
      String rawKey =
          equals < 0 ? (options.isEmpty() ? "fields" : "") : part.substring(0, equals);
      String rawValue = equals < 0 ? part : part.substring(equals + 1);
      String rawKeyAlias = semanticNormalize(rawKey);
      String key = semanticNormalizeOptionKey(rawKey);
      String value = semanticNormalizeOptionValue(key, rawValue);
      if (!key.isEmpty()) {
        if ("style".equals(rawKeyAlias)
            || "datestyle".equals(rawKeyAlias)
            || "datelength".equals(rawKeyAlias)) {
          implicitDateStyle = value;
        }
        if ("timestyle".equals(rawKeyAlias)) {
          implicitTimeFields = true;
        }
        options.put(key, value);
      }
    }
    if (!options.containsKey("fields")) {
      String fields = implicitSemanticFields(implicitDateStyle, implicitTimeFields, options.get("timestyle"));
      if (!fields.isEmpty()) {
        options.put("fields", fields);
      }
    }
    return options;
  }

  private static String implicitSemanticFields(
      String dateStyle, boolean hasTimeStyle, String timeStyle) {
    String dateFields = "full".equals(dateStyle) ? "date,weekday" : "date";
    if (dateStyle != null && hasTimeStyle) {
      return "long".equals(timeStyle) || "full".equals(timeStyle)
          ? dateFields + ",time,zone"
          : dateFields + ",time";
    }
    if (dateStyle != null) {
      return dateFields;
    }
    if (hasTimeStyle) {
      return "long".equals(timeStyle) || "full".equals(timeStyle) ? "time,zone" : "time";
    }
    return "";
  }

  private static String semanticNormalizeOptionKey(String value) {
    String normalized = semanticNormalize(value);
    if ("style".equals(normalized) || "datestyle".equals(normalized) || "datelength".equals(normalized)) {
      return "length";
    }
    if ("precision".equals(normalized)) {
      return "timeprecision";
    }
    if ("timestyle".equals(normalized)) {
      return "timestyle";
    }
    if ("hour12".equals(normalized)) {
      return "hourcycle";
    }
    if ("zone".equals(normalized)
        || "timezonename".equals(normalized)
        || "timezonestyle".equals(normalized)) {
      return "zonestyle";
    }
    if ("fractionalseconddigits".equals(normalized)) {
      return "fractionalsecond";
    }
    switch (normalized) {
      case "era":
        return "erastyle";
      case "year":
        return "yearstyle";
      case "month":
        return "monthstyle";
      case "quarter":
        return "quarterstyle";
      case "day":
        return "daystyle";
      case "weekday":
        return "weekdaystyle";
      case "dayperiod":
        return "dayperiodstyle";
      case "hour":
        return "hourstyle";
      case "minute":
        return "minutestyle";
      case "second":
        return "secondstyle";
      default:
        return normalized;
    }
  }

  private static String semanticNormalizeOptionValue(String key, String value) {
    if ("fields".equals(key)) {
      return value.trim().toLowerCase(Locale.ROOT);
    }
    String normalized = semanticNormalize(value);
    if ("yearstyle".equals(key) && "withera".equals(normalized)) {
      return "with-era";
    }
    if (SEMANTIC_STYLE_OPTION_KEYS.contains(key)
        && ("2digit".equals(normalized) || "twodigit".equals(normalized))) {
      return "2-digit";
    }
    if (SEMANTIC_STYLE_OPTION_KEYS.contains(key) && "wide".equals(normalized)) {
      return "long";
    }
    if (SEMANTIC_STYLE_OPTION_KEYS.contains(key) && "abbreviated".equals(normalized)) {
      return "short";
    }
    if ("timeprecision".equals(key) && "short".equals(normalized)) {
      return "minute";
    }
    if ("timeprecision".equals(key) && "medium".equals(normalized)) {
      return "second";
    }
    if ("timeprecision".equals(key) && "minuteoptional".equals(normalized)) {
      return "minute-optional";
    }
    if ("timeprecision".equals(key) && "fractionalsecond".equals(normalized)) {
      return "fractional-second";
    }
    if ("zonestyle".equals(key) && ("shortoffset".equals(normalized) || "longoffset".equals(normalized))) {
      return "offset";
    }
    if ("zonestyle".equals(key) && ("shortgeneric".equals(normalized) || "longgeneric".equals(normalized))) {
      return "generic";
    }
    if ("zonestyle".equals(key) && ("short".equals(normalized) || "long".equals(normalized))) {
      return "specific";
    }
    if ("hourcycle".equals(key) && "true".equals(normalized)) {
      return "clock12";
    }
    if ("hourcycle".equals(key) && "false".equals(normalized)) {
      return "clock24";
    }
    return normalized;
  }

  private static String semanticNormalize(String value) {
    return value.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
  }

  private static Set<String> parseSemanticSkeletonFields(Map<String, String> options) {
    Set<String> fields = new LinkedHashSet<>();
    String fieldsText = options.getOrDefault("fields", "");
    for (String field : fieldsText.split(",")) {
      String normalized = semanticNormalizeField(field);
      if (!normalized.isEmpty()) {
        fields.addAll(semanticCanonicalFields(normalized));
      }
    }
    return fields;
  }

  private static List<String> semanticCanonicalFields(String normalized) {
    return switch (normalized) {
      case "date", "yearmonthday" -> List.of("year", "month", "day");
      case "eradate", "erayearmonthday" -> List.of("era", "year", "month", "day");
      case "eradateweekday",
              "weekdayeradate",
              "erayearmonthdayweekday",
              "weekdayerayearmonthday" ->
          List.of("era", "year", "month", "day", "weekday");
      case "eradatetime", "erayearmonthdaytime" ->
          List.of("era", "year", "month", "day", "time");
      case "eradatetimeweekday",
              "weekdayeradatetime",
              "erayearmonthdaytimeweekday",
              "weekdayerayearmonthdaytime" ->
          List.of("era", "year", "month", "day", "weekday", "time");
      case "datetime", "yearmonthdaytime" -> List.of("year", "month", "day", "time");
      case "datetimeweekday",
              "weekdaydatetime",
              "yearmonthdaytimeweekday",
              "weekdayyearmonthdaytime" ->
          List.of("year", "month", "day", "weekday", "time");
      case "datetimeweekdayzone",
              "weekdaydatetimezone",
              "zoneddatetimeweekday",
              "zonedweekdaydatetime",
              "yearmonthdaytimeweekdayzone",
              "weekdayyearmonthdaytimezone",
              "zonedyearmonthdaytimeweekday",
              "zonedweekdayyearmonthdaytime" ->
          List.of("year", "month", "day", "weekday", "time", "zone");
      case "eradatetimezone",
              "zonederadatetime",
              "erayearmonthdaytimezone",
              "zonederayearmonthdaytime" ->
          List.of("era", "year", "month", "day", "time", "zone");
      case "eradatetimeweekdayzone",
              "weekdayeradatetimezone",
              "zonederadatetimeweekday",
              "zonedweekdayeradatetime",
              "erayearmonthdaytimeweekdayzone",
              "weekdayerayearmonthdaytimezone",
              "zonederayearmonthdaytimeweekday",
              "zonedweekdayerayearmonthdaytime" ->
          List.of("era", "year", "month", "day", "weekday", "time", "zone");
      case "dateweekday",
              "weekdaydate",
              "yearmonthdayweekday",
              "weekdayyearmonthday" ->
          List.of("year", "month", "day", "weekday");
      case "datetimezone", "zoneddatetime", "yearmonthdaytimezone", "zonedyearmonthdaytime" ->
          List.of("year", "month", "day", "time", "zone");
      case "yearmonth" -> List.of("year", "month");
      case "erayearmonth" -> List.of("era", "year", "month");
      case "yearquarter" -> List.of("year", "quarter");
      case "erayearquarter" -> List.of("era", "year", "quarter");
      case "yearweek" -> List.of("year", "weekofyear");
      case "erayearweek" -> List.of("era", "year", "weekofyear");
      case "erayear" -> List.of("era", "year");
      case "monthweek" -> List.of("month", "weekofmonth");
      case "yearmonthweek" -> List.of("year", "month", "weekofmonth");
      case "erayearmonthweek" -> List.of("era", "year", "month", "weekofmonth");
      case "monthday" -> List.of("month", "day");
      default -> List.of(normalized);
    };
  }

  private static String semanticNormalizeField(String value) {
    String normalized = semanticNormalize(value);
    if ("dayofmonth".equals(normalized)) {
      return "day";
    }
    if ("dayofweek".equals(normalized)) {
      return "weekday";
    }
    if ("monthofyear".equals(normalized)) {
      return "month";
    }
    if ("quarterofyear".equals(normalized)) {
      return "quarter";
    }
    if ("yearofera".equals(normalized)) {
      return "year";
    }
    if ("week".equals(normalized)) {
      return "weekofyear";
    }
    if ("weekofyear".equals(normalized)) {
      return "weekofyear";
    }
    if ("weekofmonth".equals(normalized)) {
      return "weekofmonth";
    }
    if ("dayofyear".equals(normalized)) {
      return "dayofyear";
    }
    if ("dayofweekinmonth".equals(normalized)) {
      return "dayofweekinmonth";
    }
    if ("modifiedjulianday".equals(normalized)) {
      return "modifiedjulianday";
    }
    if ("millisecondsinday".equals(normalized)) {
      return "millisecondsinday";
    }
    if ("fractionalseconddigits".equals(normalized)) {
      return "fractionalsecond";
    }
    if ("dayperiod".equals(normalized)) {
      return "dayperiod";
    }
    if ("hourofday".equals(normalized)) {
      return "hour";
    }
    if ("minuteofhour".equals(normalized)) {
      return "minute";
    }
    if ("secondofminute".equals(normalized)) {
      return "second";
    }
    if ("timezonename".equals(normalized)) {
      return "zone";
    }
    if ("timezone".equals(normalized)) {
      return "zone";
    }
    return normalized;
  }

  private static String semanticFieldSetKey(Set<String> fields, List<String> order) {
    return order.stream()
        .filter(fields::contains)
        .reduce((left, right) -> left + "," + right)
        .orElse("");
  }

  private static String semanticOption(Map<String, String> options, String key, String fallback) {
    return options.getOrDefault(key, fallback);
  }

  private static String semanticFormatOption(
      JsonNode formatOptions, String optionKey, String semanticKey, String fallback) {
    JsonNode value = formatOptions.get(optionKey);
    return value == null || value.isNull()
        ? fallback
        : semanticNormalizeOptionValue(semanticKey, value.asText());
  }

  private static ReferenceSkeleton semanticDirectStyleReferenceSkeleton(
      ULocale locale,
      Map<String, String> options,
      Set<String> fields,
      String dateKey,
      String timeKey,
      String length,
      String timeStyle,
      boolean hasTimeField,
      boolean hasZone) {
    if (!SEMANTIC_DIRECT_STYLE_OPTION_KEYS.containsAll(options.keySet())) {
      return null;
    }
    if (!timeKey.isEmpty()) {
      return null;
    }
    boolean fullDate = "full".equals(length) && "year,month,day,weekday".equals(dateKey);
    boolean date = "year,month,day".equals(dateKey);
    boolean hasDate = date || fullDate;
    if (!hasDate && !hasTimeField) {
      return null;
    }
    boolean hasTimeStyle = options.containsKey("timestyle") && !"auto".equals(timeStyle);
    if (hasTimeField != hasTimeStyle) {
      return null;
    }
    boolean timeStyleRequiresZone = hasTimeStyle && semanticTimeStyleRequiresZone(timeStyle);
    if (hasZone != timeStyleRequiresZone) {
      return null;
    }
    int expectedFieldCount = (hasDate ? fullDate ? 4 : 3 : 0) + (hasTimeField ? 1 : 0) + (hasZone ? 1 : 0);
    if (fields.size() != expectedFieldCount) {
      return null;
    }

    String pattern;
    if (hasDate && hasTimeField) {
      pattern = semanticDateTimeStylePattern(locale, length, timeStyle);
    } else if (hasTimeField) {
      pattern = semanticTimeStylePattern(locale, timeStyle);
    } else {
      pattern = semanticDateStylePattern(locale, length);
    }
    return pattern == null ? null : new ReferenceSkeleton(null, pattern, Map.of(), null, false);
  }

  private static boolean semanticTimeStyleRequiresZone(String timeStyle) {
    return "long".equals(timeStyle) || "full".equals(timeStyle);
  }

  private static String semanticDateStylePattern(ULocale locale, String length) {
    DateFormat dateFormat = DateFormat.getDateInstance(semanticDateFormatStyle(length), locale);
    return dateFormat instanceof SimpleDateFormat simpleDateFormat
        ? simpleDateFormat.toPattern()
        : null;
  }

  private static String semanticTimeStylePattern(ULocale locale, String length) {
    DateFormat timeFormat = DateFormat.getTimeInstance(semanticDateFormatStyle(length), locale);
    return timeFormat instanceof SimpleDateFormat simpleDateFormat
        ? simpleDateFormat.toPattern()
        : null;
  }

  private static String semanticDateTimeStylePattern(
      ULocale locale, String dateLength, String timeLength) {
    String datePattern = semanticDateStylePattern(locale, dateLength);
    String timePattern = semanticTimeStylePattern(locale, timeLength);
    if (datePattern == null || timePattern == null) {
      return null;
    }
    return dateTimeStyleJoinPattern(locale, dateLength)
        .replace("{1}", datePattern)
        .replace("{0}", timePattern);
  }

  private static String dateTimeStyleJoinPattern(ULocale locale, String length) {
    String localeKey = baseLocaleTag(locale);
    return CLDR_DATE_TIME_STYLE_JOIN_FORMATS
        .getOrDefault(localeKey, Map.of())
        .getOrDefault(
            length,
            CLDR_DATE_TIME_FORMATS
                .getOrDefault(localeKey, Map.of())
                .getOrDefault(length, "{1} {0}"));
  }

  private static int semanticDateFormatStyle(String length) {
    return switch (length) {
      case "full" -> DateFormat.FULL;
      case "long" -> DateFormat.LONG;
      case "short" -> DateFormat.SHORT;
      default -> DateFormat.MEDIUM;
    };
  }

  private static Map<Character, Integer> semanticDateFieldWidths(ULocale locale, String length) {
    DateFormat dateFormat = DateFormat.getDateInstance(semanticDateFormatStyle(length), locale);
    String pattern = dateFormat instanceof SimpleDateFormat simpleDateFormat
        ? simpleDateFormat.toPattern()
        : "";
    Map<Character, Integer> widths = new LinkedHashMap<>();
    for (FieldRun run : patternFieldRuns(pattern)) {
      if (run.symbol() == 'G'
          || isYearField(run.symbol())
          || isMonthField(run.symbol())
          || run.symbol() == 'd') {
        widths.putIfAbsent(run.symbol(), run.width());
      }
    }
    if (widths.keySet().stream().noneMatch(Icu4jReference::isYearField)) {
      widths.put('y', "short".equals(length) ? 2 : 1);
    }
    if (widths.keySet().stream().noneMatch(Icu4jReference::isMonthField)) {
      widths.put('M', switch (length) {
        case "full", "long" -> 4;
        case "medium" -> 3;
        default -> 1;
      });
    }
    widths.putIfAbsent('d', 1);
    return widths;
  }

  private static List<FieldRun> patternFieldRuns(String pattern) {
    List<FieldRun> fields = new ArrayList<>();
    boolean inQuote = false;
    for (int index = 0; index < pattern.length(); ) {
      char ch = pattern.charAt(index);
      if (ch == '\'') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
          index += 2;
        } else {
          inQuote = !inQuote;
          index++;
        }
      } else if (!inQuote && Character.isLetter(ch) && ch < 128) {
        int end = index + 1;
        while (end < pattern.length() && pattern.charAt(end) == ch) {
          end++;
        }
        fields.add(new FieldRun(ch, end - index));
        index = end;
      } else {
        index++;
      }
    }
    return fields;
  }

  private static boolean isYearField(char symbol) {
    return symbol == 'y' || symbol == 'Y' || symbol == 'u' || symbol == 'r';
  }

  private static boolean isMonthField(char symbol) {
    return symbol == 'M' || symbol == 'L';
  }

  private static String semanticEraSkeleton(
      Map<Character, Integer> dateWidths, String length, String eraStyle) {
    int width =
        "auto".equals(eraStyle)
            ? dateWidths.getOrDefault('G', isWideLength(length) ? 4 : 1)
            : eraStyleWidth(eraStyle);
    return "G".repeat(width);
  }

  private static int eraStyleWidth(String style) {
    return "long".equals(style) ? 4 : "narrow".equals(style) ? 5 : 1;
  }

  private static String semanticYearSkeleton(
      Map<Character, Integer> dateWidths, String yearStyle, boolean includeEra) {
    char yearSymbol =
        Stream.of('y', 'u', 'r').filter(dateWidths::containsKey).findFirst().orElse('y');
    int sourceWidth = dateWidths.getOrDefault(yearSymbol, 1);
    int yearWidth = semanticYearWidth(sourceWidth, yearStyle);
    StringBuilder skeleton = new StringBuilder(Character.toString(yearSymbol).repeat(yearWidth));
    if (includeEra && dateWidths.containsKey('G')) {
      skeleton.insert(0, "G".repeat(dateWidths.get('G')));
    }
    if (includeEra && "with-era".equals(yearStyle) && !dateWidths.containsKey('G')) {
      skeleton.insert(0, 'G');
    }
    return skeleton.toString();
  }

  private static int semanticYearWidth(int sourceWidth, String yearStyle) {
    if ("auto".equals(yearStyle)) {
      return sourceWidth;
    }
    if ("2-digit".equals(yearStyle)) {
      return 2;
    }
    if ("numeric".equals(yearStyle)) {
      return 1;
    }
    return sourceWidth == 2 ? 1 : sourceWidth;
  }

  private static String semanticQuarterSkeleton(
      Set<String> fields, String length, String alignment, String quarterStyle) {
    String symbol = fields.size() == 1 ? "q" : "Q";
    int width = "auto".equals(quarterStyle) ? lengthStyleWidth(length) : dateFieldStyleWidth(quarterStyle);
    return symbol.repeat("column".equals(alignment) && width < 3 ? Math.max(width, 2) : width);
  }

  private static String semanticMonthSkeleton(
      Set<String> fields,
      Map<Character, Integer> dateWidths,
      String length,
      String alignment,
      String monthStyle) {
    if (fields.size() == 1) {
      int width = "auto".equals(monthStyle) ? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle);
      return "L".repeat("column".equals(alignment) && width < 3 ? Math.max(width, 2) : width);
    }
    char symbol = dateWidths.containsKey('M') ? 'M' : dateWidths.containsKey('L') ? 'L' : 'M';
    int width =
        "auto".equals(monthStyle)
            ? dateWidths.getOrDefault(symbol, lengthStyleWidth(length))
            : dateFieldStyleWidth(monthStyle);
    return Character.toString(symbol)
        .repeat("column".equals(alignment) && width < 3 ? Math.max(width, 2) : width);
  }

  private static int lengthStyleWidth(String length) {
    return isWideLength(length) ? 4 : "medium".equals(length) ? 3 : 1;
  }

  private static boolean isWideLength(String length) {
    return "full".equals(length) || "long".equals(length);
  }

  private static int dateFieldStyleWidth(String style) {
    return switch (style) {
      case "numeric" -> 1;
      case "2-digit" -> 2;
      case "short" -> 3;
      case "long" -> 4;
      default -> 5;
    };
  }

  private static String semanticDaySkeleton(
      Map<Character, Integer> dateWidths, String alignment, String dayStyle) {
    int width =
        "auto".equals(dayStyle)
            ? dateWidths.getOrDefault('d', 1)
            : dateFieldStyleWidth(dayStyle);
    return "d".repeat("column".equals(alignment) && width < 3 ? Math.max(width, 2) : width);
  }

  private static String semanticWeekdaySkeleton(
      Set<String> fields, String length, String weekdayStyle) {
    if ("short".equals(weekdayStyle)) {
      return "EEE";
    }
    if ("long".equals(weekdayStyle)) {
      return "EEEE";
    }
    if ("narrow".equals(weekdayStyle)) {
      return "EEEEE";
    }
    if (fields.size() == 1 && "short".equals(length)) {
      return "EEEEE";
    }
    return isWideLength(length) ? "EEEE" : "EEE";
  }

  private static String semanticDayPeriodSkeleton(String length, String dayPeriodStyle) {
    String style = "auto".equals(dayPeriodStyle) ? length : dayPeriodStyle;
    return "B".repeat(
        isWideLength(style)
            ? 4
            : "narrow".equals(style) || ("auto".equals(dayPeriodStyle) && "short".equals(length))
                ? 5
                : 1);
  }

  private static String semanticExplicitTimeSkeleton(
      Set<String> fields, String hourCycle, String alignment, Map<String, String> options) {
    boolean hasHour = fields.contains("hour");
    boolean hasMinute = fields.contains("minute");
    boolean hasSecond = fields.contains("second");
    boolean hasFractionalSecond = fields.contains("fractionalsecond");
    boolean hasMillisecondsInDay = fields.contains("millisecondsinday");
    StringBuilder skeleton = new StringBuilder();
    if (hasHour) {
      skeleton.append(
          semanticHourSymbol(hourCycle)
              .repeat(
                  semanticNumericFieldWidth(
                      options, "hourstyle", "column".equals(alignment) ? 2 : 1)));
    }
    if (hasMinute) {
      skeleton.append(
          "m".repeat(
              semanticNumericFieldWidth(
                  options,
                  "minutestyle",
                  !hasHour && !hasSecond && "column".equals(alignment) ? 2 : 1)));
    }
    if (hasSecond) {
      skeleton.append(
          "s".repeat(
              semanticNumericFieldWidth(
                  options,
                  "secondstyle",
                  !hasHour && !hasMinute && "column".equals(alignment) ? 2 : 1)));
    }
    if (hasFractionalSecond) {
      Integer width = semanticFractionalSecondWidth(options);
      if (width == null) {
        return null;
      }
      skeleton.append("S".repeat(width));
    }
    if (hasMillisecondsInDay) {
      skeleton.append("A".repeat("column".equals(alignment) ? 8 : 1));
    }
    return skeleton.toString();
  }

  private static String semanticExplicitTimeWithoutHourUnsupportedReason(
      Set<String> fields, String alignment, Map<String, String> options) {
    if (fields.contains("hour")
        || fields.stream().noneMatch(SEMANTIC_TIME_FIELD_ORDER::contains)) {
      return null;
    }
    return null;
  }

  private static Map<Character, Integer> semanticFieldWidthOverrides(Map<String, String> options) {
    Map<Character, Integer> overrides = new LinkedHashMap<>();
    Integer hourWidth = semanticExplicitNumericFieldWidth(options, "hourstyle");
    if (hourWidth != null) {
      for (char symbol : new char[] {'h', 'H', 'k', 'K'}) {
        overrides.put(symbol, hourWidth);
      }
    }
    Integer minuteWidth = semanticExplicitNumericFieldWidth(options, "minutestyle");
    if (minuteWidth != null) {
      overrides.put('m', minuteWidth);
    }
    Integer secondWidth = semanticExplicitNumericFieldWidth(options, "secondstyle");
    if (secondWidth != null) {
      overrides.put('s', secondWidth);
    }
    return overrides;
  }

  private static Integer semanticExplicitNumericFieldWidth(
      Map<String, String> options, String key) {
    String style = options.get(key);
    if (style == null || "auto".equals(style)) {
      return null;
    }
    return "2-digit".equals(style) ? 2 : 1;
  }

  private static int semanticNumericFieldWidth(
      Map<String, String> options, String key, int fallbackWidth) {
    String style = options.getOrDefault(key, "auto");
    if ("auto".equals(style)) {
      return fallbackWidth;
    }
    if ("2-digit".equals(style)) {
      return 2;
    }
    return 1;
  }

  private static Integer semanticFractionalSecondWidth(Map<String, String> options) {
    String rawWidth = options.get("fractionalsecond");
    return rawWidth != null && rawWidth.matches("[1-9]") ? Integer.parseInt(rawWidth) : null;
  }

  private static String semanticTimeSkeleton(
      String timePrecision,
      String hourCycle,
      String alignment,
      Date date,
      Map<String, String> options) {
    StringBuilder skeleton =
        new StringBuilder(semanticHourSymbol(hourCycle).repeat("column".equals(alignment) ? 2 : 1));
    if (Set.of("minute", "second", "fractional-second").contains(timePrecision)) {
      skeleton.append('m');
    }
    if ("minute-optional".equals(timePrecision)
        && date.toInstant().atZone(java.time.ZoneOffset.UTC).getMinute() != 0) {
      skeleton.append('m');
    }
    if (Set.of("second", "fractional-second").contains(timePrecision)) {
      skeleton.append('s');
    }
    if ("fractional-second".equals(timePrecision)) {
      Integer width = semanticFractionalSecondWidth(options);
      if (width == null) {
        return null;
      }
      skeleton.append("S".repeat(width));
    }
    return skeleton.toString();
  }

  private static String semanticTimeStylePrecision(String timeStyle, String timePrecision) {
    return switch (timeStyle) {
      case "short" -> "minute";
      case "medium", "long", "full" -> "second";
      default -> timePrecision;
    };
  }

  private static String semanticTimeStyleZoneStyle(String timeStyle, String zoneStyle) {
    return "long".equals(timeStyle) || "full".equals(timeStyle) ? "specific" : zoneStyle;
  }

  private static String semanticHourSymbol(String hourCycle) {
    return switch (hourCycle) {
      case "h11" -> "K";
      case "h12", "clock12" -> "h";
      case "h23", "clock24" -> "H";
      case "h24" -> "k";
      default -> "j";
    };
  }

  private static String semanticZoneSkeleton(
      String zoneStyle, boolean standalone, String length) {
    String style = "auto".equals(zoneStyle) ? "generic" : zoneStyle;
    return switch (style) {
      case "specific" -> standalone && !"short".equals(length) ? "zzzz" : "z";
      case "location" -> "VVVV";
      case "offset" -> "O";
      default -> standalone && !"short".equals(length) ? "vvvv" : "v";
    };
  }

  private static String semanticTimezoneUnsupportedReason(String zoneSkeleton) {
    if (zoneSkeleton.isEmpty()) {
      return null;
    }
    return null;
  }

  private static HourSkeleton hourCycleReferenceSkeleton(
      String skeleton, ULocale locale, String hourCycle) {
    StringBuilder output = new StringBuilder(skeleton.length());
    String effectiveHourCycle =
        hourCycle == null || hourCycle.isBlank() || "auto".equals(hourCycle) ? null : hourCycle;
    boolean suppressDayPeriod = shouldSuppressDayPeriod(skeleton);
    for (int index = 0; index < skeleton.length(); ) {
      char ch = skeleton.charAt(index);
      int end = index + 1;
      while (end < skeleton.length() && skeleton.charAt(end) == ch) {
        end++;
      }
      int width = end - index;
      if (ch == 'C') {
        output.append(cHourReferenceSkeleton(locale, effectiveHourCycle, width));
      } else if (ch == 'j' || ch == 'J') {
        output.append(Character.toString(preferredHourSymbol(locale, effectiveHourCycle)).repeat(width));
      } else {
        output.append(skeleton, index, end);
      }
      index = end;
    }
    return new HourSkeleton(output.toString(), suppressDayPeriod);
  }

  private static String cHourReferenceSkeleton(ULocale locale, String hourCycle, int width) {
    if (hourCycle != null) {
      char hourSymbol = preferredHourSymbol(locale, hourCycle);
      return Character.toString(hourSymbol).repeat(cHourWidth(width))
          + (isHour12Field(hourSymbol) ? "B".repeat(dayPeriodWidthForC(width)) : "");
    }
    for (String token : CLDR_ALLOWED_HOUR_FORMATS.getOrDefault(baseLocaleTag(locale), "").split("\\s+")) {
      if (!token.matches("[hHkK][bB]?")) {
        continue;
      }
      String hour = Character.toString(token.charAt(0)).repeat(cHourWidth(width));
      String period = token.length() > 1 ? Character.toString(token.charAt(1)).repeat(dayPeriodWidthForC(width)) : "";
      return hour + period;
    }
    return Character.toString(preferredHourSymbol(locale, null)).repeat(cHourWidth(width));
  }

  private static char preferredHourSymbol(ULocale locale, String hourCycle) {
    if ("h11".equals(hourCycle)) {
      return 'K';
    }
    if ("h12".equals(hourCycle)) {
      return 'h';
    }
    if ("h23".equals(hourCycle)) {
      return 'H';
    }
    if ("h24".equals(hourCycle)) {
      return 'k';
    }
    String shortTime = CLDR_SHORT_TIME_FORMATS.getOrDefault(baseLocaleTag(locale), "");
    if (shortTime.indexOf('H') >= 0) {
      return 'H';
    }
    if (shortTime.indexOf('k') >= 0) {
      return 'k';
    }
    if (shortTime.indexOf('K') >= 0) {
      return 'K';
    }
    return 'h';
  }

  private static boolean shouldSuppressDayPeriod(String skeleton) {
    return skeleton.indexOf('J') >= 0
        && skeleton.indexOf('a') < 0
        && skeleton.indexOf('b') < 0
        && skeleton.indexOf('B') < 0
        && skeleton.indexOf('C') < 0;
  }

  private static int cHourWidth(int width) {
    return width % 2 == 0 ? 2 : 1;
  }

  private static int dayPeriodWidthForC(int width) {
    if (width >= 5) {
      return 5;
    }
    return width >= 3 ? 4 : 1;
  }

  private static boolean isHour12Field(char symbol) {
    return symbol == 'h' || symbol == 'K';
  }

  private static String baseLocaleTag(ULocale locale) {
    String tag = locale.toLanguageTag();
    int extensionIndex = tag.indexOf("-u-");
    return extensionIndex < 0 ? tag : tag.substring(0, extensionIndex);
  }

  private static String localeHourCycle(ULocale locale) {
    String[] subtags = locale.toLanguageTag().split("-");
    for (int index = 0; index + 2 < subtags.length; index++) {
      if ("u".equals(subtags[index]) && "hc".equals(subtags[index + 1])) {
        String value = subtags[index + 2];
        return Set.of("h11", "h12", "h23", "h24").contains(value) ? value : null;
      }
    }
    return null;
  }

  private static String directIcu4jSkeletonUnsupportedReason(
      String skeleton, JsonNode options, boolean loweredHourCycleOption) {
    if (!skeleton.chars().allMatch(ch -> ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z')) {
      return "skeleton is not an ASCII pattern-letter sequence";
    }
    String dayPeriodUnsupportedReason = directDayPeriodUnsupportedReason(skeleton);
    if (dayPeriodUnsupportedReason != null) {
      return dayPeriodUnsupportedReason;
    }
    if (options.has("hourCycle") && !loweredHourCycleOption) {
      return "hour-cycle override handling is a micro-runtime option";
    }
    return null;
  }

  private static String directDayPeriodUnsupportedReason(String skeleton) {
    if (!containsAny(skeleton, "abB")) {
      return null;
    }
    if (skeleton.indexOf('b') < 0) {
      return null;
    }
    return "exact b day-period skeletons use micro-runtime period-name selection";
  }

  private static boolean isBareMinuteSecondPatternSkeleton(String skeleton) {
    return skeleton.matches("m+") || skeleton.matches("s+");
  }

  private static boolean isZeroOffsetTimeZone(String value) {
    return switch (value) {
      case "UTC",
          "GMT",
          "Z",
          "+00:00",
          "-00:00",
          "Etc/GMT",
          "Etc/GMT+0",
          "Etc/GMT-0",
          "GMT+0",
          "GMT-0",
          "GMT+00:00",
          "GMT-00:00" -> true;
      default -> false;
    };
  }

  private static boolean containsRepeatedFieldWidth(String skeleton, char symbol, int minimumWidth) {
    int width = 0;
    for (int index = 0; index < skeleton.length(); index++) {
      if (skeleton.charAt(index) == symbol) {
        width++;
        if (width >= minimumWidth) {
          return true;
        }
      } else {
        width = 0;
      }
    }
    return false;
  }

  private static boolean containsFieldWidth(String skeleton, char symbol, int expectedWidth) {
    for (int index = 0; index < skeleton.length(); ) {
      char ch = skeleton.charAt(index);
      int end = index + 1;
      while (end < skeleton.length() && skeleton.charAt(end) == ch) {
        end++;
      }
      if (ch == symbol && end - index == expectedWidth) {
        return true;
      }
      index = end;
    }
    return false;
  }

  private static boolean containsAny(String value, String chars) {
    for (int index = 0; index < chars.length(); index++) {
      if (value.indexOf(chars.charAt(index)) >= 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDateAndTimeSkeletonFields(String skeleton) {
    return containsAny(skeleton, "GyYQqMLdE")
        && containsAny(skeleton, "hHkKjJmsSaAbBzZOvVXx");
  }

  private static void relativeTime(Path fixturePath) throws IOException {
    JsonNode fixture = OBJECT_MAPPER.readTree(fixturePath.toFile());
    JsonNode defaultOptions = fixture.path("defaultOptions");
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (JsonNode formatCase : fixture.path("cases")) {
      String name = formatCase.path("label").asText();
      JsonNode resolved = formatCase.path("resolved");
      String unitName = resolved.path("unit").asText(null);
      RelativeDateTimeFormatter.RelativeDateTimeUnit unit = relativeDateTimeUnit(unitName);
      if (unit == null) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: ICU4J unit not mapped: %s%n", name, unitName);
        continue;
      }

      double value = relativeTimeValue(resolved);
      String locale = formatCase.path("locale").asText("en");
      String source = formatCase.path("source").asText();
      String style = sourceOption(source, "style", defaultOptions.path("style").asText("short"));
      String numeric =
          sourceOption(source, "numeric", defaultOptions.path("numeric").asText("always"));
      RelativeDateTimeFormatter formatter =
          RelativeDateTimeFormatter.getInstance(
              ULocale.forLanguageTag(locale.replace('_', '-')),
              null,
              relativeDateTimeStyle(style),
              DisplayContext.CAPITALIZATION_NONE);
      String actual =
          "auto".equals(numeric)
              ? formatter.format(value, unit)
              : formatter.formatNumeric(value, unit);
      String expected = formatCase.path("expected").asText();
      if (expected.equals(actual)) {
        passed++;
      } else {
        failed++;
        System.out.printf(
            "MISMATCH %s:%n  expected: %s%n  actual:   %s%n", name, expected, actual);
      }
    }

    System.out.printf(
        "icu4j relative-time total=%d passed=%d failed=%d unsupported=%d%n",
        fixture.path("cases").size(), passed, failed, unsupported);
    if (failed > 0) {
      System.exit(1);
    }
  }

  private static double relativeTimeValue(JsonNode resolved) {
    if (resolved.has("relativeOffset")) {
      return resolved.path("relativeOffset").asDouble();
    }
    double quantity = resolved.path("quantity").asDouble();
    return "past".equals(resolved.path("direction").asText()) ? -quantity : quantity;
  }

  private static String sourceOption(String source, String name, String defaultValue) {
    String prefix = name + "=";
    for (String token : source.split("\\s+")) {
      String normalized = token.endsWith("}") ? token.substring(0, token.length() - 1) : token;
      if (normalized.startsWith(prefix)) {
        return normalized.substring(prefix.length());
      }
    }
    return defaultValue;
  }

  private static RelativeDateTimeFormatter.Style relativeDateTimeStyle(String style) {
    return switch (style) {
      case "long" -> RelativeDateTimeFormatter.Style.LONG;
      case "narrow" -> RelativeDateTimeFormatter.Style.NARROW;
      default -> RelativeDateTimeFormatter.Style.SHORT;
    };
  }

  private static RelativeDateTimeFormatter.RelativeDateTimeUnit relativeDateTimeUnit(
      String unit) {
    return switch (unit == null ? "" : unit) {
      case "second" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND;
      case "minute" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE;
      case "hour" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR;
      case "day" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY;
      case "week" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK;
      case "month" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH;
      case "quarter" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.QUARTER;
      case "year" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR;
      default -> null;
    };
  }

  private static void pluralCategories(Path generatedPluralRulesPath) throws IOException {
    JsonNode generated = OBJECT_MAPPER.readTree(generatedPluralRulesPath.toFile());
    for (String pluralTypeName : List.of("cardinal", "ordinal")) {
      JsonNode locales = generated.path(pluralTypeName).path("locales");
      JsonNode parents = generated.path(pluralTypeName).path("parents");
      Set<String> localeIdSet = new LinkedHashSet<>();
      locales.fieldNames().forEachRemaining(localeIdSet::add);
      parents.fieldNames().forEachRemaining(localeIdSet::add);
      addPluralProbeLocales(localeIdSet);
      List<String> localeIds = new ArrayList<>(localeIdSet);
      localeIds.sort(String::compareTo);

      PluralRules.PluralType pluralType =
          pluralTypeName.equals("cardinal")
              ? PluralRules.PluralType.CARDINAL
              : PluralRules.PluralType.ORDINAL;

      for (String localeId : localeIds) {
        PluralRules rules =
            PluralRules.forLocale(
                ULocale.forLanguageTag(localeId.replace('_', '-')), pluralType);
        for (String sample : pluralSamples()) {
          Map<String, String> row = new LinkedHashMap<>();
          row.put("type", pluralTypeName);
          row.put("locale", localeId);
          row.put("sample", sample);
          row.put("category", selectPluralSample(rules, sample));
          System.out.println(OBJECT_MAPPER.writeValueAsString(row));
        }
      }
    }
  }

  private static void addPluralProbeLocales(Set<String> localeIds) {
    if (localeIds.contains("az")) {
      localeIds.add("az-Arab");
    }
    if (localeIds.contains("en")) {
      localeIds.add("en-US-u-nu-latn");
    }
    if (localeIds.contains("pt")) {
      localeIds.add("pt-AO");
      localeIds.add("pt-PT-u-nu-latn");
    }
    if (localeIds.contains("sr")) {
      localeIds.add("sr-Latn");
    }
    if (localeIds.contains("zh")) {
      localeIds.add("zh-Hant-TW");
    }
  }

  private static String selectPluralSample(PluralRules rules, String sample) {
    String normalized = sample.trim();
    String unsigned = normalized.replaceFirst("^[+-]", "");
    String fraction = "";
    int decimalIndex = unsigned.indexOf('.');
    if (decimalIndex >= 0) {
      fraction = unsigned.substring(decimalIndex + 1);
    }
    BigDecimal decimal = new BigDecimal(normalized).abs();
    long fractionDigits = fraction.isEmpty() ? 0 : Long.parseLong(fraction);
    return rules.select(decimal.doubleValue(), fraction.length(), fractionDigits);
  }

  private static List<String> pluralSamples() {
    Set<String> samples = new LinkedHashSet<>();
    samples.add("-2");
    samples.add("-1");
    for (int value = 0; value <= 250; value++) {
      samples.add(Integer.toString(value));
    }
    for (String value :
        List.of(
            "1000",
            "1001",
            "1002",
            "10000",
            "10001",
            "100000",
            "100001",
            "1000000",
            "1000001",
            "2000000")) {
      samples.add(value);
    }
    for (String value :
        List.of(
            "0.0",
            "0.1",
            "0.2",
            "0.5",
            "1.0",
            "1.00",
            "1.01",
            "1.1",
            "1.2",
            "1.5",
            "2.0",
            "2.00",
            "2.01",
            "2.1",
            "2.2",
            "3.0",
            "3.1",
            "4.0",
            "5.0",
            "10.0",
            "11.0",
            "12.0",
            "14.0",
            "21.0",
            "22.0",
            "100.0",
            "101.0",
            "1000.0",
            "-1.5")) {
      samples.add(value);
    }
    return List.copyOf(samples);
  }

  private static void compare(Path fixtureDirectory) throws IOException {
    List<ReferenceCase> cases = loadCases(fixtureDirectory);
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (ReferenceCase referenceCase : cases) {
      if (referenceCase.unsupportedReason != null) {
        unsupported++;
        System.out.printf(
            "UNSUPPORTED %s: %s%n", referenceCase.name, referenceCase.unsupportedReason);
        continue;
      }

      try {
        String actual = referenceCase.formatter.formatToString(referenceCase.arguments);
        if (actual.equals(referenceCase.expected)) {
          passed++;
        } else {
          failed++;
          System.out.printf(
              "MISMATCH %s:%n  expected: %s%n  actual:   %s%n",
              referenceCase.name, referenceCase.expected, actual);
        }
      } catch (RuntimeException e) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", referenceCase.name, e.getMessage());
      }
    }

    System.out.printf(
        "icu4j compare total=%d passed=%d failed=%d unsupported=%d%n",
        cases.size(), passed, failed, unsupported);

    if (failed > 0) {
      System.exit(1);
    }
  }

  private static void bench(Path fixtureDirectory, int iterations, int warmupIterations)
      throws IOException {
    List<ReferenceCase> cases =
        loadCases(fixtureDirectory).stream()
            .filter(referenceCase -> referenceCase.unsupportedReason == null)
            .toList();
    if (cases.isEmpty()) {
      System.err.println("No supported ICU4J format cases found.");
      System.exit(2);
    }

    for (int index = 0; index < warmupIterations; index++) {
      ReferenceCase referenceCase = cases.get(index % cases.size());
      sink = referenceCase.formatter.formatToString(referenceCase.arguments).length();
    }

    long bytes = 0;
    long started = System.nanoTime();
    for (int index = 0; index < iterations; index++) {
      ReferenceCase referenceCase = cases.get(index % cases.size());
      String output = referenceCase.formatter.formatToString(referenceCase.arguments);
      bytes += output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    long elapsed = System.nanoTime() - started;
    double seconds = elapsed / 1_000_000_000.0;
    sink = (int) bytes;

    System.out.printf(
        Locale.ROOT,
        "icu4j format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f bytes=%d%n",
        iterations,
        warmupIterations,
        cases.size(),
        seconds,
        iterations / seconds,
        bytes);
  }

  private static List<ReferenceCase> loadCases(Path fixtureDirectory) throws IOException {
    List<ReferenceCase> cases = new ArrayList<>();
    try (Stream<Path> paths = Files.list(fixtureDirectory)) {
      List<Path> fixturePaths =
          paths
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();

      for (Path fixturePath : fixturePaths) {
        JsonNode fixture = OBJECT_MAPPER.readTree(fixturePath.toFile());
        String source = fixture.path("source").asText();
        for (JsonNode formatCase : fixture.path("formatCases")) {
          String locale = formatCase.path("locale").asText("en");
          String name = fixture.path("name").asText(fixturePath.getFileName().toString());
          Map<String, Object> arguments = toMap(formatCase.path("arguments"));
          String expected = formatCase.path("expected").asText();
          String bidiIsolation = formatCase.path("bidiIsolation").asText("none");
          cases.add(buildCase(
              name + "[" + locale + "]", source, locale, bidiIsolation, arguments, expected));
        }
      }
    }
    return cases;
  }

  private static ReferenceCase buildCase(
      String name,
      String source,
      String locale,
      String bidiIsolation,
      Map<String, Object> arguments,
      String expected) {
    try {
      MessageFormatter formatter =
          MessageFormatter.builder()
              .setLocale(Locale.forLanguageTag(locale.replace('_', '-')))
              .setBidiIsolation(toBidiIsolation(bidiIsolation))
              .setErrorHandlingBehavior(MessageFormatter.ErrorHandlingBehavior.STRICT)
              .setPattern(source)
              .build();
      formatter.formatToString(arguments);
      return new ReferenceCase(name, formatter, arguments, expected, null);
    } catch (RuntimeException e) {
      return new ReferenceCase(name, null, arguments, expected, e.getMessage());
    }
  }

  private static MessageFormatter.BidiIsolation toBidiIsolation(String value) {
    return "default".equals(value)
        ? MessageFormatter.BidiIsolation.DEFAULT
        : MessageFormatter.BidiIsolation.NONE;
  }

  private static Map<String, Object> toMap(JsonNode node) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!node.isObject()) {
      return result;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      result.put(field.getKey(), toObject(field.getValue()));
    }
    return result;
  }

  private static Object toObject(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToInt() ? node.asInt() : node.asLong();
    }
    if (node.isFloatingPointNumber()) {
      return node.asDouble();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      for (JsonNode item : node) {
        values.add(toObject(item));
      }
      return values;
    }
    if (node.isObject()) {
      return toMap(node);
    }
    return node.asText();
  }

  private record ReferenceCase(
      String name,
      MessageFormatter formatter,
      Map<String, Object> arguments,
      String expected,
      String unsupportedReason) {}

  private record ReferenceSkeleton(
      String skeleton,
      String pattern,
      Map<Character, Integer> fieldWidthOverrides,
      String unsupportedReason,
      boolean suppressDayPeriod) {}

  private record HourSkeleton(String skeleton, boolean suppressDayPeriod) {}

  private record WeekMetadata(int firstDayOfWeek, int minDaysInFirstWeek) {}

  private record WeekYearInfo(int year, int week) {}

  private record FieldRun(char symbol, int width) {}
}
