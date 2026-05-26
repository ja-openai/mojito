import Foundation

public struct MF2FormatResult: Equatable {
    public let value: String
    public let errors: [MF2Error]

    public var ok: Bool {
        errors.isEmpty
    }

    public var hasErrors: Bool {
        !errors.isEmpty
    }
}

public struct MF2PartsResult: Equatable {
    public let parts: [MF2FormattedPart]
    public let errors: [MF2Error]

    public var ok: Bool {
        errors.isEmpty
    }

    public var hasErrors: Bool {
        !errors.isEmpty
    }
}

public struct MF2RecoveryContext {
    public let code: String
    public let message: String
    public let locale: String
    public let variableName: String?
    public let functionName: String?
    public let sourceExpression: String
    public let fallbackValue: String
    public let error: MF2Error
}

public typealias MF2RecoveryHandler = (MF2RecoveryContext) -> String?

public func formatMessage(
    _ model: MF2Message,
    arguments: [String: MF2Value] = [:],
    locale: String = "en",
    functions: MF2FunctionRegistry = .defaults,
    bidiIsolation: MF2BidiIsolation = .none,
    onMissingArgument: MF2RecoveryHandler? = nil,
    onFormatError: MF2RecoveryHandler? = nil
) throws -> MF2FormatResult {
    try model.format(
        arguments: arguments,
        locale: locale,
        functions: functions,
        bidiIsolation: bidiIsolation,
        onMissingArgument: onMissingArgument,
        onFormatError: onFormatError
    )
}

public func formatMessageToParts(
    _ model: MF2Message,
    arguments: [String: MF2Value] = [:],
    locale: String = "en",
    functions: MF2FunctionRegistry = .defaults,
    onMissingArgument: MF2RecoveryHandler? = nil,
    onFormatError: MF2RecoveryHandler? = nil
) throws -> MF2PartsResult {
    try model.formatToParts(
        arguments: arguments,
        locale: locale,
        functions: functions,
        onMissingArgument: onMissingArgument,
        onFormatError: onFormatError
    )
}

public extension MF2Message {
    func format(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults,
        bidiIsolation: MF2BidiIsolation = .none,
        onMissingArgument: MF2RecoveryHandler? = nil,
        onFormatError: MF2RecoveryHandler? = nil
    ) throws -> MF2FormatResult {
        let result = try formatToParts(
            arguments: arguments,
            locale: locale,
            functions: functions,
            onMissingArgument: onMissingArgument,
            onFormatError: onFormatError
        )
        return MF2FormatResult(
            value: result.parts.stringValue(bidiIsolation: bidiIsolation),
            errors: result.errors
        )
    }

    func formatToParts(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults,
        onMissingArgument: MF2RecoveryHandler? = nil,
        onFormatError: MF2RecoveryHandler? = nil
    ) throws -> MF2PartsResult {
        try validate()
        var context = MF2FormatContext(
            values: Dictionary(
                uniqueKeysWithValues: arguments.map { (MF2NameKey($0.key), $0.value) }
            ),
            locale: locale,
            functions: functions,
            fallback: true,
            onMissingArgument: onMissingArgument,
            onFormatError: onFormatError
        )
        try context.apply(declarations: declarations)
        let parts: [MF2FormattedPart]
        switch self {
        case let .message(_, pattern):
            parts = try context.formatToParts(pattern: pattern)
        case let .select(_, selectors, variants):
            parts = try context.formatToParts(selectors: selectors, variants: variants)
        }
        return MF2PartsResult(parts: parts, errors: context.errors)
    }

    private var declarations: [MF2Declaration] {
        switch self {
        case let .message(declarations, _), let .select(declarations, _, _):
            declarations
        }
    }

    private func validate() throws {
        try validate(declarations: declarations)
        switch self {
        case let .message(_, pattern):
            try validate(pattern: pattern)
        case let .select(declarations, selectors, variants):
            try validateSelectorAnnotations(declarations: declarations, selectors: selectors)
            for variant in variants {
                try validate(pattern: variant.value)
            }
        }
    }

    private func validate(declarations: [MF2Declaration]) throws {
        var names: Set<MF2NameKey> = []
        for declaration in declarations {
            if case let .input(name, value) = declaration {
                try validateInputDeclaration(name: name, value: value)
            }
            let name = declaration.name
            guard names.insert(MF2NameKey(name)).inserted else {
                throw MF2Error.duplicateDeclaration(name)
            }
        }
        try validateLocalReferences(declarations: declarations)
    }

