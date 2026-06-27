import Foundation

private let maxFoundationFractionDigits = 100
private let maxFoundationDateOperandLength = 256
private let maxFoundationLocaleLength = 256
private let maxFoundationNumericOperandLength = 256
private let maxFoundationNumericOptionLength = 256
private let maxFoundationOptionLength = 256
private let maxFoundationTimeZoneOptionLength = 256
private let foundationISO8601DatePattern = #"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})$"#
private let foundationDateTimeSecondPattern = #"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$"#
private let foundationDateTimeMinutePattern = #"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$"#
private let foundationDatePattern = #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#
private let foundationTimeSecondPattern = #"^[0-9]{2}:[0-9]{2}:[0-9]{2}$"#
private let foundationTimeMinutePattern = #"^[0-9]{2}:[0-9]{2}$"#

func makeFoundationFunctionRegistry() -> MF2FunctionRegistry {
    var registry = MF2FunctionRegistry.portable
        .withFunction("number", formatter: formatFoundationNumber)
        .withFunction("percent", formatter: formatFoundationPercent)
        .withFunction("integer", formatter: formatFoundationInteger)
        .withFunction("currency", formatter: formatFoundationCurrency)
        .withFunction("date", formatter: formatFoundationDate)
        .withFunction("time", formatter: formatFoundationTime)
        .withFunction("datetime", formatter: formatFoundationDateTime)

    #if os(macOS) || os(iOS) || os(tvOS) || os(watchOS) || os(visionOS)
        registry = registry.withFunction("relativeTime", formatter: formatFoundationRelativeTime)
    #endif

    return registry
}

private func formatFoundationNumber(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Number function requires a numeric operand.")
    let formatter = NumberFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.numberStyle = .decimal
    try applyFractionOptions(call, formatter: formatter)
    return try applySignDisplay(formatterString(formatter, value), value: value, call: call)
}

private func formatFoundationPercent(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Percent function requires a numeric operand.")
    let formatter = NumberFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.numberStyle = .percent
    try applyFractionOptions(call, formatter: formatter)
    return try applySignDisplay(formatterString(formatter, value), value: value, call: call)
}

private func formatFoundationInteger(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Integer function requires a numeric operand.")
    let formatter = NumberFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 0
    formatter.minimumFractionDigits = 0
    return try applySignDisplay(
        formatterString(formatter, value.rounded(.towardZero)),
        value: value,
        call: call
    )
}

private func formatFoundationCurrency(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Currency function requires a numeric operand.")
    guard let currency = try currencyCode(call) else {
        throw MF2Error.badOption("Currency function requires a currency option.")
    }
    guard isCurrencyCode(currency) else {
        throw MF2Error.badOption("Currency option must be an ISO 4217 currency code.")
    }

    let formatter = NumberFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.numberStyle = .currency
    formatter.currencyCode = currency.uppercased()
    if let fractionDigits = try nonNegativeIntegerOption(call, "fractionDigits") {
        formatter.minimumFractionDigits = fractionDigits
        formatter.maximumFractionDigits = fractionDigits
    }
    return try formatterString(formatter, value)
}

private func formatFoundationDate(_ call: MF2FunctionCall) throws -> String {
    let date = try foundationDate(call, message: "Date function requires a date or datetime operand.")
    let formatter = DateFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.timeZone = try timeZone(call)
    formatter.dateStyle = try dateStyle(try dateStyleOption(call))
    formatter.timeStyle = .none
    return formatter.string(from: date)
}

private func formatFoundationTime(_ call: MF2FunctionCall) throws -> String {
    let date = try foundationTime(call, message: "Time function requires a time or datetime operand.")
    let formatter = DateFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.timeZone = try timeZone(call)
    formatter.dateStyle = .none
    formatter.timeStyle = try timeStyle(try timeStyleOption(call))
    return formatter.string(from: date)
}

private func formatFoundationDateTime(_ call: MF2FunctionCall) throws -> String {
    let date = try foundationDateTime(call, message: "Datetime function requires a date or datetime operand.")
    let formatter = DateFormatter()
    formatter.locale = try foundationLocale(call.locale)
    formatter.timeZone = try timeZone(call)
    formatter.dateStyle = try dateStyle(try dateTimeDateStyleOption(call))
    formatter.timeStyle = try timeStyle(try dateTimeTimeStyleOption(call))
    return formatter.string(from: date)
}

