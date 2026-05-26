import Foundation

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
    return try formatUnlocalizedNumber(
        value,
        minimumFractionDigits: minimumFractionDigits(call),
        signAlways: signDisplayAlways(call.function)
    )
}

private func formatUnlocalizedPercent(_ call: MF2FunctionCall) throws -> String {
    let value = try parseCallNumber(call, error: .badOperand("Percent function requires a numeric operand."))
    var formatted = try formatUnlocalizedNumberWithMaximumFractionDigits(
        value * 100,
        maximumFractionDigits: maximumFractionDigits(call)
    )
    if signDisplayAlways(call.function), value >= 0 {
        formatted = "+\(formatted)"
    }
    return try "\(appendMinimumFractionDigits(formatted, minimumFractionDigits(call)))%"
}

private func formatUnlocalizedInteger(_ call: MF2FunctionCall) throws -> String {
    let value = try parseCallNumber(call, error: .badOperand("Integer function requires a numeric operand."))
    let integer = Int(value.rounded(.towardZero))
    return signDisplayAlways(call.function) && integer >= 0 ? "+\(integer)" : String(integer)
}

private func offsetFunction(_ call: MF2FunctionCall) throws -> String {
    let value = try parseInteger(call.value, error: .badOperand("Offset function requires a numeric operand."))
    let add = try call.optionValue("add")
    let subtract = try call.optionValue("subtract")
    guard (add == nil) != (subtract == nil) else {
        throw MF2Error.badOption("Offset function requires exactly one of add or subtract.")
    }
    if let add {
        let result = value + (try parseInteger(add, error: .badOption("Offset add option must be an integer.")))
        return try inheritedSignDisplayAlways(call.inheritedSource) && result >= 0 ? "+\(result)" : String(result)
    }
    let result = value - (try parseInteger(subtract ?? "", error: .badOption("Offset subtract option must be an integer.")))
    return try inheritedSignDisplayAlways(call.inheritedSource) && result >= 0 ? "+\(result)" : String(result)
}

private func selectNumber(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Number selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Number selector requires a numeric operand."))
    guard let key = Double(match.key) else {
        return nil
    }
    return value == key ? 1 : nil
}

private func selectPercent(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Percent selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Percent selector requires a numeric operand.")) * 100
    guard let key = Double(match.key) else {
        return nil
    }
    return value == key ? 1 : nil
}

private func selectInteger(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Integer selector cannot match this operand.")
    }
    let value = try parseMatchNumber(match, error: .badSelector("Integer selector requires a numeric operand."))
    guard let key = Int(match.key) else {
        return nil
    }
    return Int(value.rounded(.towardZero)) == key ? 1 : nil
}

private func selectOffset(_ match: MF2FunctionMatch) throws -> Int? {
    let value = try parseInteger(match.value, error: .badSelector("Offset selector requires a numeric operand."))
    guard let key = Int(match.key) else {
        return nil
    }
    return value == key ? 1 : nil
}

private func parseCallNumber(_ call: MF2FunctionCall, error: MF2Error) throws -> Double {
    if let parsed = parseNumber(call.value) ?? parseSourceNumber(call.inheritedSource) {
        return parsed
    }
    throw error
}

private func parseMatchNumber(_ match: MF2FunctionMatch, error: MF2Error) throws -> Double {
    if let parsed = parseNumber(match.value) ?? parseSourceNumber(match.inheritedSource) {
        return parsed
    }
    throw error
}

private func parseSourceNumber(_ source: MF2FunctionSource?) -> Double? {
    guard let source else {
        return nil
    }
    if isDecimalSourceFunction(source.function) {
        return parseNumber(source.value)
    }
    return parseSourceNumber(source.inheritedSource)
}

private func parseNumber(_ value: String) -> Double? {
    guard isDecimalLiteral(value), let parsed = Double(value), parsed.isFinite else {
        return nil
    }
    return parsed
}

private func parseInteger(_ value: String, error: MF2Error) throws -> Int {
    guard let parsed = Int(value), String(parsed) == value || (value.hasPrefix("+") && String(parsed) == String(value.dropFirst())) else {
        throw error
    }
    return parsed
}

private func formatUnlocalizedNumber(
    _ value: Double,
    minimumFractionDigits: Int = 0,
    signAlways: Bool = false
) -> String {
    let output: String
    if value.isFinite, value.rounded(.towardZero) == value {
        output = String(Int64(value))
    } else {
        output = String(value)
    }
    let formatted = appendMinimumFractionDigits(output, minimumFractionDigits)
    return signAlways && value >= 0 ? "+\(formatted)" : formatted
}

private func formatUnlocalizedNumberWithMaximumFractionDigits(
    _ value: Double,
    maximumFractionDigits: Int?
) -> String {
    guard let maximumFractionDigits else {
        return formatUnlocalizedNumber(value)
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
    guard let value = try call.optionValue("minimumFractionDigits") else {
        return 0
    }
    return try parseNonNegativeIntegerOption(
        value,
        error: .badOption("minimumFractionDigits option must be a non-negative integer.")
    )
}

private func maximumFractionDigits(_ call: MF2FunctionCall) throws -> Int? {
    guard let value = try call.optionValue("maximumFractionDigits") else {
        return nil
    }
    return try parseNonNegativeIntegerOption(
        value,
        error: .badOption("maximumFractionDigits option must be a non-negative integer.")
    )
}

private func parseNonNegativeIntegerOption(_ value: String, error: MF2Error) throws -> Int {
    guard isNonNegativeIntegerLiteral(value), let parsed = Int(value) else {
        throw error
    }
    return parsed
}

private func signDisplayAlways(_ function: MF2Function) -> Bool {
    functionOptionLiteral(function, name: "signDisplay") == "always"
}

private func inheritedSignDisplayAlways(_ source: MF2FunctionSource?) throws -> Bool {
    guard let source else {
        return false
    }
    if (source.function.name == "number" || source.function.name == "integer"),
       try source.optionValue("signDisplay") == "always" {
        return true
    }
    return try inheritedSignDisplayAlways(source.inheritedSource)
}

private func invalidNumericSelector(_ function: MF2Function, source: MF2FunctionSource?) throws -> Bool {
    if numericSelectUsesVariable(function) {
        return true
    }
    if functionOptionLiteral(function, name: "select") == "exact" {
        return false
    }
    return try inheritedExactNumericSource(source)
}

private func numericSelectUsesVariable(_ function: MF2Function) -> Bool {
    if case .variable? = function.options["select"] {
        return true
    }
    return false
}

private func inheritedExactNumericSource(_ source: MF2FunctionSource?) throws -> Bool {
    guard let source else {
        return false
    }
    if isNumericFunction(source.function), try source.optionValue("select") == "exact" {
        return true
    }
    return try inheritedExactNumericSource(source.inheritedSource)
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

private func appendMinimumFractionDigits(_ value: String, _ minimumFractionDigits: Int) -> String {
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
