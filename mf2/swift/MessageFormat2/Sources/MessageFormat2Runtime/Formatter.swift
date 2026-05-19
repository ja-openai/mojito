import Foundation

public extension MF2Message {
    func format(arguments: [String: MF2Value] = [:], locale: String = "en") throws -> String {
        var context = MF2FormatContext(values: arguments, locale: locale)
        try context.apply(declarations: declarations)
        switch self {
        case let .message(_, pattern):
            return try context.format(pattern: pattern)
        case let .select(_, selectors, variants):
            return try context.format(selectors: selectors, variants: variants)
        }
    }

    private var declarations: [MF2Declaration] {
        switch self {
        case let .message(declarations, _), let .select(declarations, _, _):
            declarations
        }
    }
}

private struct MF2FormatContext {
    var values: [String: MF2Value]
    var inputFunctions: [String: String] = [:]
    var locale: String

    mutating func apply(declarations: [MF2Declaration]) throws {
        for declaration in declarations {
            switch declaration {
            case let .input(name, value):
                if let function = value.function {
                    inputFunctions[name] = function.name
                }
            case let .local(name, value):
                values[name] = .string(try format(expression: value))
            }
        }
    }

    func format(selectors: [MF2VariableRef], variants: [MF2Variant]) throws -> String {
        let selectorValues = try selectors.map { selector in
            guard let value = values[selector.name] else {
                throw MF2Error.missingArgument(selector.name)
            }
            return MF2SelectorValue(
                rendered: value.rendered,
                pluralCategory: pluralCategory(selectorName: selector.name, value: value)
            )
        }

        let selected = variants.first { variant in
            variant.matches(selectorValues: selectorValues)
        } ?? variants.first { variant in
            variant.keys.allSatisfy { $0 == .catchAll }
        }

        guard let selected else {
            throw MF2Error.missingSelectVariant
        }

        return try format(pattern: selected.value)
    }

    func format(pattern: [MF2PatternPart]) throws -> String {
        var output = ""
        for part in pattern {
            switch part {
            case let .text(text):
                output += text
            case let .expression(expression):
                output += try format(expression: expression)
            case .markup:
                continue
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

    private func pluralCategory(selectorName: String, value: MF2Value) -> String? {
        guard inputFunctions[selectorName] == "number" else {
            return nil
        }
        return selectCardinalPluralCategory(locale: locale, value: value)
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
                value == selector.rendered || value == selector.pluralCategory
            }
        }
    }
}

private struct MF2SelectorValue {
    let rendered: String
    let pluralCategory: String?
}