    private func validateLocalReferences(declarations: [MF2Declaration]) throws {
        var forbidden: Set<MF2NameKey> = []
        for declaration in declarations.reversed() {
            guard case let .local(name, value) = declaration else {
                continue
            }
            forbidden.insert(MF2NameKey(name))
            if value.referencesAny(forbidden) {
                throw MF2Error.duplicateDeclaration(name)
            }
        }
    }

    private func validateInputDeclaration(name: String, value: MF2Expression) throws {
        guard case let .variable(variableName)? = value.arg,
              MF2NameKey(variableName) == MF2NameKey(name)
        else {
            throw MF2Error.invalidInputDeclaration(name)
        }
    }

    private func validate(pattern: [MF2PatternPart]) throws {
        for part in pattern {
            if case let .text(text) = part, text.isEmpty {
                throw MF2Error.invalidPatternText
            }
            if case let .markup(markup) = part {
                try validate(markup: markup)
            }
        }
    }

    private func validate(markup: MF2Markup) throws {
        switch markup.kind {
        case "open", "standalone", "close":
            return
        default:
            throw MF2Error.invalidMarkupKind
        }
    }

    private func validateSelectorAnnotations(
        declarations: [MF2Declaration],
        selectors: [MF2VariableRef]
    ) throws {
        let annotations = collectSelectorAnnotations(for: declarations)
        for selector in selectors where annotations[MF2NameKey(selector.name)] == nil {
            throw MF2Error.missingSelectorAnnotation(selector.name)
        }
    }

}

private struct MF2FormatContext {
    var values: [MF2NameKey: MF2Value]
    var sources: [MF2NameKey: MF2FunctionSource] = [:]
    var selectorAnnotations: [MF2NameKey: MF2SelectorAnnotation] = [:]
    var failedLocals: Set<MF2NameKey> = []
    var errors: [MF2Error] = []
    var locale: String
    var functions: MF2FunctionRegistry
    var fallback = false
    var onMissingArgument: MF2RecoveryHandler?
    var onFormatError: MF2RecoveryHandler?

    mutating func apply(declarations: [MF2Declaration]) throws {
        selectorAnnotations = collectSelectorAnnotations(for: declarations)
        for declaration in declarations {
            switch declaration {
            case let .input(name, value):
                guard value.function != nil, values[MF2NameKey(name)] != nil else {
                    continue
                }
                let rendered = try formatExpressionOutput(value)
                if rendered.hadError {
                    failedLocals.insert(MF2NameKey(name))
                } else {
                    let key = MF2NameKey(name)
                    values[key] = .string(rendered.value)
                    sources[key] = rendered.source
                }
            case let .local(name, value):
                let rendered = try formatExpressionOutput(value)
                if rendered.hadError {
                    failedLocals.insert(MF2NameKey(name))
                } else {
                    let key = MF2NameKey(name)
                    values[key] = .string(rendered.value)
                    sources[key] = rendered.source
                }
            }
        }
    }

    mutating func formatToParts(selectors: [MF2VariableRef], variants: [MF2Variant]) throws -> [MF2FormattedPart] {
        let selectorValues = try selectors.map { selector in
            guard let value = values[MF2NameKey(selector.name)] else {
                if fallback {
                    let key = MF2NameKey(selector.name)
                    if !failedLocals.contains(key) {
                        errors.append(.unresolvedVariable(selector.name))
                    }
                    let annotation = selectorAnnotations[key]
                    if let annotation, functions.hasSelector(annotation.function) {
                        if !failedLocals.contains(key) {
                            errors.append(.badOperand("Selector operand is not available."))
                        }
                        errors.append(.badSelector("Selector operand is not available."))
                    }
                    return MF2SelectorValue(
                        rendered: "",
                        rawValue: .string(""),
                        normalizedRendered: annotation?.isString == true ? normalizeStringKey("") : nil,
                        exactMatch: false,
                        selectionKey: nil,
                        function: annotation?.function,
                        source: nil
                    )
                }
                throw MF2Error.missingArgument(selector.name)
            }
            let annotation = selectorAnnotations[MF2NameKey(selector.name)]
            let rendered = value.rendered
            return MF2SelectorValue(
                rendered: rendered,
                rawValue: value,
                normalizedRendered: annotation?.isString == true ? normalizeStringKey(rendered) : nil,
                exactMatch: annotation?.exactMatch ?? true,
                selectionKey: selectionKey(selectorName: selector.name, value: value),
                function: annotation?.function,
                source: sources[MF2NameKey(selector.name)]
            )
        }

        var signatures: Set<[MF2VariantKeySignature]> = []
        var fallbackVariant: MF2Variant?
        var selected: MF2Variant?
        var selectedRank: [Int]?
        for variant in variants {
            guard variant.keys.count == selectorValues.count else {
                throw MF2Error.variantKeyCountMismatch
            }
            guard signatures.insert(variant.signature(selectorValues: selectorValues)).inserted else {
                throw MF2Error.duplicateVariant
            }
            if fallbackVariant == nil, variant.isFallback {
                fallbackVariant = variant
            }
            if let rank = try variantMatchRank(variant, selectorValues: selectorValues),
               selectedRank == nil || compareRank(rank, selectedRank!) > 0 {
                selected = variant
                selectedRank = rank
            }
        }

        guard let fallbackVariant else {
            throw MF2Error.missingFallbackVariant
        }

        let selectedVariant = selected ?? fallbackVariant
        return try formatToParts(pattern: selectedVariant.value)
    }

