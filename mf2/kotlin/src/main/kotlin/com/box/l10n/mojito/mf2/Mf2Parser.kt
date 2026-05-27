package com.box.l10n.mojito.mf2

object Mf2Parser {
    @JvmStatic
    fun parseToModel(source: String?): Mf2ParseResult {
        val parser = Parser(source.orEmpty(), 0)
        val model = parser.parseMessageModel()
        return Mf2ParseResult(
            model = if (parser.diagnostics.isEmpty()) model else null,
            diagnostics = parser.diagnostics,
        )
    }
}

private class Parser(
    private val source: String,
    private val baseOffset: Int,
) {
    var index = 0
    val diagnostics = mutableListOf<Mf2ParseDiagnostic>()

    fun parseMessageModel(): Mf2Model? {
        val messageStart = index
        val declarations = parseDeclarations()
        skipSyntaxWhitespace()
        if (startsWith(".match")) {
            return parseMatch(declarations)
        }
        if (startsWith("{{")) {
            val pattern = parseQuotedPattern() ?: return null
            skipSyntaxWhitespace()
            if (!isDone()) {
                pushDiagnostic("trailing-content", "Unexpected content after complex message body.", index, source.length)
            }
            return linkedMapOf("type" to "message", "declarations" to declarations, "pattern" to pattern)
        }
        if (declarations.isNotEmpty()) {
            pushDiagnostic(
                "missing-complex-body",
                "Complex message declarations must be followed by a quoted pattern or matcher.",
                index,
                source.length,
            )
            return null
        }
        if (startsWith(".")) {
            pushDiagnostic("invalid-simple-start", "Simple messages cannot start with '.'.", index, index + 1)
            return null
        }
        index = messageStart
        return linkedMapOf("type" to "message", "declarations" to declarations, "pattern" to parsePatternUntilEnd())
    }

    private fun parseDeclarations(): List<Map<String, Any?>> {
        val declarations = mutableListOf<Map<String, Any?>>()
        while (true) {
            val beforePadding = index
            skipSyntaxWhitespace()
            if (startsWith(".input")) {
                parseInputDeclaration()?.let { declarations += it }
                continue
            }
            if (startsWith(".local")) {
                parseLocalDeclaration()?.let { declarations += it }
                continue
            }
            index = beforePadding
            return declarations
        }
    }

    private fun parseInputDeclaration(): Map<String, Any?>? {
        consumeString(".input")
        skipSyntaxWhitespace()
        val start = index
        val value = parseExpressionPlaceholder() ?: return null
        val arg = asMap(value["arg"])
        if (arg["type"] == "variable") {
            return linkedMapOf("type" to "input", "name" to arg["name"], "value" to value)
        }
        pushDiagnostic("invalid-input-declaration", ".input declarations must reference a variable expression.", start, index)
        return null
    }

    private fun parseLocalDeclaration(): Map<String, Any?>? {
        consumeString(".local")
        skipSyntaxWhitespace()
        val start = index
        val name = parseVariableName() ?: return null
        skipSyntaxWhitespace()
        if (peekCodePoint() != code("=")) {
            pushDiagnostic("missing-local-equals", ".local declarations must include '='.", start, index)
            return null
        }
        advanceCodePoint()
        skipSyntaxWhitespace()
        val value = parseExpressionPlaceholder() ?: return null
        return linkedMapOf("type" to "local", "name" to name, "value" to value)
    }

    private fun parseMatch(declarations: List<Map<String, Any?>>): Mf2Model? {
        consumeString(".match")
        val selectors = mutableListOf<Map<String, Any?>>()
        if (!isDone() && peekCodePoint() == code("$")) {
            pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", index, index)
            return null
        }
        while (true) {
            val skippedSpace = skipSyntaxGap()
            if (!isDone() && peekCodePoint() == code("$")) {
                if (!skippedSpace && selectors.isNotEmpty()) {
                    pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", index, index)
                    return null
                }
                parseVariableName()?.let { selectors += linkedMapOf("type" to "variable", "name" to it) }
                if (!isDone() && !isWhitespace(peekCodePoint())) {
                    pushDiagnostic(
                        "missing-match-space",
                        ".match selectors must be separated from variants by whitespace.",
                        index,
                        index,
                    )
                    return null
                }
                continue
            }
            if (!isDone() && peekCodePoint() == code("{")) {
                val start = index
                if (consumeBracedContent() != null) {
                    pushDiagnostic(
                        "unsupported-match-selector-expression",
                        ".match selectors must be declared variables such as .input {\$name :string} followed by .match \$name; inline selector expressions are not supported.",
                        start,
                        index,
                    )
                }
                return null
            }
            break
        }
        if (selectors.isEmpty()) {
            pushDiagnostic("missing-match-selector", ".match must include at least one selector variable.", index, index)
            return null
        }
        val variants = mutableListOf<Map<String, Any?>>()
        while (true) {
            skipSyntaxWhitespace()
            if (isDone()) break
            val variantStart = index
            val keys = parseVariantKeys(variantStart) ?: return null
            skipSyntaxWhitespace()
            if (!startsWith("{{")) {
                pushDiagnostic("missing-variant-pattern", "Variant keys must be followed by a quoted pattern.", variantStart, index)
                return null
            }
            val value = parseQuotedPattern() ?: return null
            if (keys.size != selectors.size) {
                pushDiagnostic("variant-key-count-mismatch", "Variant key count must match selector count.", variantStart, index)
                return null
            }
            variants += linkedMapOf("keys" to keys, "value" to value)
        }
        if (variants.isEmpty()) {
            pushDiagnostic("missing-match-variants", ".match must include at least one variant.", index, index)
            return null
        }
        return linkedMapOf(
            "type" to "select",
            "declarations" to declarations,
            "selectors" to selectors,
            "variants" to variants,
        )
    }

    private fun parseVariantKeys(start: Int): List<Map<String, Any?>>? {
        val keys = mutableListOf<Map<String, Any?>>()
        while (!isDone() && !startsWith("{{") && peekCodePoint() != code("\n")) {
            val skippedSpace = skipSyntaxGap()
            if (startsWith("{{") || isDone() || peekCodePoint() == code("\n")) break
            if (keys.isNotEmpty() && !skippedSpace) {
                pushDiagnostic("missing-variant-key-space", "Variant keys must be separated by whitespace.", start, index)
                return null
            }
            if (peekCodePoint() == code("*")) {
                advanceCodePoint()
                keys += linkedMapOf("type" to "*")
                continue
            }
            if (peekCodePoint() == code("|")) {
                val rest = source.substring(index)
                val split = parseQuotedLiteral(rest)
                if (split == null) {
                    pushDiagnostic("unclosed-quoted-literal", "Quoted variant key is missing closing '|'.", index, source.length)
                    return null
                }
                index += rest.length - split.rest.length
                keys += linkedMapOf("type" to "literal", "value" to split.value)
                continue
            }
            val key = takeWhile { !isSyntaxWhitespace(it) && it != code("{") }
            if (key.isNotEmpty()) {
                keys += linkedMapOf("type" to "literal", "value" to key)
            }
        }
        return keys
    }

    private fun parseQuotedPattern(): List<Any?>? {
        val start = index
        if (!consumeString("{{")) {
            pushDiagnostic("missing-quoted-pattern", "Expected a quoted pattern starting with '{{'.", start, start)
            return null
        }
        val contentStart = index
        var scan = index
        var placeholderDepth = 0
        var inQuote = false
        while (scan < source.length) {
            if (placeholderDepth == 0 && source.startsWith("}}", scan)) {
                val content = source.substring(contentStart, scan)
                index = scan + 2
                val nested = Parser(content, baseOffset + contentStart)
                val pattern = nested.parsePatternUntilEnd()
                diagnostics += nested.diagnostics
                return pattern
            }
            val cp = source.codePointAt(scan)
            if (cp == code("\\")) {
                scan += Character.charCount(cp)
                if (scan < source.length) scan += Character.charCount(source.codePointAt(scan))
                continue
            }
            if (placeholderDepth > 0 && cp == code("|")) inQuote = !inQuote
            else if (!inQuote && cp == code("{")) placeholderDepth += 1
            else if (!inQuote && cp == code("}") && placeholderDepth > 0) placeholderDepth -= 1
            scan += Character.charCount(cp)
        }
        pushDiagnostic("unclosed-quoted-pattern", "Quoted pattern is missing closing '}}'.", start, source.length)
        return null
    }

    fun parsePatternUntilEnd(): List<Any?> {
        val parts = mutableListOf<Any?>()
        var text = ""
        while (!isDone()) {
            when (peekCodePoint()) {
                code("\\") -> text += parseEscape()
                code("{") -> {
                    if (text.isNotEmpty()) {
                        parts += text
                        text = ""
                    }
                    parseBracedPatternPart()?.let { parts += it }
                }
                code("}") -> {
                    val start = index
                    advanceCodePoint()
                    pushDiagnostic("unescaped-closing-brace", "Closing brace must be escaped in text.", start, index)
                }
                else -> text += advanceCodePoint()
            }
        }
        if (text.isNotEmpty()) parts += text
        return parts
    }

    private fun parseEscape(): String {
        val start = index
        advanceCodePoint()
        if (isDone()) {
            pushDiagnostic("dangling-escape", "Backslash at end of message has no escaped character.", start, start + 1)
            return ""
        }
        val cp = peekCodePoint()
        return if (cp == code("{") || cp == code("}") || cp == code("\\")) advanceCodePoint() else "\\"
    }

    private fun parseBracedPatternPart(): Map<String, Any?>? {
        val start = index
        val content = consumeBracedContent() ?: return null
        val trimmed = stripSyntaxWhitespace(content)
        return if (trimmed.startsWith("#") || trimmed.startsWith("/")) {
            parseMarkupContent(trimmed, start, start + content.length + 2)
        } else {
            parseExpressionContent(trimmed, start, start + content.length + 2)
        }
    }

    private fun parseExpressionPlaceholder(): Map<String, Any?>? {
        val start = index
        val content = consumeBracedContent() ?: return null
        return parseExpressionContent(stripSyntaxWhitespace(content), start, start + content.length + 2)
    }

    private fun consumeBracedContent(): String? {
        val start = index
        if (peekCodePoint() != code("{")) {
            pushDiagnostic("missing-placeholder", "Expected a placeholder starting with '{'.", start, start)
            return null
        }
        advanceCodePoint()
        val contentStart = index
        var inQuote = false
        while (!isDone()) {
            val cp = peekCodePoint()
            if (inQuote) {
                if (cp == code("\\")) {
                    advanceCodePoint()
                    if (!isDone()) advanceCodePoint()
                    continue
                }
                if (cp == code("}")) {
                    val content = source.substring(contentStart, index)
                    advanceCodePoint()
                    return content
                }
                if (cp == code("|")) inQuote = false
                advanceCodePoint()
                continue
            }
            if (cp == code("|")) {
                inQuote = true
                advanceCodePoint()
                continue
            }
            if (cp == code("}")) {
                val content = source.substring(contentStart, index)
                advanceCodePoint()
                return content
            }
            advanceCodePoint()
        }
        pushDiagnostic("unclosed-placeholder", "Placeholder is missing a closing brace.", start, source.length)
        return null
    }

    private fun parseExpressionContent(content: String, start: Int, end: Int): Map<String, Any?>? {
        val expression: Map<String, Any?>
        val rest: String
        when {
            content.startsWith("$") -> {
                val split = splitName(content.drop(1))
                if (split.name.isEmpty()) {
                    pushDiagnostic(variableNameDiagnosticCode(content.drop(1)), "Variable placeholder is missing a name.", start, end)
                    return null
                }
                expression = expressionModel(linkedMapOf("type" to "variable", "name" to split.name))
                rest = restAfterOperand(split.rest, start, end) ?: return null
            }
            content.startsWith("|") -> {
                val split = parseQuotedLiteral(content)
                if (split == null) {
                    pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
                    return null
                }
                expression = expressionModel(linkedMapOf("type" to "literal", "value" to split.value))
                rest = restAfterOperand(split.rest, start, end) ?: return null
            }
            content.startsWith(":") -> {
                expression = expressionModel(null)
                rest = content
            }
            else -> {
                val split = splitUnquotedLiteral(content)
                if (split == null) {
                    pushDiagnostic(if (content.isEmpty()) "missing-expression" else "invalid-literal", "Placeholder literal is invalid.", start, end)
                    return null
                }
                expression = expressionModel(linkedMapOf("type" to "literal", "value" to split.value))
                rest = restAfterOperand(split.rest, start, end) ?: return null
            }
        }
        if (rest.isEmpty()) return expression
        val tail = parseTail(rest, start, end) ?: return null
        return expressionModel(asMap(expression["arg"]), tail.function, tail.attributes)
    }

    private fun restAfterOperand(rest: String, start: Int, end: Int): String? {
        if (rest.isEmpty()) return rest
        if (!isWhitespace(rest.codePointAt(0))) {
            pushDiagnostic("missing-expression-space", "Expression arguments must be separated from functions or attributes by whitespace.", start, end)
            return null
        }
        return stripLeadingSyntaxWhitespace(rest)
    }

    private fun parseTail(rest: String, start: Int, end: Int): Tail? {
        if (rest.trim().isEmpty()) return Tail(null, null)
        val tokens = splitTailTokens(rest, start, end) ?: return null
        var tokenIndex = 0
        var functionRef: Map<String, Any?>? = null
        val attributes = linkedMapOf<String, Any?>()
        if (tokenIndex < tokens.size && tokens[tokenIndex].startsWith(":")) {
            val result = parseFunctionAnnotation(tokens, tokenIndex, start, end) ?: return null
            functionRef = result.function
            tokenIndex = result.nextIndex
        }
        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (!token.startsWith("@")) {
                pushDiagnostic("unsupported-expression", "Expression content after the argument must be a function annotation or attribute.", start, end)
                return null
            }
            val attribute = parseAttributeTokens(tokens, tokenIndex, start, end) ?: return null
            tokenIndex = attribute.nextIndex
            if (attributes.containsKey(attribute.name)) {
                pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end)
                return null
            }
            attributes[attribute.name] = attribute.value
        }
        return Tail(functionRef, attributes.takeIf { it.isNotEmpty() })
    }

    private fun splitTailTokens(rest: String, start: Int, end: Int): List<String>? {
        val tokens = mutableListOf<String>()
        var tokenStart = -1
        var inQuote = false
        var scan = 0
        while (scan < rest.length) {
            val cp = rest.codePointAt(scan)
            if (inQuote && cp == code("\\")) {
                if (tokenStart < 0) tokenStart = scan
                scan += Character.charCount(cp)
                if (scan < rest.length) scan += Character.charCount(rest.codePointAt(scan))
                continue
            }
            if (cp == code("|")) {
                inQuote = !inQuote
                if (tokenStart < 0) tokenStart = scan
                scan += Character.charCount(cp)
                continue
            }
            if (isSyntaxWhitespace(cp) && !inQuote) {
                if (tokenStart >= 0) {
                    tokens += rest.substring(tokenStart, scan)
                    tokenStart = -1
                }
                scan += Character.charCount(cp)
                continue
            }
            if (tokenStart < 0) tokenStart = scan
            scan += Character.charCount(cp)
        }
        if (inQuote) {
            pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
            return null
        }
        if (tokenStart >= 0) tokens += rest.substring(tokenStart)
        return tokens
    }

    private fun parseFunctionAnnotation(tokens: List<String>, index: Int, start: Int, end: Int): FunctionParse? {
        val content = tokens[index].drop(1)
        val split = splitIdentifier(content)
        if (split.name.isEmpty()) {
            pushDiagnostic(if (content.isEmpty()) "missing-function-name" else "invalid-function-name", "Function annotation is missing a name.", start, end)
            return null
        }
        if (split.rest.isNotEmpty()) {
            pushDiagnostic("unsupported-expression", "Function annotation must separate options with whitespace.", start, end)
            return null
        }
        val options = linkedMapOf<String, Any?>()
        var tokenIndex = index + 1
        while (tokenIndex < tokens.size && !tokens[tokenIndex].startsWith("@")) {
            val option = parseOptionTokens(tokens, tokenIndex, start, end) ?: return null
            tokenIndex = option.nextIndex
            if (options.containsKey(option.name)) {
                pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end)
                return null
            }
            options[option.name] = option.value
        }
        return FunctionParse(functionModel(split.name, options.takeIf { it.isNotEmpty() }), tokenIndex)
    }

    private fun parseOptionTokens(tokens: List<String>, index: Int, start: Int, end: Int): NamedParse? {
        val assignment = parseRequiredAssignment(tokens, index, start, end) ?: return null
        val split = splitIdentifier(assignment.key)
        if (split.name.isEmpty() || split.rest.isNotEmpty()) {
            pushDiagnostic("invalid-function-option", "Option key must be a valid identifier.", start, end)
            return null
        }
        return NamedParse(split.name, parseLiteralOrVariable(stripSyntaxWhitespace(assignment.rawValue)), assignment.nextIndex)
    }

    private fun parseRequiredAssignment(tokens: List<String>, index: Int, start: Int, end: Int): Assignment? {
        val token = tokens[index]
        val equals = token.indexOf("=")
        if (equals >= 0) return finishAssignment(token.take(equals), token.drop(equals + 1), tokens, index + 1, start, end)
        if (index + 1 >= tokens.size || !tokens[index + 1].startsWith("=")) {
            pushDiagnostic("invalid-function-option", "Options must use key=value syntax.", start, end)
            return null
        }
        return finishAssignment(token, tokens[index + 1].drop(1), tokens, index + 2, start, end)
    }

    private fun finishAssignment(key: String, rawValue: String, tokens: List<String>, nextIndex: Int, start: Int, end: Int): Assignment? {
        if (key.isEmpty()) {
            pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
            return null
        }
        if (rawValue.isNotEmpty()) return Assignment(key, rawValue, nextIndex)
        if (nextIndex >= tokens.size) {
            pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
            return null
        }
        return Assignment(key, tokens[nextIndex], nextIndex + 1)
    }

    private fun parseAttributeTokens(tokens: List<String>, index: Int, start: Int, end: Int): NamedParse? {
        val content = tokens[index].drop(1)
        if (content.isEmpty()) {
            pushDiagnostic("missing-attribute-name", "Attribute is missing a name.", start, end)
            return null
        }
        if (!hasAttributeAssignment(content, tokens, index)) {
            val split = splitIdentifier(content)
            if (split.name.isEmpty() || split.rest.isNotEmpty()) {
                pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
                return null
            }
            return NamedParse(split.name, true, index + 1)
        }
        val assignment = parseAttributeAssignment(content, tokens, index, start, end) ?: return null
        val value = parseAttributeValue(assignment.rawValue, start, end) ?: return null
        return NamedParse(assignment.name, value, assignment.nextIndex)
    }

    private fun hasAttributeAssignment(content: String, tokens: List<String>, index: Int): Boolean =
        content.contains("=") || (index + 1 < tokens.size && tokens[index + 1].startsWith("="))

    private fun parseAttributeAssignment(content: String, tokens: List<String>, index: Int, start: Int, end: Int): AttributeAssignment? {
        val assignment = attributeAssignmentParts(content, tokens, index, start, end) ?: return null
        val split = splitIdentifier(assignment.key)
        if (split.name.isEmpty() || split.rest.isNotEmpty()) {
            pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
            return null
        }
        return AttributeAssignment(split.name, assignment.rawValue, assignment.nextIndex)
    }

    private fun attributeAssignmentParts(content: String, tokens: List<String>, index: Int, start: Int, end: Int): Assignment? {
        val equals = content.indexOf("=")
        if (equals >= 0) return finishAttributeAssignment(content.take(equals), content.drop(equals + 1), tokens, index + 1, start, end)
        if (index + 1 >= tokens.size || !tokens[index + 1].startsWith("=")) return null
        return finishAttributeAssignment(content, tokens[index + 1].drop(1), tokens, index + 2, start, end)
    }

    private fun finishAttributeAssignment(key: String, rawValue: String, tokens: List<String>, nextIndex: Int, start: Int, end: Int): Assignment? {
        if (key.isEmpty()) {
            pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
            return null
        }
        if (rawValue.isNotEmpty()) return Assignment(key, rawValue, nextIndex)
        if (nextIndex >= tokens.size) {
            pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
            return null
        }
        return Assignment(key, tokens[nextIndex], nextIndex + 1)
    }

    private fun parseAttributeValue(rawValueInput: String, start: Int, end: Int): Map<String, Any?>? {
        val rawValue = stripSyntaxWhitespace(rawValueInput)
        if (rawValue.startsWith("|") && rawValue.endsWith("|") && rawValue.length >= 2) {
            val split = parseQuotedLiteral(rawValue)
            if (split == null) {
                pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
                return null
            }
            if (split.rest.isNotEmpty()) {
                pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
                return null
            }
            return linkedMapOf("type" to "literal", "value" to split.value)
        }
        val split = splitUnquotedLiteral(rawValue)
        if (split == null || split.rest.isNotEmpty()) {
            pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
            return null
        }
        return linkedMapOf("type" to "literal", "value" to split.value)
    }

    private fun parseMarkupContent(content: String, start: Int, end: Int): Map<String, Any?>? {
        val kind: String
        val rest: String
        if (content.startsWith("#")) {
            val trimmed = stripTrailingSyntaxWhitespace(content.drop(1))
            if (trimmed.endsWith("/")) {
                kind = "standalone"
                rest = stripTrailingSyntaxWhitespace(trimmed.dropLast(1))
            } else {
                kind = "open"
                rest = trimmed
            }
        } else {
            kind = "close"
            rest = stripSyntaxWhitespace(content.drop(1))
        }
        val split = splitIdentifier(stripLeadingSyntaxWhitespace(rest))
        if (split.name.isEmpty()) {
            pushDiagnostic("missing-markup-name", "Markup placeholder is missing a name.", start, end)
            return null
        }
        if (stripSyntaxWhitespace(split.rest).isEmpty()) return markupModel(kind, split.name)
        val tail = parseMarkupTail(split.rest, start, end) ?: return null
        return markupModel(kind, split.name, tail.options, tail.attributes)
    }

    private fun parseMarkupTail(rest: String, start: Int, end: Int): Tail {
        val tokens = splitTailTokens(rest, start, end) ?: return Tail(null, null)
        val options = linkedMapOf<String, Any?>()
        val attributes = linkedMapOf<String, Any?>()
        var seenAttribute = false
        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (token.startsWith("@")) {
                seenAttribute = true
                val attribute = parseAttributeTokens(tokens, tokenIndex, start, end) ?: return Tail(null, null)
                tokenIndex = attribute.nextIndex
                if (attributes.containsKey(attribute.name)) {
                    pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end)
                    return Tail(null, null)
                }
                attributes[attribute.name] = attribute.value
                continue
            }
            if (seenAttribute) {
                pushDiagnostic("unsupported-markup", "Markup options must come before attributes.", start, end)
                return Tail(null, null)
            }
            if (token.startsWith(":")) {
                pushDiagnostic("unsupported-markup", "Markup placeholders do not support function annotations.", start, end)
                return Tail(null, null)
            }
            val option = parseOptionTokens(tokens, tokenIndex, start, end) ?: return Tail(null, null)
            tokenIndex = option.nextIndex
            if (options.containsKey(option.name)) {
                pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end)
                return Tail(null, null)
            }
            options[option.name] = option.value
        }
        return Tail(options = options.takeIf { it.isNotEmpty() }, attributes = attributes.takeIf { it.isNotEmpty() })
    }

    private fun parseVariableName(): String? {
        val start = index
        if (peekCodePoint() != code("$")) {
            pushDiagnostic("missing-variable", "Expected a variable starting with '$'.", start, start)
            return null
        }
        advanceCodePoint()
        val scan = scanName(source, index)
        if (scan.name.isEmpty()) {
            pushDiagnostic(variableNameDiagnosticCode(source, index), "Variable is missing a name.", start, index)
            return null
        }
        index = scan.endIndex
        return scan.name
    }

    private fun skipSyntaxWhitespace(): Boolean {
        val start = index
        while (!isDone()) {
            val cp = peekCodePoint()
            if (!isSyntaxWhitespace(cp)) break
            index += Character.charCount(cp)
        }
        return index != start
    }

    private fun skipSyntaxGap(): Boolean {
        var sawWhitespace = false
        while (!isDone()) {
            val cp = peekCodePoint()
            if (isWhitespace(cp)) {
                sawWhitespace = true
                index += Character.charCount(cp)
                continue
            }
            if (isBidiMarker(cp)) {
                index += Character.charCount(cp)
                continue
            }
            break
        }
        return sawWhitespace
    }

    private fun takeWhile(predicate: (Int) -> Boolean): String {
        val start = index
        while (!isDone() && predicate(peekCodePoint())) advanceCodePoint()
        return source.substring(start, index)
    }

    private fun startsWith(expected: String): Boolean = source.startsWith(expected, index)

    private fun consumeString(expected: String): Boolean {
        if (!startsWith(expected)) return false
        index += expected.length
        return true
    }

    private fun isDone(): Boolean = index >= source.length

    private fun peekCodePoint(): Int = if (isDone()) -1 else source.codePointAt(index)

    private fun advanceCodePoint(): String {
        val cp = peekCodePoint()
        val value = String(Character.toChars(cp))
        index += Character.charCount(cp)
        return value
    }

    private fun pushDiagnostic(code: String, message: String, start: Int, end: Int) {
        diagnostics += Mf2ParseDiagnostic(code, message, baseOffset + start, baseOffset + end)
    }
}

