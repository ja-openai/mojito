import Foundation

public struct MF2ParseDiagnostic: Equatable {
    public let code: String
    public let message: String
    public let start: Int
    public let end: Int
    public let severity: String

    public init(
        code: String,
        message: String,
        start: Int,
        end: Int,
        severity: String = "error"
    ) {
        self.code = code
        self.message = message
        self.start = start
        self.end = end
        self.severity = severity
    }
}

public struct MF2ParseResult: Equatable {
    public let model: MF2Message?
    public let diagnostics: [MF2ParseDiagnostic]

    public var hasDiagnostics: Bool {
        !diagnostics.isEmpty
    }
}

public enum MF2Parser {
    public static func parse(_ source: String) -> MF2ParseResult {
        let parser = MF2SourceParser(source: source, baseOffset: 0)
        guard let object = parser.parseMessageModel(), parser.diagnostics.isEmpty else {
            return MF2ParseResult(model: nil, diagnostics: parser.diagnostics)
        }

        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            let model = try JSONDecoder().decode(MF2Message.self, from: data)
            return MF2ParseResult(model: model, diagnostics: parser.diagnostics)
        } catch {
            let diagnostic = MF2ParseDiagnostic(
                code: "invalid-model",
                message: "Parsed source could not be decoded as an MF2 model.",
                start: 0,
                end: source.count
            )
            return MF2ParseResult(model: nil, diagnostics: parser.diagnostics + [diagnostic])
        }
    }
}

public func parseToModel(_ source: String) -> MF2ParseResult {
    MF2Parser.parse(source)
}

private typealias JSONValue = Any
private typealias JSONObject = [String: JSONValue]

private let bidiMarkers: Set<UInt32> = [0x061C, 0x200E, 0x200F, 0x2066, 0x2067, 0x2068, 0x2069]

private final class MF2SourceParser {
    private let source: [Character]
    private let baseOffset: Int
    private var index = 0
    var diagnostics: [MF2ParseDiagnostic] = []

    init(source: String, baseOffset: Int) {
        self.source = Array(source)
        self.baseOffset = baseOffset
    }

    func parseMessageModel() -> JSONObject? {
        let messageStart = index
        let declarations = parseDeclarations()
        skipSyntaxWhitespace()
        if startsWith(".match") {
            return parseMatch(declarations: declarations)
        }
        if startsWith("{{") {
            guard let pattern = parseQuotedPattern() else {
                return nil
            }
            skipSyntaxWhitespace()
            if !isDone {
                pushDiagnostic(
                    code: "trailing-content",
                    message: "Unexpected content after complex message body.",
                    start: index,
                    end: source.count
                )
            }
            return ["type": "message", "declarations": declarations, "pattern": pattern]
        }
        if !declarations.isEmpty {
            pushDiagnostic(
                code: "missing-complex-body",
                message: "Complex message declarations must be followed by a quoted pattern or matcher.",
                start: index,
                end: source.count
            )
            return nil
        }
        if startsWith(".") {
            pushDiagnostic(
                code: "invalid-simple-start",
                message: "Simple messages cannot start with '.'.",
                start: index,
                end: index + 1
            )
            return nil
        }
        index = messageStart
        return [
            "type": "message",
            "declarations": declarations,
            "pattern": parsePatternUntilEnd(),
        ]
    }

    private func parseDeclarations() -> [JSONObject] {
        var declarations: [JSONObject] = []
        while true {
            let beforePadding = index
            skipSyntaxWhitespace()
            if startsWith(".input") {
                if let declaration = parseInputDeclaration() {
                    declarations.append(declaration)
                }
                continue
            }
            if startsWith(".local") {
                if let declaration = parseLocalDeclaration() {
                    declarations.append(declaration)
                }
                continue
            }
            index = beforePadding
            return declarations
        }
    }

    private func parseInputDeclaration() -> JSONObject? {
        consumeString(".input")
        skipSyntaxWhitespace()
        let start = index
        guard let value = parseExpressionPlaceholder() else {
            return nil
        }
        if let arg = value["arg"] as? JSONObject,
           arg["type"] as? String == "variable",
           let name = arg["name"] as? String
        {
            return ["type": "input", "name": name, "value": value]
        }
        pushDiagnostic(
            code: "invalid-input-declaration",
            message: ".input declarations must reference a variable expression.",
            start: start,
            end: index
        )
        return nil
    }

    private func parseLocalDeclaration() -> JSONObject? {
        consumeString(".local")
        skipSyntaxWhitespace()
        let start = index
        guard let name = parseVariableName() else {
            return nil
        }
        skipSyntaxWhitespace()
        guard peek() == "=" else {
            pushDiagnostic(
                code: "missing-local-equals",
                message: ".local declarations must include '='.",
                start: start,
                end: index
            )
            return nil
        }
        index += 1
        skipSyntaxWhitespace()
        guard let value = parseExpressionPlaceholder() else {
            return nil
        }
        return ["type": "local", "name": name, "value": value]
    }