    mutating func formatToParts(pattern: [MF2PatternPart]) throws -> [MF2FormattedPart] {
        var output: [MF2FormattedPart] = []
        for part in pattern {
            switch part {
            case let .text(text):
                output.append(.text(text))
            case let .expression(expression):
                let rendered = try formatExpressionOutput(expression)
                if rendered.hadError {
                    let source = rendered.fallbackSource ?? fallbackSource(expression)
                    let value = rendered.value == fallbackValue(source) ? nil : rendered.value
                    output.append(.fallback(source: source, value: value))
                } else {
                    output.append(.expression(
                        rendered.value,
                        attributes: expression.attributes
                    ))
                }
            case let .markup(markup):
                output.append(.markup(
                    kind: markup.kind,
                    name: markup.name,
                    options: markup.options,
                    attributes: markup.attributes
                ))
            }
        }
        return output
    }

    mutating func formatExpressionOutput(_ expression: MF2Expression) throws -> MF2ExpressionOutput {
        let value: String
        let rawValue: MF2Value
        var hadError = false
        var source: MF2FunctionSource?
        switch expression.arg {
        case let .literal(literal):
            value = literal
            rawValue = .string(literal)
        case let .variable(name):
            if let argument = values[MF2NameKey(name)] {
                value = argument.rendered
                rawValue = argument
                source = sources[MF2NameKey(name)]
            } else if fallback {
                hadError = true
                let key = MF2NameKey(name)
                let error = MF2Error.unresolvedVariable(name)
                if !failedLocals.contains(key) {
                    errors.append(error)
                }
                if expression.function != nil {
                    errors.append(.badOperand("Function operand is not available."))
                }
                let source = fallbackSource(expression)
                value = recoverMissingArgument(
                    expression: expression,
                    variableName: name,
                    source: source,
                    error: error
                )
                rawValue = .string(value)
            } else {
                throw MF2Error.missingArgument(name)
            }
        case .none:
            value = ""
            rawValue = .string("")
        }

        if hadError {
            return MF2ExpressionOutput(value: value, hadError: true, fallbackSource: fallbackSource(expression))
        }

        switch expression.function?.name {
        case .none:
            return MF2ExpressionOutput(value: value, hadError: false, source: source)
        case .some(_):
            guard let function = expression.function else {
                return MF2ExpressionOutput(value: value, hadError: false)
            }
            let optionValues = values
            do {
                return try MF2ExpressionOutput(
                    value: functions.format(MF2FunctionCall(
                        value: value,
                        rawValue: rawValue,
                        function: function,
                        locale: locale,
                        inheritedSource: source,
                        optionResolver: { optionName, defaultValue in
                            try Self.optionValue(
                                function: function,
                                name: optionName,
                                default: defaultValue,
                                values: optionValues
                            )
                        }
                    )),
                    hadError: false,
                    source: MF2FunctionSource(
                        value: source?.value ?? value,
                        function: function,
                        inheritedSource: source,
                        optionResolver: { optionName, defaultValue in
                            try Self.optionValue(
                                function: function,
                                name: optionName,
                                default: defaultValue,
                                values: optionValues
                            )
                        }
                    )
                )
            } catch let error as MF2Error {
                guard fallback else {
                    throw error
                }
                let recoverable = fallbackError(error)
                errors.append(recoverable)
                let source = fallbackSource(expression)
                return MF2ExpressionOutput(
                    value: recoverFormatError(expression: expression, source: source, error: recoverable),
                    hadError: true,
                    fallbackSource: source
                )
            }
        }
    }

