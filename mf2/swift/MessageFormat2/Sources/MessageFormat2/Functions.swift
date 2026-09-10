public typealias MF2FunctionFormatter = (MF2FunctionCall) throws -> String
public typealias MF2FunctionSelector = (MF2FunctionMatch) throws -> Int?

public struct MF2FunctionRegistry: @unchecked Sendable {
    private let formatters: [String: MF2FunctionFormatter]
    private let selectors: [String: MF2FunctionSelector]
    private var productionNumericFormatters: Set<String> = []

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
        var registry = MF2FunctionRegistry(formatters: formatters, selectors: selectors)
        registry.productionNumericFormatters = productionNumericFormatters.subtracting([name])
        return registry
    }

    public func withSelector(_ name: String, selector: @escaping MF2FunctionSelector) -> MF2FunctionRegistry {
        var selectors = self.selectors
        selectors[name] = selector
        var registry = MF2FunctionRegistry(formatters: formatters, selectors: selectors)
        registry.productionNumericFormatters = productionNumericFormatters
        return registry
    }

    func format(_ call: MF2FunctionCall) throws -> String {
        guard let formatter = formatters[call.function.name] else {
            throw MF2Error.unsupportedFunction(call.function.name)
        }
        return try formatter(call)
    }

    func withProductionNumericFunctions(_ names: Set<String>) -> MF2FunctionRegistry {
        var registry = self
        registry.productionNumericFormatters.formUnion(names)
        return registry
    }

    func isProductionNumericFormatter(_ function: MF2Function) -> Bool {
        productionNumericFormatters.contains(function.name)
    }

    func hasFormatter(_ function: MF2Function) -> Bool { formatters[function.name] != nil }

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
        if name == "u:dir" { return defaultValue }
        return try optionResolver(name, defaultValue)
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
        if name == "u:dir" { return defaultValue }
        return try optionResolver(name, defaultValue)
    }
}

struct MF2ResolvedBidi {
    var direction: String?
    var explicitDirection = false
    var forceIsolation = false
    var publicDirection: String? { explicitDirection ? direction : nil }
}

public final class MF2FunctionSource {
    public let value: String
    public let function: MF2Function
    public var inheritedSource: MF2FunctionSource? { storedInheritedSource }
    private var storedInheritedSource: MF2FunctionSource?
    let bidi: MF2ResolvedBidi
    let cacheableNumericSource: Bool
    var numericOperand: String?
    var numericOperandComputed = false
    var numericOptions: [[String]: String?] = [:]
    private let optionResolver: (String, String?) throws -> String?

    init(
        value: String,
        function: MF2Function,
        inheritedSource: MF2FunctionSource?,
        bidi: MF2ResolvedBidi = MF2ResolvedBidi(),
        optionResolver: @escaping (String, String?) throws -> String?
    ) {
        self.bidi = bidi
        self.cacheableNumericSource =
            function.options.values.allSatisfy {
                if case .literal = $0 { return true }; return false
            } && (inheritedSource?.cacheableNumericSource ?? true)
        self.value = value
        self.function = function
        self.storedInheritedSource = inheritedSource
        self.optionResolver = optionResolver
    }

    deinit {
        // Release unique history tails iteratively, keeping long chains off the stack.
        while isKnownUniquelyReferenced(&storedInheritedSource) {
            let next = storedInheritedSource?.storedInheritedSource
            storedInheritedSource?.storedInheritedSource = nil
            storedInheritedSource = next
        }
    }

    public func optionValue(_ name: String, default defaultValue: String? = nil) throws -> String? {
        if name == "u:dir" { return defaultValue }
        return try optionResolver(name, defaultValue)
    }
}

func resolvedOptionValue(
    _ call: MF2FunctionCall,
    name: String,
    inheritedFrom functionNames: Set<String>
) throws -> String? {
    if let value = try call.optionValue(name) {
        return value
    }
    return try inheritedOptionValue(
        call.inheritedSource,
        name: name,
        targetFunction: call.function.name,
        from: functionNames
    )
}

func resolvedOptionValue(
    _ match: MF2FunctionMatch,
    name: String,
    inheritedFrom functionNames: Set<String>
) throws -> String? {
    if let value = try match.optionValue(name) {
        return value
    }
    return try inheritedOptionValue(
        match.inheritedSource,
        name: name,
        targetFunction: match.function.name,
        from: functionNames
    )
}

func inheritedOptionValue(
    _ source: MF2FunctionSource?,
    name: String,
    targetFunction: String,
    from functionNames: Set<String>
) throws -> String? {
    guard !numericOptionIsDiscarded(function: targetFunction, option: name) else { return nil }
    var current = source
    var allowedFunctions = functionNames
    var pending: [(MF2FunctionSource, [String])] = []
    var value: String?
    while let source = current {
        let key = [name] + allowedFunctions.sorted()
        if source.cacheableNumericSource {
            if let cached = source.numericOptions[key] { value = cached; break }
            pending.append((source, key))
        }
        let sourceFunction = source.function.name
        guard allowedFunctions.contains(sourceFunction),
            !numericOptionIsDiscarded(function: sourceFunction, option: name)
        else { break }
        if let resolved = try source.optionValue(name) { value = resolved; break }
        allowedFunctions = sourceFunction == "currency" ? Set(["currency"]) : numericOptionSources(for: sourceFunction)
        current = source.inheritedSource
    }
    for (source, key) in pending { source.numericOptions[key] = .some(value) }
    return value
}

func numericOptionSources(for functionName: String) -> Set<String> {
    switch functionName {
    case "number", "integer", "percent", "offset":
        ["number", "integer", "percent", "offset"]
    default:
        []
    }
}

private func numericOptionIsDiscarded(function: String, option: String) -> Bool {
    switch function {
    case "integer":
        [
            "minimumFractionDigits",
            "maximumFractionDigits",
            "minimumSignificantDigits",
        ].contains(option)
    case "percent":
        ["minimumIntegerDigits", "roundingIncrement", "select"].contains(option)
    case "offset":
        ["add", "subtract"].contains(option)
    default:
        false
    }
}