#if os(macOS) || os(iOS) || os(tvOS) || os(watchOS) || os(visionOS)
    private func formatFoundationRelativeTime(_ call: MF2FunctionCall) throws -> String {
        let value = try parseFoundationIntegerValue(call.value, source: call.inheritedSource)
        let unit = try optionOneOf(
            call,
            "unit",
            ["second", "minute", "hour", "day", "week", "month", "year"],
            defaultValue: nil
        )
        guard let unit else {
            throw MF2Error.badOption("Relative time function requires a unit option.")
        }

        let formatter = RelativeDateTimeFormatter()
        formatter.locale = try foundationLocale(call.locale)
        formatter.unitsStyle = try relativeUnitsStyle(try boundedOptionValue(call, "style", defaultValue: "long") ?? "long")
        formatter.dateTimeStyle = try relativeDateTimeStyle(try boundedOptionValue(call, "numeric", defaultValue: "always") ?? "always")
        return formatter.localizedString(from: try dateComponents(value: value, unit: unit))
    }

    private func parseFoundationIntegerValue(_ value: String, source: MF2FunctionSource?) throws -> Int {
        if let parsed = try? parseFoundationInteger(
            value,
            error: .badOperand("Relative time function requires an integer operand.")
        ) {
            return parsed
        }
        var current = source
        while let source = current {
            if isRelativeTimeNumericSourceFunction(source.function.name),
               let parsed = try? parseFoundationInteger(
                   source.value,
                   error: .badOperand("Relative time function requires an integer operand.")
               ) {
                return parsed
            }
            current = source.inheritedSource
        }
        throw MF2Error.badOperand("Relative time function requires an integer operand.")
    }

    private func isRelativeTimeNumericSourceFunction(_ name: String) -> Bool {
        switch name {
        case "number", "integer", "percent", "offset", "currency", "relativeTime":
            return true
        default:
            return false
        }
    }

    private func relativeUnitsStyle(_ value: String) throws -> RelativeDateTimeFormatter.UnitsStyle {
        switch value {
        case "long":
            .full
        case "short", "narrow":
            .abbreviated
        default:
            throw MF2Error.badOption("Relative time style option must be long, short, or narrow.")
        }
    }

    private func relativeDateTimeStyle(_ value: String) throws -> RelativeDateTimeFormatter.DateTimeStyle {
        switch value {
        case "always":
            .numeric
        case "auto":
            .named
        default:
            throw MF2Error.badOption("Relative time numeric option must be always or auto.")
        }
    }

    private func dateComponents(value: Int, unit: String) throws -> DateComponents {
        switch unit {
        case "second":
            DateComponents(second: value)
        case "minute":
            DateComponents(minute: value)
        case "hour":
            DateComponents(hour: value)
        case "day":
            DateComponents(day: value)
        case "week":
            DateComponents(day: value * 7)
        case "month":
            DateComponents(month: value)
        case "year":
            DateComponents(year: value)
        default:
            throw MF2Error.badOption("Relative time function requires unit second, minute, hour, day, week, month, or year.")
        }
    }
#endif

private func parseFoundationNumber(_ call: MF2FunctionCall, message: String) throws -> Double {
    if let parsed = parseFoundationNumberLiteral(call.value) ?? parseSourceNumber(call.inheritedSource) {
        return parsed
    }
    throw MF2Error.badOperand(message)
}

private func parseSourceNumber(_ source: MF2FunctionSource?) -> Double? {
    guard let source else {
        return nil
    }
    if source.function.name == "number" || source.function.name == "integer" || source.function.name == "percent" || source.function.name == "currency" {
        return parseFoundationNumberLiteral(source.value)
    }
    return parseSourceNumber(source.inheritedSource)
}

private func parseFoundationNumberLiteral(_ value: String) -> Double? {
    guard value.utf8.count <= maxFoundationNumericOperandLength,
          let range = value.range(
        of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#,
        options: .regularExpression
    ), range == value.startIndex..<value.endIndex,
          let parsed = Double(value),
          parsed.isFinite
    else {
        return nil
    }
    return parsed
}

