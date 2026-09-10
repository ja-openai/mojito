import Foundation
import MessageFormat2

// JSON-lines transport for the shared official assertion runner. All standard
// functions come from the production registry selected by the request.
func runOfficialBridge() throws {
    let decoder = JSONDecoder()
    while let line = readLine() {
        let response: [String: Any]
        do {
            let request = try decoder.decode(OfficialBridgeRequest.self, from: Data(line.utf8))
            let parsed = parseToModel(request.source)
            var result: [String: Any] = [
                "diagnostics": parsed.diagnostics.map(\.code), "errors": [String](), "parts": [[String: Any]](),
            ]
            if let model = parsed.model {
                let registry = officialTestRegistry(
                    request.registry == "platform" ? MF2FunctionRegistry.foundation : .portable)
                do {
                    let formatted = try model.format(
                        arguments: request.arguments, locale: request.locale, functions: registry,
                        bidiIsolation: request.bidiIsolation)
                    result["value"] = formatted.value
                    result["errors"] = formatted.errors.map(\.code)
                } catch let error as MF2Error { result["errors"] = [error.code] }
                do {
                    let parts = try model.formatToParts(
                        arguments: request.arguments, locale: request.locale, functions: registry)
                    result["parts"] = try parts.parts.map(bridgePart)
                    result["partsErrors"] = parts.errors.map(\.code)
                } catch let error as MF2Error { result["partsErrors"] = [error.code] }
            }
            response = result
        } catch { response = ["transportError": String(describing: error)] }
        let data = try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
    }
}

private struct OfficialBridgeRequest: Decodable {
    let source: String
    let arguments: [String: MF2Value]
    let locale: String
    let bidiIsolation: MF2BidiIsolation
    let registry: String
    private enum CodingKeys: String, CodingKey { case source, arguments, locale, bidiIsolation, registry }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        source = try c.decode(String.self, forKey: .source)
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        bidiIsolation = try c.decodeIfPresent(MF2BidiIsolation.self, forKey: .bidiIsolation) ?? .none
        registry = try c.decodeIfPresent(String.self, forKey: .registry) ?? "portable"
    }
}

private func bridgePart(_ part: MF2FormattedPart) throws -> [String: Any] {
    switch part {
    case .text(let value): return ["type": "text", "value": value]
    case .fallback(let source, let value):
        var result: [String: Any] = ["type": "fallback", "source": source]
        if let value { result["value"] = value }
        return result
    case .expression(let value, let attributes, let direction):
        var result: [String: Any] = ["type": "expression", "value": value]
        if !attributes.isEmpty { result["attributes"] = try bridgeJSON(attributes) }
        if let direction { result["dir"] = direction }
        return result
    case .markup(let kind, let name, let options, let attributes):
        var result: [String: Any] = ["type": "markup", "kind": kind, "name": name]
        if !options.isEmpty { result["options"] = try bridgeJSON(options) }
        if !attributes.isEmpty { result["attributes"] = try bridgeJSON(attributes) }
        return result
    }
}

private func bridgeJSON<T: Encodable>(_ value: T) throws -> Any {
    try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
}

// Only the upstream suite's explicit test namespace is implemented here. Standard
// functions always remain the production implementation from the chosen registry.
private func officialTestRegistry(_ registry: MF2FunctionRegistry) -> MF2FunctionRegistry {
    registry
        .withFunction("test:function") { call in
            let state = try testState(value: call.value, source: call.inheritedSource) { try call.optionValue($0) }
            if state.failsFormat { throw MF2Error.badOption("Requested test formatting failure.") }
            return state.formatted
        }
        .withFunction("test:select") { call in
            try testState(value: call.value, source: call.inheritedSource) { try call.optionValue($0) }.formatted
        }
        .withFunction("test:format") { call in
            try testState(value: call.value, source: call.inheritedSource) { try call.optionValue($0) }.formatted
        }
        .withSelector("test:function", selector: testSelector)
        .withSelector("test:select", selector: testSelector)
        .withSelector("test:format") { _ in throw MF2Error.badSelector("Test format function is not selectable.") }
}

private struct OfficialTestState {
    let input: Double
    var decimalPlaces = 0
    var failsFormat = false
    var failsSelect = false
    var formatted: String {
        let integer = Int(abs(input).rounded(.down))
        let sign = input < 0 ? "-" : ""
        return decimalPlaces == 1
            ? "\(sign)\(integer).\(Int(((abs(input) - Double(integer)) * 10).rounded(.down)))" : "\(sign)\(integer)"
    }
    mutating func apply(_ option: (String) throws -> String?) throws {
        if let digits = try option("decimalPlaces") {
            guard digits == "0" || digits == "1" else {
                throw MF2Error.badOption("Test decimalPlaces must be zero or one.")
            }
            decimalPlaces = digits == "1" ? 1 : 0
        }
        switch try option("fails") {
        case "always":
            failsFormat = true
            failsSelect = true
        case "format": failsFormat = true
        case "select": failsSelect = true
        default: break
        }
    }
}

private func testState(value: String, source: MF2FunctionSource?, option: (String) throws -> String?) throws
    -> OfficialTestState
{
    var chain: [MF2FunctionSource] = []
    var current = source
    while let source = current {
        chain.append(source)
        current = source.inheritedSource
    }
    let input = chain.last?.value ?? value
    guard let number = Double(input), number.isFinite, abs(number) <= 9_007_199_254_740_991 else {
        throw MF2Error.badOperand("Test function requires a numeric operand.")
    }
    var state = OfficialTestState(input: number)
    for source in chain.reversed() where ["test:function", "test:select", "test:format"].contains(source.function.name)
    {
        try state.apply { try source.optionValue($0) }
    }
    try state.apply(option)
    return state
}

private func testSelector(_ match: MF2FunctionMatch) throws -> Int? {
    let state = try testState(value: match.value, source: match.inheritedSource) { try match.optionValue($0) }
    if state.failsSelect { throw MF2Error.badSelector("Requested test selection failure.") }
    guard state.input.rounded(.towardZero) == 1 else { return nil }
    if state.decimalPlaces == 1, match.key == "1.0" { return 2 }
    return match.key == "1" ? 1 : nil
}
