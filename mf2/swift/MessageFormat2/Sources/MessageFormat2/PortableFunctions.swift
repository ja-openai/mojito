import Foundation

private let maximumPortableFractionDigits = 1_000

func makePortableFunctionRegistry() -> MF2FunctionRegistry {
    let formatters: [String: MF2FunctionFormatter] = [
        "string": passthroughFunction,
        "number": formatUnlocalizedNumber,
        "percent": formatUnlocalizedPercent,
        "integer": formatUnlocalizedInteger,
        "offset": offsetFunction,
    ]
    let selectors: [String: MF2FunctionSelector] = [
        "number": selectNumber,
        "percent": selectPercent,
        "integer": selectInteger,
        "offset": selectOffset,
    ]
    return MF2FunctionRegistry(formatters: formatters, selectors: selectors)
}

private func passthroughFunction(_ call: MF2FunctionCall) throws -> String {
    call.value
}

private func formatUnlocalizedNumber(_ call: MF2FunctionCall) throws -> String {
    let value = try parseCallNumber(call, error: .badOperand("Number function requires a numeric operand."))
    var formatted = try formatUnlocalizedNumberWithMaximumFractionDigits(
        value,
        maximumFractionDigits: maximumFractionDigits(call)
    )
    formatted = try appendMinimumFractionDigits(formatted, minimumFractionDigits(call))
    return try signDisplayAlways(call) && value >= 0 ? "+\(formatted)" : formatted
}

private func formatUnlocalizedPercent(_ call: MF2FunctionCall) throws -> String {
    let value = try parseCallNumber(call, error: .badOperand("Percent function requires a numeric operand."))
    let percent = value * 100
    guard percent.isFinite else {
        throw MF2Error.badOperand("Percent function requires a bounded numeric operand.")
    }
    var formatted = try formatUnlocalizedNumberWithMaximumFractionDigits(
        percent,
        maximumFractionDigits: maximumFractionDigits(call)
    )
    if try signDisplayAlways(call), value >= 0 {
        formatted = "+\(formatted)"
    }
    return try "\(appendMinimumFractionDigits(formatted, minimumFractionDigits(call)))%"
}

private func formatUnlocalizedInteger(_ call: MF2FunctionCall) throws -> String {
    let value = try parseCallNumber(call, error: .badOperand("Integer function requires a numeric operand."))
    let integer = try truncatedInteger(
        value,
        error: .badOperand("Integer function requires an operand in the supported integer range.")
    )
    return try signDisplayAlways(call) && integer >= 0 ? "+\(integer)" : String(integer)
}

private func offsetFunction(_ call: MF2FunctionCall) throws -> String {
    let operand = try resolvedNumericSourceText(call.inheritedSource) ?? call.value
    guard let value = parseDecimal(operand) else {
        throw MF2Error.badOperand("Offset function requires a numeric operand.")
    }
    let add = try call.optionValue("add")
    let subtract = try call.optionValue("subtract")
    guard (add == nil) != (subtract == nil) else {
        throw MF2Error.badOption("Offset function requires exactly one of add or subtract.")
    }
    let offset: Int
    if let add {
        offset = try parseInteger(add, error: .badOption("Offset add option must be an integer."))
    } else {
        offset = try parseInteger(
            subtract ?? "",
            error: .badOption("Offset subtract option must be an integer.")
        )
    }
    guard let result = applyIntegerOffset(
        value,
        offset: offset,
        subtract: subtract != nil
    ) else {
        throw MF2Error.badOperand("Offset function result is outside the supported decimal range.")
    }
    let formatted = canonicalDecimal(result)
    let signAlways = try resolvedOptionValue(
        call,
        name: "signDisplay",
        inheritedFrom: numericOptionSources(for: call.function.name)
    ) == "always"
    return signAlways && !decimalIsNegative(result) ? "+\(formatted)" : formatted
}

private func selectNumber(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Number selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Number selector requires a numeric operand."))
    try validateNumericVariantKey(match.key)
    return try match.key == numericMatchOperand(match, value: value) ? 2 : nil
}

private func selectPercent(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Percent selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Percent selector requires a numeric operand.")) * 100
    guard value.isFinite else {
        throw MF2Error.badSelector("Percent selector requires a bounded numeric operand.")
    }
    try validateNumericVariantKey(match.key)
    return try match.key == numericMatchOperand(match, value: value) ? 2 : nil
}