    private func recoverMissingArgument(
        expression: MF2Expression,
        variableName: String,
        source: String,
        error: MF2Error
    ) -> String {
        recoverValue(
            handler: onMissingArgument,
            context: MF2RecoveryContext(
                code: error.code,
                message: error.description,
                locale: locale,
                variableName: variableName,
                functionName: expression.function?.name,
                sourceExpression: expressionSource(expression),
                fallbackValue: fallbackValue(source),
                error: error
            )
        )
    }

    private func recoverFormatError(expression: MF2Expression, source: String, error: MF2Error) -> String {
        recoverValue(
            handler: onFormatError,
            context: MF2RecoveryContext(
                code: error.code,
                message: error.description,
                locale: locale,
                variableName: expression.variableName,
                functionName: expression.function?.name,
                sourceExpression: expressionSource(expression),
                fallbackValue: fallbackValue(source),
                error: error
            )
        )
    }

    private static func optionValue(
        function: MF2Function,
        name: String,
        default defaultValue: String?,
        values: [MF2NameKey: MF2Value]
    ) throws -> String? {
        guard let option = function.options[name] else {
            return defaultValue
        }
        switch option {
        case let .literal(value):
            return value
        case let .variable(name):
            guard let argument = values[MF2NameKey(name)] else {
                throw MF2Error.missingArgument(name)
            }
            return argument.rendered
        }
    }

    private func exactMatch(selectorName: String) -> Bool {
        selectorAnnotations[MF2NameKey(selectorName)]?.exactMatch ?? true
    }

    private func selectionKey(selectorName: String, value: MF2Value) -> String? {
        guard let annotation = selectorAnnotations[MF2NameKey(selectorName)], annotation.isNumeric else {
            return nil
        }
        let selectionValue = annotation.function.name == "percent"
            ? percentPluralOperand(value)
            : value
        return selectPluralCategory(
            locale: locale,
            value: selectionValue,
            numberSelect: annotation.numberSelect
        )
    }

    private mutating func variantMatchRank(
        _ variant: MF2Variant,
        selectorValues: [MF2SelectorValue]
    ) throws -> [Int]? {
        guard variant.keys.count == selectorValues.count else {
            return nil
        }
        var rank: [Int] = []
        for (key, selector) in zip(variant.keys, selectorValues) {
            guard let itemRank = try keyMatchRank(key, selector: selector) else {
                return nil
            }
            rank.append(itemRank)
        }
        return rank
    }

    private mutating func keyMatchRank(_ key: MF2VariantKey, selector: MF2SelectorValue) throws -> Int? {
        switch key {
        case .catchAll:
            return 0
        case let .literal(value):
            if (selector.exactMatch && literalKeyMatches(value, selector: selector)) || value == selector.selectionKey {
                return 1
            }
            guard let function = selector.function else {
                return nil
            }
            let optionValues = values
            do {
                return try functions.select(MF2FunctionMatch(
                    value: selector.rendered,
                    rawValue: selector.rawValue,
                    function: function,
                    key: value,
                    locale: locale,
                    inheritedSource: selector.source,
                    optionResolver: { optionName, defaultValue in
                        try Self.optionValue(
                            function: function,
                            name: optionName,
                            default: defaultValue,
                            values: optionValues
                        )
                    }
                ))
            } catch let error as MF2Error {
                guard fallback else {
                    throw error
                }
                errors.append(fallbackError(error))
                errors.append(.badSelector("Selector failed to match."))
                return nil
            }
        }
    }
}

private func collectSelectorAnnotations(for declarations: [MF2Declaration]) -> [MF2NameKey: MF2SelectorAnnotation] {
    var expressions: [MF2NameKey: MF2Expression] = [:]
    for declaration in declarations {
        expressions[MF2NameKey(declaration.name)] = declaration.value
    }
    var annotations = expressions.compactMapValues { expression in
        expression.function.map(MF2SelectorAnnotation.init(function:))
    }

    var changed = true
    while changed {
        changed = false
        for (name, expression) in expressions where annotations[name] == nil {
            guard case let .variable(source)? = expression.arg,
                  let annotation = annotations[MF2NameKey(source)]
            else {
                continue
            }
            annotations[name] = annotation
            changed = true
        }
    }

    return annotations
}

