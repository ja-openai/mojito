import Foundation

public struct MF2FallbackFormatResult: Equatable {
    public let value: String
    public let errors: [MF2Error]
}

public struct MF2FallbackPartsResult: Equatable {
    public let parts: [MF2FormattedPart]
    public let errors: [MF2Error]
}

public extension MF2Message {
    func format(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults,
        bidiIsolation: MF2BidiIsolation = .none
    ) throws -> String {
        try formatToParts(
            arguments: arguments,
            locale: locale,
            functions: functions
        ).stringValue(bidiIsolation: bidiIsolation)
    }

    func formatToParts(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults
    ) throws -> [MF2FormattedPart] {
        try validate()
        var context = MF2FormatContext(
            values: Dictionary(
                uniqueKeysWithValues: arguments.map { (MF2NameKey($0.key), $0.value) }
            ),
            locale: locale,
            functions: functions
        )
        try context.apply(declarations: declarations)
        switch self {
        case let .message(_, pattern):
            return try context.formatToParts(pattern: pattern)
        case let .select(_, selectors, variants):
            return try context.formatToParts(selectors: selectors, variants: variants)
        }
    }

    func formatWithFallback(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults,
        bidiIsolation: MF2BidiIsolation = .none
    ) throws -> MF2FallbackFormatResult {
        let result = try formatToPartsWithFallback(
            arguments: arguments,
            locale: locale,
            functions: functions
        )
        return MF2FallbackFormatResult(
            value: result.parts.stringValue(bidiIsolation: bidiIsolation),
            errors: result.errors
        )
    }

    func formatToPartsWithFallback(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults
    ) throws -> MF2FallbackPartsResult {
        try validate()
        var context = MF2FormatContext(
            values: Dictionary(
                uniqueKeysWithValues: arguments.map { (MF2NameKey($0.key), $0.value) }
            ),
            locale: locale,
            functions: functions,
            fallback: true
        )
        try context.apply(declarations: declarations)
        let parts: [MF2FormattedPart]
        switch self {
        case let .message(_, pattern):
            parts = try context.formatToParts(pattern: pattern)
        case let .select(_, selectors, variants):
            parts = try context.formatToParts(selectors: selectors, variants: variants)
        }
        return MF2FallbackPartsResult(parts: parts, errors: context.errors)
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
    var selectorAnnotations: [MF2NameKey: MF2SelectorAnnotation] = [:]
    var failedLocals: Set<MF2NameKey> = []
    var errors: [MF2Error] = []
    var locale: String
    var functions: MF2FunctionRegistry
    var fallback = false

    mutating func apply(declarations: [MF2Declaration]) throws {
        selectorAnnotations = collectSelectorAnnotations(for: declarations)
        for declaration in declarations {
            switch declaration {
            case .input:
                continue
            case let .local(name, value):
                let rendered = try formatExpressionOutput(value)
                if rendered.hadError {
                    failedLocals.insert(MF2NameKey(name))
                } else {
                    values[MF2NameKey(name)] = .string(rendered.value)
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
                    return MF2SelectorValue(
                        rendered: "",
                        normalizedRendered: annotation?.isString == true ? normalizeStringKey("") : nil,
                        exactMatch: false,
                        selectionKey: nil
                    )
                }
                throw MF2Error.missingArgument(selector.name)
            }
            let annotation = selectorAnnotations[MF2NameKey(selector.name)]
            let rendered = value.rendered
            return MF2SelectorValue(
                rendered: rendered,
                normalizedRendered: annotation?.isString == true ? normalizeStringKey(rendered) : nil,
                exactMatch: annotation?.exactMatch ?? true,
                selectionKey: selectionKey(selectorName: selector.name, value: value)
            )
        }

        var signatures: Set<[MF2VariantKeySignature]> = []
        var fallbackVariant: MF2Variant?
        var selected: MF2Variant?
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
            if selected == nil, variant.matches(selectorValues: selectorValues) {
                selected = variant
            }
        }

        guard let fallbackVariant else {
            throw MF2Error.missingFallbackVariant
        }

        let selectedVariant = selected ?? fallbackVariant
        return try formatToParts(pattern: selectedVariant.value)
    }

    mutating func format(pattern: [MF2PatternPart]) throws -> String {
        try formatToParts(pattern: pattern).stringValue()
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
                    output.append(.fallback(source: fallbackSource(expression)))
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

    mutating func format(expression: MF2Expression) throws -> String {
        try formatExpressionOutput(expression).value
    }

    mutating func formatExpressionOutput(_ expression: MF2Expression) throws -> MF2ExpressionOutput {
        let value: String
        var hadError = false
        switch expression.arg {
        case let .literal(literal):
            value = literal
        case let .variable(name):
            if let argument = values[MF2NameKey(name)] {
                value = argument.rendered
            } else if fallback {
                hadError = true
                let key = MF2NameKey(name)
                if !failedLocals.contains(key) {
                    errors.append(.unresolvedVariable(name))
                }
                if expression.function != nil {
                    errors.append(.badOperand("Function operand is not available."))
                }
                value = fallbackSource(expression)
            } else {
                throw MF2Error.missingArgument(name)
            }
        case .none:
            value = ""
        }

        if hadError {
            return MF2ExpressionOutput(value: value, hadError: true)
        }

        switch expression.function?.name {
        case .none:
            return MF2ExpressionOutput(value: value, hadError: false)
        case .some(_):
            guard let function = expression.function else {
                return MF2ExpressionOutput(value: value, hadError: false)
            }
            let optionValues = values
            do {
                return try MF2ExpressionOutput(
                    value: functions.format(MF2FunctionCall(
                        value: value,
                        function: function,
                        locale: locale,
                        optionResolver: { optionName, defaultValue in
                            try Self.optionValue(
                                function: function,
                                name: optionName,
                                default: defaultValue,
                                values: optionValues
                            )
                        }
                    )),
                    hadError: false
                )
            } catch let error as MF2Error {
                guard fallback else {
                    throw error
                }
                errors.append(fallbackError(error))
                return MF2ExpressionOutput(value: fallbackSource(expression), hadError: true)
            }
        }
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
        return selectPluralCategory(
            locale: locale,
            value: value,
            numberSelect: annotation.numberSelect
        )
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
    let function: String
    let numberSelect: MF2NumberSelect

    init(function: MF2Function) {
        self.function = function.name
        numberSelect = Self.numberSelect(from: function.options["select"])
    }

    var exactMatch: Bool {
        function == "string" || (isNumeric && numberSelect == .exact)
    }

    var isString: Bool {
        function == "string"
    }

    var isNumeric: Bool {
        function == "number" || function == "integer"
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
    func matches(selectorValues: [MF2SelectorValue]) -> Bool {
        guard keys.count == selectorValues.count else {
            return false
        }
        return zip(keys, selectorValues).allSatisfy { key, selector in
            switch key {
            case .catchAll:
                true
            case let .literal(value):
                (selector.exactMatch && literalKeyMatches(value, selector: selector)) || value == selector.selectionKey
            }
        }
    }

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
    let normalizedRendered: String?
    let exactMatch: Bool
    let selectionKey: String?
}

private struct MF2ExpressionOutput {
    let value: String
    let hadError: Bool
}

private enum MF2VariantKeySignature: Hashable {
    case literal(String)
    case catchAll
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
    case fallback(source: String)
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
            self = .fallback(source: try container.decode(String.self, forKey: .source))
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
            case let .fallback(source):
                "{\(source)}"
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
