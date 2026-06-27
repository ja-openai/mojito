import Foundation

public enum MF2NumberCore {
    private static let absentOption = "\0__mojito_mf2_absent__"
    private static let maxLocaleLength = 256
    private static let maxOptionLength = 256
    private static let maxOperandLength = 256
    private static let maxFractionDigits = 100
    private static let maxAbsoluteFormatValue = Decimal(string: "1e21", locale: Locale(identifier: "en_US_POSIX"))!

    public enum Style {
        case number
        case integer
        case percent
        case currency
    }

    public enum CurrencyDisplay {
        case symbol
        case narrowSymbol
        case code
    }

    public enum SignDisplay {
        case auto
        case always
        case never
    }

    public struct Options {
        public var locale: String
        public var style: Style
        public var currency: String?
        public var currencyDisplay: CurrencyDisplay
        public var useGrouping: Bool
        public var minimumFractionDigits: Int?
        public var maximumFractionDigits: Int?
        public var signDisplay: SignDisplay

        public init(
            locale: String = "en-US",
            style: Style = .number,
            currency: String? = nil,
            currencyDisplay: CurrencyDisplay = .symbol,
            useGrouping: Bool = true,
            minimumFractionDigits: Int? = nil,
            maximumFractionDigits: Int? = nil,
            signDisplay: SignDisplay = .auto
        ) {
            self.locale = locale
            self.style = style
            self.currency = currency
            self.currencyDisplay = currencyDisplay
            self.useGrouping = useGrouping
            self.minimumFractionDigits = minimumFractionDigits
            self.maximumFractionDigits = maximumFractionDigits
            self.signDisplay = signDisplay
        }
    }

