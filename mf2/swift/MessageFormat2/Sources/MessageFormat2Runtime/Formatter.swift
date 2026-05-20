import Foundation

public extension MF2Message {
    func format(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults
    ) throws -> String {
        try formatToParts(arguments: arguments, locale: locale, functions: functions).stringValue
    }

    func formatToParts(
        arguments: [String: MF2Value] = [:],
        locale: String = "en",
        functions: MF2FunctionRegistry = .defaults
    ) throws -> [MF2FormattedPart] {
        try validate()
        var context = MF2FormatContext(values: arguments, locale: locale, functions: functions)
        try context.apply(declarations: declarations)
        switch self {
        case let .message(_, pattern):
            return try context.formatToParts(pattern: pattern)
        case let .select(_, selectors, variants):
            return try context.formatToParts(selectors: selectors, variants: variants)
        }
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
        var names: Set<String> = []
        for declaration in declarations {
            if case let .input(name, value) = declaration {
                try validateInputDeclaration(name: name, value: value)
            }
            let name = declaration.name
            guard names.insert(name).inserted else {
                throw MF2Error.duplicateDeclaration(name)
            }
        }
    }

    private func validateInputDeclaration(name: String, value: MF2Expression) throws {
        guard case let .variable(variableName)? = value.arg,
              variableName == name
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
        for selector in selectors where annotations[selector.name] == nil {
            throw MF2Error.missingSelectorAnnotation(selector.name)
        }
    }

}

private struct MF2FormatContext {
    var values: [String: MF2Value]
    var selectorAnnotations: [String: MF2SelectorAnnotation] = [:]
    var locale: String
    var functions: MF2FunctionRegistry

    mutating func apply(declarations: [MF2Declaration]) throws {
        selectorAnnotations = collectSelectorAnnotations(for: declarations)
        for declaration in declarations {
            switch declaration {
            case .input:
                continue
            case let .local(name, value):
                values[name] = .string(try format(expression: value))
            }
        }
    }

    func formatToParts(selectors: [MF2VariableRef], variants: [MF2Variant]) throws -> [MF2FormattedPart] {
        let selectorValues = try selectors.map { selector in
            guard let value = values[selector.name] else {
                throw MF2Error.missingArgument(selector.name)
            }
            return MF2SelectorValue(
                rendered: value.rendered,
                exactMatch: exactMatch(selectorName: selector.name),
                selectionKey: selectionKey(selectorName: selector.name, value: value)
            )
        }

        var signatures: Set<[MF2VariantKey]> = []
        var fallback: MF2Variant?
        var selected: MF2Variant?
        for variant in variants {
            guard variant.keys.count == selectorValues.count else {
                throw MF2Error.variantKeyCountMismatch
            }
            guard signatures.insert(variant.keys).inserted else {
                throw MF2Error.duplicateVariant
            }
            if fallback == nil, variant.isFallback {
                fallback = variant
            }
            if selected == nil, variant.matches(selectorValues: selectorValues) {
                selected = variant
            }
        }

        guard let fallback else {
            throw MF2Error.missingFallbackVariant
        }

        let selectedVariant = selected ?? fallback
        return try formatToParts(pattern: selectedVariant.value)
    }

    func format(pattern: [MF2PatternPart]) throws -> String {
        try formatToParts(pattern: pattern).stringValue
    }

    func formatToParts(pattern: [MF2PatternPart]) throws -> [MF2FormattedPart] {
        var output: [MF2FormattedPart] = []
        for part in pattern {
            switch part {
            case let .text(text):
                output.append(.text(text))
            case let .expression(expression):
                output.append(.expression(
                    try format(expression: expression),
                    attributes: expression.attributes
                ))
            case let .markup(markup):
                output.append(.markup(
                    kind: markup.kind,
                    name: markup.name,
                    attributes: markup.attributes
                ))
            }
        }
        return output
    }

    func format(expression: MF2Expression) throws -> String {
        let value: String
        switch expression.arg {
        case let .literal(literal):
            value = literal
        case let .variable(name):
            guard let argument = values[name] else {
                throw MF2Error.missingArgument(name)
            }
            value = argument.rendered
        case .none:
            value = ""
        }

        switch expression.function?.name {
        case .none:
            return value
        case .some(_):
            guard let function = expression.function else {
                return value
            }
            return try functions.format(MF2FunctionCall(
                value: value,
                function: function,
                locale: locale,
                optionResolver: { optionName, defaultValue in
                    try optionValue(function: function, name: optionName, default: defaultValue)
                }
            ))
        }
    }

    private func optionValue(function: MF2Function, name: String, default defaultValue: String?) throws -> String? {
        guard let option = function.options[name] else {
            return defaultValue
        }
        switch option {
        case let .literal(value):
            return value
        case let .variable(name):
            guard let argument = values[name] else {
                throw MF2Error.missingArgument(name)
            }
            return argument.rendered
        }
    }

    private func exactMatch(selectorName: String) -> Bool {
        selectorAnnotations[selectorName]?.exactMatch ?? true
    }

    private func selectionKey(selectorName: String, value: MF2Value) -> String? {
        guard let annotation = selectorAnnotations[selectorName], annotation.isNumeric else {
            return nil
        }
        return selectPluralCategory(
            locale: locale,
            value: value,
            numberSelect: annotation.numberSelect
        )
    }
}

private func collectSelectorAnnotations(for declarations: [MF2Declaration]) -> [String: MF2SelectorAnnotation] {
    let expressions = Dictionary(uniqueKeysWithValues: declarations.map { ($0.name, $0.value) })
    var annotations = expressions.compactMapValues { expression in
        expression.function.map(MF2SelectorAnnotation.init(function:))
    }

    var changed = true
    while changed {
        changed = false
        for (name, expression) in expressions where annotations[name] == nil {
            guard case let .variable(source)? = expression.arg,
                  let annotation = annotations[source]
            else {
                continue
            }
            annotations[name] = annotation
            changed = true
        }
    }

    return annotations
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
                (selector.exactMatch && value == selector.rendered) || value == selector.selectionKey
            }
        }
    }
}

private struct MF2SelectorValue {
    let rendered: String
    let exactMatch: Bool
    let selectionKey: String?
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

private extension MF2Variant {
    var isFallback: Bool {
        keys.allSatisfy { $0 == .catchAll }
    }
}

public enum MF2FormattedPart: Equatable, Decodable {
    case text(String)
    case expression(String, attributes: [String: MF2AttributeValue])
    case markup(kind: String, name: String, attributes: [String: MF2AttributeValue])

    private enum CodingKeys: String, CodingKey {
        case type
        case value
        case kind
        case name
        case attributes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .value))
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

private extension Array where Element == MF2FormattedPart {
    var stringValue: String {
        map { part in
            switch part {
            case let .text(value), let .expression(value, _):
                value
            case .markup:
                ""
            }
        }.joined()
    }
}