private func parseFoundationInteger(_ value: String, error: MF2Error) throws -> Int {
    guard let parsed = Int(value),
          String(parsed) == value || (value.hasPrefix("+") && String(parsed) == String(value.dropFirst()))
    else {
        throw error
    }
    return parsed
}

private func formatterString(_ formatter: NumberFormatter, _ value: Double) throws -> String {
    guard let output = formatter.string(from: NSNumber(value: value)) else {
        throw MF2Error.badOperand("Number formatter could not format the operand.")
    }
    return output
}

private func applySignDisplay(_ formatted: String, value: Double, call: MF2FunctionCall) throws -> String {
    if value >= 0.0, try boundedOptionValue(call, "signDisplay") == "always" {
        return "+\(formatted)"
    }
    return formatted
}

private func applyFractionOptions(_ call: MF2FunctionCall, formatter: NumberFormatter) throws {
    let minimum = try nonNegativeIntegerOption(call, "minimumFractionDigits")
    let maximum = try nonNegativeIntegerOption(call, "maximumFractionDigits")
    if let minimum, let maximum, maximum < minimum {
        throw MF2Error.badOption("maximumFractionDigits option must be greater than or equal to minimumFractionDigits.")
    }
    if let minimum {
        formatter.minimumFractionDigits = minimum
    }
    if let maximum {
        formatter.maximumFractionDigits = maximum
    }
}

private func nonNegativeIntegerOption(_ call: MF2FunctionCall, _ name: String) throws -> Int? {
    guard let value = try call.optionValue(name) else {
        return nil
    }
    guard value.utf8.count <= maxFoundationNumericOptionLength else {
        throw MF2Error.badOption("\(name) option must be a non-negative integer.")
    }
    guard value.range(of: #"^[0-9]+$"#, options: .regularExpression) == value.startIndex..<value.endIndex,
          let parsed = Int(value),
          parsed <= maxFoundationFractionDigits
    else {
        throw MF2Error.badOption("\(name) option must be a non-negative integer.")
    }
    return parsed
}

private func currencyCode(_ call: MF2FunctionCall) throws -> String? {
    if let currency = try call.optionValue("currency") {
        return currency
    }
    return try inheritedCurrencyCode(call.inheritedSource)
}

private func inheritedCurrencyCode(_ source: MF2FunctionSource?) throws -> String? {
    guard let source else {
        return nil
    }
    if source.function.name == "currency", let currency = try source.optionValue("currency") {
        return currency
    }
    return try inheritedCurrencyCode(source.inheritedSource)
}

private func isCurrencyCode(_ value: String) -> Bool {
    value.range(of: #"^[A-Za-z]{3}$"#, options: .regularExpression) == value.startIndex..<value.endIndex
}

private func foundationDate(_ call: MF2FunctionCall, message: String) throws -> Date {
    if let parsed = parseFoundationDate(call.value) ?? parseSourceDate(call.inheritedSource, parser: parseFoundationDate) {
        return parsed
    }
    throw MF2Error.badOperand(message)
}

private func foundationTime(_ call: MF2FunctionCall, message: String) throws -> Date {
    if let parsed = parseFoundationTime(call.value) ?? parseSourceDate(call.inheritedSource, parser: parseFoundationTime) {
        return parsed
    }
    throw MF2Error.badOperand(message)
}

private func foundationDateTime(_ call: MF2FunctionCall, message: String) throws -> Date {
    if let parsed = parseFoundationDateTime(call.value) ?? parseSourceDate(call.inheritedSource, parser: parseFoundationDateTime) {
        return parsed
    }
    throw MF2Error.badOperand(message)
}

private func parseSourceDate(_ source: MF2FunctionSource?, parser: (String) -> Date?) -> Date? {
    guard let source else {
        return nil
    }
    if source.function.name == "date" || source.function.name == "time" || source.function.name == "datetime" {
        return parser(source.value)
    }
    return parseSourceDate(source.inheritedSource, parser: parser)
}

private func parseFoundationDate(_ value: String) -> Date? {
    guard value.count <= maxFoundationDateOperandLength else {
        return nil
    }
    return parseISO8601Date(value)
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm:ss",
            pattern: foundationDateTimeSecondPattern
        )
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm",
            pattern: foundationDateTimeMinutePattern
        )
        ?? parseFixedDate(value, format: "yyyy-MM-dd", pattern: foundationDatePattern)
}

