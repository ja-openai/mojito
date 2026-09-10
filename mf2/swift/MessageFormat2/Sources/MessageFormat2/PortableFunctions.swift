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
    return MF2FunctionRegistry(formatters: formatters, selectors: selectors).withProductionNumericFunctions([
        "number", "integer", "percent",
    ])
}

private func passthroughFunction(_ call: MF2FunctionCall) throws -> String {
    call.value
}

private func formatUnlocalizedNumber(_ call: MF2FunctionCall) throws -> String { try formatNumeric(call) }
private func formatUnlocalizedPercent(_ call: MF2FunctionCall) throws -> String { try formatNumeric(call) }
private func formatUnlocalizedInteger(_ call: MF2FunctionCall) throws -> String { try formatNumeric(call) }

private func formatNumeric(_ call: MF2FunctionCall) throws -> String {
    let operand = try resolvedNumericSourceText(call.inheritedSource) ?? call.value
    let integer = call.function.name == "integer"
    var formatted = try portableNumericOperand(
        operand, function: call.function.name,
        minimum: integer ? 0 : minimumFractionDigits(call), maximum: integer ? nil : maximumFractionDigits(call))
    if try signDisplayAlways(call), !formatted.hasPrefix("-") { formatted = "+" + formatted }
    return call.function.name == "percent" ? formatted + "%" : formatted
}

private func offsetFunction(_ call: MF2FunctionCall) throws -> String {
    let operand = try resolvedNumericSourceText(call.inheritedSource) ?? call.value
    guard var decimal = DecimalOperand(operand) else {
        throw MF2Error.badOperand("Offset function requires a supported decimal operand.")
    }
    let add = try call.optionValue("add"), subtract = try call.optionValue("subtract")
    guard (add == nil) != (subtract == nil) else {
        throw MF2Error.badOption("Offset requires exactly one of add or subtract.")
    }
    let offset = try parseInteger(add ?? subtract ?? "", error: .badOption("Offset option must be an integer."))
    guard decimal.offset(offset, subtract: subtract != nil) else {
        throw MF2Error.badOperand("Offset result exceeds the supported decimal range.")
    }
    let formatted = decimal.canonical
    return try signDisplayAlways(call) && !decimal.negative ? "+" + formatted : formatted
}

private func selectNumber(_ match: MF2FunctionMatch) throws -> Int? { try selectNumeric(match) }
private func selectPercent(_ match: MF2FunctionMatch) throws -> Int? { try selectNumeric(match) }
private func selectInteger(_ match: MF2FunctionMatch) throws -> Int? { try selectNumeric(match) }
private func selectOffset(_ match: MF2FunctionMatch) throws -> Int? { try selectNumeric(match) }

private func selectNumeric(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Numeric selector cannot match this operand.")
    }
    try validateNumericVariantKey(match.key)
    let operand = try resolvedNumericSourceText(match.inheritedSource) ?? match.value
    let sources = numericOptionSources(for: match.function.name)
    let minimum = try parseNonNegativeIntegerOption(
        resolvedOptionValue(match, name: "minimumFractionDigits", inheritedFrom: sources) ?? "0",
        error: .badOption("Invalid minimumFractionDigits."))
    let maximum = try resolvedOptionValue(match, name: "maximumFractionDigits", inheritedFrom: sources).map {
        try parseNonNegativeIntegerOption($0, error: .badOption("Invalid maximumFractionDigits."))
    }
    let rendered = try portableNumericOperand(
        operand, function: match.function.name, minimum: minimum, maximum: maximum)
    return match.key == rendered ? 2 : nil
}

private func validateNumericVariantKey(_ key: String) throws {
    if ["zero", "one", "two", "few", "many", "other"].contains(key) || isDecimalLiteral(key) {
        return
    }
    throw MF2Error.badVariantKey(
        "Numeric selector keys must be number literals or plural keywords."
    )
}

func resolvedNumericSourceText(_ source: MF2FunctionSource?) throws -> String? {
    guard let source, isDecimalSourceFunction(source.function) else {
        return nil
    }
    return try numericSourceOperandText(source)
}

private func numericSourceOperandText(_ source: MF2FunctionSource?) throws -> String? {
    var chain: [MF2FunctionSource] = []
    var current = source
    var operand: String?
    while let item = current {
        if item.cacheableNumericSource, item.numericOperandComputed { operand = item.numericOperand; break }
        chain.append(item)
        current = item.inheritedSource
    }
    for source in chain.reversed() {
        let input = operand ?? source.value
        if isDecimalSourceFunction(source.function) {
            guard var decimal = DecimalOperand(input) else { return nil }
            switch source.function.name {
            case "integer": decimal.truncate()
            case "offset":
                let add = try source.optionValue("add"), subtract = try source.optionValue("subtract")
                guard (add == nil) != (subtract == nil), let delta = Int(add ?? subtract ?? ""),
                    decimal.offset(delta, subtract: subtract != nil)
                else { return nil }
            default: break
            }
            operand = decimal.canonical
        } else {
            operand = input
        }
        if source.cacheableNumericSource {
            source.numericOperand = operand
            source.numericOperandComputed = true
        }
    }
    return operand
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
    guard DecimalOperand(input) != nil else {
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

    return .number(try portableNumericOperand(input, function: function.name, minimum: minimum, maximum: maximum))
}

private func parseInteger(_ value: String, error: MF2Error) throws -> Int {
    guard let parsed = Int(value), String(parsed) == value || (value.hasPrefix("+") && String(parsed) == String(value.dropFirst())) else {
        throw error
    }
    return parsed
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

private func isDecimalLiteral(_ value: String) -> Bool {
    guard let range = value.range(
            of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#,
            options: .regularExpression
        )
    else {
        return false
    }
    return range == value.startIndex..<value.endIndex
}

private func isNonNegativeIntegerLiteral(_ value: String) -> Bool {
    guard let range = value.range(of: #"^[0-9]+$"#, options: .regularExpression) else {
        return false
    }
    return range == value.startIndex..<value.endIndex
}
