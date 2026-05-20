import Foundation

public extension MF2Message {
    func format(arguments: [String: MF2Value] = [:], locale: String = "en") throws -> String {
        try formatToParts(arguments: arguments, locale: locale).stringValue
    }

    func formatToParts(arguments: [String: MF2Value] = [:], locale: String = "en") throws -> [MF2FormattedPart] {
        try validate()
        var context = MF2FormatContext(values: arguments, locale: locale)
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
    }

    private func validate(declarations: [MF2Declaration]) throws {
        guard declarations.count > 1 else {
            return
        }
        var names: Set<String> = []
        for declaration in declarations {
            let name = declaration.name
            guard names.insert(name).inserted else {
                throw MF2Error.duplicateDeclaration(name)
            }
        }
    }

}

private struct MF2FormatContext {
    var values: [String: MF2Value]
    var selectorAnnotations: [String: MF2SelectorAnnotation] = [:]
    var locale: String

    mutating func apply(declarations: [MF2Declaration]) throws {
        for declaration in declarations {
            switch declaration {
            case let .input(name, value):
                if let function = value.function {
                    selectorAnnotations[name] = MF2SelectorAnnotation(function: function)
                }
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
                output.append(.expression(try format(expression: expression)))
            case let .markup(markup):
                output.append(.markup(kind: markup.kind, name: markup.name))
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
        case .none, "string", "number", "datetime", "date", "time":
            return value
        case let .some(name):
            throw MF2Error.unsupportedFunction(name)
        }
    }

    private func exactMatch(selectorName: String) -> Bool {
        selectorAnnotations[selectorName]?.exactMatch ?? true
    }

    private func selectionKey(selectorName: String, value: MF2Value) -> String? {
        guard let annotation = selectorAnnotations[selectorName], annotation.function == "number" else {
            return nil
        }
        return selectPluralCategory(
            locale: locale,
            value: value,
            numberSelect: annotation.numberSelect
        )
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
        function == "string" || (function == "number" && numberSelect == .exact)
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
}

private extension MF2Variant {
    var isFallback: Bool {
        keys.allSatisfy { $0 == .catchAll }
    }
}

public enum MF2FormattedPart: Equatable, Decodable {
    case text(String)
    case expression(String)
    case markup(kind: String, name: String)

    private enum CodingKeys: String, CodingKey {
        case type
        case value
        case kind
        case name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .value))
        case "expression":
            self = .expression(try container.decode(String.self, forKey: .value))
        case "markup":
            self = .markup(
                kind: try container.decode(String.self, forKey: .kind),
                name: try container.decode(String.self, forKey: .name)
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
            case let .text(value), let .expression(value):
                value
            case .markup:
                ""
            }
        }.joined()
    }
}
