import Foundation

public enum MF2RelativeTimeCore {
    private static let maxLocaleLength = 256
    private static let maxOptionLength = 256
    private static let maxOperandLength = 256
    private static let maxRelativeTimeQuantity = 1_000_000_000.0
    private static let decimalNumberPattern = #"^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$"#

    public enum Style {
        case long
        case short
        case narrow

        public static func fromName(_ value: String) throws -> Style {
            switch try MF2RelativeTimeCore.optionName(value, name: "style") {
            case "long":
                .long
            case "short":
                .short
            case "narrow":
                .narrow
            default:
                throw MF2Error.badOption("style must be one of long, short, narrow.")
            }
        }

        var key: String {
            switch self {
            case .long:
                "long"
            case .short:
                "short"
            case .narrow:
                "narrow"
            }
        }
    }

    public enum Numeric {
        case always
        case auto

        public static func fromName(_ value: String) throws -> Numeric {
            switch try MF2RelativeTimeCore.optionName(value, name: "numeric") {
            case "always":
                .always
            case "auto":
                .auto
            default:
                throw MF2Error.badOption("numeric must be one of always, auto.")
            }
        }
    }

    public enum Policy {
        case precise
        case compact
        case chat

        public static func fromName(_ value: String) throws -> Policy {
            switch try MF2RelativeTimeCore.optionName(value, name: "policy") {
            case "precise":
                .precise
            case "compact":
                .compact
            case "chat":
                .chat
            default:
                throw MF2Error.badOption("policy must be one of precise, compact, chat.")
            }
        }

        var steps: [PolicyStep] {
            switch self {
            case .precise:
                [
                    PolicyStep(upper: 60, unit: .second),
                    PolicyStep(upper: 3_600, unit: .minute),
                    PolicyStep(upper: 86_400, unit: .hour),
                    PolicyStep(upper: 604_800, unit: .day),
                    PolicyStep(upper: 2_592_000, unit: .week),
                    PolicyStep(upper: 31_536_000, unit: .month),
                    PolicyStep(upper: .infinity, unit: .year),
                ]
            case .compact:
                [
                    PolicyStep(upper: 60, unit: .second),
                    PolicyStep(upper: 3_600, unit: .minute),
                    PolicyStep(upper: 86_400, unit: .hour),
                    PolicyStep(upper: .infinity, unit: .day),
                ]
            case .chat:
                [
                    PolicyStep(upper: 45, unit: .second),
                    PolicyStep(upper: 2_700, unit: .minute),
                    PolicyStep(upper: 79_200, unit: .hour),
                    PolicyStep(upper: 604_800, unit: .day),
                    PolicyStep(upper: .infinity, unit: .week),
                ]
            }
        }
    }

    public enum Unit {
        case auto
        case second
        case minute
        case hour
        case day
        case week
        case month
        case quarter
        case year

        public static func fromName(_ value: String) throws -> Unit {
            switch try MF2RelativeTimeCore.optionName(value, name: "unit") {
            case "auto":
                .auto
            case "second":
                .second
            case "minute":
                .minute
            case "hour":
                .hour
            case "day":
                .day
            case "week":
                .week
            case "month":
                .month
            case "quarter":
                .quarter
            case "year":
                .year
            default:
                throw MF2Error.badOption("unit must be one of auto, second, minute, hour, day, week, month, quarter, year.")
            }
        }

        var key: String {
            switch self {
            case .auto:
                "auto"
            case .second:
                "second"
            case .minute:
                "minute"
            case .hour:
                "hour"
            case .day:
                "day"
            case .week:
                "week"
            case .month:
                "month"
            case .quarter:
                "quarter"
            case .year:
                "year"
            }
        }

        var seconds: Double {
            switch self {
            case .auto:
                1
            case .second:
                1
            case .minute:
                60
            case .hour:
                3_600
            case .day:
                86_400
            case .week:
                604_800
            case .month:
                2_592_000
            case .quarter:
                7_776_000
            case .year:
                31_536_000
            }
        }
    }

    public struct Options {
        public var locale: String
        public var style: Style
        public var numeric: Numeric
        public var policy: Policy
        public var unit: Unit

        public init(
            locale: String = "en",
            style: Style = .short,
            numeric: Numeric = .always,
            policy: Policy = .precise,
            unit: Unit = .auto
        ) {
            self.locale = locale
            self.style = style
            self.numeric = numeric
            self.policy = policy
            self.unit = unit
        }
    }

    public struct Data: Decodable {
        public let localeMap: [String: String]
        public let patternSets: [PatternSet]

        public init(localeMap: [String: String], patternSets: [PatternSet]) {
            self.localeMap = localeMap
            self.patternSets = patternSets
        }

