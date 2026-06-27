import Foundation

private let maxFoundationFractionDigits = 100

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
    formatter.locale = foundationLocale(call.locale)
    formatter.numberStyle = .decimal
    try applyFractionOptions(call, formatter: formatter)
    return try applySignDisplay(formatterString(formatter, value), value: value, call: call)
}

private func formatFoundationPercent(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Percent function requires a numeric operand.")
    let formatter = NumberFormatter()
    formatter.locale = foundationLocale(call.locale)
    formatter.numberStyle = .percent
    try applyFractionOptions(call, formatter: formatter)
    return try applySignDisplay(formatterString(formatter, value), value: value, call: call)
}

private func formatFoundationInteger(_ call: MF2FunctionCall) throws -> String {
    let value = try parseFoundationNumber(call, message: "Integer function requires a numeric operand.")
    let formatter = NumberFormatter()
    formatter.locale = foundationLocale(call.locale)
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
    formatter.locale = foundationLocale(call.locale)
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
    formatter.locale = foundationLocale(call.locale)
    formatter.timeZone = try timeZone(call)
    formatter.dateStyle = try dateStyle(try dateStyleOption(call))
    formatter.timeStyle = .none
    return formatter.string(from: date)
}

private func formatFoundationTime(_ call: MF2FunctionCall) throws -> String {
    let date = try foundationDate(call, message: "Time function requires a time or datetime operand.")
    let formatter = DateFormatter()
    formatter.locale = foundationLocale(call.locale)
    formatter.timeZone = try timeZone(call)
    formatter.dateStyle = .none
    formatter.timeStyle = try timeStyle(try timeStyleOption(call))
    return formatter.string(from: date)
}

private func formatFoundationDateTime(_ call: MF2FunctionCall) throws -> String {
    let date = try foundationDate(call, message: "Datetime function requires a date or datetime operand.")
    let formatter = DateFormatter()
    formatter.locale = foundationLocale(call.locale)
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
        formatter.locale = foundationLocale(call.locale)
        formatter.unitsStyle = try relativeUnitsStyle(try call.optionValue("style", default: "long") ?? "long")
        formatter.dateTimeStyle = try relativeDateTimeStyle(try call.optionValue("numeric", default: "always") ?? "always")
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
    guard let range = value.range(
        of: #"^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$"#,
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
    if value >= 0.0, try call.optionValue("signDisplay") == "always" {
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
    if let parsed = parseFoundationDate(call.value) ?? parseSourceDate(call.inheritedSource) {
        return parsed
    }
    throw MF2Error.badOperand(message)
}

private func parseSourceDate(_ source: MF2FunctionSource?) -> Date? {
    guard let source else {
        return nil
    }
    if source.function.name == "date" || source.function.name == "time" || source.function.name == "datetime" {
        return parseFoundationDate(source.value)
    }
    return parseSourceDate(source.inheritedSource)
}

private func parseFoundationDate(_ value: String) -> Date? {
    parseISO8601Date(value)
        ?? parseFixedDate(value, format: "yyyy-MM-dd'T'HH:mm:ss")
        ?? parseFixedDate(value, format: "yyyy-MM-dd'T'HH:mm")
        ?? parseFixedDate(value, format: "yyyy-MM-dd")
        ?? parseFixedDate(value, format: "HH:mm:ss")
        ?? parseFixedDate(value, format: "HH:mm")
}

private func parseISO8601Date(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: value) {
        return date
    }
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.date(from: value)
}

private func parseFixedDate(_ value: String, format: String) -> Date? {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = format
    return formatter.date(from: value)
}

private func dateStyleOption(_ call: MF2FunctionCall) throws -> String {
    try call.optionValue(
        "dateStyle",
        default: try call.optionValue("length", default: try call.optionValue("style", default: "medium"))
    ) ?? "medium"
}

private func timeStyleOption(_ call: MF2FunctionCall) throws -> String {
    try call.optionValue(
        "timeStyle",
        default: try call.optionValue("precision", default: try call.optionValue("style", default: "medium"))
    ) ?? "medium"
}

private func dateTimeDateStyleOption(_ call: MF2FunctionCall) throws -> String {
    try call.optionValue(
        "dateStyle",
        default: try call.optionValue("dateLength", default: try call.optionValue("style", default: "medium"))
    ) ?? "medium"
}

private func dateTimeTimeStyleOption(_ call: MF2FunctionCall) throws -> String {
    try call.optionValue(
        "timeStyle",
        default: try call.optionValue("timePrecision", default: try call.optionValue("style", default: "medium"))
    ) ?? "medium"
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
    let value = try call.optionValue(name, default: defaultValue)
    guard let value else {
        return nil
    }
    guard allowed.contains(value) else {
        throw MF2Error.badOption("\(name) option must be one of \(allowed.sorted().joined(separator: ", ")).")
    }
    return value
}

private func foundationLocale(_ locale: String) -> Locale {
    Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
}