private data class Tail(
    val function: Map<String, Any?>? = null,
    val attributes: Map<String, Any?>? = null,
    val options: Map<String, Any?>? = null,
)

private data class FunctionParse(val function: Map<String, Any?>, val nextIndex: Int)
private data class NamedParse(val name: String, val value: Any?, val nextIndex: Int)
private data class Assignment(val key: String, val rawValue: String, val nextIndex: Int)
private data class AttributeAssignment(val name: String, val rawValue: String, val nextIndex: Int)
private data class QuotedSplit(val value: String, val rest: String)
private data class LiteralSplit(val value: String, val rest: String)
private data class NameSplit(val name: String, val rest: String, val consumedLength: Int)
private data class NameScan(val name: String, val endIndex: Int)

private fun expressionModel(
    arg: Map<String, Any?>?,
    functionRef: Map<String, Any?>? = null,
    attributes: Map<String, Any?>? = null,
): Map<String, Any?> {
    val output = linkedMapOf<String, Any?>("type" to "expression")
    if (arg != null) output["arg"] = arg
    if (functionRef != null) output["function"] = functionRef
    if (!attributes.isNullOrEmpty()) output["attributes"] = sortedMap(attributes)
    return output
}

private fun functionModel(name: String, options: Map<String, Any?>?): Map<String, Any?> {
    val output = linkedMapOf<String, Any?>("type" to "function", "name" to name)
    if (!options.isNullOrEmpty()) output["options"] = sortedMap(options)
    return output
}