    private func parseMatch(declarations: [JSONObject]) -> JSONObject? {
        consumeString(".match")
        var selectors: [JSONObject] = []
        if !isDone, peek() == "$" {
            pushDiagnostic(
                code: "missing-match-space",
                message: ".match selectors must be separated by whitespace.",
                start: index,
                end: index
            )
            return nil
        }
        while true {
            let skippedSpace = skipSyntaxGap()
            if !isDone, peek() == "$" {
                if !skippedSpace, !selectors.isEmpty {
                    pushDiagnostic(
                        code: "missing-match-space",
                        message: ".match selectors must be separated by whitespace.",
                        start: index,
                        end: index
                    )
                    return nil
                }
                if let name = parseVariableName() {
                    selectors.append(["type": "variable", "name": name])
                }
                if !isDone, let ch = peek(), !isWhitespace(ch) {
                    pushDiagnostic(
                        code: "missing-match-space",
                        message: ".match selectors must be separated from variants by whitespace.",
                        start: index,
                        end: index
                    )
                    return nil
                }
                continue
            }
            if !isDone, peek() == "{" {
                let start = index
                if consumeBracedContent() != nil {
                    pushDiagnostic(
                        code: "unsupported-match-selector-expression",
                        message: ".match selectors must be declared variables such as .input {$name :string} followed by .match $name; inline selector expressions are not supported.",
                        start: start,
                        end: index
                    )
                }
                return nil
            }
            break
        }
        if selectors.isEmpty {
            pushDiagnostic(
                code: "missing-match-selector",
                message: ".match must include at least one selector variable.",
                start: index,
                end: index
            )
            return nil
        }

        var variants: [JSONObject] = []
        while true {
            skipSyntaxWhitespace()
            if isDone {
                break
            }
            let variantStart = index
            guard let keys = parseVariantKeys(start: variantStart) else {
                return nil
            }
            skipSyntaxWhitespace()
            guard startsWith("{{") else {
                pushDiagnostic(
                    code: "missing-variant-pattern",
                    message: "Variant keys must be followed by a quoted pattern.",
                    start: variantStart,
                    end: index
                )
                return nil
            }
            guard let value = parseQuotedPattern() else {
                return nil
            }
            if keys.count != selectors.count {
                pushDiagnostic(
                    code: "variant-key-count-mismatch",
                    message: "Variant key count must match selector count.",
                    start: variantStart,
                    end: index
                )
                return nil
            }
            variants.append(["keys": keys, "value": value])
        }
        if variants.isEmpty {
            pushDiagnostic(
                code: "missing-match-variants",
                message: ".match must include at least one variant.",
                start: index,
                end: index
            )
            return nil
        }
        return [
            "type": "select",
            "declarations": declarations,
            "selectors": selectors,
            "variants": variants,
        ]
    }

    private func parseVariantKeys(start: Int) -> [JSONObject]? {
        var keys: [JSONObject] = []
        while !isDone, !startsWith("{{"), peek() != "\n" {
            let skippedSpace = skipSyntaxGap()
            if startsWith("{{") || peek() == "\n" || isDone {
                break
            }
            if !keys.isEmpty, !skippedSpace {
                pushDiagnostic(
                    code: "missing-variant-key-space",
                    message: "Variant keys must be separated by whitespace.",
                    start: start,
                    end: index
                )
                return nil
            }
            if peek() == "*" {
                index += 1
                keys.append(["type": "*"])
                continue
            }
            if peek() == "|" {
                guard let split = parseQuotedLiteral(text(from: index, to: source.count)) else {
                    pushDiagnostic(
                        code: "unclosed-quoted-literal",
                        message: "Quoted variant key is missing closing '|'.",
                        start: index,
                        end: source.count
                    )
                    return nil
                }
                index += split.consumed
                keys.append(["type": "literal", "value": split.value])
                continue
            }
            let key = takeWhile { ch in
                !isSyntaxWhitespace(ch) && ch != "{"
            }
            if !key.isEmpty {
                keys.append(["type": "literal", "value": key])
            }
        }
        return keys
    }

    private func parseQuotedPattern() -> [JSONValue]? {
        let start = index
        guard consumeString("{{") else {
            pushDiagnostic(
                code: "missing-quoted-pattern",
                message: "Expected a quoted pattern starting with '{{'.",
                start: start,
                end: start
            )
            return nil
        }
        let contentStart = index
        var scan = index
        var placeholderDepth = 0
        var inQuote = false
        while scan < source.count {
            if placeholderDepth == 0, startsWith("}}", at: scan) {
                let content = text(from: contentStart, to: scan)
                index = scan + 2
                let nested = MF2SourceParser(source: content, baseOffset: baseOffset + contentStart)
                let pattern = nested.parsePatternUntilEnd()
                diagnostics.append(contentsOf: nested.diagnostics)
                return pattern
            }
            let ch = source[scan]
            if ch == "\\" {
                scan += 2
                continue
            }
            if placeholderDepth > 0, ch == "|" {
                inQuote.toggle()
            } else if !inQuote, ch == "{" {
                placeholderDepth += 1
            } else if !inQuote, ch == "}", placeholderDepth > 0 {
                placeholderDepth -= 1
            }
            scan += 1
        }
        pushDiagnostic(
            code: "unclosed-quoted-pattern",
            message: "Quoted pattern is missing closing '}}'.",
            start: start,
            end: source.count
        )
        return nil
    }

    fileprivate func parsePatternUntilEnd() -> [JSONValue] {
        var parts: [JSONValue] = []
        var text = ""
        while !isDone {
            guard let ch = peek() else {
                break
            }
            if ch == "\\" {
                text += parseEscape()
            } else if ch == "{" {
                if !text.isEmpty {
                    parts.append(text)
                    text = ""
                }
                if let part = parseBracedPatternPart() {
                    parts.append(part)
                }
            } else if ch == "}" {
                let start = index
                index += 1
                pushDiagnostic(
                    code: "unescaped-closing-brace",
                    message: "Closing brace must be escaped in text.",
                    start: start,
                    end: index
                )
            } else {
                text.append(ch)
                index += 1
            }
        }
        if !text.isEmpty {
            parts.append(text)
        }
        return parts
    }

