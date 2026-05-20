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
            "integer": passthroughFunction,
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
    public let function: MF2Function
    public let locale: String
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        function: MF2Function,
        locale: String,
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.value = value
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