private func selectInteger(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Integer selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Integer selector requires a numeric operand."))
    try validateNumericVariantKey(match.key)
    let integer = try truncatedInteger(
        value,
        error: .badSelector("Integer selector operand is outside the supported integer range.")
    )
    return match.key == String(integer) ? 2 : nil
}

private func selectOffset(_ match: MF2FunctionMatch) throws -> Int? {
    let value = try parseMatchNumber(
        match,
        error: .badSelector("Offset selector requires a numeric operand.")
    )
    try validateNumericVariantKey(match.key)
    return try match.key == numericMatchOperand(match, value: value) ? 2 : nil
}

private func numericMatchOperand(_ match: MF2FunctionMatch, value: Double) throws -> String {
    let numericFunctions = numericOptionSources(for: match.function.name)
    let minimum = try parseNonNegativeIntegerOption(
        resolvedOptionValue(
            match,
            name: "minimumFractionDigits",
            inheritedFrom: numericFunctions
        ) ?? "0",
        error: .badOption("minimumFractionDigits option must be a non-negative integer.")
    )
    let maximum = try resolvedOptionValue(
        match,
        name: "maximumFractionDigits",
        inheritedFrom: numericFunctions
    ).map {
        try parseNonNegativeIntegerOption(
            $0,
            error: .badOption("maximumFractionDigits option must be a non-negative integer.")
        )
    }
    return try appendMinimumFractionDigits(
        try formatUnlocalizedNumberWithMaximumFractionDigits(
            value,
            maximumFractionDigits: maximum
        ),
        minimum
    )
}

private func validateNumericVariantKey(_ key: String) throws {
    if ["zero", "one", "two", "few", "many", "other"].contains(key) || isDecimalLiteral(key) {
        return
    }
    throw MF2Error.badVariantKey(
        "Numeric selector keys must be number literals or plural keywords."
    )
}

private func parseCallNumber(_ call: MF2FunctionCall, error: MF2Error) throws -> Double {
    if let parsed = try resolvedNumericSourceValue(call.inheritedSource) {
        return parsed
    }
    if let parsed = parseNumber(call.value) {
        return parsed
    }
    throw error
}

private func parseMatchNumber(_ match: MF2FunctionMatch, error: MF2Error) throws -> Double {
    if let parsed = try resolvedNumericSourceValue(match.inheritedSource) {
        return parsed
    }
    if let parsed = parseNumber(match.value) {
        return parsed
    }
    throw error
}

func resolvedNumericSourceValue(_ source: MF2FunctionSource?) throws -> Double? {
    guard let operand = try resolvedNumericSourceText(source) else {
        return nil
    }
    return parseNumber(operand)
}

func resolvedNumericSourceText(_ source: MF2FunctionSource?) throws -> String? {
    guard let source, isDecimalSourceFunction(source.function) else {
        return nil
    }
    return try numericSourceOperandText(source)
}

private func numericSourceOperandText(_ source: MF2FunctionSource?) throws -> String? {
    guard let source else {
        return nil
    }
    let operand = try numericSourceOperandText(source.inheritedSource) ?? source.value
    guard isDecimalSourceFunction(source.function) else {
        return operand
    }
    guard let decimal = parseDecimal(operand) else {
        return nil
    }
    switch source.function.name {
    case "integer":
        return canonicalDecimal(truncateDecimal(decimal))
    case "offset":
        let add = try source.optionValue("add")
        let subtract = try source.optionValue("subtract")
        guard (add == nil) != (subtract == nil),
              let delta = try? parseInteger(
                  add ?? subtract ?? "",
                  error: .badOption("Offset option must be an integer.")
              )
        else {
            return nil
        }
        guard let result = applyIntegerOffset(
            decimal,
            offset: delta,
            subtract: subtract != nil
        ) else {
            return nil
        }
        return canonicalDecimal(result)
    default:
        return canonicalDecimal(decimal)
    }
}

private func parseNumber(_ value: String) -> Double? {
    guard isDecimalLiteral(value), let parsed = Double(value), parsed.isFinite else {
        return nil
    }
    return parsed
}

private func parseDecimal(_ value: String) -> Decimal? {
    guard isDecimalLiteral(value),
          let parsed = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")),
          NSDecimalNumber(decimal: parsed) != .notANumber
    else {
        return nil
    }
    return parsed
}