    private func parseEscape() -> String {
        let start = index
        index += 1
        if isDone {
            pushDiagnostic(
                code: "dangling-escape",
                message: "Backslash at end of message has no escaped character.",
                start: start,
                end: start + 1
            )
            return ""
        }
        guard let ch = peek() else {
            return "\\"
        }
        if ch == "{" || ch == "}" || ch == "\\" || ch == "|" {
            index += 1
            return String(ch)
        }
        return "\\"
    }

    private func parseBracedPatternPart() -> JSONObject? {
        let start = index
        guard let content = consumeBracedContent() else {
            return nil
        }
        let trimmed = stripSyntaxWhitespace(content)
        if trimmed.hasPrefix("#") || trimmed.hasPrefix("/") {
            return parseMarkupContent(content: trimmed, start: start, end: start + content.count + 2)
        }
        return parseExpressionContent(content: trimmed, start: start, end: start + content.count + 2)
    }

    private func parseExpressionPlaceholder() -> JSONObject? {
        let start = index
        guard let content = consumeBracedContent() else {
            return nil
        }
        return parseExpressionContent(
            content: stripSyntaxWhitespace(content),
            start: start,
            end: start + content.count + 2
        )
    }

    private func consumeBracedContent() -> String? {
        let start = index
        guard peek() == "{" else {
            pushDiagnostic(
                code: "missing-placeholder",
                message: "Expected a placeholder starting with '{'.",
                start: start,
                end: start
            )
            return nil
        }
        index += 1
        let contentStart = index
        var inQuote = false
        while !isDone {
            guard let ch = peek() else {
                break
            }
            if inQuote {
                if ch == "\\" {
                    index += 1
                    if !isDone {
                        index += 1
                    }
                    continue
                }
                if ch == "}" {
                    let content = text(from: contentStart, to: index)
                    index += 1
                    return content
                }
                if ch == "|" {
                    inQuote = false
                }
                index += 1
                continue
            }
            if ch == "|" {
                inQuote = true
                index += 1
                continue
            }
            if ch == "}" {
                let content = text(from: contentStart, to: index)
                index += 1
                return content
            }
            index += 1
        }
        pushDiagnostic(
            code: "unclosed-placeholder",
            message: "Placeholder is missing a closing brace.",
            start: start,
            end: source.count
        )
        return nil
    }

    private func parseExpressionContent(content: String, start: Int, end: Int) -> JSONObject? {
        let expression: JSONObject
        let rest: String
        if content.hasPrefix("$") {
            let split = splitName(String(content.dropFirst()))
            if split.name.isEmpty {
                pushDiagnostic(
                    code: variableNameDiagnosticCode(String(content.dropFirst())),
                    message: "Variable placeholder is missing a name.",
                    start: start,
                    end: end
                )
                return nil
            }
            expression = expressionModel(["type": "variable", "name": split.name])
            guard let nextRest = restAfterOperand(split.rest, start: start, end: end) else {
                return nil
            }
            rest = nextRest
        } else if content.hasPrefix("|") {
            guard let split = parseQuotedLiteral(content) else {
                pushDiagnostic(
                    code: "unclosed-quoted-literal",
                    message: "Quoted literal is missing closing '|'.",
                    start: start,
                    end: end
                )
                return nil
            }
            expression = expressionModel(["type": "literal", "value": split.value])
            guard let nextRest = restAfterOperand(split.rest, start: start, end: end) else {
                return nil
            }
            rest = nextRest
        } else if content.hasPrefix(":") {
            expression = expressionModel(nil)
            rest = content
        } else {
            guard let split = splitUnquotedLiteral(content) else {
                pushDiagnostic(
                    code: content.isEmpty ? "missing-expression" : "invalid-literal",
                    message: "Placeholder literal is invalid.",
                    start: start,
                    end: end
                )
                return nil
            }
            expression = expressionModel(["type": "literal", "value": split.value])
            guard let nextRest = restAfterOperand(split.rest, start: start, end: end) else {
                return nil
            }
            rest = nextRest
        }
        if rest.isEmpty {
            return expression
        }
        guard let tail = parseTail(rest: rest, start: start, end: end) else {
            return nil
        }
        return expressionModel(
            expression["arg"] as? JSONObject,
            functionRef: tail["function"] as? JSONObject,
            attributes: tail["attributes"] as? [String: JSONValue]
        )
    }

    private func restAfterOperand(_ rest: String, start: Int, end: Int) -> String? {
        if rest.isEmpty {
            return rest
        }
        guard let first = rest.first, isWhitespace(first) else {
            pushDiagnostic(
                code: "missing-expression-space",
                message: "Expression arguments must be separated from functions or attributes by whitespace.",
                start: start,
                end: end
            )
            return nil
        }
        return stripLeadingSyntaxWhitespace(rest)
    }

    private func parseTail(rest: String, start: Int, end: Int) -> JSONObject? {
        if rest.allSatisfy(isSyntaxWhitespace) {
            return ["function": NSNull(), "attributes": NSNull()]
        }
        guard let tokens = splitTailTokens(rest: rest, start: start, end: end) else {
            return nil
        }
        var index = 0
        var functionRef: JSONObject?
        var attributes: [String: JSONValue] = [:]
        if index < tokens.count, tokens[index].hasPrefix(":") {
            guard let result = parseFunctionAnnotation(tokens: tokens, index: index, start: start, end: end) else {
                return nil
            }
            functionRef = result.function
            index = result.nextIndex
        }
        while index < tokens.count {
            let token = tokens[index]
            if !token.hasPrefix("@") {
                pushDiagnostic(
                    code: "unsupported-expression",
                    message: "Expression content after the argument must be a function annotation or attribute.",
                    start: start,
                    end: end
                )
                return nil
            }
            guard let attribute = parseAttributeTokens(tokens: tokens, index: index, start: start, end: end) else {
                return nil
            }
            index = attribute.nextIndex
            if attributes[attribute.name] != nil {
                pushDiagnostic(
                    code: "duplicate-attribute-name",
                    message: "Attribute names must be unique within an expression or markup placeholder.",
                    start: start,
                    end: end
                )
                return nil
            }
            attributes[attribute.name] = attribute.value
        }
        var output: JSONObject = [:]
        if let functionRef {
            output["function"] = functionRef
        }
        if !attributes.isEmpty {
            output["attributes"] = sortKeys(attributes)
        }
        return output
    }