private fun markupModel(
    kind: String,
    name: String,
    options: Map<String, Any?>? = null,
    attributes: Map<String, Any?>? = null,
): Map<String, Any?> {
    val output = linkedMapOf<String, Any?>("type" to "markup", "kind" to kind, "name" to name)
    if (!options.isNullOrEmpty()) output["options"] = sortedMap(options)
    if (!attributes.isNullOrEmpty()) output["attributes"] = sortedMap(attributes)
    return output
}

private fun parseLiteralOrVariable(rawValue: String): Map<String, Any?> {
    if (rawValue.startsWith("$")) {
        val split = splitName(rawValue.drop(1))
        if (split.name.isNotEmpty() && split.rest.isEmpty()) return linkedMapOf("type" to "variable", "name" to split.name)
        return linkedMapOf("type" to "variable", "name" to rawValue.drop(1))
    }
    val quoted = parseQuotedLiteral(rawValue)
    if (quoted != null && quoted.rest.isEmpty()) return linkedMapOf("type" to "literal", "value" to quoted.value)
    return linkedMapOf("type" to "literal", "value" to rawValue)
}

private fun parseQuotedLiteral(input: String): QuotedSplit? {
    if (!input.startsWith("|")) return null
    val output = StringBuilder()
    var index = 1
    while (index < input.length) {
        val cp = input.codePointAt(index)
        index += Character.charCount(cp)
        if (cp == code("|")) return QuotedSplit(output.toString(), input.substring(index))
        if (cp == code("\\")) {
            if (index >= input.length) {
                output.append("\\")
                break
            }
            val escaped = input.codePointAt(index)
            if (escaped == code("\\") || escaped == code("{") || escaped == code("|") || escaped == code("}")) {
                output.appendCodePoint(escaped)
                index += Character.charCount(escaped)
            } else {
                output.append("\\")
            }
        } else {
            output.appendCodePoint(cp)
        }
    }
    return null
}