private func parseFoundationTime(_ value: String) -> Date? {
    guard value.count <= maxFoundationDateOperandLength else {
        return nil
    }
    return parseFixedDate(value, format: "HH:mm:ss", pattern: foundationTimeSecondPattern)
        ?? parseFixedDate(value, format: "HH:mm", pattern: foundationTimeMinutePattern)
        ?? parseISO8601Date(value)
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm:ss",
            pattern: foundationDateTimeSecondPattern
        )
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm",
            pattern: foundationDateTimeMinutePattern
        )
}

private func parseFoundationDateTime(_ value: String) -> Date? {
    guard value.count <= maxFoundationDateOperandLength else {
        return nil
    }
    return parseISO8601Date(value)
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm:ss",
            pattern: foundationDateTimeSecondPattern
        )
        ?? parseFixedDate(
            value,
            format: "yyyy-MM-dd'T'HH:mm",
            pattern: foundationDateTimeMinutePattern
        )
        ?? parseFixedDate(value, format: "yyyy-MM-dd", pattern: foundationDatePattern)
}

private func parseISO8601Date(_ value: String) -> Date? {
    guard matches(value, foundationISO8601DatePattern),
          hasValidISO8601Offset(value)
    else {
        return nil
    }
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: value), isPortableDate(date) {
        return date
    }
    formatter.formatOptions = [.withInternetDateTime]
    guard let date = formatter.date(from: value), isPortableDate(date) else {
        return nil
    }
    return date
}

private func hasValidISO8601Offset(_ value: String) -> Bool {
    if value.hasSuffix("Z") {
        return true
    }
    guard let timeIndex = value.firstIndex(of: "T"),
          let signIndex = value[value.index(after: timeIndex)...].lastIndex(where: { $0 == "+" || $0 == "-" })
    else {
        return false
    }
    return parseOffsetMinutes(String(value[signIndex...])) != nil
}

private func parseOffsetMinutes(_ value: String) -> Int? {
    guard value.count == 6, let sign = value.first, sign == "+" || sign == "-" else {
        return nil
    }
    let body = value.dropFirst()
    guard body[body.index(body.startIndex, offsetBy: 2)] == ":" else {
        return nil
    }
    let hourText = String(body.prefix(2))
    let minuteText = String(body.suffix(2))
    guard hourText.allSatisfy(isAsciiDigit), minuteText.allSatisfy(isAsciiDigit),
          let hours = Int(hourText), let minutes = Int(minuteText),
          hours <= 18, minutes <= 59, !(hours == 18 && minutes != 0)
    else {
        return nil
    }
    return (hours * 60) + minutes
}

private func parseFixedDate(_ value: String, format: String, pattern: String) -> Date? {
    guard matches(value, pattern) else {
        return nil
    }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = format
    formatter.isLenient = false
    guard let date = formatter.date(from: value),
          formatter.string(from: date) == value,
          isPortableDate(date)
    else {
        return nil
    }
    return date
}

private func isPortableDate(_ value: Date) -> Bool {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let components = calendar.dateComponents([.era, .year], from: value)
    guard components.era == 1, let year = components.year else {
        return false
    }
    return year >= 1 && year <= 9999
}

private func matches(_ value: String, _ pattern: String) -> Bool {
    value.range(of: pattern, options: .regularExpression) == value.startIndex..<value.endIndex
}

private func isAsciiDigit(_ value: Character) -> Bool {
    guard value.unicodeScalars.count == 1, let scalar = value.unicodeScalars.first else {
        return false
    }
    return scalar.value >= 48 && scalar.value <= 57
}

private func dateStyleOption(_ call: MF2FunctionCall) throws -> String {
    try firstBoundedOptionValue(call, ["dateStyle", "length", "style"], defaultValue: "medium")
}

private func timeStyleOption(_ call: MF2FunctionCall) throws -> String {
    try firstBoundedOptionValue(call, ["timeStyle", "precision", "style"], defaultValue: "medium")
}

private func dateTimeDateStyleOption(_ call: MF2FunctionCall) throws -> String {
    try firstBoundedOptionValue(call, ["dateStyle", "dateLength", "style"], defaultValue: "medium")
}

private func dateTimeTimeStyleOption(_ call: MF2FunctionCall) throws -> String {
    try firstBoundedOptionValue(call, ["timeStyle", "timePrecision", "style"], defaultValue: "medium")
}