private func applyIntegerOffset(
    _ value: Decimal,
    offset: Int,
    subtract: Bool
) -> Decimal? {
    var value = value
    var offset = Decimal(offset)
    var result = Decimal()
    let error = subtract
        ? NSDecimalSubtract(&result, &value, &offset, .plain)
        : NSDecimalAdd(&result, &value, &offset, .plain)
    guard error == .noError else {
        return nil
    }
    return result
}

private func truncateDecimal(_ value: Decimal) -> Decimal {
    var value = value
    var result = Decimal()
    NSDecimalRound(
        &result,
        &value,
        0,
        decimalIsNegative(value) ? .up : .down
    )
    return result
}

private func decimalIsNegative(_ value: Decimal) -> Bool {
    var value = value
    var zero = Decimal.zero
    return NSDecimalCompare(&value, &zero) == .orderedAscending
}

private func canonicalDecimal(_ value: Decimal) -> String {
    NSDecimalNumber(decimal: value).stringValue
}

func numericSelectionOperand(
    value: MF2Value,
    function: MF2Function,
    source: MF2FunctionSource?
) throws -> MF2Value? {
    if functionOptionLiteral(function, name: "select") == "exact" {
        return nil
    }
    let sourceInput = try resolvedNumericSourceText(source)
    let input = function.name == "offset" ? value.rendered : (sourceInput ?? value.rendered)
    guard var number = parseNumber(input) else {
        return nil
    }
    let inheritedMinimum = try inheritedOptionValue(
        source,
        name: "minimumFractionDigits",
        targetFunction: function.name,
        from: numericOptionSources(for: function.name)
    )
    let minimumText = functionOptionLiteral(function, name: "minimumFractionDigits")
        ?? inheritedMinimum
        ?? "0"
    let inheritedMaximum = try inheritedOptionValue(
        source,
        name: "maximumFractionDigits",
        targetFunction: function.name,
        from: numericOptionSources(for: function.name)
    )
    let maximumText = functionOptionLiteral(function, name: "maximumFractionDigits")
        ?? inheritedMaximum
    guard let minimum = try? parseNonNegativeIntegerOption(
        minimumText,
        error: .badOption("minimumFractionDigits option must be a non-negative integer.")
    ) else {
        return nil
    }
    let maximum: Int?
    if let maximumText {
        guard let parsed = try? parseNonNegativeIntegerOption(
            maximumText,
            error: .badOption("maximumFractionDigits option must be a non-negative integer.")
        ) else {
            return nil
        }
        maximum = parsed
    } else {
        maximum = nil
    }

    if function.name == "integer" {
        guard let integer = Int64(exactly: number.rounded(.towardZero)) else {
            return nil
        }
        return .number(String(integer))
    }
    if function.name == "percent" {
        number *= 100
        guard number.isFinite else {
            return nil
        }
    }
    if function.name == "number" || function.name == "percent" {
        guard let rendered = try? formatUnlocalizedNumberWithMaximumFractionDigits(
            number,
            maximumFractionDigits: maximum
        ), let padded = try? appendMinimumFractionDigits(rendered, minimum) else {
            return nil
        }
        return .number(padded)
    }
    return .number(input)
}

private func parseInteger(_ value: String, error: MF2Error) throws -> Int {
    guard let parsed = Int(value), String(parsed) == value || (value.hasPrefix("+") && String(parsed) == String(value.dropFirst())) else {
        throw error
    }
    return parsed
}

private func truncatedInteger(_ value: Double, error: MF2Error) throws -> Int64 {
    guard value.isFinite,
          let integer = Int64(exactly: value.rounded(.towardZero))
    else {
        throw error
    }
    return integer
}

private func formatUnlocalizedNumber(
    _ value: Double,
    minimumFractionDigits: Int = 0,
    signAlways: Bool = false
) throws -> String {
    guard value.isFinite else {
        throw MF2Error.badOperand("Numeric operand must be finite.")
    }
    let output: String
    if value.rounded(.towardZero) == value {
        guard let integer = Int64(exactly: value) else {
            throw MF2Error.badOperand(
                "Numeric operand is outside the supported integer range."
            )
        }
        output = String(integer)
    } else {
        output = String(value)
    }
    let formatted = try appendMinimumFractionDigits(output, minimumFractionDigits)
    return signAlways && value >= 0 ? "+\(formatted)" : formatted
}