private fun splitUnquotedLiteral(input: String): LiteralSplit? {
    var scan = 0
    var sawChar = false
    while (scan < input.length) {
        val cp = input.codePointAt(scan)
        if (isSyntaxWhitespace(cp) || cp == code(":") || cp == code("@")) break
        if (!isUnquotedLiteralChar(cp)) return null
        sawChar = true
        scan += Character.charCount(cp)
    }
    return if (sawChar) LiteralSplit(input.substring(0, scan), input.substring(scan)) else null
}

private fun isUnquotedLiteralChar(cp: Int): Boolean {
    if (isControl(cp) || isSyntaxWhitespace(cp) || isNoncharacter(cp)) return false
    return String(Character.toChars(cp)) !in setOf("^", "!", "%", "*", "<", ">", "?", "~", "&", "\\", "$")
}

private fun variableNameDiagnosticCode(input: String, offset: Int = 0): String {
    if (offset >= input.length) return "missing-variable-name"
    return if (input[offset] in listOf('}', ' ', '\t', '\n', '\r')) "missing-variable-name" else "invalid-variable-name"
}

private fun splitName(input: String): NameSplit {
    val scan = scanName(input, 0)
    return NameSplit(scan.name, input.substring(scan.endIndex), scan.endIndex)
}

private fun scanName(input: String, offset: Int): NameScan {
    var scan = offset
    if (scan < input.length && isBidiMarker(input.codePointAt(scan))) scan += Character.charCount(input.codePointAt(scan))
    val nameStart = scan
    if (nameStart >= input.length) return NameScan("", offset)
    val first = input.codePointAt(nameStart)
    if (first <= 0x7f) {
        val ascii = scanAsciiName(input, offset, nameStart)
        if (ascii != null) return ascii
    }
    if (!isNameStart(first)) return NameScan("", offset)
    scan += Character.charCount(first)
    while (scan < input.length) {
        val cp = input.codePointAt(scan)
        if (!isNameChar(cp)) break
        scan += Character.charCount(cp)
    }
    val nameEnd = scan
    if (scan < input.length && isBidiMarker(input.codePointAt(scan))) scan += Character.charCount(input.codePointAt(scan))
    return NameScan(input.substring(nameStart, nameEnd), scan)
}