        private enum CodingKeys: String, CodingKey {
            case localeMap
            case patternSets
        }
    }

    public struct PatternSet: Decodable {
        public let id: String
        public let data: [String: [String: UnitData]]

        public init(id: String, data: [String: [String: UnitData]]) {
            self.id = id
            self.data = data
        }
    }

    public struct UnitData: Decodable {
        public let future: [String: String]
        public let past: [String: String]
        public let relative: [String: String]

        public init(
            future: [String: String] = [:],
            past: [String: String] = [:],
            relative: [String: String] = [:]
        ) {
            self.future = future
            self.past = past
            self.relative = relative
        }

        private enum CodingKeys: String, CodingKey {
            case future
            case past
            case relative
        }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            future = try container.decodeIfPresent([String: String].self, forKey: .future) ?? [:]
            past = try container.decodeIfPresent([String: String].self, forKey: .past) ?? [:]
            relative = try container.decodeIfPresent([String: String].self, forKey: .relative) ?? [:]
        }
    }

    public struct Formatter {
        private let localeMap: [String: String]
        private let patternSets: [String: PatternSet]

        public init(data: Data) throws {
            guard !data.localeMap.isEmpty, !data.patternSets.isEmpty else {
                throw MF2Error.missingLocaleData("Relative-time core data has an unsupported shape.")
            }
            localeMap = data.localeMap
            var nextPatternSets: [String: PatternSet] = [:]
            for patternSet in data.patternSets where !patternSet.id.isEmpty && !patternSet.data.isEmpty {
                nextPatternSets[patternSet.id] = patternSet
            }
            patternSets = nextPatternSets
            if patternSets.isEmpty {
                throw MF2Error.missingLocaleData("Relative-time core data has an unsupported shape.")
            }
        }

        public func registry() -> MF2FunctionRegistry {
            MF2FunctionRegistry.portable
                .withFunction("relativeTime") { call in
                    try formatCall(call)
                }
        }

        public func format(_ value: MF2Value, options: Options = Options()) throws -> String {
            let seconds = try parseFiniteNumber(value)
            let unit = options.unit == .auto ? selectUnit(seconds, policy: options.policy) : options.unit
            let quantity = try relativeTimeQuantity(seconds, unit: unit)
            let locale = try MF2RelativeTimeCore.localeOption(options.locale)

            if useRelativeZero(policy: options.policy, numeric: options.numeric, seconds: seconds),
               let relative = try relativeTerm(locale: locale, style: options.style, unit: unit, offset: "0")
            {
                return relative
            }
            if options.numeric == .auto, let offset = relativeOffset(seconds, unit: unit, quantity: quantity),
               let relative = try relativeTerm(locale: locale, style: options.style, unit: unit, offset: offset)
            {
                return relative
            }

            let direction = isNegativeRelativeTime(seconds) ? "past" : "future"
            let data = try unitData(locale: locale, style: options.style, unit: unit)
            let category = NumberOperands(quantity.text)
                .map { selectCardinal(locale: locale, operands: $0) } ?? "other"
            let pattern = try relativeTimePattern(
                data: data,
                locale: locale,
                style: options.style,
                unit: unit,
                direction: direction,
                category: category
            )
            return pattern.replacingOccurrences(of: "{0}", with: quantity.text)
        }

        public func formatToParts(_ value: MF2Value, options: Options = Options()) throws -> [MF2FormattedPart] {
            [.text(try format(value, options: options))]
        }

        private func formatCall(_ call: MF2FunctionCall) throws -> String {
            let options = try Options(
                locale: call.locale,
                style: Style.fromName(try call.optionValue("style", default: "short") ?? "short"),
                numeric: Numeric.fromName(try call.optionValue("numeric", default: "always") ?? "always"),
                policy: Policy.fromName(try call.optionValue("policy", default: "precise") ?? "precise"),
                unit: Unit.fromName(try call.optionValue("unit", default: "auto") ?? "auto")
            )
            do {
                return try format(call.rawValue, options: options)
            } catch let error as MF2Error {
                guard error.code == "bad-operand", let sourceValue = relativeTimeSourceValue(call.inheritedSource) else {
                    throw error
                }
                return try format(.string(sourceValue), options: options)
            }
        }

        private func relativeTimeSourceValue(_ source: MF2FunctionSource?) -> String? {
            var current = source
            while let source = current {
                if isRelativeTimeNumericSourceFunction(source.function.name) {
                    return source.value
                }
                current = source.inheritedSource
            }
            return nil
        }

        private func isRelativeTimeNumericSourceFunction(_ name: String) -> Bool {
            switch name {
            case "number", "integer", "percent", "offset", "currency", "relativeTime":
                return true
            default:
                return false
            }
        }

        private func relativeTerm(
            locale: String,
            style: Style,
            unit: Unit,
            offset: String
        ) throws -> String? {
            try unitData(locale: locale, style: style, unit: unit).relative[offset]
        }

        private func relativeTimePattern(
            data: UnitData,
            locale: String,
            style: Style,
            unit: Unit,
            direction: String,
            category: String
        ) throws -> String {
            let patterns = direction == "past" ? data.past : data.future
            if let pattern = patterns[category] ?? patterns["other"] {
                return pattern
            }
            throw MF2Error.missingLocaleData(
                "Missing relative-time pattern for \(locale)/\(style.key)/\(unit.key)/\(direction)."
            )
        }

        private func unitData(locale: String, style: Style, unit: Unit) throws -> UnitData {
            let patternSet = try patternSetFor(locale: locale)
            guard let data = patternSet.data[style.key]?[unit.key] else {
                throw MF2Error.missingLocaleData("Missing relative-time unit data for \(locale)/\(style.key)/\(unit.key).")
            }
            return data
        }

        private func patternSetFor(locale: String) throws -> PatternSet {
            for candidate in MF2LocaleKey.lookupChain(locale) {
                guard let setId = localeMap[candidate] else {
                    continue
                }
                guard let patternSet = patternSets[setId] else {
                    throw MF2Error.missingLocaleData("Missing relative-time pattern set \(setId).")
                }
                return patternSet
            }
            throw MF2Error.missingLocaleData("Missing relative-time locale data for \(locale).")
        }
    }

    public static func registry(data: Data) throws -> MF2FunctionRegistry {
        try Formatter(data: data).registry()
    }

    public static func format(_ value: MF2Value, data: Data, options: Options = Options()) throws -> String {
        try Formatter(data: data).format(value, options: options)
    }

    private static func localeOption(_ locale: String) throws -> String {
        let value = locale.isEmpty ? "en" : locale
        if value.count > maxLocaleLength {
            throw MF2Error.badOption("locale must not exceed 256 characters.")
        }
        return value
    }

    private static func optionName(_ value: String, name: String) throws -> String {
        if value.count > maxOptionLength {
            throw MF2Error.badOption("\(name) must not exceed 256 characters.")
        }
        return value
    }

    public static func formatToParts(_ value: MF2Value, data: Data, options: Options = Options()) throws -> [MF2FormattedPart] {
        try Formatter(data: data).formatToParts(value, options: options)
    }

    struct PolicyStep {
        let upper: Double
        let unit: Unit
    }

    private static func parseFiniteNumber(_ value: MF2Value) throws -> Double {
        let raw: String
        switch value {
        case let .number(value), let .string(value):
            raw = value.trimmingCharacters(in: .whitespacesAndNewlines)
        case .bool, .null:
            throw MF2Error.badOperand("Relative-time core requires a finite numeric value.")
        }
        guard raw.count <= maxOperandLength,
            !raw.isEmpty,
            raw.range(of: decimalNumberPattern, options: .regularExpression) != nil,
            let parsed = Double(raw),
            parsed.isFinite
        else {
            throw MF2Error.badOperand("Relative-time core requires a finite numeric value.")
        }
        return parsed
    }

    private static func selectUnit(_ seconds: Double, policy: Policy) -> Unit {
        let absolute = abs(seconds)
        return policy.steps.first(where: { absolute < $0.upper })?.unit ?? .year
    }

    private static func relativeTimeQuantity(_ seconds: Double, unit: Unit) throws -> RelativeQuantity {
        let absolute = abs(seconds)
        if absolute == 0 {
            return RelativeQuantity(value: 0, text: "0")
        }
        let quantity = max(1, floor(absolute / unit.seconds + 0.5))
        if quantity > maxRelativeTimeQuantity {
            throw MF2Error.badOperand("Relative-time core quantity is outside the supported range.")
        }
        return RelativeQuantity(value: quantity, text: mf2FormatWholeDoubleNative(quantity))
    }

    private static func useRelativeZero(policy: Policy, numeric: Numeric, seconds: Double) -> Bool {
        policy == .chat && numeric == .auto && abs(seconds) < 45
    }

    private static func relativeOffset(_ seconds: Double, unit: Unit, quantity: RelativeQuantity) -> String? {
        if quantity.value == 0 {
            return "0"
        }
        if abs(seconds) != quantity.value * unit.seconds {
            return nil
        }
        return isNegativeRelativeTime(seconds) ? "-\(quantity.text)" : quantity.text
    }

    private static func isNegativeRelativeTime(_ seconds: Double) -> Bool {
        seconds < 0 || seconds.sign == .minus
    }

    private struct RelativeQuantity {
        let value: Double
        let text: String
    }
}
