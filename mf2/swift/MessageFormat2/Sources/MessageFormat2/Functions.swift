public typealias MF2FunctionFormatter = (MF2FunctionCall) throws -> String
public typealias MF2FunctionSelector = (MF2FunctionMatch) throws -> Int?

public struct MF2FunctionRegistry: @unchecked Sendable {
    private let formatters: [String: MF2FunctionFormatter]
    private let selectors: [String: MF2FunctionSelector]

    public init(
        formatters: [String: MF2FunctionFormatter] = [:],
        selectors: [String: MF2FunctionSelector] = [:]
    ) {
        self.formatters = formatters
        self.selectors = selectors
    }

    public static let portable = makePortableFunctionRegistry()

    public static let defaults = portable

    public static let foundation = makeFoundationFunctionRegistry()

    public func withFunction(_ name: String, formatter: @escaping MF2FunctionFormatter) -> MF2FunctionRegistry {
        var formatters = self.formatters
        formatters[name] = formatter
        return MF2FunctionRegistry(formatters: formatters, selectors: selectors)
    }

    public func withSelector(_ name: String, selector: @escaping MF2FunctionSelector) -> MF2FunctionRegistry {
        var selectors = self.selectors
        selectors[name] = selector
        return MF2FunctionRegistry(formatters: formatters, selectors: selectors)
    }

    func format(_ call: MF2FunctionCall) throws -> String {
        guard let formatter = formatters[call.function.name] else {
            throw MF2Error.unsupportedFunction(call.function.name)
        }
        return try formatter(call)
    }

    func hasSelector(_ function: MF2Function) -> Bool {
        selectors[function.name] != nil
    }

    func select(_ match: MF2FunctionMatch) throws -> Int? {
        guard let selector = selectors[match.function.name] else {
            return nil
        }
        return try selector(match)
    }
}

public struct MF2FunctionCall {
    public let value: String
    public let rawValue: MF2Value
    public let function: MF2Function
    public let locale: String
    public let inheritedSource: MF2FunctionSource?
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        rawValue: MF2Value,
        function: MF2Function,
        locale: String,
        inheritedSource: MF2FunctionSource?,
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.value = value
        self.rawValue = rawValue
        self.function = function
        self.locale = locale
        self.inheritedSource = inheritedSource
        self.optionResolver = optionResolver
    }

    public func optionValue(_ name: String, default defaultValue: String? = nil) throws -> String? {
        try optionResolver(name, defaultValue)
    }
}

public struct MF2FunctionMatch {
    public let value: String
    public let rawValue: MF2Value
    public let function: MF2Function
    public let key: String
    public let locale: String
    public let inheritedSource: MF2FunctionSource?
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        rawValue: MF2Value,
        function: MF2Function,
        key: String,
        locale: String,
        inheritedSource: MF2FunctionSource?,
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.value = value
        self.rawValue = rawValue
        self.function = function
        self.key = key
        self.locale = locale
        self.inheritedSource = inheritedSource
        self.optionResolver = optionResolver
    }

    public func optionValue(_ name: String, default defaultValue: String? = nil) throws -> String? {
        try optionResolver(name, defaultValue)
    }
}

public final class MF2FunctionSource {
    public let value: String
    public let function: MF2Function
    public let inheritedSource: MF2FunctionSource?
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        function: MF2Function,
        inheritedSource: MF2FunctionSource?,
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.value = value
        self.function = function
        self.inheritedSource = inheritedSource
        self.optionResolver = optionResolver
    }

    public func optionValue(_ name: String, default defaultValue: String? = nil) throws -> String? {
        try optionResolver(name, defaultValue)
    }
}