private fun scanAsciiName(input: String, offset: Int, nameStart: Int): NameScan? {
    val first = input[nameStart].code
    if (!isAsciiNameStart(first)) return NameScan("", offset)
    var scan = nameStart + 1
    while (scan < input.length && isAsciiNameChar(input[scan].code)) scan += 1
    val nameEnd = scan
    if (scan < input.length) {
        val cp = input.codePointAt(scan)
        if (cp > 0x7f) {
            if (!isBidiMarker(cp)) return null
            return NameScan(input.substring(nameStart, nameEnd), scan + Character.charCount(cp))
        }
    }
    return NameScan(input.substring(nameStart, nameEnd), scan)
}

private fun splitIdentifier(input: String): NameSplit {
    val namespaceOrName = splitName(input)
    if (namespaceOrName.name.isEmpty()) return NameSplit("", input, 0)
    if (!namespaceOrName.rest.startsWith(":")) return namespaceOrName
    val name = splitName(namespaceOrName.rest.drop(1))
    if (name.name.isEmpty()) return namespaceOrName
    return NameSplit(
        "${namespaceOrName.name}:${name.name}",
        name.rest,
        namespaceOrName.consumedLength + 1 + name.consumedLength,
    )
}

private val bidiMarkers = setOf(0x061c, 0x200e, 0x200f, 0x2066, 0x2067, 0x2068, 0x2069)