    private func splitTailTokens(rest: String, start: Int, end: Int) -> [String]? {
        let chars = Array(rest)
        var tokens: [String] = []
        var tokenStart = -1
        var inQuote = false
        var index = 0
        while index < chars.count {
            let ch = chars[index]
            if inQuote, ch == "\\" {
                if tokenStart < 0 {
                    tokenStart = index
                }
                index += 2
                continue
            }
            if ch == "|" {
                inQuote.toggle()
                if tokenStart < 0 {
                    tokenStart = index
                }
                index += 1
                continue
            }
            if isSyntaxWhitespace(ch), !inQuote {
                if tokenStart >= 0 {
                    tokens.append(String(chars[tokenStart..<index]))
                    tokenStart = -1
                }
                index += 1
                continue
            }
            if tokenStart < 0 {
                tokenStart = index
            }
            index += 1
        }
        if inQuote {
            pushDiagnostic(
                code: "unclosed-quoted-literal",
                message: "Quoted literal is missing closing '|'.",
                start: start,
                end: end
            )
            return nil
        }
        if tokenStart >= 0 {
            tokens.append(String(chars[tokenStart..<chars.count]))
        }
        return tokens
    }

    private func parseFunctionAnnotation(
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (function: JSONObject, nextIndex: Int)? {
        let content = String(tokens[index].dropFirst())
        let split = splitIdentifier(content)
        if split.name.isEmpty {
            pushDiagnostic(
                code: content.isEmpty ? "missing-function-name" : "invalid-function-name",
                message: "Function annotation is missing a name.",
                start: start,
                end: end
            )
            return nil
        }
        if !split.rest.isEmpty {
            pushDiagnostic(
                code: "unsupported-expression",
                message: "Function annotation must separate options with whitespace.",
                start: start,
                end: end
            )
            return nil
        }
        var options: [String: JSONValue] = [:]
        var nextIndex = index + 1
        while nextIndex < tokens.count, !tokens[nextIndex].hasPrefix("@") {
            guard let option = parseOptionTokens(tokens: tokens, index: nextIndex, start: start, end: end) else {
                return nil
            }
            nextIndex = option.nextIndex
            if options[option.name] != nil {
                pushDiagnostic(
                    code: "duplicate-option-name",
                    message: "Option names must be unique within a function or markup placeholder.",
                    start: start,
                    end: end
                )
                return nil
            }
            options[option.name] = option.value
        }
        return (functionModel(name: split.name, options: options.isEmpty ? nil : options), nextIndex)
    }

    private func parseOptionTokens(
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (name: String, value: JSONObject, nextIndex: Int)? {
        guard let assignment = parseRequiredAssignment(tokens: tokens, index: index, start: start, end: end) else {
            return nil
        }
        let keySplit = splitIdentifier(assignment.key)
        if keySplit.name.isEmpty || !keySplit.rest.isEmpty {
            pushDiagnostic(
                code: "invalid-function-option",
                message: "Option key must be a valid identifier.",
                start: start,
                end: end
            )
            return nil
        }
        guard let value = parseOptionValue(rawValue: assignment.rawValue, start: start, end: end) else {
            return nil
        }
        return (
            keySplit.name,
            value,
            assignment.nextIndex
        )
    }

    private func parseOptionValue(rawValue: String, start: Int, end: Int) -> JSONObject? {
        let rawValue = stripSyntaxWhitespace(rawValue)
        if rawValue.hasPrefix("|") {
            guard let split = parseQuotedLiteral(rawValue) else {
                pushDiagnostic(
                    code: "unclosed-quoted-literal",
                    message: "Quoted literal is missing closing '|'.",
                    start: start,
                    end: end
                )
                return nil
            }
            if !split.rest.isEmpty {
                pushDiagnostic(
                    code: "invalid-function-option",
                    message: "Option value must be a single literal or variable.",
                    start: start,
                    end: end
                )
                return nil
            }
            return ["type": "literal", "value": split.value]
        }
        if rawValue.hasPrefix("$") {
            let rawName = String(rawValue.dropFirst())
            let split = splitName(rawName)
            if !split.name.isEmpty, split.rest.isEmpty {
                return ["type": "variable", "name": split.name]
            }
            pushDiagnostic(
                code: variableNameDiagnosticCode(rawName),
                message: "Option variable value must be a valid variable name.",
                start: start,
                end: end
            )
            return nil
        }
        return parseLiteralOrVariable(rawValue)
    }

    private func parseRequiredAssignment(
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (key: String, rawValue: String, nextIndex: Int)? {
        let token = tokens[index]
        if let equals = token.firstIndex(of: "=") {
            let key = String(token[..<equals])
            let value = String(token[token.index(after: equals)...])
            return finishAssignment(key: key, rawValue: value, tokens: tokens, nextIndex: index + 1, start: start, end: end)
        }
        if index + 1 >= tokens.count || !tokens[index + 1].hasPrefix("=") {
            pushDiagnostic(
                code: "invalid-function-option",
                message: "Options must use key=value syntax.",
                start: start,
                end: end
            )
            return nil
        }
        let value = String(tokens[index + 1].dropFirst())
        return finishAssignment(
            key: token,
            rawValue: value,
            tokens: tokens,
            nextIndex: index + 2,
            start: start,
            end: end
        )
    }

    private func finishAssignment(
        key: String,
        rawValue: String,
        tokens: [String],
        nextIndex: Int,
        start: Int,
        end: Int
    ) -> (key: String, rawValue: String, nextIndex: Int)? {
        if key.isEmpty {
            pushDiagnostic(
                code: "invalid-function-option",
                message: "Option key and value must be non-empty.",
                start: start,
                end: end
            )
            return nil
        }
        if !rawValue.isEmpty {
            return (key, rawValue, nextIndex)
        }
        if nextIndex >= tokens.count {
            pushDiagnostic(
                code: "invalid-function-option",
                message: "Option key and value must be non-empty.",
                start: start,
                end: end
            )
            return nil
        }
        return (key, tokens[nextIndex], nextIndex + 1)
    }

    private func parseAttributeTokens(
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (name: String, value: JSONValue, nextIndex: Int)? {
        let token = tokens[index]
        let content = String(token.dropFirst())
        if content.isEmpty {
            pushDiagnostic(
                code: "missing-attribute-name",
                message: "Attribute is missing a name.",
                start: start,
                end: end
            )
            return nil
        }
        if !hasAttributeAssignment(content: content, tokens: tokens, index: index) {
            let split = splitIdentifier(content)
            if split.name.isEmpty || !split.rest.isEmpty {
                pushDiagnostic(
                    code: "invalid-attribute",
                    message: "Attribute name must be a valid identifier.",
                    start: start,
                    end: end
                )
                return nil
            }
            return (split.name, true, index + 1)
        }
        guard let assignment = parseAttributeAssignment(content: content, tokens: tokens, index: index, start: start, end: end),
              let value = parseAttributeValue(rawValue: assignment.rawValue, start: start, end: end)
        else {
            return nil
        }
        return (assignment.name, value, assignment.nextIndex)
    }

    private func hasAttributeAssignment(content: String, tokens: [String], index: Int) -> Bool {
        content.contains("=") || (index + 1 < tokens.count && tokens[index + 1].hasPrefix("="))
    }

    private func parseAttributeAssignment(
        content: String,
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (name: String, rawValue: String, nextIndex: Int)? {
        guard let assignment = attributeAssignmentParts(content: content, tokens: tokens, index: index, start: start, end: end) else {
            return nil
        }
        let split = splitIdentifier(assignment.key)
        if split.name.isEmpty || !split.rest.isEmpty {
            pushDiagnostic(
                code: "invalid-attribute",
                message: "Attribute name must be a valid identifier.",
                start: start,
                end: end
            )
            return nil
        }
        return (split.name, assignment.rawValue, assignment.nextIndex)
    }

    private func attributeAssignmentParts(
        content: String,
        tokens: [String],
        index: Int,
        start: Int,
        end: Int
    ) -> (key: String, rawValue: String, nextIndex: Int)? {
        if let equals = content.firstIndex(of: "=") {
            let key = String(content[..<equals])
            let value = String(content[content.index(after: equals)...])
            return finishAttributeAssignment(
                key: key,
                rawValue: value,
                tokens: tokens,
                nextIndex: index + 1,
                start: start,
                end: end
            )
        }
        if index + 1 >= tokens.count || !tokens[index + 1].hasPrefix("=") {
            return nil
        }
        return finishAttributeAssignment(
            key: content,
            rawValue: String(tokens[index + 1].dropFirst()),
            tokens: tokens,
            nextIndex: index + 2,
            start: start,
            end: end
        )
    }

    private func finishAttributeAssignment(
        key: String,
        rawValue: String,
        tokens: [String],
        nextIndex: Int,
        start: Int,
        end: Int
    ) -> (key: String, rawValue: String, nextIndex: Int)? {
        if key.isEmpty {
            pushDiagnostic(
                code: "invalid-attribute",
                message: "Attribute key and value must be non-empty.",
                start: start,
                end: end
            )
            return nil
        }
        if !rawValue.isEmpty {
            return (key, rawValue, nextIndex)
        }
        if nextIndex >= tokens.count {
            pushDiagnostic(
                code: "invalid-attribute",
                message: "Attribute key and value must be non-empty.",
                start: start,
                end: end
            )
            return nil
        }
        return (key, tokens[nextIndex], nextIndex + 1)
    }

    private func parseAttributeValue(rawValue: String, start: Int, end: Int) -> JSONObject? {
        let rawValue = stripSyntaxWhitespace(rawValue)
        if rawValue.hasPrefix("|"), rawValue.hasSuffix("|"), rawValue.count >= 2 {
            guard let split = parseQuotedLiteral(rawValue) else {
                pushDiagnostic(
                    code: "unclosed-quoted-literal",
                    message: "Quoted literal is missing closing '|'.",
                    start: start,
                    end: end
                )
                return nil
            }
            if !split.rest.isEmpty {
                pushDiagnostic(
                    code: "invalid-attribute",
                    message: "Attribute value must be a single literal.",
                    start: start,
                    end: end
                )
                return nil
            }
            return ["type": "literal", "value": split.value]
        }
        guard let split = splitUnquotedLiteral(rawValue), split.rest.isEmpty else {
            pushDiagnostic(
                code: "invalid-attribute",
                message: "Attribute value must be a single literal.",
                start: start,
                end: end
            )
            return nil
        }
        return ["type": "literal", "value": split.value]
    }

    private func parseMarkupContent(content: String, start: Int, end: Int) -> JSONObject? {
        let kind: String
        let rest: String
        if content.hasPrefix("#") {
            let trimmed = stripTrailingSyntaxWhitespace(String(content.dropFirst()))
            if trimmed.hasSuffix("/") {
                kind = "standalone"
                rest = stripTrailingSyntaxWhitespace(String(trimmed.dropLast()))
            } else {
                kind = "open"
                rest = trimmed
            }
        } else {
            kind = "close"
            rest = stripSyntaxWhitespace(String(content.dropFirst()))
        }
        let split = splitIdentifier(stripLeadingSyntaxWhitespace(rest))
        if split.name.isEmpty {
            pushDiagnostic(
                code: "missing-markup-name",
                message: "Markup placeholder is missing a name.",
                start: start,
                end: end
            )
            return nil
        }
        if stripSyntaxWhitespace(split.rest).isEmpty {
            return markupModel(kind: kind, name: split.name)
        }
        guard let tail = parseMarkupTail(rest: split.rest, start: start, end: end) else {
            return nil
        }
        return markupModel(
            kind: kind,
            name: split.name,
            options: tail["options"] as? [String: JSONValue],
            attributes: tail["attributes"] as? [String: JSONValue]
        )
    }

    private func parseMarkupTail(rest: String, start: Int, end: Int) -> JSONObject? {
        guard let tokens = splitTailTokens(rest: rest, start: start, end: end) else {
            return nil
        }
        var options: [String: JSONValue] = [:]
        var attributes: [String: JSONValue] = [:]
        var seenAttribute = false
        var index = 0
        while index < tokens.count {
            let token = tokens[index]
            if token.hasPrefix("@") {
                seenAttribute = true
                guard let attribute = parseAttributeTokens(tokens: tokens, index: index, start: start, end: end) else {
                    return nil
                }
                index = attribute.nextIndex
                if attributes[attribute.name] != nil {
                    pushDiagnostic(
                        code: "duplicate-attribute-name",
                        message: "Attribute names must be unique within an expression or markup placeholder.",
                        start: start,
                        end: end
                    )
                    return nil
                }
                attributes[attribute.name] = attribute.value
                continue
            }
            if seenAttribute {
                pushDiagnostic(
                    code: "unsupported-markup",
                    message: "Markup options must come before attributes.",
                    start: start,
                    end: end
                )
                return nil
            }
            if token.hasPrefix(":") {
                pushDiagnostic(
                    code: "unsupported-markup",
                    message: "Markup placeholders do not support function annotations.",
                    start: start,
                    end: end
                )
                return nil
            }
            guard let option = parseOptionTokens(tokens: tokens, index: index, start: start, end: end) else {
                return nil
            }
            index = option.nextIndex
            if options[option.name] != nil {
                pushDiagnostic(
                    code: "duplicate-option-name",
                    message: "Option names must be unique within a function or markup placeholder.",
                    start: start,
                    end: end
                )
                return nil
            }
            options[option.name] = option.value
        }
        var output: JSONObject = [:]
        if !options.isEmpty {
            output["options"] = sortKeys(options)
        }
        if !attributes.isEmpty {
            output["attributes"] = sortKeys(attributes)
        }
        return output
    }

    private func parseVariableName() -> String? {
        let start = index
        guard peek() == "$" else {
            pushDiagnostic(
                code: "missing-variable",
                message: "Expected a variable starting with '$'.",
                start: start,
                end: start
            )
            return nil
        }
        index += 1
        let scan = scanName(text(from: 0, to: source.count), offset: index)
        if scan.name.isEmpty {
            pushDiagnostic(
                code: variableNameDiagnosticCode(text(from: 0, to: source.count), offset: index),
                message: "Variable is missing a name.",
                start: start,
                end: index
            )
            return nil
        }
        index = scan.endIndex
        return scan.name
    }

    @discardableResult
    private func skipSyntaxWhitespace() -> Bool {
        let start = index
        while !isDone, let ch = peek(), isSyntaxWhitespace(ch) {
            index += 1
        }
        return index != start
    }

    @discardableResult
    private func skipSyntaxGap() -> Bool {
        var sawWhitespace = false
        while !isDone, let ch = peek() {
            if isWhitespace(ch) {
                sawWhitespace = true
                index += 1
                continue
            }
            if isBidiMarker(ch) {
                index += 1
                continue
            }
            break
        }
        return sawWhitespace
    }

    private func takeWhile(_ predicate: (Character) -> Bool) -> String {
        let start = index
        while !isDone, let ch = peek(), predicate(ch) {
            index += 1
        }
        return text(from: start, to: index)
    }

    private func startsWith(_ expected: String) -> Bool {
        startsWith(expected, at: index)
    }

    private func startsWith(_ expected: String, at offset: Int) -> Bool {
        let expectedCharacters = Array(expected)
        guard offset + expectedCharacters.count <= source.count else {
            return false
        }
        for i in expectedCharacters.indices where source[offset + i] != expectedCharacters[i] {
            return false
        }
        return true
    }

    @discardableResult
    private func consumeString(_ expected: String) -> Bool {
        guard startsWith(expected) else {
            return false
        }
        index += expected.count
        return true
    }

    private var isDone: Bool {
        index >= source.count
    }

    private func peek() -> Character? {
        isDone ? nil : source[index]
    }

    private func text(from start: Int, to end: Int) -> String {
        guard start < end else {
            return ""
        }
        return String(source[start..<end])
    }

    private func pushDiagnostic(code: String, message: String, start: Int, end: Int) {
        diagnostics.append(
            MF2ParseDiagnostic(
                code: code,
                message: message,
                start: baseOffset + start,
                end: baseOffset + end
            )
        )
    }
}

private func expressionModel(
    _ arg: JSONObject?,
    functionRef: JSONObject? = nil,
    attributes: [String: JSONValue]? = nil
) -> JSONObject {
    var output: JSONObject = ["type": "expression"]
    if let arg {
        output["arg"] = arg
    }
    if let functionRef {
        output["function"] = functionRef
    }
    if let attributes, !attributes.isEmpty {
        output["attributes"] = sortKeys(attributes)
    }
    return output
}

private func functionModel(name: String, options: [String: JSONValue]?) -> JSONObject {
    var output: JSONObject = ["type": "function", "name": name]
    if let options, !options.isEmpty {
        output["options"] = sortKeys(options)
    }
    return output
}

private func markupModel(
    kind: String,
    name: String,
    options: [String: JSONValue]? = nil,
    attributes: [String: JSONValue]? = nil
) -> JSONObject {
    var output: JSONObject = ["type": "markup", "kind": kind, "name": name]
    if let options, !options.isEmpty {
        output["options"] = sortKeys(options)
    }
    if let attributes, !attributes.isEmpty {
        output["attributes"] = sortKeys(attributes)
    }
    return output
}

private func parseLiteralOrVariable(_ rawValue: String) -> JSONObject {
    if rawValue.hasPrefix("$") {
        let split = splitName(String(rawValue.dropFirst()))
        if !split.name.isEmpty, split.rest.isEmpty {
            return ["type": "variable", "name": split.name]
        }
        return ["type": "variable", "name": String(rawValue.dropFirst())]
    }
    if let quoted = parseQuotedLiteral(rawValue), quoted.rest.isEmpty {
        return ["type": "literal", "value": quoted.value]
    }
    return ["type": "literal", "value": rawValue]
}

private struct Split {
    var value = ""
    var name = ""
    var rest = ""
    var consumed = 0
    var endIndex = 0
}

private func parseQuotedLiteral(_ input: String) -> Split? {
    let chars = Array(input)
    guard chars.first == "|" else {
        return nil
    }
    var output = ""
    var index = 1
    while index < chars.count {
        let ch = chars[index]
        index += 1
        if ch == "|" {
            return Split(
                value: output,
                rest: String(chars[index..<chars.count]),
                consumed: index
            )
        }
        if ch == "\\" {
            if index >= chars.count {
                output += "\\"
                break
            }
            let escaped = chars[index]
            if escaped == "\\" || escaped == "{" || escaped == "|" || escaped == "}" {
                output.append(escaped)
                index += 1
            } else {
                return nil
            }
        } else {
            output.append(ch)
        }
    }
    return nil
}

private func splitUnquotedLiteral(_ input: String) -> Split? {
    let chars = Array(input)
    var scan = 0
    var sawCharacter = false
    while scan < chars.count {
        let ch = chars[scan]
        if isSyntaxWhitespace(ch) || ch == ":" || ch == "@" {
            break
        }
        guard isUnquotedLiteralChar(ch) else {
            return nil
        }
        sawCharacter = true
        scan += 1
    }
    guard sawCharacter else {
        return nil
    }
    return Split(
        value: String(chars[0..<scan]),
        rest: String(chars[scan..<chars.count]),
        consumed: scan
    )
}

private func isUnquotedLiteralChar(_ ch: Character) -> Bool {
    let codePoint = firstScalarValue(ch) ?? 0
    if isControl(ch) || isSyntaxWhitespace(ch) || isNoncharacter(codePoint) {
        return false
    }
    return ch != "^" && ch != "!" && ch != "%" && ch != "*" && ch != "<" && ch != ">" &&
        ch != "?" && ch != "~" && ch != "&" && ch != "\\" && ch != "$"
}

private func variableNameDiagnosticCode(_ input: String, offset: Int = 0) -> String {
    let chars = Array(input)
    if offset >= chars.count {
        return "missing-variable-name"
    }
    return chars[offset] == "}" || chars[offset] == " " || chars[offset] == "\t" ||
        chars[offset] == "\n" || chars[offset] == "\r" ? "missing-variable-name" : "invalid-variable-name"
}

private func splitName(_ input: String) -> Split {
    let scan = scanName(input, offset: 0)
    let chars = Array(input)
    return Split(
        name: scan.name,
        rest: String(chars[scan.endIndex..<chars.count]),
        consumed: scan.endIndex
    )
}

private func scanName(_ input: String, offset: Int) -> Split {
    let chars = Array(input)
    var scan = offset
    if scan < chars.count, isBidiMarker(chars[scan]) {
        scan += 1
    }
    let nameStart = scan
    if nameStart >= chars.count {
        return Split(name: "", endIndex: offset)
    }
    let first = chars[nameStart]
    if let codePoint = firstScalarValue(first), codePoint <= 0x7F,
       let asciiScan = scanAsciiName(input, offset: offset, nameStart: nameStart)
    {
        return asciiScan
    }
    if !isNameStart(first) {
        return Split(name: "", endIndex: offset)
    }
    scan += 1
    while scan < chars.count, isNameChar(chars[scan]) {
        scan += 1
    }
    let nameEnd = scan
    if scan < chars.count, isBidiMarker(chars[scan]) {
        scan += 1
    }
    return Split(name: String(chars[nameStart..<nameEnd]), endIndex: scan)
}

private func scanAsciiName(_ input: String, offset: Int, nameStart: Int) -> Split? {
    let chars = Array(input)
    let first = chars[nameStart]
    if !isAsciiNameStart(first) {
        return Split(name: "", endIndex: offset)
    }
    var scan = nameStart + 1
    while scan < chars.count, isAsciiNameChar(chars[scan]) {
        scan += 1
    }
    let nameEnd = scan
    if scan < chars.count,
       let codePoint = firstScalarValue(chars[scan]),
       codePoint > 0x7F
    {
        if !isBidiMarker(chars[scan]) {
            return nil
        }
        return Split(name: String(chars[nameStart..<nameEnd]), endIndex: scan + 1)
    }
    return Split(name: String(chars[nameStart..<nameEnd]), endIndex: scan)
}

private func splitIdentifier(_ input: String) -> Split {
    let namespaceOrName = splitName(input)
    if namespaceOrName.name.isEmpty {
        return Split(name: "", rest: input, consumed: 0)
    }
    guard namespaceOrName.rest.hasPrefix(":") else {
        return namespaceOrName
    }
    let name = splitName(String(namespaceOrName.rest.dropFirst()))
    if name.name.isEmpty {
        return namespaceOrName
    }
    return Split(
        name: "\(namespaceOrName.name):\(name.name)",
        rest: name.rest,
        consumed: namespaceOrName.consumed + 1 + name.consumed
    )
}

private func isNameStart(_ ch: Character) -> Bool {
    let scalars = Array(ch.unicodeScalars)
    guard let first = scalars.first else {
        return false
    }
    let codePoint = first.value
    if codePoint <= 0x7F {
        return isAsciiNameStartScalar(codePoint) &&
            scalars.dropFirst().allSatisfy { CharacterSet.nonBaseCharacters.contains($0) }
    }
    return scalars.allSatisfy { scalar in
        let value = scalar.value
        return value >= 0xA1 && value <= 0x10_FFFD &&
            !bidiMarkers.contains(value) &&
            !isControlScalar(value) &&
            !(0xD800...0xDFFF).contains(value) &&
            !CharacterSet.whitespacesAndNewlines.contains(scalar) &&
            !isNoncharacter(value)
    }
}

private func isNameChar(_ ch: Character) -> Bool {
    isNameStart(ch) || isAsciiDigit(ch) || isMark(ch) || ch == "-" || ch == "."
}

private func isMark(_ ch: Character) -> Bool {
    ch.unicodeScalars.contains { CharacterSet.nonBaseCharacters.contains($0) }
}

private func isAsciiNameStart(_ ch: Character) -> Bool {
    let scalars = Array(ch.unicodeScalars)
    guard let first = scalars.first else {
        return false
    }
    return isAsciiNameStartScalar(first.value) &&
        scalars.dropFirst().allSatisfy { CharacterSet.nonBaseCharacters.contains($0) }
}

private func isAsciiNameChar(_ ch: Character) -> Bool {
    isAsciiNameStart(ch) || isAsciiDigit(ch) || ch == "-" || ch == "."
}

private func isAsciiDigit(_ ch: Character) -> Bool {
    let scalars = Array(ch.unicodeScalars)
    guard scalars.count == 1, let first = scalars.first else {
        return false
    }
    return (scalar("0")...scalar("9")).contains(first.value)
}

private func isAsciiNameStartScalar(_ codePoint: UInt32) -> Bool {
    (scalar("a")...scalar("z")).contains(codePoint) ||
        (scalar("A")...scalar("Z")).contains(codePoint) ||
        codePoint == scalar("+") ||
        codePoint == scalar("_")
}

private func isBidiMarker(_ ch: Character) -> Bool {
    guard let codePoint = firstScalarValue(ch) else {
        return false
    }
    return bidiMarkers.contains(codePoint)
}

private func isSyntaxWhitespace(_ ch: Character) -> Bool {
    isWhitespace(ch) || isBidiMarker(ch)
}

private func isWhitespace(_ ch: Character) -> Bool {
    ch == "\t" || ch == "\n" || ch == "\r" || ch == " " || ch == "\u{3000}"
}

private func isUnicodeWhitespace(_ ch: Character) -> Bool {
    ch.unicodeScalars.allSatisfy { CharacterSet.whitespacesAndNewlines.contains($0) }
}

private func isControl(_ ch: Character) -> Bool {
    guard let codePoint = firstScalarValue(ch) else {
        return false
    }
    return isControlScalar(codePoint)
}

private func isControlScalar(_ codePoint: UInt32) -> Bool {
    (0...0x1F).contains(codePoint) || (0x7F...0x9F).contains(codePoint)
}

private func isNoncharacter(_ codePoint: UInt32) -> Bool {
    (0xFDD0...0xFDEF).contains(codePoint) || (codePoint & 0xFFFE) == 0xFFFE
}

private func stripSyntaxWhitespace(_ value: String) -> String {
    stripTrailingSyntaxWhitespace(stripLeadingSyntaxWhitespace(value))
}

private func stripLeadingSyntaxWhitespace(_ value: String) -> String {
    let chars = Array(value)
    var start = 0
    while start < chars.count, isSyntaxWhitespace(chars[start]) {
        start += 1
    }
    return String(chars[start..<chars.count])
}

private func stripTrailingSyntaxWhitespace(_ value: String) -> String {
    let chars = Array(value)
    var end = chars.count
    while end > 0, isSyntaxWhitespace(chars[end - 1]) {
        end -= 1
    }
    return String(chars[0..<end])
}

private func sortKeys(_ value: [String: JSONValue]) -> JSONObject {
    Dictionary(uniqueKeysWithValues: value.keys.sorted().map { ($0, value[$0]!) })
}

private func firstScalarValue(_ ch: Character) -> UInt32? {
    ch.unicodeScalars.first?.value
}

private func scalar(_ ch: Character) -> UInt32 {
    ch.unicodeScalars.first!.value
}
