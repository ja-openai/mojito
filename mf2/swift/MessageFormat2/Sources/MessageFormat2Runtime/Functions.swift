public typealias MF2FunctionFormatter = (MF2FunctionCall) throws -> String

public struct MF2FunctionRegistry: @unchecked Sendable {
    private let formatters: [String: MF2FunctionFormatter]

    public init(formatters: [String: MF2FunctionFormatter] = [:]) {
        self.formatters = formatters
    }

    public static let defaults = MF2FunctionRegistry(
        formatters: [
            "string": passthroughFunction,
            "number": passthroughFunction,
            "integer": integerFunction,
            "offset": offsetFunction,
            "datetime": passthroughFunction,
            "date": passthroughFunction,
            "time": passthroughFunction,
        ]
    )

    public func withFunction(_ name: String, formatter: @escaping MF2FunctionFormatter) -> MF2FunctionRegistry {
        var formatters = self.formatters
        formatters[name] = formatter
        return MF2FunctionRegistry(formatters: formatters)
    }

    func format(_ call: MF2FunctionCall) throws -> String {
        guard let formatter = formatters[call.function.name] else {
            throw MF2Error.unsupportedFunction(call.function.name)
        }
        return try formatter(call)
    }
}

public struct MF2FunctionCall {
    public let value: String
    public let rawValue: MF2Value
    public let function: MF2Function
    public let locale: String
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        rawValue: MF2Value,
        function: MF2Function,
        locale: String,
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.value = value
        self.rawValue = rawValue
        self.function = function
        self.locale = locale
        self.optionResolver = optionResolver
    }

    public func optionValue(_ name: String, default defaultValue: String? = nil) throws -> String? {
        try optionResolver(name, defaultValue)
    }
}

private func passthroughFunction(_ call: MF2FunctionCall) throws -> String {
    call.value
}

private func integerFunction(_ call: MF2FunctionCall) throws -> String {
    let value = try parseNumber(call.value, error: .badOperand("Integer function requires a numeric operand."))
    return String(Int(value.rounded(.towardZero)))
}

private func offsetFunction(_ call: MF2FunctionCall) throws -> String {
    let value = try parseInteger(call.value, error: .badOperand("Offset function requires a numeric operand."))
    let add = try call.optionValue("add")
    let subtract = try call.optionValue("subtract")
    guard (add == nil) != (subtract == nil) else {
        throw MF2Error.badOption("Offset function requires exactly one of add or subtract.")
    }
    if let add {
        return String(value + (try parseInteger(add, error: .badOption("Offset add option must be an integer."))))
    }
    return String(value - (try parseInteger(subtract ?? "", error: .badOption("Offset subtract option must be an integer."))))
}

private func parseNumber(_ value: String, error: MF2Error) throws -> Double {
    guard let parsed = Double(value), parsed.isFinite else {
        throw error
    }
    return parsed
}

private func parseInteger(_ value: String, error: MF2Error) throws -> Int {
    guard let parsed = Int(value), String(parsed) == value || (value.hasPrefix("+") && String(parsed) == String(value.dropFirst())) else {
        throw error
    }
    return parsed
}