private struct MF2NameKey: Hashable {
    private let value: String

    init(_ value: String) {
        self.value = value
    }

    static func == (left: MF2NameKey, right: MF2NameKey) -> Bool {
        left.value.utf8.elementsEqual(right.value.utf8)
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(value.utf8.count)
        for byte in value.utf8 {
            hasher.combine(byte)
        }
    }
}

private struct MF2SelectorAnnotation {
    let function: MF2Function
    let numberSelect: MF2NumberSelect

    init(function: MF2Function) {
        self.function = function
        numberSelect = Self.numberSelect(from: function.options["select"])
    }

    var exactMatch: Bool {
        function.name == "string" || (isNumeric && numberSelect == .exact)
    }

    var isString: Bool {
        function.name == "string"
    }

    var isNumeric: Bool {
        function.name == "number" || function.name == "integer" || function.name == "percent" || function.name == "offset"
    }

    private static func numberSelect(from option: MF2ExpressionArgument?) -> MF2NumberSelect {
        guard case let .literal(value) = option else {
            return .plural
        }
        switch value {
        case "ordinal":
            return .ordinal
        case "exact":
            return .exact
        default:
            return .plural
        }
    }
}

private extension MF2Variant {
    func signature(selectorValues: [MF2SelectorValue]) -> [MF2VariantKeySignature] {
        zip(keys, selectorValues).map { key, selector in
            switch key {
            case .catchAll:
                .catchAll
            case let .literal(value):
                .literal(selector.normalizedRendered == nil ? value : normalizeStringKey(value))
            }
        }
    }
}

private struct MF2SelectorValue {
    let rendered: String
    let rawValue: MF2Value
    let normalizedRendered: String?
    let exactMatch: Bool
    let selectionKey: String?
    let function: MF2Function?
    let source: MF2FunctionSource?
}

private struct MF2ExpressionOutput {
    let value: String
    let hadError: Bool
    let source: MF2FunctionSource?
    let fallbackSource: String?

    init(value: String, hadError: Bool, source: MF2FunctionSource? = nil, fallbackSource: String? = nil) {
        self.value = value
        self.hadError = hadError
        self.source = source
        self.fallbackSource = fallbackSource
    }
}

private enum MF2VariantKeySignature: Hashable {
    case literal(String)
    case catchAll
}

private func compareRank(_ left: [Int], _ right: [Int]) -> Int {
    for (leftItem, rightItem) in zip(left, right) {
        if leftItem != rightItem {
            return leftItem - rightItem
        }
    }
    return left.count - right.count
}

private func literalKeyMatches(_ value: String, selector: MF2SelectorValue) -> Bool {
    guard let normalizedRendered = selector.normalizedRendered else {
        return value == selector.rendered
    }
    return normalizeStringKey(value) == normalizedRendered
}

private func normalizeStringKey(_ value: String) -> String {
    value.precomposedStringWithCanonicalMapping
}

private func percentPluralOperand(_ value: MF2Value) -> MF2Value {
    let rendered = value.rendered
    if rendered.hasSuffix("%") {
        return .number(String(rendered.dropLast()))
    }
    guard let number = Double(rendered) else {
        return value
    }
    return .number(formatPluralNumber(number * 100))
}

private func formatPluralNumber(_ value: Double) -> String {
    if value.isFinite, value.rounded(.towardZero) == value {
        return String(Int64(value))
    }
    return String(value)
}

private func fallbackError(_ error: MF2Error) -> MF2Error {
    switch error {
    case let .unsupportedFunction(name):
        .unknownFunction(name)
    default:
        error
    }
}

private func fallbackSource(_ expression: MF2Expression) -> String {
    if let arg = expression.arg {
        return expressionArgumentSource(arg)
    }
    if let function = expression.function {
        return functionSource(function)
    }
    return ""
}

private func fallbackValue(_ source: String) -> String {
    "{\(source)}"
}

private func recoverValue(handler: MF2RecoveryHandler?, context: MF2RecoveryContext) -> String {
    handler?(context) ?? context.fallbackValue
}

