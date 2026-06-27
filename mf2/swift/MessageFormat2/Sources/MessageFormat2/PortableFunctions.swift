import Foundation

private let maxFractionDigits = 100
private let maxDecimalOperandLength = 256
private let maxDecimalOutputChars = 1000
private let maxOffsetInteger = "1000000000000000000000"

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
    let message = "Number function requires a numeric operand."
    let value = try parseCallDecimalOperand(call, error: .badOperand(message))
    let minimum = try minimumFractionDigits(call)
    let maximum = try maximumFractionDigits(call)
    try validateFractionDigits(minimum: minimum, maximum: maximum)
    let rounded = roundDecimalOperandToMaximumFractionDigits(value, maximumFractionDigits: maximum)
    try ensureDecimalOutputBounded(rounded, minimumFractionDigits: minimum, message: message)
    return formatDecimalOperand(
        rounded,
        minimumFractionDigits: minimum,
        signAlways: signDisplayAlways(call.function)
    )
}

private func formatUnlocalizedPercent(_ call: MF2FunctionCall) throws -> String {
    let message = "Percent function requires a numeric operand."
    let value = try parseCallDecimalOperand(call, error: .badOperand(message))
    let minimum = try minimumFractionDigits(call)
    let maximum = try maximumFractionDigits(call)
    try validateFractionDigits(minimum: minimum, maximum: maximum)
    let percentValue = roundDecimalOperandToMaximumFractionDigits(
        value.shifted(2),
        maximumFractionDigits: maximum
    )
    try ensureDecimalOutputBounded(percentValue, minimumFractionDigits: minimum, message: message)
    var formatted = formatDecimalOperand(percentValue, minimumFractionDigits: 0, signAlways: false)
    if signDisplayAlways(call.function), !value.negative {
        formatted = "+\(formatted)"
    }
    return "\(appendMinimumFractionDigits(formatted, minimum))%"
}

private func formatUnlocalizedInteger(_ call: MF2FunctionCall) throws -> String {
    let message = "Integer function requires a numeric operand."
    let integer = try parseCallDecimalOperand(call, error: .badOperand(message)).truncatedToInteger()
    try ensureDecimalOutputBounded(integer, minimumFractionDigits: 0, message: message)
    return formatDecimalOperand(integer, minimumFractionDigits: 0, signAlways: signDisplayAlways(call.function))
}

private func offsetFunction(_ call: MF2FunctionCall) throws -> String {
    let value = try parseOffsetInteger(call.value, error: .badOperand("Offset function requires a numeric operand."))
    let add = try call.optionValue("add")
    let subtract = try call.optionValue("subtract")
    guard (add == nil) != (subtract == nil) else {
        throw MF2Error.badOption("Offset function requires exactly one of add or subtract.")
    }
    let delta: String
    if let add {
        delta = try parseOffsetInteger(add, error: .badOption("Offset add option must be an integer."))
    } else {
        delta = try negateOffsetInteger(parseOffsetInteger(subtract ?? "", error: .badOption("Offset subtract option must be an integer.")))
    }
    let result = try addOffsetIntegers(value, delta, error: .badOperand("Offset result is outside the supported integer range."))
    return try inheritedSignDisplayAlways(call.inheritedSource) && !result.hasPrefix("-") ? "+\(result)" : result
}

private func selectNumber(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Number selector cannot match this operand.")
    }
    let value = try parseMatchDecimalOperand(match, error: .badSelector("Number selector requires a numeric operand."))
    guard let key = parseDecimalOperand(match.key) else {
        return nil
    }
    return value == key ? 2 : nil
}

private func selectPercent(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Percent selector cannot match this operand.")
    }
    let value = try parseMatchDecimalOperand(match, error: .badSelector("Percent selector requires a numeric operand.")).shifted(2)
    guard let key = parseDecimalOperand(match.key) else {
        return nil
    }
    return value == key ? 2 : nil
}

private func selectInteger(_ match: MF2FunctionMatch) throws -> Int? {
    if try invalidNumericSelector(match.function, source: match.inheritedSource) {
        throw MF2Error.badSelector("Integer selector cannot match this operand.")
    }
    let value = try parseMatchDecimalOperand(match, error: .badSelector("Integer selector requires a numeric operand.")).truncatedToInteger()
    guard let key = parseIntegerOperand(match.key) else {
        return nil
    }
    return value == key ? 2 : nil
}