    private static let defaultLocale = "en-US"
    private static let decimalText = try! NSRegularExpression(
        pattern: #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$"#
    )
    private static let currencyCode = try! NSRegularExpression(pattern: #"^[A-Za-z]{3}$"#)

    public static func registry() -> MF2FunctionRegistry {
        MF2FunctionRegistry.portable
            .withFunction("number") { try formatCall($0, style: .number) }
            .withFunction("integer") { try formatCall($0, style: .integer) }
            .withFunction("percent") { try formatCall($0, style: .percent) }
            .withFunction("currency") { try formatCall($0, style: .currency) }
    }

    public static func format(_ value: MF2Value, options: Options = Options()) throws -> String {
        let localeData = try resolveLocaleData(options.locale)
        guard var parsed = parseFiniteNumber(value) else {
            throw MF2Error.badOperand("Number core requires a finite numeric value.")
        }

        let currency = try options.style == .currency ? parseCurrency(options.currency) : nil
        let pattern = patternForStyle(localeData, options.style)
        let fraction = try fractionOptions(style: options.style, currency: currency, options: options, pattern: pattern)
        if options.style == .integer {
            parsed = NumericValue(decimal: truncatedToInteger(parsed.decimal), double: parsed.double.rounded(.towardZero))
        }
        if options.style == .percent {
            parsed = NumericValue(decimal: parsed.decimal * Decimal(100), double: parsed.double * 100)
        }
        try ensureSupportedMagnitude(parsed)

        let formatted = formatDecimal(abs(parsed.decimal), localeData: localeData, pattern: pattern, fraction: fraction, options: options)

        switch options.style {
        case .percent:
            return applySignedPattern(
                pattern,
                formatted: formatted,
                value: parsed,
                symbols: localeData.symbols,
                signDisplay: options.signDisplay,
                percentSign: localeData.symbols["percentSign"]
            )
        case .currency:
            return applySignedPattern(
                pattern,
                formatted: formatted,
                value: parsed,
                symbols: localeData.symbols,
                signDisplay: options.signDisplay,
                currency: currencyDisplay(localeData: localeData, currency: currency ?? "", display: options.currencyDisplay)
            )
        case .number, .integer:
            return applySign(formatted, value: parsed.double, symbols: localeData.symbols, signDisplay: options.signDisplay)
        }
    }

    public static func formatToParts(_ value: MF2Value, options: Options = Options()) throws -> [MF2FormattedPart] {
        [.text(try format(value, options: options))]
    }

    private static func formatCall(_ call: MF2FunctionCall, style: Style) throws -> String {
        try format(
            callNumberValue(call, style: style),
            options: Options(
                locale: call.locale,
                style: style,
                currency: currencyOption(call, style: style),
                currencyDisplay: currencyDisplayOption(call.optionValue("currencyDisplay", default: "symbol") ?? "symbol"),
                useGrouping: booleanOption(call.optionValue("useGrouping", default: "true") ?? "true", name: "useGrouping"),
                minimumFractionDigits: integerOption(call.optionValue("minimumFractionDigits")),
                maximumFractionDigits: integerOption(call.optionValue("maximumFractionDigits")),
                signDisplay: signDisplayOption(call.optionValue("signDisplay", default: "auto") ?? "auto")
            )
        )
    }

    private static func currencyOption(_ call: MF2FunctionCall, style: Style) throws -> String? {
        if style == .currency {
            return try inheritedOptionValue(call, "currency")
        }
        return try call.optionValue("currency")
    }

    private static func inheritedOptionValue(_ call: MF2FunctionCall, _ name: String) throws -> String? {
        let own = try call.optionValue(name, default: absentOption)
        if own != absentOption {
            return own
        }
        var source = call.inheritedSource
        while let current = source {
            let value = try current.optionValue(name, default: absentOption)
            if value != absentOption {
                return value
            }
            source = current.inheritedSource
        }
        return nil
    }

    private static func callNumberValue(_ call: MF2FunctionCall, style: Style) -> MF2Value {
        if let source = call.inheritedSource {
            if style == .number, source.function.name == "integer",
               let parsed = parseFiniteNumber(.number(source.value)) {
                return .number(NSDecimalNumber(decimal: truncatedToInteger(parsed.decimal)).stringValue)
            }
            return .number(source.value)
        }
        return call.rawValue
    }

    private static func resolveLocaleData(_ locale: String) throws -> CldrNumberData.LocaleData {
        let locale = try localeOption(locale)
        for candidate in MF2LocaleKey.lookupChain(locale) {
            if let exact = CldrNumberData.locales[candidate] {
                return exact
            }
            if let inherited = CldrNumberData.locales.values.first(where: { $0.numbersSourceLocale == candidate }) {
                return inherited
            }
        }
        return CldrNumberData.locales[defaultLocale]!
    }

    private static func localeOption(_ locale: String) throws -> String {
        let value = locale.isEmpty ? defaultLocale : locale
        if value.count > maxLocaleLength {
            throw MF2Error.badOption("locale must not exceed 256 characters.")
        }
        return value
    }

    private static func patternForStyle(_ localeData: CldrNumberData.LocaleData, _ style: Style) -> String {
        switch style {
        case .percent:
            localeData.percentPattern
        case .currency:
            localeData.currencyPattern
        case .number, .integer:
            localeData.decimalPattern
        }
    }

    private static func fractionOptions(
        style: Style,
        currency: String?,
        options: Options,
        pattern: String
    ) throws -> FractionOptions {
        var defaults = fractionDefaultsFromPattern(pattern)
        if style == .integer {
            defaults = FractionOptions(minimum: 0, maximum: 0)
        }
        if style == .currency {
            let currencyDefaults = CldrNumberData.currencyFractions[currency ?? ""]
                ?? CldrNumberData.currencyFractions["DEFAULT"]!
            defaults = FractionOptions(minimum: currencyDefaults.digits, maximum: currencyDefaults.digits)
        }
        var minimum = options.minimumFractionDigits ?? defaults.minimum
        var maximum = options.maximumFractionDigits ?? defaults.maximum
        if minimum < 0 || minimum > maxFractionDigits {
            throw MF2Error.badOption("minimumFractionDigits must be a non-negative integer.")
        }
        if maximum < 0 || maximum > maxFractionDigits {
            throw MF2Error.badOption("maximumFractionDigits must be a non-negative integer.")
        }
        if options.minimumFractionDigits != nil, options.maximumFractionDigits == nil, maximum < minimum {
            maximum = minimum
        }
        if options.maximumFractionDigits != nil, options.minimumFractionDigits == nil, maximum < minimum {
            minimum = maximum
        }
        if maximum < minimum {
            throw MF2Error.badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
        }
        return FractionOptions(minimum: minimum, maximum: maximum)
    }

    private static func fractionDefaultsFromPattern(_ pattern: String) -> FractionOptions {
        let number = numberPattern(pattern)
        guard let dot = number.firstIndex(of: ".") else {
            return FractionOptions(minimum: 0, maximum: 0)
        }
        let fraction = number[number.index(after: dot)...]
        return FractionOptions(minimum: fraction.filter { $0 == "0" }.count, maximum: fraction.count)
    }

    private static func formatDecimal(
        _ value: Decimal,
        localeData: CldrNumberData.LocaleData,
        pattern: String,
        fraction: FractionOptions,
        options: Options
    ) -> String {
        let handler = NSDecimalNumberHandler(
            roundingMode: .plain,
            scale: Int16(fraction.maximum),
            raiseOnExactness: false,
            raiseOnOverflow: false,
            raiseOnUnderflow: false,
            raiseOnDivideByZero: false
        )
        let rounded = NSDecimalNumber(decimal: value).rounding(accordingToBehavior: handler).stringValue
        let pieces = rounded.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        var integer = pieces.first ?? "0"
        var decimal = pieces.count > 1 ? pieces[1] : ""
        while decimal.count > fraction.minimum, decimal.hasSuffix("0") {
            decimal.removeLast()
        }
        while decimal.count < fraction.minimum {
            decimal.append("0")
        }

        let grouping = groupingInfo(pattern)
        if options.useGrouping, shouldGroup(integer: integer, grouping: grouping, minimumGroupingDigits: localeData.minimumGroupingDigits) {
            integer = groupInteger(integer, grouping: grouping, separator: localeData.symbols["group"] ?? ",")
        }
        integer = localizeDigits(integer, digits: localeData.numberingSystemDigits)
        if !decimal.isEmpty {
            return integer + (localeData.symbols["decimal"] ?? ".") + localizeDigits(decimal, digits: localeData.numberingSystemDigits)
        }
        return integer
    }

    private static func groupingInfo(_ pattern: String) -> GroupingInfo {
        let integerPattern = numberPattern(pattern).split(separator: ".", maxSplits: 1).first.map(String.init) ?? ""
        let groups = integerPattern.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        guard groups.count > 1 else {
            return GroupingInfo(primary: 0, secondary: 0)
        }
        let primary = placeholderCount(groups[groups.count - 1])
        let secondary = groups.count > 2 ? placeholderCount(groups[groups.count - 2]) : primary
        return GroupingInfo(primary: primary, secondary: secondary)
    }

    private static func shouldGroup(integer: String, grouping: GroupingInfo, minimumGroupingDigits: Int) -> Bool {
        guard grouping.primary > 0 else {
            return false
        }
        return integer.count >= grouping.primary + minimumGroupingDigits
    }

    private static func groupInteger(_ integer: String, grouping: GroupingInfo, separator: String) -> String {
        var output = ""
        var end = integer.endIndex
        var size = grouping.primary
        while end > integer.startIndex {
            let start = integer.index(end, offsetBy: -size, limitedBy: integer.startIndex) ?? integer.startIndex
            if !output.isEmpty {
                output = separator + output
            }
            output = String(integer[start..<end]) + output
            end = start
            size = grouping.secondary == 0 ? grouping.primary : grouping.secondary
        }
        return output
    }

    private static func applySign(
        _ formatted: String,
        value: Double,
        symbols: [String: String],
        signDisplay: SignDisplay
    ) -> String {
        if signDisplay == .never {
            return formatted
        }
        if value.sign == .minus {
            return (symbols["minusSign"] ?? "-") + formatted
        }
        if signDisplay == .always {
            return (symbols["plusSign"] ?? "+") + formatted
        }
        return formatted
    }

    private static func truncatedToInteger(_ value: Decimal) -> Decimal {
        var input = value
        var output = Decimal()
        NSDecimalRound(&output, &input, 0, value < 0 ? .up : .down)
        return output
    }

    private static func applyPattern(
        _ pattern: String,
        formatted: String,
        percentSign: String? = nil,
        currency: String? = nil
    ) -> String {
        var output = replaceFirstNumberPattern(in: pattern, with: formatted)
        if let percentSign {
            output = output.replacingOccurrences(of: "%", with: percentSign)
        }
        if let currency {
            output = output.replacingOccurrences(of: "\u{00a4}", with: currency)
        }
        return output
    }

    private static func applySignedPattern(
        _ pattern: String,
        formatted: String,
        value: NumericValue,
        symbols: [String: String],
        signDisplay: SignDisplay,
        percentSign: String? = nil,
        currency: String? = nil
    ) -> String {
        let parts = pattern.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
        let positivePattern = String(parts[0])
        if value.double.sign == .minus, signDisplay != .never {
            if parts.count > 1 {
                return applyPattern(String(parts[1]), formatted: formatted, percentSign: percentSign, currency: currency)
            }
            return (symbols["minusSign"] ?? "-") + applyPattern(positivePattern, formatted: formatted, percentSign: percentSign, currency: currency)
        }
        let output = applyPattern(positivePattern, formatted: formatted, percentSign: percentSign, currency: currency)
        if signDisplay == .always {
            return (symbols["plusSign"] ?? "+") + output
        }
        return output
    }

    private static func currencyDisplay(
        localeData: CldrNumberData.LocaleData,
        currency: String,
        display: CurrencyDisplay
    ) -> String {
        if display == .code {
            return currencyCodeDisplay(localeData: localeData, currency: currency)
        }
        guard let data = localeData.currencies[currency] else {
            return currency
        }
        if display == .narrowSymbol, let narrow = data.narrowSymbol {
            return narrow
        }
        return data.symbol ?? currency
    }

    private static func currencyCodeDisplay(
        localeData: CldrNumberData.LocaleData,
        currency: String
    ) -> String {
        let positivePattern = localeData.currencyPattern.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
        let before = positivePattern.contains("#\u{a4}") || positivePattern.contains("0\u{a4}")
            ? localeData.currencySpacing.beforeCurrency
            : ""
        let after = positivePattern.contains("\u{a4}#") || positivePattern.contains("\u{a4}0")
            ? localeData.currencySpacing.afterCurrency
            : ""
        return before + currency + after
    }

    private static func parseFiniteNumber(_ value: MF2Value) -> NumericValue? {
        switch value {
        case let .number(text), let .string(text):
            guard text.count <= maxOperandLength,
                  decimalText.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil,
                  let double = Double(text),
                  double.isFinite,
                  let decimal = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))
            else {
                return nil
            }
            return NumericValue(decimal: decimal, double: double)
        case .bool, .null:
            return nil
        }
    }

    private static func ensureSupportedMagnitude(_ value: NumericValue) throws {
        if !value.double.isFinite || abs(value.decimal) >= maxAbsoluteFormatValue {
            throw MF2Error.badOperand("Number core numeric value is outside the supported magnitude.")
        }
    }

    private static func parseCurrency(_ value: String?) throws -> String {
        guard let value else {
            throw MF2Error.badOption("currency must be a three-letter ISO 4217 code.")
        }
        let text = try optionName(value, name: "currency")
        guard currencyCode.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        else {
            throw MF2Error.badOption("currency must be a three-letter ISO 4217 code.")
        }
        return text.uppercased()
    }

    private static func integerOption(_ value: String?) throws -> Int? {
        guard let value else {
            return nil
        }
        guard !value.isEmpty, value.allSatisfy(isAsciiDigit), let parsed = Int(value) else {
            throw MF2Error.badOption("Option must be a non-negative integer.")
        }
        return parsed
    }

    private static func signDisplayOption(_ value: String) throws -> SignDisplay {
        switch try optionName(value, name: "signDisplay") {
        case "auto":
            .auto
        case "always":
            .always
        case "never":
            .never
        default:
            throw MF2Error.badOption("signDisplay must be auto, always, or never.")
        }
    }

    private static func currencyDisplayOption(_ value: String) throws -> CurrencyDisplay {
        switch try optionName(value, name: "currencyDisplay") {
        case "symbol":
            .symbol
        case "narrowSymbol":
            .narrowSymbol
        case "code":
            .code
        default:
            throw MF2Error.badOption("currencyDisplay must be symbol, narrowSymbol, or code.")
        }
    }

    private static func booleanOption(_ value: String, name: String) throws -> Bool {
        switch try optionName(value, name: name) {
        case "true":
            true
        case "false":
            false
        default:
            throw MF2Error.badOption("\(name) must be true or false.")
        }
    }

    private static func optionName(_ value: String, name: String) throws -> String {
        if value.count > maxOptionLength {
            throw MF2Error.badOption("\(name) must not exceed 256 characters.")
        }
        return value
    }

    private static func numberPattern(_ pattern: String) -> String {
        var output = ""
        var found = false
        for character in pattern {
            if "#0,.".contains(character) {
                output.append(character)
                found = true
            } else if found {
                break
            }
        }
        return output
    }

    private static func replaceFirstNumberPattern(in pattern: String, with value: String) -> String {
        guard let start = pattern.firstIndex(where: { "#0,.".contains($0) }) else {
            return pattern
        }
        var end = start
        while end < pattern.endIndex, "#0,.".contains(pattern[end]) {
            end = pattern.index(after: end)
        }
        var output = pattern
        output.replaceSubrange(start..<end, with: value)
        return output
    }

    private static func placeholderCount(_ pattern: String) -> Int {
        pattern.filter { $0 == "#" || $0 == "0" }.count
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

    private static func isAsciiDigit(_ character: Character) -> Bool {
        guard let ascii = character.asciiValue else {
            return false
        }
        return ascii >= 48 && ascii <= 57
    }

    private struct NumericValue {
        let decimal: Decimal
        let double: Double
    }

    private struct FractionOptions {
        let minimum: Int
        let maximum: Int
    }

    private struct GroupingInfo {
        let primary: Int
        let secondary: Int
    }
}