private func expressionSource(_ expression: MF2Expression) -> String {
    var items: [String] = []
    if let arg = expression.arg {
        items.append(expressionArgumentSource(arg))
    }
    if let function = expression.function {
        items.append(functionSource(function))
    }
    return "{\(items.joined(separator: " "))}"
}

private func expressionArgumentSource(_ argument: MF2ExpressionArgument) -> String {
    switch argument {
    case let .literal(value):
        quoteLiteralSource(value)
    case let .variable(name):
        "$\(name)"
    }
}

private func functionSource(_ function: MF2Function) -> String {
    var source = ":\(function.name)"
    for key in function.options.keys.sorted() {
        guard let value = function.options[key] else {
            continue
        }
        source += " \(key)=\(expressionArgumentSource(value))"
    }
    return source
}

private func quoteLiteralSource(_ value: String) -> String {
    var source = "|"
    for character in value {
        if character == "\\" || character == "|" {
            source.append("\\")
        }
        source.append(character)
    }
    source.append("|")
    return source
}

private extension MF2Declaration {
    var name: String {
        switch self {
        case let .input(name, _), let .local(name, _):
            name
        }
    }

    var value: MF2Expression {
        switch self {
        case let .input(_, value), let .local(_, value):
            value
        }
    }
}

private extension MF2Expression {
    func referencesAny(_ names: Set<MF2NameKey>) -> Bool {
        arg.referencesAny(names) || function?.referencesAny(names) == true
    }

    var variableName: String? {
        guard case let .variable(name)? = arg else {
            return nil
        }
        return name
    }
}

private extension Optional where Wrapped == MF2ExpressionArgument {
    func referencesAny(_ names: Set<MF2NameKey>) -> Bool {
        guard case let .variable(name)? = self else {
            return false
        }
        return names.contains(MF2NameKey(name))
    }
}

private extension MF2Function {
    func referencesAny(_ names: Set<MF2NameKey>) -> Bool {
        options.values.contains { option in
            option.referencesAny(names)
        }
    }
}

private extension MF2ExpressionArgument {
    func referencesAny(_ names: Set<MF2NameKey>) -> Bool {
        guard case let .variable(name) = self else {
            return false
        }
        return names.contains(MF2NameKey(name))
    }
}

private extension MF2Variant {
    var isFallback: Bool {
        keys.allSatisfy { $0 == .catchAll }
    }
}

public enum MF2FormattedPart: Equatable, Decodable {
    case text(String)
    case fallback(source: String, value: String?)
    case expression(String, attributes: [String: MF2AttributeValue])
    case markup(
        kind: String,
        name: String,
        options: [String: MF2ExpressionArgument],
        attributes: [String: MF2AttributeValue]
    )

    private enum CodingKeys: String, CodingKey {
        case type
        case value
        case source
        case kind
        case name
        case options
        case attributes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .value))
        case "fallback":
            self = .fallback(
                source: try container.decode(String.self, forKey: .source),
                value: try container.decodeIfPresent(String.self, forKey: .value)
            )
        case "expression":
            self = .expression(
                try container.decode(String.self, forKey: .value),
                attributes: try container.decodeIfPresent(
                    [String: MF2AttributeValue].self,
                    forKey: .attributes
                ) ?? [:]
            )
        case "markup":
            self = .markup(
                kind: try container.decode(String.self, forKey: .kind),
                name: try container.decode(String.self, forKey: .name),
                options: try container.decodeIfPresent(
                    [String: MF2ExpressionArgument].self,
                    forKey: .options
                ) ?? [:],
                attributes: try container.decodeIfPresent(
                    [String: MF2AttributeValue].self,
                    forKey: .attributes
                ) ?? [:]
            )
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported MF2 formatted part type: \(type)"
            )
        }
    }
}

public enum MF2BidiIsolation: String, Decodable {
    case none
    case `default`
}

private extension Array where Element == MF2FormattedPart {
    func stringValue(bidiIsolation: MF2BidiIsolation = .none) -> String {
        map { part in
            switch part {
            case let .text(value):
                value
            case let .fallback(source, value):
                value ?? fallbackValue(source)
            case let .expression(value, _):
                isolateExpression(value, bidiIsolation: bidiIsolation)
            case .markup:
                ""
            }
        }.joined()
    }
}

private func isolateExpression(_ value: String, bidiIsolation: MF2BidiIsolation) -> String {
    switch bidiIsolation {
    case .none:
        value
    case .default:
        "\u{2068}\(value)\u{2069}"
    }
}