private func selectOffset(_ match: MF2FunctionMatch) throws -> Int? {
    let value = try parseOffsetInteger(match.value, error: .badSelector("Offset selector requires a numeric operand."))
    guard let key = parseOffsetInteger(match.key) else {
        return nil
    }
    return value == key ? 2 : nil
}

private func parseCallNumber(_ call: MF2FunctionCall, error: MF2Error) throws -> Double {
    if let parsed = parseNumber(call.value) ?? parseSourceNumber(call.inheritedSource) {
        return parsed
    }
    throw error
}

private func parseCallDecimalOperand(_ call: MF2FunctionCall, error: MF2Error) throws -> DecimalOperand {
    if let parsed = parseDecimalOperand(call.value) ?? parseSourceDecimalOperand(call.inheritedSource) {
        return parsed
    }
    throw error
}

private func parseMatchDecimalOperand(_ match: MF2FunctionMatch, error: MF2Error) throws -> DecimalOperand {
    if let parsed = parseSourceDecimalOperand(match.inheritedSource) ?? parseDecimalOperand(match.value) {
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

private func parseSourceDecimalOperand(_ source: MF2FunctionSource?) -> DecimalOperand? {
    guard let source else {
        return nil
    }
    if isDecimalSourceFunction(source.function) {
        return parseDecimalOperand(source.value)
    }
    return parseSourceDecimalOperand(source.inheritedSource)
}

private func parseNumber(_ value: String) -> Double? {
    guard value.count <= maxDecimalOperandLength, isDecimalLiteral(value), let parsed = Double(value), parsed.isFinite else {
        return nil
    }
    return parsed
}

private struct DecimalOperand: Equatable {
    let negative: Bool
    let digits: String
    let scale: Int

    func shifted(_ places: Int) -> DecimalOperand {
        normalizeDecimalOperand(negative: negative, digits: digits, scale: scale - places)
    }

    func truncatedToInteger() -> DecimalOperand {
        if scale <= 0 {
            return self
        }
        let keep = digits.count - scale
        if keep <= 0 {
            return normalizeDecimalOperand(negative: false, digits: "0", scale: 0)
        }
        return normalizeDecimalOperand(negative: negative, digits: String(digits.prefix(keep)), scale: 0)
    }
}

private func parseDecimalOperand(_ value: String) -> DecimalOperand? {
    guard value.count <= maxDecimalOperandLength, isDecimalLiteral(value) else {
        return nil
    }
    let negative = value.hasPrefix("-")
    let body = negative ? String(value.dropFirst()) : value
    let split = splitDecimalExponent(body)
    guard let exponent = parseBoundedDecimalExponent(split.exponent) else {
        return nil
    }
    let pieces = split.significand.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
    let integer = String(pieces[0])
    let fraction = pieces.count == 2 ? String(pieces[1]) : ""
    return normalizeDecimalOperand(negative: negative, digits: integer + fraction, scale: fraction.count - exponent)
}

func isDecimalOperand(_ value: String) -> Bool {
    parseDecimalOperand(value) != nil
}

private func parseIntegerOperand(_ value: String) -> DecimalOperand? {
    guard value.count <= maxDecimalOperandLength, isIntegerLiteral(value) else {
        return nil
    }
    let negative = value.hasPrefix("-")
    let digits = negative || value.hasPrefix("+") ? String(value.dropFirst()) : value
    return normalizeDecimalOperand(negative: negative, digits: digits, scale: 0)
}

private func splitDecimalExponent(_ value: String) -> (significand: String, exponent: String) {
    guard let index = value.firstIndex(where: { $0 == "e" || $0 == "E" }) else {
        return (value, "")
    }
    return (String(value[..<index]), String(value[value.index(after: index)...]))
}

private func parseBoundedDecimalExponent(_ value: String) -> Int? {
    if value.isEmpty {
        return 0
    }
    let negative = value.hasPrefix("-")
    var digits = negative || value.hasPrefix("+") ? String(value.dropFirst()) : value
    while digits.first == "0" {
        digits.removeFirst()
    }
    if digits.isEmpty {
        return 0
    }
    guard digits.count <= 7, let parsed = Int(digits), parsed <= 1_000_000 else {
        return nil
    }
    return negative ? -parsed : parsed
}

private func normalizeDecimalOperand(negative: Bool, digits inputDigits: String, scale inputScale: Int) -> DecimalOperand {
    var digits = inputDigits
    var scale = inputScale
    while digits.first == "0" {
        digits.removeFirst()
    }
    if digits.isEmpty {
        return DecimalOperand(negative: false, digits: "0", scale: 0)
    }
    while digits.last == "0" {
        digits.removeLast()
        scale -= 1
    }
    return DecimalOperand(negative: negative, digits: digits, scale: scale)
}

private func parseOffsetInteger(_ value: String, error: MF2Error) throws -> String {
    guard let parsed = parseOffsetInteger(value) else {
        throw error
    }
    return parsed
}

private func parseOffsetInteger(_ value: String) -> String? {
    guard isIntegerLiteral(value) else {
        return nil
    }
    let negative = value.hasPrefix("-")
    var digits = negative || value.hasPrefix("+") ? String(value.dropFirst()) : value
    while digits.first == "0" {
        digits.removeFirst()
    }
    if digits.isEmpty {
        return "0"
    }
    guard offsetDigitsInRange(digits) else {
        return nil
    }
    return negative ? "-\(digits)" : digits
}

private func addOffsetIntegers(_ left: String, _ right: String, error: MF2Error) throws -> String {
    let leftNegative = left.hasPrefix("-")
    let rightNegative = right.hasPrefix("-")
    let leftDigits = leftNegative ? String(left.dropFirst()) : left
    let rightDigits = rightNegative ? String(right.dropFirst()) : right
    let result: String
    if leftNegative == rightNegative {
        result = normalizeOffsetInteger(negative: leftNegative, digits: addOffsetDigits(leftDigits, rightDigits))
    } else {
        let comparison = compareOffsetDigits(leftDigits, rightDigits)
        if comparison == 0 {
            result = "0"
        } else if comparison > 0 {
            result = normalizeOffsetInteger(negative: leftNegative, digits: subtractOffsetDigits(leftDigits, rightDigits))
        } else {
            result = normalizeOffsetInteger(negative: rightNegative, digits: subtractOffsetDigits(rightDigits, leftDigits))
        }
    }
    guard offsetIntegerInRange(result) else {
        throw error
    }
    return result
}

private func negateOffsetInteger(_ value: String) -> String {
    if value == "0" {
        return "0"
    }
    return value.hasPrefix("-") ? String(value.dropFirst()) : "-\(value)"
}

private func offsetIntegerInRange(_ value: String) -> Bool {
    offsetDigitsInRange(value.hasPrefix("-") ? String(value.dropFirst()) : value)
}

private func offsetDigitsInRange(_ digits: String) -> Bool {
    digits.count < maxOffsetInteger.count || (digits.count == maxOffsetInteger.count && digits < maxOffsetInteger)
}

private func addOffsetDigits(_ left: String, _ right: String) -> String {
    let leftDigits = Array(left.reversed()).compactMap(\.wholeNumberValue)
    let rightDigits = Array(right.reversed()).compactMap(\.wholeNumberValue)
    var carry = 0
    var output: [String] = []
    let count = max(leftDigits.count, rightDigits.count)
    for index in 0..<count {
        let sum = carry + (index < leftDigits.count ? leftDigits[index] : 0) + (index < rightDigits.count ? rightDigits[index] : 0)
        output.append(String(sum % 10))
        carry = sum / 10
    }
    if carry > 0 {
        output.append(String(carry))
    }
    return output.reversed().joined()
}

private func subtractOffsetDigits(_ left: String, _ right: String) -> String {
    let leftDigits = Array(left.reversed()).compactMap(\.wholeNumberValue)
    let rightDigits = Array(right.reversed()).compactMap(\.wholeNumberValue)
    var borrow = 0
    var output: [String] = []
    for index in 0..<leftDigits.count {
        var difference = leftDigits[index] - borrow - (index < rightDigits.count ? rightDigits[index] : 0)
        if difference < 0 {
            difference += 10
            borrow = 1
        } else {
            borrow = 0
        }
        output.append(String(difference))
    }
    return trimLeadingZeroDigits(output.reversed().joined())
}

private func compareOffsetDigits(_ left: String, _ right: String) -> Int {
    if left.count != right.count {
        return left.count < right.count ? -1 : 1
    }
    if left == right {
        return 0
    }
    return left < right ? -1 : 1
}

private func normalizeOffsetInteger(negative: Bool, digits inputDigits: String) -> String {
    let digits = trimLeadingZeroDigits(inputDigits)
    return negative && digits != "0" ? "-\(digits)" : digits
}

private func trimLeadingZeroDigits(_ inputDigits: String) -> String {
    var digits = inputDigits
    while digits.first == "0" {
        digits.removeFirst()
    }
    return digits.isEmpty ? "0" : digits
}

private func formatDecimalOperand(
    _ value: DecimalOperand,
    minimumFractionDigits: Int,
    signAlways: Bool
) -> String {
    let formatted = appendMinimumFractionDigits(decimalOperandToString(value), minimumFractionDigits)
    return signAlways && !value.negative ? "+\(formatted)" : formatted
}

private func decimalOperandToString(_ value: DecimalOperand) -> String {
    let sign = value.negative ? "-" : ""
    if value.scale <= 0 {
        return sign + value.digits + String(repeating: "0", count: -value.scale)
    }
    if value.scale >= value.digits.count {
        return sign + "0." + String(repeating: "0", count: value.scale - value.digits.count) + value.digits
    }
    let split = value.digits.index(value.digits.startIndex, offsetBy: value.digits.count - value.scale)
    return sign + String(value.digits[..<split]) + "." + String(value.digits[split...])
}

private func roundDecimalOperandToMaximumFractionDigits(
    _ operand: DecimalOperand,
    maximumFractionDigits: Int?
) -> DecimalOperand {
    guard let maximumFractionDigits, operand.scale > maximumFractionDigits else {
        return operand
    }
    let drop = operand.scale - maximumFractionDigits
    let keep = operand.digits.count - drop
    let kept: String
    let remainder: String
    if keep > 0 {
        let split = operand.digits.index(operand.digits.startIndex, offsetBy: keep)
        kept = String(operand.digits[..<split])
        remainder = String(operand.digits[split...])
    } else {
        kept = "0"
        remainder = operand.digits
    }
    var rounded = trimLeadingZeroDigits(kept)
    let comparison = compareDecimalRemainderToHalf(remainder, droppedDigits: drop)
    if comparison >= 0 {
        rounded = incrementDecimalString(rounded)
    }
    return normalizeDecimalOperand(negative: operand.negative, digits: rounded, scale: maximumFractionDigits)
}

private func compareDecimalRemainderToHalf(_ remainder: String, droppedDigits: Int) -> Int {
    guard remainder.contains(where: { $0 != "0" }) else {
        return -1
    }
    guard remainder.count >= droppedDigits, let first = remainder.first?.wholeNumberValue else {
        return -1
    }
    if first < 5 {
        return -1
    }
    if first > 5 {
        return 1
    }
    return remainder.dropFirst().contains(where: { ($0.wholeNumberValue ?? 0) != 0 }) ? 1 : 0
}

private func incrementDecimalString(_ value: String) -> String {
    var digits = value.compactMap(\.wholeNumberValue)
    for index in stride(from: digits.count - 1, through: 0, by: -1) {
        if digits[index] != 9 {
            digits[index] += 1
            return digits.map(String.init).joined()
        }
        digits[index] = 0
    }
    return "1" + digits.map(String.init).joined()
}

private func ensureDecimalOutputBounded(
    _ value: DecimalOperand,
    minimumFractionDigits: Int,
    message: String
) throws {
    if estimatedDecimalOutputChars(value, minimumFractionDigits: minimumFractionDigits) > maxDecimalOutputChars {
        throw MF2Error.badOperand(message)
    }
}

private func estimatedDecimalOutputChars(_ value: DecimalOperand, minimumFractionDigits: Int) -> Int {
    let sign = value.negative ? 1 : 0
    if value.scale <= 0 {
        return sign + value.digits.count - value.scale
    }
    let integerDigits = max(value.digits.count - value.scale, 1)
    let fractionDigits = max(value.scale, minimumFractionDigits)
    return sign + integerDigits + (fractionDigits > 0 ? 1 + fractionDigits : 0)
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

private func validateFractionDigits(minimum: Int, maximum: Int?) throws {
    if let maximum, minimum > maximum {
        throw MF2Error.badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
    }
}

private func parseNonNegativeIntegerOption(_ value: String, error: MF2Error) throws -> Int {
    guard value.utf8.count <= maxDecimalOperandLength else {
        throw error
    }
    guard isNonNegativeIntegerLiteral(value), let parsed = Int(value), parsed <= maxFractionDigits else {
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
    isNumericFunction(function) || function.name == "currency" || function.name == "relativeTime"
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
        of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#,
        options: .regularExpression
    ) else {
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

private func isIntegerLiteral(_ value: String) -> Bool {
    guard let range = value.range(of: #"^[+-]?[0-9]+$"#, options: .regularExpression) else {
        return false
    }
    return range == value.startIndex..<value.endIndex
}