private func formatUnlocalizedNumberWithMaximumFractionDigits(
    _ value: Double,
    maximumFractionDigits: Int?
) throws -> String {
    guard value.isFinite else {
        throw MF2Error.badOperand("Numeric operand must be finite.")
    }
    guard let maximumFractionDigits else {
        return try formatUnlocalizedNumber(value)
    }
    guard maximumFractionDigits >= 0,
          maximumFractionDigits <= maximumPortableFractionDigits
    else {
        throw MF2Error.badOption(
            "maximumFractionDigits option exceeds the supported range."
        )
    }
    var output = String(
        format: "%.\(maximumFractionDigits)f",
        locale: Locale(identifier: "en_US_POSIX"),
        value
    )
    while output.contains("."), output.hasSuffix("0") {
        output.removeLast()
    }
    if output.hasSuffix(".") {
        output.removeLast()
    }
    return output
}

private func minimumFractionDigits(_ call: MF2FunctionCall) throws -> Int {
    guard let value = try resolvedOptionValue(
        call,
        name: "minimumFractionDigits",
        inheritedFrom: numericOptionSources(for: call.function.name)
    ) else {
        return 0
    }
    return try parseNonNegativeIntegerOption(
        value,
        error: .badOption("minimumFractionDigits option must be a non-negative integer.")
    )
}

private func maximumFractionDigits(_ call: MF2FunctionCall) throws -> Int? {
    guard let value = try resolvedOptionValue(
        call,
        name: "maximumFractionDigits",
        inheritedFrom: numericOptionSources(for: call.function.name)
    ) else {
        return nil
    }
    return try parseNonNegativeIntegerOption(
        value,
        error: .badOption("maximumFractionDigits option must be a non-negative integer.")
    )
}

private func parseNonNegativeIntegerOption(_ value: String, error: MF2Error) throws -> Int {
    guard isNonNegativeIntegerLiteral(value),
          let parsed = Int(value),
          parsed <= maximumPortableFractionDigits
    else {
        throw error
    }
    return parsed
}

private func signDisplayAlways(_ call: MF2FunctionCall) throws -> Bool {
    try resolvedOptionValue(
        call,
        name: "signDisplay",
        inheritedFrom: numericOptionSources(for: call.function.name)
    ) == "always"
}

private func invalidNumericSelector(_ function: MF2Function, source: MF2FunctionSource?) throws -> Bool {
    if numericSelectUsesVariable(function) {
        return true
    }
    if functionOptionLiteral(function, name: "select") == "exact" {
        return false
    }
    return try inheritedOptionValue(
        source,
        name: "select",
        targetFunction: function.name,
        from: numericOptionSources(for: function.name)
    ) == "exact"
}

private func numericSelectUsesVariable(_ function: MF2Function) -> Bool {
    if case .variable? = function.options["select"] {
        return true
    }
    return false
}

private func isNumericFunction(_ function: MF2Function) -> Bool {
    function.name == "number" || function.name == "integer" || function.name == "percent" || function.name == "offset"
}

private func isDecimalSourceFunction(_ function: MF2Function) -> Bool {
    isNumericFunction(function) || function.name == "currency"
}

private func functionOptionLiteral(_ function: MF2Function, name: String) -> String? {
    if case let .literal(value)? = function.options[name] {
        return value
    }
    return nil
}

private func appendMinimumFractionDigits(
    _ value: String,
    _ minimumFractionDigits: Int
) throws -> String {
    guard minimumFractionDigits >= 0,
          minimumFractionDigits <= maximumPortableFractionDigits
    else {
        throw MF2Error.badOption(
            "minimumFractionDigits option exceeds the supported range."
        )
    }
    guard minimumFractionDigits > 0 else {
        return value
    }
    let pieces = value.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
    let fractionCount = pieces.count == 2 ? pieces[1].count : 0
    guard fractionCount < minimumFractionDigits else {
        return value
    }
    let padding = String(repeating: "0", count: minimumFractionDigits - fractionCount)
    return pieces.count == 2 ? "\(value)\(padding)" : "\(value).\(padding)"
}

private func isDecimalLiteral(_ value: String) -> Bool {
    guard let range = value.range(
        of: #"^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$"#,
        options: .regularExpression
    ) else {
        return false
    }
    return range == value.startIndex..<value.endIndex
}

private func isNonNegativeIntegerLiteral(_ value: String) -> Bool {
    guard let range = value.range(of: #"^\d+$"#, options: .regularExpression) else {
        return false
    }
    return range == value.startIndex..<value.endIndex
}