private fun isNameStart(cp: Int): Boolean =
    if (cp <= 0x7f) {
        isAsciiNameStart(cp)
    } else {
        cp in 0x00a1..0x10fffd && !isBidiMarker(cp) && !isControl(cp) && !isSurrogate(cp) && !isSyntaxWhitespace(cp) && !isNoncharacter(cp)
    }

private fun isNameChar(cp: Int): Boolean =
    isNameStart(cp) || cp in code("0")..code("9") || isMark(cp) || cp == code("-") || cp == code(".")

private fun isAsciiNameStart(cp: Int): Boolean =
    cp in code("a")..code("z") || cp in code("A")..code("Z") || cp == code("+") || cp == code("_")

private fun isAsciiNameChar(cp: Int): Boolean =
    isAsciiNameStart(cp) || cp in code("0")..code("9") || cp == code("-") || cp == code(".")

private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    -> true
    else -> false
}

private fun isBidiMarker(cp: Int): Boolean = cp in bidiMarkers
private fun isSyntaxWhitespace(cp: Int): Boolean = isWhitespace(cp) || isBidiMarker(cp)
private fun isWhitespace(cp: Int): Boolean = cp >= 0 && Character.isWhitespace(cp)
private fun isControl(cp: Int): Boolean = cp in 0..0x1f || cp in 0x7f..0x9f
private fun isSurrogate(cp: Int): Boolean = cp in 0xd800..0xdfff
private fun isNoncharacter(cp: Int): Boolean = cp in 0xfdd0..0xfdef || cp and 0xfffe == 0xfffe

private fun stripSyntaxWhitespace(value: String): String = stripTrailingSyntaxWhitespace(stripLeadingSyntaxWhitespace(value))

private fun stripLeadingSyntaxWhitespace(value: String): String {
    var start = 0
    while (start < value.length) {
        val cp = value.codePointAt(start)
        if (!isSyntaxWhitespace(cp)) break
        start += Character.charCount(cp)
    }
    return value.substring(start)
}

private fun stripTrailingSyntaxWhitespace(value: String): String {
    var end = value.length
    while (end > 0) {
        val index = previousIndex(value, end)
        val cp = value.codePointAt(index)
        if (!isSyntaxWhitespace(cp)) break
        end = index
    }
    return value.substring(0, end)
}

private fun previousIndex(value: String, end: Int): Int {
    val before = value[end - 1].code
    return if (before in 0xdc00..0xdfff && end >= 2) end - 2 else end - 1
}

private fun code(value: String): Int = value.codePointAt(0)
