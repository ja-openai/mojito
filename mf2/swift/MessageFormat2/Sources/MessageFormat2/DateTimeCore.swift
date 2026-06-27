import Foundation

public enum MF2DateTimeCore {
    public enum Style {
        case full
        case long
        case medium
        case short
    }

    public struct Options {
        public var locale: String
        public var style: Style
        public var dateStyle: Style?
        public var timeStyle: Style?
        public var skeleton: String?
        public var hourCycle: String?
        public var timeZone: String
        public var calendar: String?

        public init(
            locale: String = "en-US",
            style: Style = .medium,
            dateStyle: Style? = nil,
            timeStyle: Style? = nil,
            skeleton: String? = nil,
            hourCycle: String? = nil,
            timeZone: String = "UTC",
            calendar: String? = nil
        ) {
            self.locale = locale
            self.style = style
            self.dateStyle = dateStyle
            self.timeStyle = timeStyle
            self.skeleton = skeleton
            self.hourCycle = hourCycle
            self.timeZone = timeZone
            self.calendar = calendar
        }

        var effectiveDateStyle: Style {
            dateStyle ?? style
        }

        var effectiveTimeStyle: Style {
            timeStyle ?? style
        }
    }

    private static let defaultLocale = "en-US"
    private static let utc = "UTC"
    private static let maxLocaleLength = 256
    private static let maxOptionLength = 256
    private static let maxOperandLength = 256
    private static let maxSkeletonFieldWidth = 32
    private static let maxSkeletonLength = 256
    private static let semanticSkeletonPrefix = "semantic:"
    private static let semanticFieldOrder = ["era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear", "dayperiod", "hour", "minute", "second", "fractionalsecond", "millisecondsinday", "time", "zone"]
    private static let semanticDateFieldOrder = ["era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear"]
    private static let semanticTimeFieldOrder = ["hour", "minute", "second", "fractionalsecond", "millisecondsinday"]
    private static let semanticOptionKeys: Set<String> = [
        "fields", "length", "alignment", "yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle", "timeprecision", "timestyle", "fractionalsecond", "hourcycle", "zonestyle",
    ]
    private static let semanticDirectStyleOptionKeys: Set<String> = ["fields", "length", "timestyle"]
    private static let semanticStyleOptionKeys: Set<String> = ["yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle"]
    private static let semanticDateStyleValues: Set<String> = ["auto", "numeric", "2-digit", "short", "long", "narrow"]
    private static let semanticNumericStyleValues: Set<String> = ["auto", "numeric", "2-digit"]
    private static let semanticTextStyleValues: Set<String> = ["auto", "short", "long", "narrow"]
    private static let semanticDateFieldSets: Set<String> = [
        "day", "weekday", "day,weekday", "month,day", "month,day,weekday", "era,year,month,day", "era,year,month,day,weekday", "year,month,day", "year,month,day,weekday",
    ]
    private static let semanticCalendarPeriodFieldSets: Set<String> = [
        "era", "year", "quarter", "month", "era,year", "era,year,quarter", "era,year,month",
        "era,year,weekofyear", "era,year,month,weekofmonth", "year,quarter", "year,month",
        "year,weekofyear", "month,weekofmonth", "year,month,weekofmonth",
        "dayofyear", "dayofweekinmonth", "modifiedjulianday",
    ]
    private static let semanticTimeFieldSets: Set<String> = [
        "hour", "minute", "second", "millisecondsinday", "hour,minute", "hour,minute,second",
        "hour,minute,second,fractionalsecond", "minute,second",
        "minute,second,fractionalsecond", "second,fractionalsecond",
    ]
    private static let skeletonFieldOrder = Array("GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx")
    private static let skeletonTimeFields = Set(Array("abBhHkKJmsSAzZOvVXx"))
    private static let skeletonHourFields = Set(Array("hHkK"))
    private static let weekdayKeys = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]

    public static func registry() -> MF2FunctionRegistry {
        MF2FunctionRegistry.portable
            .withFunction("date") { try formatCallDate($0) }
            .withFunction("time") { try formatCallTime($0) }
            .withFunction("datetime") { try formatCallDateTime($0) }
    }

    public static func formatDate(_ value: MF2Value, options: Options = Options()) throws -> String {
        let locale = try localeOption(options.locale)
        let localeData = try resolveNumberingSystemData(resolveLocaleData(locale), locale: locale)
        try validate(options)
        let preserveSameFamilyHourCycle = !(options.hourCycle?.isEmpty ?? true)
        let hourCycle = try validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, key: "hc")))
        let timeZone = try parseTimeZone(options.timeZone)
        let date = try parseDate(value)
        if let skeleton = options.skeleton {
            return try formatSkeleton(skeleton, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle)
        }
        return try formatPattern(
            localeData.dateFormats[styleKey(options.effectiveDateStyle)] ?? "",
            date: date,
            localeData: localeData,
            timeZone: timeZone
        )
    }

    public static func formatDateToParts(_ value: MF2Value, options: Options = Options()) throws -> [MF2FormattedPart] {
        [.text(try formatDate(value, options: options))]
    }

    public static func formatTime(_ value: MF2Value, options: Options = Options()) throws -> String {
        let locale = try localeOption(options.locale)
        let localeData = try resolveNumberingSystemData(resolveLocaleData(locale), locale: locale)
        try validate(options)
        let preserveSameFamilyHourCycle = !(options.hourCycle?.isEmpty ?? true)
        let hourCycle = try validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, key: "hc")))
        let timeZone = try parseTimeZone(options.timeZone)
        let date = try parseDate(value)
        if let skeleton = options.skeleton {
            return try formatSkeleton(skeleton, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle)
        }
        return try formatTimeStylePattern(
            localeData.timeFormats[styleKey(options.effectiveTimeStyle)] ?? "",
            date: date,
            localeData: localeData,
            timeZone: timeZone,
            hourCycle: hourCycle,
            preserveSameFamilyHourCycle: preserveSameFamilyHourCycle
        )
    }

    public static func formatTimeToParts(_ value: MF2Value, options: Options = Options()) throws -> [MF2FormattedPart] {
        [.text(try formatTime(value, options: options))]
    }

    public static func formatDateTime(_ value: MF2Value, options: Options = Options()) throws -> String {
        let locale = try localeOption(options.locale)
        let localeData = try resolveNumberingSystemData(resolveLocaleData(locale), locale: locale)
        try validate(options)
        let preserveSameFamilyHourCycle = !(options.hourCycle?.isEmpty ?? true)
        let hourCycle = try validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, key: "hc")))
        let timeZone = try parseTimeZone(options.timeZone)
        let date = try parseDate(value)
        if let skeleton = options.skeleton {
            return try formatSkeleton(skeleton, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle)
        }
        let dateStyle = options.effectiveDateStyle
        let timeStyle = options.effectiveTimeStyle
        let datePart = try formatPattern(
            localeData.dateFormats[styleKey(dateStyle)] ?? "",
            date: date,
            localeData: localeData,
            timeZone: timeZone
        )
        let timePart = try formatTimeStylePattern(
            localeData.timeFormats[styleKey(timeStyle)] ?? "",
            date: date,
            localeData: localeData,
            timeZone: timeZone,
            hourCycle: hourCycle,
            preserveSameFamilyHourCycle: preserveSameFamilyHourCycle
        )
        return dateTimeStyleJoinPattern(localeData, style: styleKey(dateStyle))
            .replacingOccurrences(of: "{1}", with: datePart)
            .replacingOccurrences(of: "{0}", with: timePart)
    }

    public static func formatDateTimeToParts(_ value: MF2Value, options: Options = Options()) throws -> [MF2FormattedPart] {
        [.text(try formatDateTime(value, options: options))]
    }

    private static func formatCallDate(_ call: MF2FunctionCall) throws -> String {
        try formatDate(
            callSourceValue(call),
            options: Options(
                locale: call.locale,
                dateStyle: callStyle(call, option: "dateStyle", legacy: "length", defaultValue: "medium", legacyTimePrecision: false),
                skeleton: nonEmptyCallOption(call, "skeleton", default: nil),
                hourCycle: nonEmptyCallOption(call, "hourCycle", default: nil),
                timeZone: nonEmptyCallOption(call, "timeZone", default: utc) ?? utc,
                calendar: nonEmptyCallOption(call, "calendar", default: nil)
            )
        )
    }

    private static func formatCallTime(_ call: MF2FunctionCall) throws -> String {
        try formatTime(
            callSourceValue(call),
            options: Options(
                locale: call.locale,
                timeStyle: callStyle(call, option: "timeStyle", legacy: "precision", defaultValue: "medium", legacyTimePrecision: true),
                skeleton: nonEmptyCallOption(call, "skeleton", default: nil),
                hourCycle: nonEmptyCallOption(call, "hourCycle", default: nil),
                timeZone: nonEmptyCallOption(call, "timeZone", default: utc) ?? utc,
                calendar: nonEmptyCallOption(call, "calendar", default: nil)
            )
        )
    }

    private static func formatCallDateTime(_ call: MF2FunctionCall) throws -> String {
        try formatDateTime(
            callSourceValue(call),
            options: Options(
                locale: call.locale,
                dateStyle: callStyle(call, option: "dateStyle", legacy: "dateLength", defaultValue: "medium", legacyTimePrecision: false),
                timeStyle: callStyle(call, option: "timeStyle", legacy: "timePrecision", defaultValue: "medium", legacyTimePrecision: true),
                skeleton: nonEmptyCallOption(call, "skeleton", default: nil),
                hourCycle: nonEmptyCallOption(call, "hourCycle", default: nil),
                timeZone: nonEmptyCallOption(call, "timeZone", default: utc) ?? utc,
                calendar: nonEmptyCallOption(call, "calendar", default: nil)
            )
        )
    }

    private static func callSourceValue(_ call: MF2FunctionCall) -> MF2Value {
        if let source = call.inheritedSource {
            return .string(source.value)
        }
        return call.rawValue
    }

    private static func resolveLocaleData(_ locale: String) -> CldrDateTimeData.LocaleData {
        for candidate in MF2LocaleKey.lookupChain(locale) {
            if let exact = CldrDateTimeData.locales[candidate] {
                return exact
            }
            if let inherited = CldrDateTimeData.locales.values.first(where: {
                $0.sourceLocale == candidate || $0.numbersSourceLocale == candidate
            }) {
                return inherited
            }
        }
        return CldrDateTimeData.locales[defaultLocale]!
    }

    private static func localeOption(_ locale: String) throws -> String {
        let value = locale.isEmpty ? defaultLocale : locale
        if value.count > maxLocaleLength {
            throw MF2Error.badOption("locale must not exceed 256 characters.")
        }
        return value
    }

    private static func localeUnicodeExtension(_ locale: String, key: String) -> String? {
        let parts = locale
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
            .split(separator: "-")
            .map { $0.lowercased() }
        guard let unicodeIndex = parts.firstIndex(of: "u") else {
            return nil
        }
        var index = unicodeIndex + 1
        while index < parts.count {
            let part = parts[index]
            if part.count == 1 {
                return nil
            }
            if part.count != 2 {
                index += 1
                continue
            }
            var end = index + 1
            while end < parts.count, parts[end].count > 2 {
                end += 1
            }
            if part == key {
                return end > index + 1 ? parts[index + 1] : nil
            }
            index = end
        }
        return nil
    }

    private static func firstNonEmpty(_ values: String?...) -> String? {
        values.first { value in
            value != nil && value != ""
        } ?? nil
    }

    private static func validate(_ options: Options) throws {
        let calendar = try boundedOption(firstNonEmpty(options.calendar, localeUnicodeExtension(options.locale, key: "ca")), name: "calendar")
        guard calendar == nil || calendar == "gregorian" || calendar == "gregory" else {
            throw MF2Error.badOption("Date/time core currently supports only the gregorian/gregory calendar.")
        }
    }

    private static func resolveNumberingSystemData(_ localeData: CldrDateTimeData.LocaleData, locale: String) throws -> CldrDateTimeData.LocaleData {
        guard let numberingSystem = localeUnicodeExtension(locale, key: "nu"), !numberingSystem.isEmpty else {
            return localeData
        }
        guard let digits = numberingSystemDigits(numberingSystem) else {
            throw MF2Error.badOption("Date/time core does not include data for the requested numbering system.")
        }
        return CldrDateTimeData.LocaleData(
            requestedLocale: localeData.requestedLocale,
            sourceLocale: localeData.sourceLocale,
            numbersSourceLocale: localeData.numbersSourceLocale,
            calendar: localeData.calendar,
            numberingSystem: localeData.numberingSystem,
            numberingSystemDigits: digits,
            decimalSeparator: localeData.decimalSeparator,
            allowedHourFormats: localeData.allowedHourFormats,
            firstDayOfWeek: localeData.firstDayOfWeek,
            minDaysInFirstWeek: localeData.minDaysInFirstWeek,
            dateFormats: localeData.dateFormats,
            timeFormats: localeData.timeFormats,
            dateTimeFormats: localeData.dateTimeFormats,
            dateTimeStyleJoinFormats: localeData.dateTimeStyleJoinFormats,
            availableFormats: localeData.availableFormats,
            appendItems: localeData.appendItems,
            fieldNames: localeData.fieldNames,
            timeZoneNames: localeData.timeZoneNames,
            months: localeData.months,
            quarters: localeData.quarters,
            weekdays: localeData.weekdays,
            eras: localeData.eras,
            dayPeriods: localeData.dayPeriods,
            dayPeriodRules: localeData.dayPeriodRules
        )
    }

    private static func numberingSystemDigits(_ numberingSystem: String) -> String? {
        if numberingSystem == "latn" {
            return "0123456789"
        }
        return CldrDateTimeData.locales.values.first {
            $0.numberingSystem == numberingSystem && $0.numberingSystemDigits != nil
        }?.numberingSystemDigits
    }

    private static func validateHourCycle(_ value: String?) throws -> String? {
        guard let value, !value.isEmpty else {
            return nil
        }
        let text = try boundedOption(value, name: "hourCycle") ?? ""
        switch text {
        case "h11", "h12", "h23", "h24":
            return text
        default:
            throw MF2Error.badOption("hourCycle must be one of h11, h12, h23, h24.")
        }
    }

    private static func parseTimeZone(_ value: String) throws -> TimeZone {
        let text = try boundedOption(value, name: "timeZone")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if text.isEmpty || text == utc || text == "Etc/UTC" || text == "Z" || text == "GMT" || text == "Etc/GMT" {
            return TimeZone(secondsFromGMT: 0)!
        }
        if let offsetMinutes = parseEtcGmtOffsetMinutes(text) {
            return TimeZone(secondsFromGMT: offsetMinutes * 60)!
        }
        let offsetText: String
        if (text.hasPrefix("UTC") || text.hasPrefix("GMT")), text.count > 3 {
            offsetText = String(text.dropFirst(3))
        } else {
            offsetText = text
        }
        guard let offsetMinutes = parseOffsetMinutes(offsetText) else {
            throw MF2Error.badOption("Date/time core supports only UTC or fixed-offset time zones.")
        }
        return TimeZone(secondsFromGMT: offsetMinutes * 60)!
    }

    private static func boundedOption(_ value: String?, name: String) throws -> String? {
        if let value, value.count > maxOptionLength {
            throw MF2Error.badOption("\(name) must not exceed 256 characters.")
        }
        return value
    }

    private static func parseEtcGmtOffsetMinutes(_ value: String) -> Int? {
        let prefix = "Etc/GMT"
        guard value.hasPrefix(prefix), value.count > prefix.count else {
            return nil
        }
        let signIndex = value.index(value.startIndex, offsetBy: prefix.count)
        let sign = value[signIndex]
        guard sign == "+" || sign == "-" else {
            return nil
        }
        let hourText = String(value[value.index(after: signIndex)...])
        guard !hourText.isEmpty, hourText.count <= 2, let hours = Int(hourText), hours <= 14 else {
            return nil
        }
        let offset = hours * 60
        return sign == "+" ? -offset : offset
    }

    private static func parseOffsetMinutes(_ value: String) -> Int? {
        guard value.count >= 2, let sign = value.first, sign == "+" || sign == "-" else {
            return nil
        }
        let body = String(value.dropFirst())
        let hourText: String
        let minuteText: String
        if let colon = body.firstIndex(of: ":") {
            hourText = String(body[..<colon])
            minuteText = String(body[body.index(after: colon)...])
        } else if body.count > 2 {
            hourText = String(body.dropLast(2))
            minuteText = String(body.suffix(2))
        } else {
            hourText = body
            minuteText = "00"
        }
        guard !hourText.isEmpty, hourText.count <= 2, minuteText.count == 2,
              let hours = Int(hourText), let minutes = Int(minuteText),
              hours <= 18, minutes <= 59, !(hours == 18 && minutes != 0)
        else {
            return nil
        }
        let total = hours * 60 + minutes
        return sign == "-" ? -total : total
    }

    private static func parseDate(_ value: MF2Value) throws -> Date {
        switch value {
        case let .string(text), let .number(text):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.count <= maxOperandLength, let date = parseDateString(trimmed), isPortableDate(date) {
                return date
            }
        case .bool, .null:
            break
        }
        throw MF2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
    }

    private static func parseDateString(_ value: String) -> Date? {
        parseISO8601Date(value)
            ?? parseFixedDate(value, format: "yyyy-MM-dd'T'HH:mm:ss")
            ?? parseFixedDate(value, format: "yyyy-MM-dd'T'HH:mm")
            ?? parseFixedDate(value, format: "yyyy-MM-dd")
    }

    private static func parseISO8601Date(_ value: String) -> Date? {
        guard hasValidISO8601Offset(value) else {
            return nil
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) {
            return date
        }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    private static func isPortableDate(_ value: Date) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = calendar.dateComponents([.era, .year], from: value)
        guard components.era == 1, let year = components.year else {
            return false
        }
        return year >= 1 && year <= 9999
    }

    private static func hasValidISO8601Offset(_ value: String) -> Bool {
        guard let timeIndex = value.firstIndex(of: "T") else {
            return true
        }
        let timePart = value[timeIndex...]
        guard let signIndex = timePart.lastIndex(where: { $0 == "+" || $0 == "-" }) else {
            return true
        }
        return parseOffsetMinutes(String(value[signIndex...])) != nil
    }

    private static func parseFixedDate(_ value: String, format: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = format
        formatter.isLenient = false
        return formatter.date(from: value)
    }

    private static func formatSkeleton(
        _ skeleton: String,
        date: Date,
        localeData: CldrDateTimeData.LocaleData,
        timeZone: TimeZone,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Bool
    ) throws -> String {
        if skeleton.count > maxSkeletonLength {
            throw MF2Error.badOption("Date/time skeleton is too large.")
        }
        if let semanticStyle = try formatSemanticStyleSkeleton(skeleton, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle) {
            return semanticStyle
        }
        let canonical = try canonicalSkeleton(skeleton, localeData: localeData, hourCycle: hourCycle, date: date, timeZone: timeZone)
        let suppressDayPeriod = shouldSuppressDayPeriod(skeleton)
        let dateTimeJoinStyle = try skeletonDateTimeJoinStyle(skeleton)
        if let pattern = skeletonPattern(canonical, localeData: localeData) {
            let resolvedPattern = suppressDayPeriod ? stripDayPeriodPatternFields(pattern) : pattern
            return try formatPattern(resolvedPattern, date: date, localeData: localeData, timeZone: timeZone)
        }
        return try formatComposedSkeleton(skeleton, canonical: canonical, date: date, localeData: localeData, timeZone: timeZone, suppressDayPeriod: suppressDayPeriod, dateTimeJoinStyle: dateTimeJoinStyle)
    }

    private static func skeletonDateTimeJoinStyle(_ skeleton: String) throws -> String {
        guard skeleton.hasPrefix(semanticSkeletonPrefix) else {
            return "medium"
        }
        let body = String(skeleton.dropFirst(semanticSkeletonPrefix.count))
        let options = try parseSemanticSkeletonOptions(body)
        return try semanticOption(options, key: "length", fallback: "medium", allowedValues: ["full", "long", "medium", "short"])
    }

    private static func formatSemanticStyleSkeleton(
        _ skeleton: String,
        date: Date,
        localeData: CldrDateTimeData.LocaleData,
        timeZone: TimeZone,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Bool
    ) throws -> String? {
        guard skeleton.hasPrefix(semanticSkeletonPrefix) else {
            return nil
        }
        let body = String(skeleton.dropFirst(semanticSkeletonPrefix.count))
        let options = try parseSemanticSkeletonOptions(body)
        let fields = try parseSemanticSkeletonFields(options)
        try validateSemanticSkeleton(fields, options: options)
        if options.keys.contains(where: { !semanticDirectStyleOptionKeys.contains($0) }) {
            return nil
        }

        let length = try semanticOption(options, key: "length", fallback: "medium", allowedValues: ["full", "long", "medium", "short"])
        let timeStyle = try semanticOption(options, key: "timestyle", fallback: "auto", allowedValues: ["auto", "short", "medium", "long", "full"])
        let dateKey = semanticFieldSetKey(fields, order: semanticDateFieldOrder)
        let expectedDateKey = length == "full" ? "year,month,day,weekday" : "year,month,day"
        let hasDate = !dateKey.isEmpty
        let hasTime = fields.contains("time")
        let hasZone = fields.contains("zone")
        if !semanticFieldSetKey(fields, order: semanticTimeFieldOrder).isEmpty {
            return nil
        }
        if hasDate, dateKey != expectedDateKey {
            return nil
        }
        if hasTime, options["timestyle"] == nil {
            return nil
        }
        if !hasTime, hasZone || timeStyle != "auto" {
            return nil
        }
        if hasTime, hasZone != semanticTimeStyleHasZone(timeStyle) {
            return nil
        }
        let expectedFieldCount = (hasDate ? expectedDateKey.split(separator: ",").count : 0) + (hasTime ? 1 : 0) + (hasZone ? 1 : 0)
        if fields.count != expectedFieldCount {
            return nil
        }

        if hasDate, hasTime {
            let datePart = try formatPattern(localeData.dateFormats[length]!, date: date, localeData: localeData, timeZone: timeZone)
            let timePart = try formatTimeStylePattern(localeData.timeFormats[timeStyle]!, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle)
            let joinPattern = dateTimeStyleJoinPattern(localeData, style: length)
            return joinPattern.replacingOccurrences(of: "{1}", with: datePart).replacingOccurrences(of: "{0}", with: timePart)
        }
        if hasDate {
            return try formatPattern(localeData.dateFormats[length]!, date: date, localeData: localeData, timeZone: timeZone)
        }
        if hasTime {
            return try formatTimeStylePattern(localeData.timeFormats[timeStyle]!, date: date, localeData: localeData, timeZone: timeZone, hourCycle: hourCycle, preserveSameFamilyHourCycle: preserveSameFamilyHourCycle)
        }
        return nil
    }

    private static func formatTimeStylePattern(
        _ pattern: String,
        date: Date,
        localeData: CldrDateTimeData.LocaleData,
        timeZone: TimeZone,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Bool
    ) throws -> String {
        guard let hourCycle else {
            return try formatPattern(pattern, date: date, localeData: localeData, timeZone: timeZone)
        }
        let hourSymbol = preferredHourSymbol(localeData, hourCycle: hourCycle)
        if let patternHourSymbol = timeStylePatternHourSymbol(pattern),
           preserveSameFamilyHourCycle,
           isHour12Field(patternHourSymbol) == isHour12Field(hourSymbol) {
            return try formatPattern(replaceTimeStylePatternHourSymbol(pattern, hourSymbol: hourSymbol), date: date, localeData: localeData, timeZone: timeZone)
        }
        guard let skeleton = timeStylePatternSkeleton(pattern, localeData: localeData, hourCycle: hourCycle) else {
            return try formatPattern(pattern, date: date, localeData: localeData, timeZone: timeZone)
        }
        let canonical = try canonicalStandardSkeleton(skeleton, localeData: localeData, hourCycle: nil)
        return try formatPattern(skeletonPattern(canonical, localeData: localeData) ?? pattern, date: date, localeData: localeData, timeZone: timeZone)
    }

    private static func dateTimeStyleJoinPattern(
        _ localeData: CldrDateTimeData.LocaleData,
        style: String
    ) -> String {
        localeData.dateTimeStyleJoinFormats[style]
            ?? localeData.dateTimeFormats[style]
            ?? localeData.dateTimeFormats["medium"]
            ?? "{1} {0}"
    }

    private static func timeStylePatternHourSymbol(_ pattern: String) -> Character? {
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let symbol = pattern[index]
            if symbol == "'" {
                index = readQuotedPattern(pattern, start: index).nextIndex
            } else if symbol.isASCII && symbol.isLetter {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == symbol {
                    end = pattern.index(after: end)
                }
                if isHourField(symbol) {
                    return symbol
                }
                index = end
            } else {
                index = pattern.index(after: index)
            }
        }
        return nil
    }

    private static func replaceTimeStylePatternHourSymbol(_ pattern: String, hourSymbol: Character) -> String {
        var output = ""
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let symbol = pattern[index]
            if symbol == "'" {
                let quoted = readQuotedPattern(pattern, start: index)
                output += String(pattern[index..<quoted.nextIndex])
                index = quoted.nextIndex
            } else if symbol.isASCII && symbol.isLetter {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == symbol {
                    end = pattern.index(after: end)
                }
                if isHourField(symbol) {
                    output += String(repeating: String(hourSymbol), count: pattern.distance(from: index, to: end))
                } else {
                    output += String(pattern[index..<end])
                }
                index = end
            } else {
                output.append(symbol)
                index = pattern.index(after: index)
            }
        }
        return output
    }

    private static func timeStylePatternSkeleton(
        _ pattern: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String
    ) -> String? {
        var widths: [Character: Int] = [:]
        let hourSymbol = preferredHourSymbol(localeData, hourCycle: hourCycle)
        var hasHour = false
        for (symbol, width) in patternFieldRuns(pattern) {
            if isHourField(symbol) {
                setSkeletonWidth(&widths, symbol: hourSymbol, width: width)
                hasHour = true
            } else if !isDayPeriodField(symbol), skeletonTimeFields.contains(symbol) {
                setSkeletonWidth(&widths, symbol: symbol, width: width)
            }
        }
        guard hasHour else {
            return nil
        }
        var skeleton = ""
        for symbol in skeletonFieldOrder {
            if let width = widths[symbol] {
                skeleton += String(repeating: String(symbol), count: width)
            }
        }
        return skeleton
    }

    private static func skeletonPattern(
        _ canonical: String,
        localeData: CldrDateTimeData.LocaleData
    ) -> String? {
        if let pattern = skeletonPatternWithoutAppend(canonical, localeData: localeData) {
            return pattern
        }
        return hasDateAndTimeFields(canonical) ? nil : appendedSkeletonPattern(canonical, localeData: localeData)
    }

    private static func skeletonPatternWithoutAppend(
        _ canonical: String,
        localeData: CldrDateTimeData.LocaleData
    ) -> String? {
        if let direct = localeData.availableFormats[canonical] {
            return direct
        }
        let requestedFields = skeletonFieldSet(canonical)
        var bestCandidate: String?
        var bestPattern: String?
        var bestDistance = Int.max
        for (candidate, pattern) in localeData.availableFormats {
            if skeletonFieldSet(candidate) != requestedFields {
                continue
            }
            let distance = skeletonDistance(requested: canonical, candidate: candidate)
            if distance < bestDistance || (distance == bestDistance && (bestCandidate == nil || candidate < bestCandidate!)) {
                bestCandidate = candidate
                bestPattern = pattern
                bestDistance = distance
            }
        }
        guard let bestPattern, let bestCandidate else {
            return syntheticSkeletonPattern(canonical, localeData: localeData)
        }
        return adjustPatternWidths(bestPattern, requestedSkeleton: canonical, candidateSkeleton: bestCandidate)
    }

    private static func appendedSkeletonPattern(
        _ canonical: String,
        localeData: CldrDateTimeData.LocaleData
    ) -> String? {
        let requestedFields = skeletonFieldSet(canonical)
        var bestCandidate: String?
        var bestPattern: String?
        var bestFieldCount = -1
        var bestDistance = Int.max
        for (candidate, pattern) in localeData.availableFormats {
            let candidateFields = skeletonFieldSet(candidate)
            if candidateFields.isEmpty || candidateFields == requestedFields {
                continue
            }
            if !fieldSetContains(container: requestedFields, subset: candidateFields) {
                continue
            }
            let fieldCount = candidateFields.count
            let distance = skeletonDistance(requested: canonical, candidate: candidate)
            if fieldCount > bestFieldCount ||
                (fieldCount == bestFieldCount && (distance < bestDistance || (distance == bestDistance && (bestCandidate == nil || candidate < bestCandidate!))))
            {
                bestCandidate = candidate
                bestPattern = pattern
                bestFieldCount = fieldCount
                bestDistance = distance
            }
        }
        guard let bestPattern, let bestCandidate else {
            return nil
        }
        var output = adjustPatternWidths(bestPattern, requestedSkeleton: canonical, candidateSkeleton: bestCandidate)
        var currentFields = Set(bestCandidate.map { fieldSetSymbol($0) })
        let requestedWidths = skeletonWidths(canonical)
        for symbol in skeletonFieldOrder {
            guard let width = requestedWidths[symbol] else {
                continue
            }
            let field = fieldSetSymbol(symbol)
            if currentFields.contains(field) {
                continue
            }
            guard let key = appendItemKey(symbol) else {
                return nil
            }
            let fieldSkeleton = String(repeating: String(symbol), count: width)
            let fieldPattern = skeletonPatternWithoutAppend(fieldSkeleton, localeData: localeData) ?? fieldSkeleton
            output = applyAppendItemPattern(
                appendItemTemplate(localeData, key: key),
                basePattern: output,
                fieldPattern: fieldPattern,
                fieldName: localeData.fieldNames[key] ?? key
            )
            currentFields.insert(field)
        }
        return output
    }

    private static func fieldSetContains(container: String, subset: String) -> Bool {
        subset.allSatisfy { container.contains($0) }
    }

    private static func applyAppendItemPattern(
        _ template: String,
        basePattern: String,
        fieldPattern: String,
        fieldName: String
    ) -> String {
        template
            .replacingOccurrences(of: "{0}", with: basePattern)
            .replacingOccurrences(of: "{1}", with: fieldPattern)
            .replacingOccurrences(of: "{2}", with: quotePatternLiteral(fieldName))
    }

    private static func quotePatternLiteral(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "''") + "'"
    }

    private static func appendItemTemplate(
        _ localeData: CldrDateTimeData.LocaleData,
        key: String
    ) -> String {
        localeData.appendItems[key] ?? defaultAppendItemTemplate(key)
    }

    private static func defaultAppendItemTemplate(_ key: String) -> String {
        switch key {
        case "Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second":
            return "{0} ({2}: {1})"
        default:
            return "{0} {1}"
        }
    }

    private static func hasDateAndTimeFields(_ canonical: String) -> Bool {
        let (dateSkeleton, timeSkeleton) = splitDateTimeSkeleton(canonical)
        return !dateSkeleton.isEmpty && !timeSkeleton.isEmpty
    }

    private static func appendItemKey(_ symbol: Character) -> String? {
        if symbol == "G" {
            return "Era"
        }
        if isYearField(symbol) {
            return "Year"
        }
        if isQuarterField(symbol) {
            return "Quarter"
        }
        if isMonthField(symbol) {
            return "Month"
        }
        if symbol == "w" || symbol == "W" {
            return "Week"
        }
        if symbol == "d" || symbol == "D" || symbol == "F" || symbol == "g" {
            return "Day"
        }
        if isWeekdayField(symbol) {
            return "Day-Of-Week"
        }
        if isHourField(symbol) {
            return "Hour"
        }
        if symbol == "m" {
            return "Minute"
        }
        if symbol == "s" || symbol == "S" || symbol == "A" {
            return "Second"
        }
        if isTimeZoneField(symbol) {
            return "Timezone"
        }
        return nil
    }

    private static func syntheticSkeletonPattern(
        _ canonical: String,
        localeData: CldrDateTimeData.LocaleData
    ) -> String? {
        let widths = skeletonWidths(canonical)
        if widths.count == 1, let (symbol, width) = widths.first, symbol == "G" {
            return String(repeating: String(symbol), count: width)
        }
        if widths.count == 1, let (symbol, width) = widths.first, isDayPeriodField(symbol) {
            return String(repeating: String(symbol), count: width)
        }
        if widths.count == 1, let (symbol, width) = widths.first, isQuarterField(symbol) {
            return String(repeating: String(symbol), count: width)
        }
        if widths.count == 1, let (symbol, width) = widths.first, isSyntheticNumericField(symbol) {
            return String(repeating: String(symbol), count: width)
        }
        if widths.count == 1, let (symbol, width) = widths.first, symbol == "S" {
            return String(repeating: String(symbol), count: width)
        }
        if widths.count == 1, let (symbol, width) = widths.first, isTimeZoneField(symbol) {
            return String(repeating: String(symbol), count: width)
        }
        if let fractionalSecond = syntheticFractionalSecondPattern(canonical, localeData: localeData, widths: widths) {
            return fractionalSecond
        }
        return nil
    }

    private static func syntheticFractionalSecondPattern(
        _ canonical: String,
        localeData: CldrDateTimeData.LocaleData,
        widths: [Character: Int]
    ) -> String? {
        guard let fractionWidth = widths["S"], widths["s"] != nil else {
            return nil
        }
        let baseSkeleton = skeletonWithoutField(canonical, removedSymbol: "S")
        let basePattern = skeletonPattern(baseSkeleton, localeData: localeData) ?? syntheticSecondsPattern(baseSkeleton)
        guard let basePattern else {
            return nil
        }
        return insertFractionalSecond(basePattern, width: fractionWidth, decimalSeparator: localeData.decimalSeparator)
    }

    private static func syntheticSecondsPattern(_ canonical: String) -> String? {
        let widths = skeletonWidths(canonical)
        guard widths.count == 1, let width = widths["s"] else {
            return nil
        }
        return String(repeating: "s", count: width)
    }

    private static func skeletonWithoutField(_ skeleton: String, removedSymbol: Character) -> String {
        var output = ""
        var index = skeleton.startIndex
        while index < skeleton.endIndex {
            let symbol = skeleton[index]
            var end = skeleton.index(after: index)
            while end < skeleton.endIndex, skeleton[end] == symbol {
                end = skeleton.index(after: end)
            }
            if symbol != removedSymbol {
                output += String(skeleton[index..<end])
            }
            index = end
        }
        return output
    }

    private static func insertFractionalSecond(_ pattern: String, width: Int, decimalSeparator: String) -> String? {
        var output = ""
        var inQuote = false
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let ch = pattern[index]
            if ch == "'" {
                output.append(ch)
                let next = pattern.index(after: index)
                if next < pattern.endIndex, pattern[next] == "'" {
                    output.append("'")
                    index = pattern.index(after: next)
                } else {
                    inQuote.toggle()
                    index = next
                }
            } else if !inQuote, ch == "s" {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == ch {
                    end = pattern.index(after: end)
                }
                output += String(pattern[index..<end])
                output += decimalSeparator
                output += String(repeating: "S", count: width)
                output += String(pattern[end...])
                return output
            } else {
                output.append(ch)
                index = pattern.index(after: index)
            }
        }
        return nil
    }

    private static func formatComposedSkeleton(
        _ rawSkeleton: String,
        canonical: String,
        date: Date,
        localeData: CldrDateTimeData.LocaleData,
        timeZone: TimeZone,
        suppressDayPeriod: Bool,
        dateTimeJoinStyle: String
    ) throws -> String {
        let (dateSkeleton, timeSkeleton) = splitDateTimeSkeleton(canonical)
        guard !dateSkeleton.isEmpty, !timeSkeleton.isEmpty else {
            throw MF2Error.badOption("Unsupported CLDR date/time skeleton: \(rawSkeleton).")
        }
        guard let datePattern = skeletonPattern(dateSkeleton, localeData: localeData) else {
            throw MF2Error.badOption("Unsupported CLDR date/time skeleton: \(rawSkeleton).")
        }
        guard var timePattern = skeletonPattern(timeSkeleton, localeData: localeData) else {
            throw MF2Error.badOption("Unsupported CLDR date/time skeleton: \(rawSkeleton).")
        }
        if suppressDayPeriod {
            timePattern = stripDayPeriodPatternFields(timePattern)
        }
        let datePart = try formatPattern(datePattern, date: date, localeData: localeData, timeZone: timeZone)
        let timePart = try formatPattern(timePattern, date: date, localeData: localeData, timeZone: timeZone)
        return (localeData.dateTimeFormats[dateTimeJoinStyle] ?? localeData.dateTimeFormats["medium"] ?? "{1} {0}")
            .replacingOccurrences(of: "{1}", with: datePart)
            .replacingOccurrences(of: "{0}", with: timePart)
    }

    private static func canonicalSkeleton(
        _ skeleton: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        date: Date,
        timeZone: TimeZone
    ) throws -> String {
        if skeleton.hasPrefix(semanticSkeletonPrefix) {
            let body = String(skeleton.dropFirst(semanticSkeletonPrefix.count))
            let standard = try semanticSkeletonToStandard(body, localeData: localeData, date: date, timeZone: timeZone)
            return try canonicalStandardSkeleton(standard, localeData: localeData, hourCycle: hourCycle)
        }
        return try canonicalStandardSkeleton(skeleton, localeData: localeData, hourCycle: hourCycle)
    }

    private static func canonicalStandardSkeleton(
        _ skeleton: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?
    ) throws -> String {
        var widths: [Character: Int] = [:]
        var index = skeleton.startIndex
        while index < skeleton.endIndex {
            let symbol = skeleton[index]
            guard symbol.isASCII, symbol.isLetter else {
                throw MF2Error.badOption("Date/time skeleton must contain only ASCII pattern letters.")
            }
            var end = skeleton.index(after: index)
            while end < skeleton.endIndex, skeleton[end] == symbol {
                end = skeleton.index(after: end)
            }
            let width = skeleton.distance(from: index, to: end)
            if width > maxSkeletonFieldWidth {
                throw MF2Error.badOption("Date/time skeleton field width is too large.")
            }
            if symbol == "C" {
                applyCHourFormat(&widths, localeData: localeData, hourCycle: hourCycle, width: width)
            } else {
                let normalized = normalizeSkeletonSymbol(symbol, localeData: localeData, hourCycle: hourCycle)
                setSkeletonWidth(&widths, symbol: normalized, width: width)
            }
            index = end
        }
        var canonical = ""
        for symbol in skeletonFieldOrder {
            canonical += String(repeating: String(symbol), count: widths[symbol] ?? 0)
        }
        guard !canonical.isEmpty else {
            throw MF2Error.badOption("Date/time skeleton must not be empty.")
        }
        return canonical
    }

    private static func semanticSkeletonToStandard(
        _ body: String,
        localeData: CldrDateTimeData.LocaleData,
        date: Date,
        timeZone: TimeZone
    ) throws -> String {
        let options = try parseSemanticSkeletonOptions(body)
        let fields = try parseSemanticSkeletonFields(options)
        try validateSemanticSkeleton(fields, options: options)
        let length = try semanticOption(options, key: "length", fallback: "medium", allowedValues: ["full", "long", "medium", "short"])
        let alignment = try semanticOption(options, key: "alignment", fallback: "inline", allowedValues: ["inline", "column"])
        let yearStyle = try semanticOption(options, key: "yearstyle", fallback: "auto", allowedValues: ["auto", "full", "with-era", "numeric", "2-digit"])
        let eraStyle = try semanticOption(options, key: "erastyle", fallback: "auto", allowedValues: semanticTextStyleValues)
        let monthStyle = try semanticOption(options, key: "monthstyle", fallback: "auto", allowedValues: semanticDateStyleValues)
        let quarterStyle = try semanticOption(options, key: "quarterstyle", fallback: "auto", allowedValues: semanticDateStyleValues)
        let dayStyle = try semanticOption(options, key: "daystyle", fallback: "auto", allowedValues: semanticNumericStyleValues)
        let weekdayStyle = try semanticOption(options, key: "weekdaystyle", fallback: "auto", allowedValues: semanticTextStyleValues)
        let dayPeriodStyle = try semanticOption(options, key: "dayperiodstyle", fallback: "auto", allowedValues: semanticTextStyleValues)
        _ = try semanticOption(options, key: "hourstyle", fallback: "auto", allowedValues: semanticNumericStyleValues)
        _ = try semanticOption(options, key: "minutestyle", fallback: "auto", allowedValues: semanticNumericStyleValues)
        _ = try semanticOption(options, key: "secondstyle", fallback: "auto", allowedValues: semanticNumericStyleValues)
        let timePrecision = try semanticOption(options, key: "timeprecision", fallback: "second", allowedValues: ["hour", "minute", "minute-optional", "second", "fractional-second"])
        let timeStyle = try semanticOption(options, key: "timestyle", fallback: "auto", allowedValues: ["auto", "short", "medium", "long", "full"])
        let effectiveTimePrecision = semanticTimeStylePrecision(timeStyle, timePrecision: timePrecision)
        let semanticHourCycle = try semanticOption(options, key: "hourcycle", fallback: "auto", allowedValues: ["auto", "h11", "h12", "h23", "h24", "clock12", "clock24"])
        let zoneStyle = try semanticOption(options, key: "zonestyle", fallback: "auto", allowedValues: ["auto", "generic", "specific", "location", "offset"])
        let effectiveZoneStyle = semanticTimeStyleZoneStyle(timeStyle, zoneStyle: zoneStyle)
        let effectiveZoneStandalone = fields.count == 1 || timeStyle == "full"
        let effectiveZoneLength = semanticTimeStyleHasZone(timeStyle) ? timeStyle : length
        let dateWidths = semanticDateFieldWidths(localeData, length: length)
        var standard = ""
        if fields.contains("era") {
            standard += semanticEraSkeleton(dateWidths, length: length, eraStyle: eraStyle)
        }
        if fields.contains("year") {
            standard += semanticYearSkeleton(dateWidths, yearStyle: yearStyle, includeEra: !fields.contains("era"))
        }
        if fields.contains("quarter") {
            standard += semanticQuarterSkeleton(fields, length: length, alignment: alignment, quarterStyle: quarterStyle)
        }
        if fields.contains("month") {
            standard += semanticMonthSkeleton(fields, dateWidths: dateWidths, length: length, alignment: alignment, monthStyle: monthStyle)
        }
        if fields.contains("weekofmonth") {
            standard += "W"
        }
        if fields.contains("day") {
            standard += semanticDaySkeleton(dateWidths, alignment: alignment, dayStyle: dayStyle)
        }
        if fields.contains("dayofyear") {
            standard += String(repeating: "D", count: alignment == "column" ? 3 : 1)
        }
        if fields.contains("dayofweekinmonth") {
            standard += String(repeating: "F", count: alignment == "column" ? 2 : 1)
        }
        if fields.contains("modifiedjulianday") {
            standard += String(repeating: "g", count: alignment == "column" ? 6 : 1)
        }
        if fields.contains("weekday") {
            standard += semanticWeekdaySkeleton(fields, length: length, weekdayStyle: weekdayStyle)
        }
        if fields.contains("weekofyear") {
            standard += alignment == "column" ? "ww" : "w"
        }
        if fields.contains("dayperiod") {
            standard += semanticDayPeriodSkeleton(length, dayPeriodStyle: dayPeriodStyle)
        }
        if hasSemanticTimeComponents(fields) {
            standard += try semanticExplicitTimeSkeleton(fields, hourCycle: semanticHourCycle, alignment: alignment, options: options)
        }
        if fields.contains("time") {
            standard += try semanticTimeSkeleton(effectiveTimePrecision, hourCycle: semanticHourCycle, alignment: alignment, date: date, timeZone: timeZone, options: options)
        }
        if fields.contains("zone") {
            standard += semanticZoneSkeleton(effectiveZoneStyle, standalone: effectiveZoneStandalone, length: effectiveZoneLength)
        }
        guard !standard.isEmpty else {
            throw MF2Error.badOption("Date/time semantic skeleton must include at least one field.")
        }
        return standard
    }

    private static func parseSemanticSkeletonOptions(_ body: String) throws -> [String: String] {
        var options: [String: String] = [:]
        var seenParts = 0
        var implicitDateStyle: String?
        var implicitTimeFields = false
        for rawPart in body.split(separator: ";", omittingEmptySubsequences: false) {
            let part = rawPart.trimmingCharacters(in: .whitespacesAndNewlines)
            if part.isEmpty {
                continue
            }
            let equals = part.firstIndex(of: "=")
            let rawKey: String
            let rawValue: String
            if let equals {
                rawKey = String(part[..<equals])
                rawValue = String(part[part.index(after: equals)...])
            } else {
                rawKey = seenParts == 0 ? "fields" : ""
                rawValue = part
            }
            let rawKeyAlias = semanticNormalize(rawKey)
            let key = semanticNormalizeOptionKey(rawKey)
            let value = semanticNormalizeOptionValue(key: key, value: rawValue)
            if key.isEmpty || value.isEmpty || !semanticOptionKeys.contains(key) || options[key] != nil {
                throw MF2Error.badOption("Invalid date/time semantic skeleton option.")
            }
            if rawKeyAlias == "style" || rawKeyAlias == "datestyle" || rawKeyAlias == "datelength" {
                implicitDateStyle = value
            }
            if rawKeyAlias == "timestyle" {
                implicitTimeFields = true
            }
            options[key] = value
            seenParts += 1
        }
        guard seenParts > 0 else {
            throw MF2Error.badOption("Date/time semantic skeleton must include fields.")
        }
        if options["fields"] == nil {
            let fields = implicitSemanticFields(dateStyle: implicitDateStyle, hasTimeStyle: implicitTimeFields, timeStyle: options["timestyle"])
            if !fields.isEmpty {
                options["fields"] = fields
            }
        }
        return options
    }

    private static func implicitSemanticFields(dateStyle: String?, hasTimeStyle: Bool, timeStyle: String?) -> String {
        let dateFields = dateStyle == "full" ? "date,weekday" : "date"
        if dateStyle != nil && hasTimeStyle {
            return timeStyle == "long" || timeStyle == "full" ? "\(dateFields),time,zone" : "\(dateFields),time"
        }
        if dateStyle != nil {
            return dateFields
        }
        if hasTimeStyle {
            return timeStyle == "long" || timeStyle == "full" ? "time,zone" : "time"
        }
        return ""
    }

    private static func semanticNormalizeOptionKey(_ value: String) -> String {
        let normalized = semanticNormalize(value)
        if normalized == "style" || normalized == "datestyle" || normalized == "datelength" {
            return "length"
        }
        if normalized == "precision" {
            return "timeprecision"
        }
        if normalized == "timestyle" {
            return "timestyle"
        }
        if normalized == "hour12" {
            return "hourcycle"
        }
        if normalized == "zone" || normalized == "timezonename" || normalized == "timezonestyle" {
            return "zonestyle"
        }
        if normalized == "fractionalseconddigits" {
            return "fractionalsecond"
        }
        switch normalized {
        case "era":
            return "erastyle"
        case "year":
            return "yearstyle"
        case "month":
            return "monthstyle"
        case "quarter":
            return "quarterstyle"
        case "day":
            return "daystyle"
        case "weekday":
            return "weekdaystyle"
        case "dayperiod":
            return "dayperiodstyle"
        case "hour":
            return "hourstyle"
        case "minute":
            return "minutestyle"
        case "second":
            return "secondstyle"
        default:
            return normalized
        }
    }

    private static func semanticNormalizeOptionValue(key: String, value: String) -> String {
        if key == "fields" {
            return value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }
        let normalized = semanticNormalize(value)
        if key == "yearstyle", normalized == "withera" {
            return "with-era"
        }
        if semanticStyleOptionKeys.contains(key), normalized == "2digit" || normalized == "twodigit" {
            return "2-digit"
        }
        if semanticStyleOptionKeys.contains(key), normalized == "wide" {
            return "long"
        }
        if semanticStyleOptionKeys.contains(key), normalized == "abbreviated" {
            return "short"
        }
        if key == "timeprecision", normalized == "short" {
            return "minute"
        }
        if key == "timeprecision", normalized == "medium" {
            return "second"
        }
        if key == "timeprecision", normalized == "minuteoptional" {
            return "minute-optional"
        }
        if key == "timeprecision", normalized == "fractionalsecond" {
            return "fractional-second"
        }
        if key == "zonestyle", normalized == "shortoffset" || normalized == "longoffset" {
            return "offset"
        }
        if key == "zonestyle", normalized == "shortgeneric" || normalized == "longgeneric" {
            return "generic"
        }
        if key == "zonestyle", normalized == "short" || normalized == "long" {
            return "specific"
        }
        if key == "hourcycle", normalized == "true" {
            return "clock12"
        }
        if key == "hourcycle", normalized == "false" {
            return "clock24"
        }
        return normalized
    }

    private static func parseSemanticSkeletonFields(_ options: [String: String]) throws -> [String] {
        guard let fieldsText = options["fields"] else {
            throw MF2Error.badOption("Date/time semantic skeleton must include fields.")
        }
        var fields: [String] = []
        for field in fieldsText.split(separator: ",", omittingEmptySubsequences: false) {
            let normalized = semanticNormalizeField(String(field))
            let canonicalFields: [String]
            if normalized == "date" || normalized == "yearmonthday" {
                canonicalFields = ["year", "month", "day"]
            } else if normalized == "eradate" || normalized == "erayearmonthday" {
                canonicalFields = ["era", "year", "month", "day"]
            } else if normalized == "eradateweekday" || normalized == "weekdayeradate" || normalized == "erayearmonthdayweekday" || normalized == "weekdayerayearmonthday" {
                canonicalFields = ["era", "year", "month", "day", "weekday"]
            } else if normalized == "eradatetime" || normalized == "erayearmonthdaytime" {
                canonicalFields = ["era", "year", "month", "day", "time"]
            } else if normalized == "eradatetimeweekday" || normalized == "weekdayeradatetime" || normalized == "erayearmonthdaytimeweekday" || normalized == "weekdayerayearmonthdaytime" {
                canonicalFields = ["era", "year", "month", "day", "weekday", "time"]
            } else if normalized == "datetime" || normalized == "yearmonthdaytime" {
                canonicalFields = ["year", "month", "day", "time"]
            } else if normalized == "datetimeweekday" || normalized == "weekdaydatetime" || normalized == "yearmonthdaytimeweekday" || normalized == "weekdayyearmonthdaytime" {
                canonicalFields = ["year", "month", "day", "weekday", "time"]
            } else if normalized == "datetimeweekdayzone" || normalized == "weekdaydatetimezone" || normalized == "zoneddatetimeweekday" || normalized == "zonedweekdaydatetime" || normalized == "yearmonthdaytimeweekdayzone" || normalized == "weekdayyearmonthdaytimezone" || normalized == "zonedyearmonthdaytimeweekday" || normalized == "zonedweekdayyearmonthdaytime" {
                canonicalFields = ["year", "month", "day", "weekday", "time", "zone"]
            } else if normalized == "eradatetimezone" || normalized == "zonederadatetime" || normalized == "erayearmonthdaytimezone" || normalized == "zonederayearmonthdaytime" {
                canonicalFields = ["era", "year", "month", "day", "time", "zone"]
            } else if normalized == "eradatetimeweekdayzone" || normalized == "weekdayeradatetimezone" || normalized == "zonederadatetimeweekday" || normalized == "zonedweekdayeradatetime" || normalized == "erayearmonthdaytimeweekdayzone" || normalized == "weekdayerayearmonthdaytimezone" || normalized == "zonederayearmonthdaytimeweekday" || normalized == "zonedweekdayerayearmonthdaytime" {
                canonicalFields = ["era", "year", "month", "day", "weekday", "time", "zone"]
            } else if normalized == "dateweekday" || normalized == "weekdaydate" || normalized == "yearmonthdayweekday" || normalized == "weekdayyearmonthday" {
                canonicalFields = ["year", "month", "day", "weekday"]
            } else if normalized == "datetimezone" || normalized == "zoneddatetime" || normalized == "yearmonthdaytimezone" || normalized == "zonedyearmonthdaytime" {
                canonicalFields = ["year", "month", "day", "time", "zone"]
            } else if normalized == "yearmonth" {
                canonicalFields = ["year", "month"]
            } else if normalized == "erayearmonth" {
                canonicalFields = ["era", "year", "month"]
            } else if normalized == "yearquarter" {
                canonicalFields = ["year", "quarter"]
            } else if normalized == "erayearquarter" {
                canonicalFields = ["era", "year", "quarter"]
            } else if normalized == "yearweek" {
                canonicalFields = ["year", "weekofyear"]
            } else if normalized == "erayearweek" {
                canonicalFields = ["era", "year", "weekofyear"]
            } else if normalized == "erayear" {
                canonicalFields = ["era", "year"]
            } else if normalized == "monthweek" {
                canonicalFields = ["month", "weekofmonth"]
            } else if normalized == "yearmonthweek" {
                canonicalFields = ["year", "month", "weekofmonth"]
            } else if normalized == "erayearmonthweek" {
                canonicalFields = ["era", "year", "month", "weekofmonth"]
            } else if normalized == "monthday" {
                canonicalFields = ["month", "day"]
            } else {
                canonicalFields = [normalized]
            }
            for canonical in canonicalFields {
                if !semanticFieldOrder.contains(canonical) || fields.contains(canonical) {
                    throw MF2Error.badOption("Invalid date/time semantic skeleton field.")
                }
                fields.append(canonical)
            }
        }
        guard !fields.isEmpty else {
            throw MF2Error.badOption("Date/time semantic skeleton must include fields.")
        }
        return fields
    }

    private static func semanticNormalizeField(_ value: String) -> String {
        let normalized = semanticNormalize(value)
        if normalized == "dayofmonth" {
            return "day"
        }
        if normalized == "dayofweek" {
            return "weekday"
        }
        if normalized == "monthofyear" {
            return "month"
        }
        if normalized == "quarterofyear" {
            return "quarter"
        }
        if normalized == "yearofera" {
            return "year"
        }
        if normalized == "week" {
            return "weekofyear"
        }
        if normalized == "weekofyear" {
            return "weekofyear"
        }
        if normalized == "weekofmonth" {
            return "weekofmonth"
        }
        if normalized == "dayofyear" {
            return "dayofyear"
        }
        if normalized == "dayofweekinmonth" {
            return "dayofweekinmonth"
        }
        if normalized == "modifiedjulianday" {
            return "modifiedjulianday"
        }
        if normalized == "millisecondsinday" {
            return "millisecondsinday"
        }
        if normalized == "fractionalseconddigits" {
            return "fractionalsecond"
        }
        if normalized == "dayperiod" {
            return "dayperiod"
        }
        if normalized == "hourofday" {
            return "hour"
        }
        if normalized == "minuteofhour" {
            return "minute"
        }
        if normalized == "secondofminute" {
            return "second"
        }
        if normalized == "timezonename" {
            return "zone"
        }
        if normalized == "timezone" {
            return "zone"
        }
        return normalized
    }

    private static func validateSemanticSkeleton(_ fields: [String], options: [String: String]) throws {
        let dateKey = semanticFieldSetKey(fields, order: semanticDateFieldOrder)
        let timeKey = semanticFieldSetKey(fields, order: semanticTimeFieldOrder)
        let hasDateFields = !dateKey.isEmpty
        let hasExplicitTime = !timeKey.isEmpty
        let hasTime = fields.contains("time") || hasExplicitTime
        let hasZone = fields.contains("zone")
        let hasDayPeriod = fields.contains("dayperiod")
        let validDateFields = if hasTime || hasZone {
            !hasDateFields || semanticDateFieldSets.contains(dateKey)
        } else {
            !hasDateFields || semanticDateFieldSets.contains(dateKey) || semanticCalendarPeriodFieldSets.contains(dateKey)
        }
        let validFieldSet = if hasDayPeriod {
            validDateFields && (hasTime || !hasZone)
        } else if hasTime || hasZone {
            !hasDateFields || semanticDateFieldSets.contains(dateKey)
        } else {
            semanticDateFieldSets.contains(dateKey) || semanticCalendarPeriodFieldSets.contains(dateKey)
        }
        guard validFieldSet else {
            throw MF2Error.badOption("Invalid date/time semantic skeleton field set.")
        }
        if fields.contains("time"), hasExplicitTime {
            throw MF2Error.badOption("time field cannot be combined with explicit time component fields.")
        }
        if options["timestyle"] != nil, options["timeprecision"] != nil {
            throw MF2Error.badOption("timeStyle cannot be combined with timePrecision.")
        }
        let timeStyle = options["timestyle"]
        if options["timestyle"] != nil, !fields.contains("time") {
            throw MF2Error.badOption("timeStyle requires the time field.")
        }
        if semanticTimeStyleHasZone(timeStyle), !hasZone {
            throw MF2Error.badOption("timeStyle=long/full requires the zone field.")
        }
        if semanticTimeStyleHasZone(timeStyle), options["zonestyle"] != nil {
            throw MF2Error.badOption("timeStyle=long/full cannot be combined with zoneStyle.")
        }
        if hasExplicitTime, !semanticTimeFieldSets.contains(timeKey) {
            throw MF2Error.badOption("Invalid date/time semantic skeleton time field set.")
        }
        if hasExplicitTime, options["timeprecision"] != nil {
            throw MF2Error.badOption("timePrecision requires the time field.")
        }
        if hasExplicitTime, options["fractionalsecond"] != nil, !fields.contains("fractionalsecond") {
            throw MF2Error.badOption("fractionalSecond requires the fractionalSecond field.")
        }
        if fields.contains("fractionalsecond") {
            _ = try semanticFractionalSecondWidth(options)
        }
        if hasExplicitTime, !fields.contains("hour"), (options["hourcycle"] != nil || hasDayPeriod) {
            throw MF2Error.badOption("hourCycle and dayPeriod require the hour field.")
        }
        if !fields.contains("hour"), options["hourstyle"] != nil {
            throw MF2Error.badOption("hourStyle requires the hour field.")
        }
        if !fields.contains("minute"), options["minutestyle"] != nil {
            throw MF2Error.badOption("minuteStyle requires the minute field.")
        }
        if !fields.contains("second"), options["secondstyle"] != nil {
            throw MF2Error.badOption("secondStyle requires the second field.")
        }
        if !fields.contains("year"), options["yearstyle"] != nil {
            throw MF2Error.badOption("yearStyle requires the year field.")
        }
        if !fields.contains("era"), options["erastyle"] != nil {
            throw MF2Error.badOption("eraStyle requires the era field.")
        }
        if !fields.contains("month"), options["monthstyle"] != nil {
            throw MF2Error.badOption("monthStyle requires the month field.")
        }
        if !fields.contains("quarter"), options["quarterstyle"] != nil {
            throw MF2Error.badOption("quarterStyle requires the quarter field.")
        }
        if !fields.contains("day"), options["daystyle"] != nil {
            throw MF2Error.badOption("dayStyle requires the day field.")
        }
        if !fields.contains("weekday"), options["weekdaystyle"] != nil {
            throw MF2Error.badOption("weekdayStyle requires the weekday field.")
        }
        if !hasDayPeriod, options["dayperiodstyle"] != nil {
            throw MF2Error.badOption("dayPeriodStyle requires the dayPeriod field.")
        }
        if !hasTime, options["timeprecision"] != nil || options["timestyle"] != nil || options["fractionalsecond"] != nil || options["hourcycle"] != nil {
            throw MF2Error.badOption("timePrecision and hourCycle require the time field.")
        }
        if !hasZone, options["zonestyle"] != nil {
            throw MF2Error.badOption("zoneStyle requires the zone field.")
        }
        if !(fields.contains("year") || fields.contains("quarter") || fields.contains("month") || fields.contains("day") || fields.contains("dayofyear") || fields.contains("dayofweekinmonth") || fields.contains("modifiedjulianday") || hasTime), options["alignment"] != nil {
            throw MF2Error.badOption("alignment requires a date or time field.")
        }
    }

    private static func semanticOption(
        _ options: [String: String],
        key: String,
        fallback: String,
        allowedValues: Set<String>
    ) throws -> String {
        let value = options[key] ?? fallback
        guard allowedValues.contains(value) else {
            throw MF2Error.badOption("Date/time semantic skeleton \(key) must be one of \(allowedValues.sorted().joined(separator: ", ")).")
        }
        return value
    }

    private static func semanticNormalize(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .filter { $0 != "-" && $0 != "_" }
            .lowercased()
    }

    private static func semanticFieldSetKey(_ fields: [String], order: [String]) -> String {
        order.filter { fields.contains($0) }.joined(separator: ",")
    }

    private static func semanticDateFieldWidths(
        _ localeData: CldrDateTimeData.LocaleData,
        length: String
    ) -> [Character: Int] {
        var widths: [Character: Int] = [:]
        for (symbol, width) in patternFieldRuns(localeData.dateFormats[length] ?? "") {
            if symbol == "G" || isYearField(symbol) || isMonthField(symbol) || symbol == "d" {
                setSkeletonWidth(&widths, symbol: symbol, width: width)
            }
        }
        if !widths.keys.contains(where: isYearField) {
            setSkeletonWidth(&widths, symbol: "y", width: length == "short" ? 2 : 1)
        }
        if !widths.keys.contains(where: isMonthField) {
            setSkeletonWidth(&widths, symbol: "M", width: isWideLength(length) ? 4 : length == "medium" ? 3 : 1)
        }
        widths["d"] = widths["d"] ?? 1
        return widths
    }

    private static func patternFieldRuns(_ pattern: String) -> [Character: Int] {
        let symbols = Array(pattern)
        var fields: [Character: Int] = [:]
        var inQuote = false
        var index = 0
        while index < symbols.count {
            let symbol = symbols[index]
            if symbol == "'" {
                if index + 1 < symbols.count, symbols[index + 1] == "'" {
                    index += 2
                } else {
                    inQuote.toggle()
                    index += 1
                }
            } else if !inQuote, symbol.isASCII, symbol.isLetter {
                var end = index + 1
                while end < symbols.count, symbols[end] == symbol {
                    end += 1
                }
                setSkeletonWidth(&fields, symbol: symbol, width: end - index)
                index = end
            } else {
                index += 1
            }
        }
        return fields
    }

    private static func semanticEraSkeleton(_ dateWidths: [Character: Int], length: String, eraStyle: String) -> String {
        let width = eraStyle == "auto" ? dateWidths["G"] ?? (isWideLength(length) ? 4 : 1) : eraStyleWidth(eraStyle)
        return String(repeating: "G", count: width)
    }

    private static func eraStyleWidth(_ style: String) -> Int {
        style == "long" ? 4 : style == "narrow" ? 5 : 1
    }

    private static func semanticYearSkeleton(_ dateWidths: [Character: Int], yearStyle: String, includeEra: Bool = true) -> String {
        let yearSymbol: Character = dateWidths["y"] != nil ? "y" : dateWidths["u"] != nil ? "u" : dateWidths["r"] != nil ? "r" : "y"
        let sourceWidth = dateWidths[yearSymbol] ?? 1
        let yearWidth = semanticYearWidth(sourceWidth, yearStyle: yearStyle)
        var skeleton = ""
        if includeEra, let eraWidth = dateWidths["G"] {
            skeleton += String(repeating: "G", count: eraWidth)
        }
        if includeEra, yearStyle == "with-era", dateWidths["G"] == nil {
            skeleton += "G"
        }
        skeleton += String(repeating: String(yearSymbol), count: yearWidth)
        return skeleton
    }

    private static func semanticYearWidth(_ sourceWidth: Int, yearStyle: String) -> Int {
        if yearStyle == "auto" {
            return sourceWidth
        }
        if yearStyle == "2-digit" {
            return 2
        }
        if yearStyle == "numeric" {
            return 1
        }
        return sourceWidth == 2 ? 1 : sourceWidth
    }

    private static func semanticQuarterSkeleton(
        _ fields: [String],
        length: String,
        alignment: String,
        quarterStyle: String
    ) -> String {
        let symbol = fields.count == 1 ? "q" : "Q"
        var width = quarterStyle == "auto" ? lengthStyleWidth(length) : dateFieldStyleWidth(quarterStyle)
        if alignment == "column", width < 3 {
            width = max(width, 2)
        }
        return String(repeating: symbol, count: width)
    }

    private static func semanticMonthSkeleton(
        _ fields: [String],
        dateWidths: [Character: Int],
        length: String,
        alignment: String,
        monthStyle: String
    ) -> String {
        let symbol: Character
        var width: Int
        if fields.count == 1 {
            symbol = "L"
            width = monthStyle == "auto" ? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle)
        } else {
            symbol = dateWidths["M"] != nil ? "M" : dateWidths["L"] != nil ? "L" : "M"
            width = monthStyle == "auto" ? dateWidths[symbol] ?? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle)
        }
        if alignment == "column", width < 3 {
            width = max(width, 2)
        }
        return String(repeating: String(symbol), count: width)
    }

    private static func lengthStyleWidth(_ length: String) -> Int {
        isWideLength(length) ? 4 : length == "medium" ? 3 : 1
    }

    private static func isWideLength(_ length: String) -> Bool {
        length == "full" || length == "long"
    }

    private static func dateFieldStyleWidth(_ style: String) -> Int {
        switch style {
        case "numeric":
            1
        case "2-digit":
            2
        case "short":
            3
        case "long":
            4
        default:
            5
        }
    }

    private static func semanticDaySkeleton(_ dateWidths: [Character: Int], alignment: String, dayStyle: String) -> String {
        var width = dayStyle == "auto" ? dateWidths["d"] ?? 1 : dateFieldStyleWidth(dayStyle)
        if alignment == "column", width < 3 {
            width = max(width, 2)
        }
        return String(repeating: "d", count: width)
    }

    private static func semanticWeekdaySkeleton(_ fields: [String], length: String, weekdayStyle: String) -> String {
        if weekdayStyle == "short" {
            return "EEE"
        }
        if weekdayStyle == "long" {
            return "EEEE"
        }
        if weekdayStyle == "narrow" {
            return "EEEEE"
        }
        if fields.count == 1, length == "short" {
            return "EEEEE"
        }
        return isWideLength(length) ? "EEEE" : "EEE"
    }

    private static func semanticDayPeriodSkeleton(_ length: String, dayPeriodStyle: String) -> String {
        let style = dayPeriodStyle == "auto" ? length : dayPeriodStyle
        return String(repeating: "B", count: isWideLength(style) ? 4 : (style == "narrow" || (dayPeriodStyle == "auto" && length == "short")) ? 5 : 1)
    }

    private static func hasSemanticTimeComponents(_ fields: [String]) -> Bool {
        fields.contains("hour") || fields.contains("minute") || fields.contains("second") || fields.contains("fractionalsecond") || fields.contains("millisecondsinday")
    }

    private static func semanticExplicitTimeSkeleton(
        _ fields: [String],
        hourCycle: String,
        alignment: String,
        options: [String: String]
    ) throws -> String {
        let hasHour = fields.contains("hour")
        let hasMinute = fields.contains("minute")
        let hasSecond = fields.contains("second")
        let hasFractionalSecond = fields.contains("fractionalsecond")
        let hasMillisecondsInDay = fields.contains("millisecondsinday")
        var skeleton = ""
        if hasHour {
            skeleton += String(repeating: String(semanticHourSymbol(hourCycle)), count: semanticNumericFieldWidth(options, key: "hourstyle", fallbackWidth: alignment == "column" ? 2 : 1))
        }
        if hasMinute {
            skeleton += String(repeating: "m", count: semanticNumericFieldWidth(options, key: "minutestyle", fallbackWidth: !hasHour && !hasSecond && alignment == "column" ? 2 : 1))
        }
        if hasSecond {
            skeleton += String(repeating: "s", count: semanticNumericFieldWidth(options, key: "secondstyle", fallbackWidth: !hasHour && !hasMinute && alignment == "column" ? 2 : 1))
        }
        if hasFractionalSecond {
            skeleton += String(repeating: "S", count: try semanticFractionalSecondWidth(options))
        }
        if hasMillisecondsInDay {
            skeleton += String(repeating: "A", count: alignment == "column" ? 8 : 1)
        }
        return skeleton
    }

    private static func semanticNumericFieldWidth(_ options: [String: String], key: String, fallbackWidth: Int) -> Int {
        switch options[key] ?? "auto" {
        case "auto":
            fallbackWidth
        case "2-digit":
            2
        default:
            1
        }
    }

    private static func semanticFractionalSecondWidth(_ options: [String: String]) throws -> Int {
        guard let text = options["fractionalsecond"], let width = Int(text), (1 ... 9).contains(width) else {
            throw MF2Error.badOption("Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.")
        }
        return width
    }

    private static func semanticTimeSkeleton(
        _ timePrecision: String,
        hourCycle: String,
        alignment: String,
        date: Date,
        timeZone: TimeZone,
        options: [String: String]
    ) throws -> String {
        var skeleton = String(repeating: String(semanticHourSymbol(hourCycle)), count: alignment == "column" ? 2 : 1)
        if ["minute", "second", "fractional-second"].contains(timePrecision) {
            skeleton += "m"
        }
        if timePrecision == "minute-optional", dateParts(date, timeZone: timeZone).minute != 0 {
            skeleton += "m"
        }
        if ["second", "fractional-second"].contains(timePrecision) {
            skeleton += "s"
        }
        if timePrecision == "fractional-second" {
            skeleton += String(repeating: "S", count: try semanticFractionalSecondWidth(options))
        } else if options["fractionalsecond"] != nil {
            throw MF2Error.badOption("fractionalSecond requires timePrecision=fractional-second.")
        }
        return skeleton
    }

    private static func semanticTimeStylePrecision(_ timeStyle: String, timePrecision: String) -> String {
        switch timeStyle {
        case "short":
            "minute"
        case "medium", "long", "full":
            "second"
        default:
            timePrecision
        }
    }

    private static func semanticTimeStyleZoneStyle(_ timeStyle: String, zoneStyle: String) -> String {
        semanticTimeStyleHasZone(timeStyle) ? "specific" : zoneStyle
    }

    private static func semanticTimeStyleHasZone(_ timeStyle: String?) -> Bool {
        timeStyle == "long" || timeStyle == "full"
    }

    private static func semanticHourSymbol(_ hourCycle: String) -> Character {
        switch hourCycle {
        case "h11":
            "K"
        case "h12", "clock12":
            "h"
        case "h23", "clock24":
            "H"
        case "h24":
            "k"
        default:
            "C"
        }
    }

    private static func semanticZoneSkeleton(_ zoneStyle: String, standalone: Bool, length: String) -> String {
        let style = zoneStyle == "auto" ? "generic" : zoneStyle
        switch style {
        case "specific":
            return standalone && length != "short" ? "zzzz" : "z"
        case "location":
            return "VVVV"
        case "offset":
            return "O"
        default:
            return standalone && length != "short" ? "vvvv" : "v"
        }
    }

    private static func applyCHourFormat(
        _ widths: inout [Character: Int],
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        width: Int
    ) {
        if hourCycle != nil {
            let hourSymbol = preferredHourSymbol(localeData, hourCycle: hourCycle)
            setSkeletonWidth(&widths, symbol: hourSymbol, width: cHourWidth(width))
            if isHour12Field(hourSymbol) {
                setSkeletonWidth(&widths, symbol: "B", width: dayPeriodWidthForC(width))
            }
            return
        }
        for token in localeData.allowedHourFormats.split(whereSeparator: { $0.isWhitespace }) {
            let symbols = Array(token)
            if !isCHourFormatToken(symbols) {
                continue
            }
            setSkeletonWidth(&widths, symbol: symbols[0], width: cHourWidth(width))
            if symbols.count > 1 {
                setSkeletonWidth(&widths, symbol: symbols[1], width: dayPeriodWidthForC(width))
            }
            return
        }
        setSkeletonWidth(&widths, symbol: preferredHourSymbol(localeData, hourCycle: hourCycle), width: cHourWidth(width))
    }

    private static func isCHourFormatToken(_ token: [Character]) -> Bool {
        (token.count == 1 || token.count == 2)
            && ["h", "H", "k", "K"].contains(token[0])
            && (token.count == 1 || token[1] == "b" || token[1] == "B")
    }

    private static func setSkeletonWidth(_ widths: inout [Character: Int], symbol: Character, width: Int) {
        widths[symbol] = max(widths[symbol] ?? 0, width)
    }

    private static func normalizeSkeletonSymbol(
        _ symbol: Character,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?
    ) -> Character {
        switch symbol {
        case "l":
            return "L"
        case "j", "J":
            return preferredHourSymbol(localeData, hourCycle: hourCycle)
        default:
            return symbol
        }
    }

    private static func cHourWidth(_ width: Int) -> Int {
        width.isMultiple(of: 2) ? 2 : 1
    }

    private static func dayPeriodWidthForC(_ width: Int) -> Int {
        if width >= 5 {
            return 5
        }
        return width >= 3 ? 4 : 1
    }

    private static func shouldSuppressDayPeriod(_ skeleton: String) -> Bool {
        skeleton.contains("J") && !skeleton.contains("a") && !skeleton.contains("b") && !skeleton.contains("B") && !skeleton.contains("C")
    }

    private static func stripDayPeriodPatternFields(_ pattern: String) -> String {
        var output = ""
        var pendingWhitespace = ""
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let character = pattern[index]
            if character == "'" {
                let quoted = readQuotedPattern(pattern, start: index)
                output += pendingWhitespace
                output += String(pattern[index..<quoted.nextIndex])
                pendingWhitespace = ""
                index = quoted.nextIndex
            } else if character.isASCII && character.isLetter {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == character {
                    end = pattern.index(after: end)
                }
                if isDayPeriodField(character) {
                    pendingWhitespace = ""
                } else {
                    output += pendingWhitespace
                    output += String(pattern[index..<end])
                    pendingWhitespace = ""
                }
                index = end
            } else if isPatternWhitespace(character) {
                pendingWhitespace.append(character)
                index = pattern.index(after: index)
            } else {
                output += pendingWhitespace
                output.append(character)
                pendingWhitespace = ""
                index = pattern.index(after: index)
            }
        }
        output += pendingWhitespace
        return trimPatternWhitespace(output)
    }

    private static func trimPatternWhitespace(_ value: String) -> String {
        var start = value.startIndex
        var end = value.endIndex
        while start < end, isPatternWhitespace(value[start]) {
            start = value.index(after: start)
        }
        while end > start {
            let previous = value.index(before: end)
            if !isPatternWhitespace(value[previous]) {
                break
            }
            end = previous
        }
        return String(value[start..<end])
    }

    private static func isPatternWhitespace(_ value: Character) -> Bool {
        value == " " || value == "\u{00A0}" || value == "\u{202F}" || value.isWhitespace
    }

    private static func preferredHourSymbol(_ localeData: CldrDateTimeData.LocaleData, hourCycle: String?) -> Character {
        switch hourCycle {
        case "h11":
            return "K"
        case "h12":
            return "h"
        case "h23":
            return "H"
        case "h24":
            return "k"
        default:
            break
        }
        let shortTime = localeData.timeFormats["short"] ?? ""
        if shortTime.contains("H") {
            return "H"
        }
        if shortTime.contains("k") {
            return "k"
        }
        if shortTime.contains("K") {
            return "K"
        }
        return "h"
    }

    private static func skeletonFieldSet(_ skeleton: String) -> String {
        let normalized = Set(skeletonWidths(skeleton).keys.map { fieldSetSymbol($0) })
        var output = ""
        for symbol in skeletonFieldOrder where normalized.contains(symbol) {
            output.append(symbol)
        }
        return output
    }

    private static func fieldSetSymbol(_ symbol: Character) -> Character {
        if isYearField(symbol) {
            return "y"
        }
        if isHourField(symbol) {
            return "J"
        }
        if isMonthField(symbol) {
            return "M"
        }
        if isQuarterField(symbol) {
            return "Q"
        }
        if isDayPeriodField(symbol) {
            return "B"
        }
        if isWeekdayField(symbol) {
            return "E"
        }
        if isTimeZoneField(symbol) {
            return "v"
        }
        return symbol
    }

    private static func skeletonDistance(requested: String, candidate: String) -> Int {
        let requestedWidths = skeletonWidths(requested)
        let candidateWidths = skeletonWidths(candidate)
        var distance = 0
        for (symbol, requestedWidth) in requestedWidths {
            let candidateSymbol = candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths)
            let candidateWidth = candidateSymbol.flatMap { candidateWidths[$0] } ?? 0
            distance += abs(requestedWidth - candidateWidth)
            if isTextWidth(requestedWidth) != isTextWidth(candidateWidth) {
                distance += 8
            }
            distance += hourFieldDistance(requestedSymbol: symbol, candidateSymbol: candidateSymbol)
        }
        return distance
    }

    private static func skeletonWidths(_ skeleton: String) -> [Character: Int] {
        var widths: [Character: Int] = [:]
        var index = skeleton.startIndex
        while index < skeleton.endIndex {
            let symbol = skeleton[index]
            var end = skeleton.index(after: index)
            while end < skeleton.endIndex, skeleton[end] == symbol {
                end = skeleton.index(after: end)
            }
            widths[symbol] = max(widths[symbol] ?? 0, skeleton.distance(from: index, to: end))
            index = end
        }
        return widths
    }

    private static func isTextWidth(_ width: Int) -> Bool {
        width >= 3
    }

    private static func isHourField(_ symbol: Character) -> Bool {
        skeletonHourFields.contains(symbol)
    }

    private static func isYearField(_ symbol: Character) -> Bool {
        symbol == "y" || symbol == "u" || symbol == "r"
    }

    private static func isWeekdayField(_ symbol: Character) -> Bool {
        symbol == "E" || symbol == "e" || symbol == "c"
    }

    private static func isMonthField(_ symbol: Character) -> Bool {
        symbol == "M" || symbol == "L"
    }

    private static func isQuarterField(_ symbol: Character) -> Bool {
        symbol == "Q" || symbol == "q"
    }

    private static func isDayPeriodField(_ symbol: Character) -> Bool {
        symbol == "a" || symbol == "b" || symbol == "B"
    }

    private static func isSyntheticNumericField(_ symbol: Character) -> Bool {
        symbol == "D" || symbol == "F" || symbol == "g" || symbol == "m" || symbol == "s" || symbol == "A"
    }

    private static func isTimeZoneField(_ symbol: Character) -> Bool {
        Array("zZOvVXx").contains(symbol)
    }

    private static func candidateSymbol(
        forRequested symbol: Character,
        candidateWidths: [Character: Int]
    ) -> Character? {
        if candidateWidths[symbol] != nil {
            return symbol
        }
        if isYearField(symbol) {
            return Array("yur").first { candidateWidths[$0] != nil }
        }
        if isHourField(symbol) {
            return Array("hHkK").first { candidateWidths[$0] != nil }
        }
        if isQuarterField(symbol) {
            return Array("Qq").first { candidateWidths[$0] != nil }
        }
        if isMonthField(symbol) {
            return Array("ML").first { candidateWidths[$0] != nil }
        }
        if isDayPeriodField(symbol) {
            return Array("Bba").first { candidateWidths[$0] != nil }
        }
        if isWeekdayField(symbol) {
            return Array("Eec").first { candidateWidths[$0] != nil }
        }
        if isTimeZoneField(symbol) {
            return Array("vzOZXxV").first { candidateWidths[$0] != nil }
        }
        return nil
    }

    private static func hourFieldDistance(
        requestedSymbol: Character,
        candidateSymbol: Character?
    ) -> Int {
        guard let candidateSymbol,
              requestedSymbol != candidateSymbol,
              isHourField(requestedSymbol),
              isHourField(candidateSymbol)
        else {
            return 0
        }
        return isHour12Field(requestedSymbol) == isHour12Field(candidateSymbol) ? 1 : 4
    }

    private static func isHour12Field(_ symbol: Character) -> Bool {
        symbol == "h" || symbol == "K"
    }

    private static func requestedSymbolForPattern(
        _ symbol: Character,
        requestedWidths: [Character: Int],
        candidateWidths: [Character: Int]
    ) -> Character {
        if isYearField(symbol) {
            return candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths) == nil
                ? symbol
                : candidateSymbol(forRequested: symbol, candidateWidths: requestedWidths) ?? symbol
        }
        if isWeekdayField(symbol) {
            return candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths) == nil
                ? symbol
                : requestedWeekdaySymbolForPattern(symbol, requestedWidths: requestedWidths)
        }
        if isDayPeriodField(symbol) {
            return candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths) == nil
                ? symbol
                : requestedDayPeriodSymbolForPattern(symbol, requestedWidths: requestedWidths)
        }
        if isTimeZoneField(symbol) {
            return candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths) == nil
                ? symbol
                : requestedTimeZoneSymbolForPattern(symbol, requestedWidths: requestedWidths)
        }
        guard (isYearField(symbol) || isHourField(symbol) || isMonthField(symbol) || isQuarterField(symbol) || isDayPeriodField(symbol) || isTimeZoneField(symbol)),
              candidateSymbol(forRequested: symbol, candidateWidths: candidateWidths) != nil
        else {
            return symbol
        }
        return candidateSymbol(forRequested: symbol, candidateWidths: requestedWidths) ?? symbol
    }

    private static func requestedWeekdaySymbolForPattern(
        _ symbol: Character,
        requestedWidths: [Character: Int]
    ) -> Character {
        if requestedWidths["c"] != nil {
            return "c"
        }
        if requestedWidths["e"] != nil {
            return "e"
        }
        if requestedWidths["E"] != nil {
            return "E"
        }
        return symbol
    }

    private static func requestedDayPeriodSymbolForPattern(
        _ symbol: Character,
        requestedWidths: [Character: Int]
    ) -> Character {
        if requestedWidths["a"] != nil {
            return "a"
        }
        if requestedWidths["b"] != nil {
            return "b"
        }
        if requestedWidths["B"] != nil {
            return "B"
        }
        return symbol
    }

    private static func requestedTimeZoneSymbolForPattern(
        _ symbol: Character,
        requestedWidths: [Character: Int]
    ) -> Character {
        for timeZoneSymbol in Array("zZOvVXx") {
            if requestedWidths[timeZoneSymbol] != nil {
                return timeZoneSymbol
            }
        }
        return symbol
    }

    private static func widthForPatternSymbol(_ symbol: Character, widths: [Character: Int]) -> Int? {
        if let width = widths[symbol] {
            return width
        }
        if isYearField(symbol) {
            for yearSymbol in Array("yur") {
                if let width = widths[yearSymbol] {
                    return width
                }
            }
        }
        if isWeekdayField(symbol) {
            for weekdaySymbol in Array("Eec") {
                if let width = widths[weekdaySymbol] {
                    return width
                }
            }
        }
        if isMonthField(symbol) {
            for monthSymbol in Array("ML") {
                if let width = widths[monthSymbol] {
                    return width
                }
            }
        }
        if isDayPeriodField(symbol) {
            for dayPeriodSymbol in Array("Bba") {
                if let width = widths[dayPeriodSymbol] {
                    return width
                }
            }
        }
        if isQuarterField(symbol) {
            for quarterSymbol in Array("Qq") {
                if let width = widths[quarterSymbol] {
                    return width
                }
            }
        }
        if isTimeZoneField(symbol) {
            for timeZoneSymbol in Array("zZOvVXx") {
                if let width = widths[timeZoneSymbol] {
                    return width
                }
            }
        }
        return nil
    }

    private static func adjustPatternWidths(
        _ pattern: String,
        requestedSkeleton: String,
        candidateSkeleton: String
    ) -> String {
        let requestedWidths = skeletonWidths(requestedSkeleton)
        let candidateWidths = skeletonWidths(candidateSkeleton)
        var output = ""
        var inQuote = false
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let ch = pattern[index]
            if ch == "'" {
                output.append(ch)
                let next = pattern.index(after: index)
                if next < pattern.endIndex, pattern[next] == "'" {
                    output.append("'")
                    index = pattern.index(after: next)
                } else {
                    inQuote.toggle()
                    index = next
                }
            } else if !inQuote, ch.isASCII, ch.isLetter {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == ch {
                    end = pattern.index(after: end)
                }
                let requestedSymbol = requestedSymbolForPattern(ch, requestedWidths: requestedWidths, candidateWidths: candidateWidths)
                let requestedWidth = widthForPatternSymbol(ch, widths: requestedWidths)
                let candidateWidth = widthForPatternSymbol(ch, widths: candidateWidths)
                let patternWidth = pattern.distance(from: index, to: end)
                let width = shouldAdjustPatternWidth(
                    symbol: requestedSymbol,
                    requestedWidth: requestedWidth,
                    candidateWidth: candidateWidth,
                    patternWidth: patternWidth
                ) ? requestedWidth! : patternWidth
                output += String(repeating: String(requestedSymbol), count: width)
                index = end
            } else {
                output.append(ch)
                index = pattern.index(after: index)
            }
        }
        return output
    }

    private static func shouldAdjustPatternWidth(
        symbol: Character,
        requestedWidth: Int?,
        candidateWidth: Int?,
        patternWidth: Int
    ) -> Bool {
        guard let requestedWidth, let candidateWidth else {
            return false
        }
        if (symbol == "e" || symbol == "c"), patternWidth >= 3, requestedWidth <= 2 {
            return true
        }
        if isWeekdayField(symbol), patternWidth >= 3, requestedWidth >= 4 {
            return true
        }
        return patternWidth == candidateWidth
    }

    private static func splitDateTimeSkeleton(_ skeleton: String) -> (String, String) {
        var dateSkeleton = ""
        var timeSkeleton = ""
        for symbol in skeleton {
            if skeletonTimeFields.contains(symbol) {
                timeSkeleton.append(symbol)
            } else {
                dateSkeleton.append(symbol)
            }
        }
        return (dateSkeleton, timeSkeleton)
    }

    private static func formatPattern(
        _ pattern: String,
        date: Date,
        localeData: CldrDateTimeData.LocaleData,
        timeZone: TimeZone
    ) throws -> String {
        let parts = dateParts(date, timeZone: timeZone)
        let offsetMinutes = timeZone.secondsFromGMT(for: date) / 60
        var output = ""
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let character = pattern[index]
            if character == "'" {
                let quoted = readQuotedPattern(pattern, start: index)
                output.append(quoted.value)
                index = quoted.nextIndex
            } else if character.isASCII && character.isLetter {
                var end = pattern.index(after: index)
                while end < pattern.endIndex, pattern[end] == character {
                    end = pattern.index(after: end)
                }
                output.append(try formatField(
                    character,
                    count: pattern.distance(from: index, to: end),
                    parts: parts,
                    offsetMinutes: offsetMinutes,
                    localeData: localeData
                ))
                index = end
            } else {
                output.append(character)
                index = pattern.index(after: index)
            }
        }
        return output
    }

    private static func readQuotedPattern(_ pattern: String, start: String.Index) -> QuotedPattern {
        let next = pattern.index(after: start)
        if next < pattern.endIndex, pattern[next] == "'" {
            return QuotedPattern(value: "'", nextIndex: pattern.index(after: next))
        }
        var value = ""
        var index = next
        while index < pattern.endIndex {
            if pattern[index] == "'" {
                let afterQuote = pattern.index(after: index)
                if afterQuote < pattern.endIndex, pattern[afterQuote] == "'" {
                    value.append("'")
                    index = pattern.index(after: afterQuote)
                } else {
                    return QuotedPattern(value: value, nextIndex: afterQuote)
                }
            } else {
                value.append(pattern[index])
                index = pattern.index(after: index)
            }
        }
        return QuotedPattern(value: value, nextIndex: index)
    }

    private static func formatField(
        _ symbol: Character,
        count: Int,
        parts: DateParts,
        offsetMinutes: Int,
        localeData: CldrDateTimeData.LocaleData
    ) throws -> String {
        switch symbol {
        case "G":
            return eraName(parts: parts, localeData: localeData, count: count)
        case "y":
            return yearValue(parts: parts, localeData: localeData, count: count)
        case "u":
            return extendedYearValue(parts: parts, localeData: localeData, count: count)
        case "r":
            return extendedYearValue(parts: parts, localeData: localeData, count: count)
        case "Y":
            return weekYearValue(parts: parts, localeData: localeData, count: count)
        case "Q", "q":
            return quarterValue(parts: parts, localeData: localeData, count: count, standAlone: symbol == "q")
        case "M", "L":
            return monthValue(parts: parts, localeData: localeData, count: count, standAlone: symbol == "L")
        case "d":
            return integerValue(parts.day, localeData: localeData, count: count)
        case "D":
            return integerValue(dayOfYear(parts: parts), localeData: localeData, count: count)
        case "F":
            return integerValue(dayOfWeekInMonth(parts: parts), localeData: localeData, count: count)
        case "g":
            return integerValue(modifiedJulianDay(parts: parts), localeData: localeData, count: count)
        case "w":
            return integerValue(weekOfYear(parts: parts, localeData: localeData), localeData: localeData, count: count)
        case "W":
            return integerValue(weekOfMonth(parts: parts, localeData: localeData), localeData: localeData, count: count)
        case "E":
            return weekdayName(parts: parts, localeData: localeData, count: count)
        case "e":
            return localWeekdayValue(parts: parts, localeData: localeData, count: count, standAlone: false)
        case "c":
            return localWeekdayValue(parts: parts, localeData: localeData, count: count, standAlone: true)
        case "a", "b", "B":
            return dayPeriodName(parts: parts, localeData: localeData, count: count, symbol: symbol)
        case "H":
            return integerValue(parts.hour, localeData: localeData, count: count)
        case "k":
            return integerValue(parts.hour == 0 ? 24 : parts.hour, localeData: localeData, count: count)
        case "h":
            return integerValue(hour12(parts), localeData: localeData, count: count)
        case "K":
            return integerValue(parts.hour % 12, localeData: localeData, count: count)
        case "m":
            return integerValue(parts.minute, localeData: localeData, count: count)
        case "s":
            return integerValue(parts.second, localeData: localeData, count: count)
        case "S":
            return fractionValue(parts: parts, localeData: localeData, count: count)
        case "A":
            return integerValue(millisecondsInDay(parts: parts), localeData: localeData, count: count)
        case "z", "Z", "O", "v", "V", "X", "x":
            return timeZoneValue(symbol, count: count, offsetMinutes: offsetMinutes, localeData: localeData)
        default:
            throw MF2Error.badOption("Unsupported CLDR date/time pattern field: \(symbol).")
        }
    }

    private static func timeZoneValue(
        _ symbol: Character,
        count: Int,
        offsetMinutes: Int,
        localeData: CldrDateTimeData.LocaleData
    ) -> String {
        if offsetMinutes != 0 {
            switch symbol {
            case "X":
                return isoOffset(offsetMinutes, count: count, useZeroZ: true)
            case "x":
                return isoOffset(offsetMinutes, count: count, useZeroZ: false)
            case "V":
                if count == 1 {
                    return "unk"
                }
                if count == 2 {
                    return fixedOffsetGmtId(offsetMinutes, localeData: localeData)
                }
                if count == 3 {
                    return "Unknown Location"
                }
                return localizedGmtOffset(localeData, offsetMinutes: offsetMinutes, count: count)
            case "Z":
                if count <= 3 {
                    return basicOffset(offsetMinutes)
                }
                if count == 5 {
                    return isoOffset(offsetMinutes, count: 3, useZeroZ: true)
                }
                return localizedGmtOffset(localeData, offsetMinutes: offsetMinutes, count: count)
            default:
                return localizedGmtOffset(localeData, offsetMinutes: offsetMinutes, count: count)
            }
        }
        switch symbol {
        case "z":
            if count >= 4 {
                return localeData.timeZoneNames["utcLong"] ?? localeData.timeZoneNames["utcShort"] ?? utc
            }
            return localeData.timeZoneNames["utcShort"] ?? utc
        case "O", "v":
            return localizedGmtZero(localeData)
        case "V":
            return localizedGmtZero(localeData)
        case "Z":
            if count <= 3 {
                return "+0000"
            }
            if count == 5 {
                return "Z"
            }
            return localizedGmtZero(localeData)
        case "X":
            return "Z"
        case "x":
            if count == 1 {
                return "+00"
            }
            return count == 2 || count == 4 ? "+0000" : "+00:00"
        default:
            return utc
        }
    }

    private static func localizedGmtZero(_ localeData: CldrDateTimeData.LocaleData) -> String {
        if let value = localeData.timeZoneNames["gmtZeroFormat"] {
            return value
        }
        return (localeData.timeZoneNames["gmtFormat"] ?? "GMT{0}").replacingOccurrences(of: "{0}", with: "")
    }

    private static func localizedGmtOffset(
        _ localeData: CldrDateTimeData.LocaleData,
        offsetMinutes: Int,
        count: Int
    ) -> String {
        let formatted = count >= 4 ? extendedOffset(offsetMinutes, paddedHour: true) : shortOffset(offsetMinutes)
        return (localeData.timeZoneNames["gmtFormat"] ?? "GMT{0}")
            .replacingOccurrences(
                of: "{0}",
                with: localizeDigits(formatted, digits: localeData.numberingSystemDigits)
            )
    }

    private static func fixedOffsetGmtId(_ offsetMinutes: Int, localeData: CldrDateTimeData.LocaleData) -> String {
        "GMT" + localizeDigits(extendedOffset(offsetMinutes, paddedHour: true), digits: localeData.numberingSystemDigits)
    }

    private static func isoOffset(
        _ offsetMinutes: Int,
        count: Int,
        useZeroZ: Bool
    ) -> String {
        if offsetMinutes == 0, useZeroZ {
            return "Z"
        }
        if count == 1 {
            return shortIsoOffset(offsetMinutes)
        }
        if count == 2 || count == 4 {
            return basicOffset(offsetMinutes)
        }
        return extendedOffset(offsetMinutes, paddedHour: true)
    }

    private static func shortIsoOffset(_ offsetMinutes: Int) -> String {
        let parts = offsetParts(offsetMinutes)
        if parts.minutes == 0 {
            return "\(parts.sign)\(pad2(parts.hours))"
        }
        return "\(parts.sign)\(pad2(parts.hours))\(pad2(parts.minutes))"
    }

    private static func shortOffset(_ offsetMinutes: Int) -> String {
        let parts = offsetParts(offsetMinutes)
        if parts.minutes == 0 {
            return "\(parts.sign)\(parts.hours)"
        }
        return "\(parts.sign)\(parts.hours):\(pad2(parts.minutes))"
    }

    private static func basicOffset(_ offsetMinutes: Int) -> String {
        let parts = offsetParts(offsetMinutes)
        return "\(parts.sign)\(pad2(parts.hours))\(pad2(parts.minutes))"
    }

    private static func extendedOffset(_ offsetMinutes: Int, paddedHour: Bool) -> String {
        let parts = offsetParts(offsetMinutes)
        let hour = paddedHour ? pad2(parts.hours) : String(parts.hours)
        return "\(parts.sign)\(hour):\(pad2(parts.minutes))"
    }

    private static func offsetParts(_ offsetMinutes: Int) -> OffsetParts {
        let sign = offsetMinutes < 0 ? "-" : "+"
        let absolute = abs(offsetMinutes)
        return OffsetParts(sign: sign, hours: absolute / 60, minutes: absolute % 60)
    }

    private static func pad2(_ value: Int) -> String {
        String(format: "%02d", value)
    }

    private static func eraName(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        let era = parts.era == 0 ? "0" : "1"
        return nameByWidth(localeData.eras, width: widthForText(count), key: era)
    }

    private static func yearValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        let year = parts.year
        if count == 2 {
            return integerText(year % 100, localeData: localeData, minimumDigits: 2)
        }
        return localizeDigits(String(year), digits: localeData.numberingSystemDigits)
    }

    private static func extendedYearValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        integerValue(parts.year, localeData: localeData, count: count)
    }

    private static func weekYearValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        let info = weekYearInfo(parts: parts, localeData: localeData)
        if count == 2 {
            return integerText(modulo(info.year, 100), localeData: localeData, minimumDigits: 2)
        }
        return localizeDigits(String(info.year), digits: localeData.numberingSystemDigits)
    }

    private static func dayOfYear(parts: DateParts) -> Int {
        daysBeforeMonth(year: parts.year, month: parts.month) + parts.day
    }

    private static func dayOfWeekInMonth(parts: DateParts) -> Int {
        ((parts.day - 1) / 7) + 1
    }

    private static func millisecondsInDay(parts: DateParts) -> Int {
        ((parts.hour * 60 + parts.minute) * 60 + parts.second) * 1000 + millisecond(parts: parts)
    }

    private static func modifiedJulianDay(parts: DateParts) -> Int {
        ordinalDay(year: parts.year, month: parts.month, day: parts.day) - ordinalDay(year: 1858, month: 11, day: 17)
    }

    private static func weekOfYear(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData
    ) -> Int {
        weekYearInfo(parts: parts, localeData: localeData).week
    }

    private static func weekYearInfo(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData
    ) -> WeekYearInfo {
        let ordinal = ordinalDay(year: parts.year, month: parts.month, day: parts.day)
        let currentStart = firstWeekStartOfYear(parts.year, localeData: localeData)
        if ordinal < currentStart {
            let previousYear = parts.year - 1
            let previousStart = firstWeekStartOfYear(previousYear, localeData: localeData)
            return WeekYearInfo(year: previousYear, week: floorDiv(ordinal - previousStart, 7) + 1)
        }

        let nextStart = firstWeekStartOfYear(parts.year + 1, localeData: localeData)
        if ordinal >= nextStart {
            return WeekYearInfo(year: parts.year + 1, week: floorDiv(ordinal - nextStart, 7) + 1)
        }

        return WeekYearInfo(year: parts.year, week: floorDiv(ordinal - currentStart, 7) + 1)
    }

    private static func weekOfMonth(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData
    ) -> Int {
        let ordinal = ordinalDay(year: parts.year, month: parts.month, day: parts.day)
        let firstStart = firstWeekStart(
            periodStart: ordinalDay(year: parts.year, month: parts.month, day: 1),
            firstDay: localeData.firstDayOfWeek,
            minDays: localeData.minDaysInFirstWeek
        )
        return floorDiv(ordinal - firstStart, 7) + 1
    }

    private static func firstWeekStartOfYear(
        _ year: Int,
        localeData: CldrDateTimeData.LocaleData
    ) -> Int {
        firstWeekStart(
            periodStart: ordinalDay(year: year, month: 1, day: 1),
            firstDay: localeData.firstDayOfWeek,
            minDays: localeData.minDaysInFirstWeek
        )
    }

    private static func firstWeekStart(periodStart: Int, firstDay: Int, minDays: Int) -> Int {
        let weekStart = startOfWeek(periodStart, firstDay: firstDay)
        return 7 - (periodStart - weekStart) >= minDays ? weekStart : weekStart + 7
    }

    private static func startOfWeek(_ ordinal: Int, firstDay: Int) -> Int {
        ordinal - modulo(dayOfWeek(ordinal) - firstDay, 7)
    }

    private static func dayOfWeek(_ ordinal: Int) -> Int {
        modulo(ordinal, 7)
    }

    private static func ordinalDay(year: Int, month: Int, day: Int) -> Int {
        daysBeforeYear(year) + daysBeforeMonth(year: year, month: month) + day
    }

    private static func daysBeforeYear(_ year: Int) -> Int {
        let previous = year - 1
        return 365 * previous + previous / 4 - previous / 100 + previous / 400
    }

    private static func daysBeforeMonth(year: Int, month: Int) -> Int {
        let offsets = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
        var days = offsets[month - 1]
        if month > 2, isLeapYear(year) {
            days += 1
        }
        return days
    }

    private static func isLeapYear(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private static func modulo(_ value: Int, _ divisor: Int) -> Int {
        let remainder = value % divisor
        return remainder < 0 ? remainder + divisor : remainder
    }

    private static func floorDiv(_ value: Int, _ divisor: Int) -> Int {
        let quotient = value / divisor
        let remainder = value % divisor
        return remainder != 0 && ((remainder < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }

    private static func monthValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Bool
    ) -> String {
        let month = parts.month
        if count <= 2 {
            return integerValue(month, localeData: localeData, count: count)
        }
        return contextualName(
            localeData.months,
            context: standAlone ? "stand-alone" : "format",
            width: widthForText(count),
            key: String(month)
        )
    }

    private static func quarterValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Bool
    ) -> String {
        let quarter = (parts.month - 1) / 3 + 1
        if count <= 2 {
            return integerValue(quarter, localeData: localeData, count: count)
        }
        return contextualName(
            localeData.quarters,
            context: standAlone ? "stand-alone" : "format",
            width: widthForText(count),
            key: String(quarter)
        )
    }

    private static func weekdayName(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        return contextualName(
            localeData.weekdays,
            context: "format",
            width: widthForWeekday(count),
            key: weekdayKeys[parts.weekday - 1]
        )
    }

    private static func localWeekdayValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Bool
    ) -> String {
        let day = parts.weekday - 1
        if count <= 2 {
            let localDay = modulo(day - localeData.firstDayOfWeek, 7) + 1
            return integerValue(localDay, localeData: localeData, count: count)
        }
        return contextualName(
            localeData.weekdays,
            context: standAlone ? "stand-alone" : "format",
            width: widthForWeekday(count),
            key: weekdayKeys[day]
        )
    }

    private static func dayPeriodName(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        symbol: Character
    ) -> String {
        let period = dayPeriodKey(parts: parts, localeData: localeData, symbol: symbol)
        return contextualName(
            localeData.dayPeriods,
            context: "format",
            width: widthForDayPeriod(count),
            key: period
        )
    }

    private static func dayPeriodKey(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        symbol: Character
    ) -> String {
        let fallback = parts.hour < 12 ? "am" : "pm"
        if symbol == "a" {
            return fallback
        }
        if symbol == "b" {
            return selectDayPeriodRule(parts: parts, encodedRules: localeData.dayPeriodRules, exactOnly: true) ?? fallback
        }
        return selectDayPeriodRule(parts: parts, encodedRules: localeData.dayPeriodRules, exactOnly: false) ?? fallback
    }

    private static func selectDayPeriodRule(
        parts: DateParts,
        encodedRules: String,
        exactOnly: Bool
    ) -> String? {
        if encodedRules.isEmpty {
            return nil
        }
        let minute = parts.hour * 60 + parts.minute
        let exactMinute = parts.second == 0 && parts.nanosecond == 0 ? minute : -1
        var rangeMatch: String?
        for rawRule in encodedRules.split(separator: ";") {
            let pieces = rawRule.split(separator: "=", maxSplits: 1)
            if pieces.count != 2 {
                continue
            }
            let period = String(pieces[0])
            let span = pieces[1]
            let rangePieces = span.split(separator: "-", maxSplits: 1)
            if rangePieces.count == 1 {
                if Int(rangePieces[0]) == exactMinute {
                    return period
                }
            } else if !exactOnly {
                let start = Int(rangePieces[0]) ?? 0
                let end = Int(rangePieces[1]) ?? 0
                if rangeMatch == nil && minuteInDayPeriodRange(minute, start: start, end: end) {
                    rangeMatch = period
                }
            }
        }
        return exactOnly ? nil : rangeMatch
    }

    private static func minuteInDayPeriodRange(_ minute: Int, start: Int, end: Int) -> Bool {
        if start <= end {
            return minute >= start && minute < end
        }
        return minute >= start || minute < end
    }

    private static func hour12(_ parts: DateParts) -> Int {
        let hour = parts.hour % 12
        return hour == 0 ? 12 : hour
    }

    private static func fractionValue(
        parts: DateParts,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        let milliseconds = String(format: "%03d", millisecond(parts: parts))
        let fraction = milliseconds + "000000000"
        let end = fraction.index(fraction.startIndex, offsetBy: count)
        return localizeDigits(String(fraction[..<end]), digits: localeData.numberingSystemDigits)
    }

    private static func millisecond(parts: DateParts) -> Int {
        min((parts.nanosecond + 500_000) / 1_000_000, 999)
    }

    private static func integerValue(
        _ value: Int,
        localeData: CldrDateTimeData.LocaleData,
        count: Int
    ) -> String {
        integerText(value, localeData: localeData, minimumDigits: count >= 2 ? count : 0)
    }

    private static func integerText(
        _ value: Int,
        localeData: CldrDateTimeData.LocaleData,
        minimumDigits: Int
    ) -> String {
        var text = String(abs(value))
        while text.count < minimumDigits {
            text = "0" + text
        }
        if value < 0 {
            text = "-" + text
        }
        return localizeDigits(text, digits: localeData.numberingSystemDigits)
    }

    private static func contextualName(
        _ source: [String: [String: [String: String]]],
        context: String,
        width: String,
        key: String
    ) -> String {
        guard let contextData = source[context] ?? source["format"] ?? source["stand-alone"] else {
            return key
        }
        return nameByWidth(contextData, width: width, key: key)
    }

    private static func nameByWidth(
        _ source: [String: [String: String]],
        width: String,
        key: String
    ) -> String {
        source[width]?[key]
            ?? source["abbreviated"]?[key]
            ?? source["wide"]?[key]
            ?? source["short"]?[key]
            ?? source["narrow"]?[key]
            ?? key
    }

    private static func widthForText(_ count: Int) -> String {
        if count == 4 { return "wide" }
        if count == 5 { return "narrow" }
        return "abbreviated"
    }

    private static func widthForWeekday(_ count: Int) -> String {
        if count == 4 { return "wide" }
        if count == 5 { return "narrow" }
        if count >= 6 { return "short" }
        return "abbreviated"
    }

    private static func widthForDayPeriod(_ count: Int) -> String {
        if count == 4 { return "wide" }
        if count >= 5 { return "narrow" }
        return "abbreviated"
    }

    private static func localizeDigits(_ value: String, digits: String?) -> String {
        guard let digits, digits != "0123456789" else {
            return value
        }
        let digitArray = Array(digits)
        return String(value.map { character in
            guard let ascii = character.asciiValue, ascii >= 48, ascii <= 57 else {
                return character
            }
            return digitArray[Int(ascii - 48)]
        })
    }

    private static func dateParts(_ date: Date, timeZone: TimeZone) -> DateParts {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let components = calendar.dateComponents([.era, .year, .month, .day, .weekday, .hour, .minute, .second, .nanosecond], from: date)
        return DateParts(
            era: components.era ?? 1,
            year: components.year ?? 1,
            month: components.month ?? 1,
            day: components.day ?? 1,
            weekday: components.weekday ?? 1,
            hour: components.hour ?? 0,
            minute: components.minute ?? 0,
            second: components.second ?? 0,
            nanosecond: components.nanosecond ?? 0
        )
    }

    private static func styleKey(_ style: Style) -> String {
        switch style {
        case .full:
            "full"
        case .long:
            "long"
        case .medium:
            "medium"
        case .short:
            "short"
        }
    }

    private static func callStyle(
        _ call: MF2FunctionCall,
        option: String,
        legacy: String,
        defaultValue: String,
        legacyTimePrecision: Bool
    ) throws -> Style {
        let absent = "\u{0}mf2-absent-date-time-style"
        let shared = try call.optionValue("style", default: absent) ?? absent
        let legacyValue = try call.optionValue(legacy, default: absent) ?? absent
        let optionValue = try call.optionValue(option, default: absent) ?? absent
        if optionValue != absent {
            return try styleOption(optionValue)
        }
        if legacyValue != absent {
            if legacyTimePrecision {
                return try timePrecisionStyleOption(legacyValue)
            }
            return try styleOption(legacyValue)
        }
        if shared != absent {
            return try styleOption(shared)
        }
        return try styleOption(defaultValue)
    }

    private static func nonEmptyCallOption(_ call: MF2FunctionCall, _ name: String, default defaultValue: String?) throws -> String? {
        let value = try call.optionValue(name, default: defaultValue)
        if value == "" {
            throw MF2Error.badOption("\(name) must not be empty.")
        }
        return value
    }

    private static func styleOption(_ value: String) throws -> Style {
        if value.count > maxOptionLength {
            throw MF2Error.badOption("date/time style must not exceed 256 characters.")
        }
        switch value {
        case "full":
            return .full
        case "long":
            return .long
        case "medium":
            return .medium
        case "short":
            return .short
        default:
            throw MF2Error.badOption("date/time style must be full, long, medium, or short.")
        }
    }

    private static func timePrecisionStyleOption(_ value: String) throws -> Style {
        value == "second" ? .medium : try styleOption(value)
    }

    private struct QuotedPattern {
        let value: String
        let nextIndex: String.Index
    }

    private struct OffsetParts {
        let sign: String
        let hours: Int
        let minutes: Int
    }

    private struct WeekYearInfo {
        let year: Int
        let week: Int
    }

    private struct DateParts {
        let era: Int
        let year: Int
        let month: Int
        let day: Int
        let weekday: Int
        let hour: Int
        let minute: Int
        let second: Int
        let nanosecond: Int
    }
}