private func dateStyle(_ value: String) throws -> DateFormatter.Style {
    switch value {
    case "full":
        .full
    case "long":
        .long
    case "medium":
        .medium
    case "short":
        .short
    default:
        throw MF2Error.badOption("Date style option must be full, long, medium, or short.")
    }
}

private func timeStyle(_ value: String) throws -> DateFormatter.Style {
    switch value {
    case "full":
        .full
    case "long":
        .long
    case "medium", "second":
        .medium
    case "short":
        .short
    default:
        throw MF2Error.badOption("Time style option must be full, long, medium, short, or second.")
    }
}

private func timeZone(_ call: MF2FunctionCall) throws -> TimeZone {
    guard let identifier = try call.optionValue("timeZone") else {
        return TimeZone(secondsFromGMT: 0)!
    }
    guard identifier.count <= maxFoundationTimeZoneOptionLength else {
        throw MF2Error.badOption("timeZone option must not exceed 256 characters.")
    }
    guard let timeZone = TimeZone(identifier: identifier) else {
        throw MF2Error.badOption("timeZone option must be a valid time zone identifier.")
    }
    return timeZone
}

private func optionOneOf(
    _ call: MF2FunctionCall,
    _ name: String,
    _ allowed: Set<String>,
    defaultValue: String?
) throws -> String? {
    let value = try boundedOptionValue(call, name, defaultValue: defaultValue)
    guard let value else {
        return nil
    }
    guard allowed.contains(value) else {
        throw MF2Error.badOption("\(name) option must be one of \(allowed.sorted().joined(separator: ", ")).")
    }
    return value
}

private func firstBoundedOptionValue(
    _ call: MF2FunctionCall,
    _ names: [String],
    defaultValue: String
) throws -> String {
    for name in names {
        if let value = try boundedOptionValue(call, name) {
            return value
        }
    }
    return defaultValue
}

private func boundedOptionValue(
    _ call: MF2FunctionCall,
    _ name: String,
    defaultValue: String? = nil
) throws -> String? {
    let value = try call.optionValue(name, default: defaultValue)
    guard let value else {
        return nil
    }
    guard value.count <= maxFoundationOptionLength else {
        throw MF2Error.badOption("\(name) option must not exceed 256 characters.")
    }
    return value
}

private func foundationLocale(_ locale: String) throws -> Locale {
    guard isWellFormedFoundationLocaleIdentifier(locale) else {
        throw MF2Error.badOption("Locale option must be a valid locale identifier.")
    }
    return Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
}

private func isWellFormedFoundationLocaleIdentifier(_ locale: String) -> Bool {
    guard !locale.isEmpty, locale.count <= maxFoundationLocaleLength else {
        return false
    }
    let subtags = locale
        .replacingOccurrences(of: "_", with: "-")
        .split(separator: "-", omittingEmptySubsequences: false)
        .map(String.init)
    guard let language = subtags.first,
          (2...8).contains(language.count),
          language.allSatisfy({ isAsciiLetter($0) })
    else {
        return false
    }

    var index = 1
    while index < subtags.count {
        let subtag = subtags[index]
        if subtag.count == 1 {
            guard let singleton = subtag.first,
                  isAsciiAlphanumeric(singleton)
            else {
                return false
            }
            let isPrivateUse = subtag.lowercased() == "x"
            index += 1
            let extensionStart = index
            while index < subtags.count,
                  ((isPrivateUse ? 1 : 2)...8).contains(subtags[index].count),
                  subtags[index].allSatisfy(isAsciiAlphanumeric) {
                index += 1
            }
            guard index > extensionStart else {
                return false
            }
            if isPrivateUse {
                return index == subtags.count
            }
            continue
        }
        guard (2...8).contains(subtag.count), subtag.allSatisfy(isAsciiAlphanumeric) else {
            return false
        }
        index += 1
    }
    return true
}

private func isAsciiLetter(_ character: Character) -> Bool {
    guard let ascii = character.asciiValue else {
        return false
    }
    return (ascii >= 65 && ascii <= 90) || (ascii >= 97 && ascii <= 122)
}

private func isAsciiAlphanumeric(_ character: Character) -> Bool {
    isAsciiLetter(character) || isAsciiDigit(character)
}
