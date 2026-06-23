const BIDI_MARKERS = new Set([0x061c, 0x200e, 0x200f, 0x2066, 0x2067, 0x2068, 0x2069]);
const MARK_RE = /^\p{Mark}$/u;

export function parseToModel(source) {
  const parser = new Parser(String(source ?? ""), 0);
  const model = parser.parseMessageModel();
  return {
    model: parser.diagnostics.length === 0 ? model : null,
    diagnostics: parser.diagnostics,
    hasDiagnostics: parser.diagnostics.length > 0,
  };
}

class Parser {
  constructor(source, baseOffset) {
    this.source = source;
    this.baseOffset = baseOffset;
    this.index = 0;
    this.diagnostics = [];
  }

  parseMessageModel() {
    const messageStart = this.index;
    const declarations = this.parseDeclarations();
    this.skipSyntaxWhitespace();
    if (this.startsWith(".match")) return this.parseMatch(declarations);
    if (this.startsWith("{{")) {
      const pattern = this.parseQuotedPattern();
      if (pattern == null) return null;
      this.skipSyntaxWhitespace();
      if (!this.isDone()) {
        this.pushDiagnostic("trailing-content", "Unexpected content after complex message body.", this.index, this.source.length);
      }
      return { type: "message", declarations, pattern };
    }
    if (declarations.length > 0) {
      this.pushDiagnostic(
        "missing-complex-body",
        "Complex message declarations must be followed by a quoted pattern or matcher.",
        this.index,
        this.source.length,
      );
      return null;
    }
    if (this.startsWith(".")) {
      this.pushDiagnostic("invalid-simple-start", "Simple messages cannot start with '.'.", this.index, this.index + 1);
      return null;
    }
    this.index = messageStart;
    return { type: "message", declarations, pattern: this.parsePatternUntilEnd() };
  }

  parseDeclarations() {
    const declarations = [];
    while (true) {
      const beforePadding = this.index;
      this.skipSyntaxWhitespace();
      if (this.startsWith(".input")) {
        const declaration = this.parseInputDeclaration();
        if (declaration) declarations.push(declaration);
        continue;
      }
      if (this.startsWith(".local")) {
        const declaration = this.parseLocalDeclaration();
        if (declaration) declarations.push(declaration);
        continue;
      }
      this.index = beforePadding;
      return declarations;
    }
  }

  parseInputDeclaration() {
    this.consumeString(".input");
    this.skipSyntaxWhitespace();
    const start = this.index;
    const value = this.parseExpressionPlaceholder();
    if (!value) return null;
    if (value.arg?.type === "variable") return { type: "input", name: value.arg.name, value };
    this.pushDiagnostic("invalid-input-declaration", ".input declarations must reference a variable expression.", start, this.index);
    return null;
  }

  parseLocalDeclaration() {
    this.consumeString(".local");
    this.skipSyntaxWhitespace();
    const start = this.index;
    const name = this.parseVariableName();
    if (name == null) return null;
    this.skipSyntaxWhitespace();
    if (this.peekCodePoint() !== code("=")) {
      this.pushDiagnostic("missing-local-equals", ".local declarations must include '='.", start, this.index);
      return null;
    }
    this.advanceCodePoint();
    this.skipSyntaxWhitespace();
    const value = this.parseExpressionPlaceholder();
    return value == null ? null : { type: "local", name, value };
  }

  parseMatch(declarations) {
    this.consumeString(".match");
    const selectors = [];
    if (!this.isDone() && this.peekCodePoint() === code("$")) {
      this.pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", this.index, this.index);
      return null;
    }
    while (true) {
      const skippedSpace = this.skipSyntaxGap();
      if (!this.isDone() && this.peekCodePoint() === code("$")) {
        if (!skippedSpace && selectors.length > 0) {
          this.pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", this.index, this.index);
          return null;
        }
        const name = this.parseVariableName();
        if (name != null) selectors.push({ type: "variable", name });
        if (!this.isDone() && !isWhitespace(this.peekCodePoint())) {
          this.pushDiagnostic("missing-match-space", ".match selectors must be separated from variants by whitespace.", this.index, this.index);
          return null;
        }
        continue;
      }
      if (!this.isDone() && this.peekCodePoint() === code("{")) {
        const start = this.index;
        const content = this.consumeBracedContent();
        if (content != null) {
          this.pushDiagnostic(
            "unsupported-match-selector-expression",
            ".match selectors must be declared variables such as .input {$name :string} followed by .match $name; inline selector expressions are not supported.",
            start,
            this.index,
          );
        }
        return null;
      }
      break;
    }
    if (selectors.length === 0) {
      this.pushDiagnostic("missing-match-selector", ".match must include at least one selector variable.", this.index, this.index);
      return null;
    }
    const variants = [];
    while (true) {
      this.skipSyntaxWhitespace();
      if (this.isDone()) break;
      const variantStart = this.index;
      const keys = this.parseVariantKeys(variantStart);
      if (keys == null) return null;
      this.skipSyntaxWhitespace();
      if (!this.startsWith("{{")) {
        this.pushDiagnostic("missing-variant-pattern", "Variant keys must be followed by a quoted pattern.", variantStart, this.index);
        return null;
      }
      const value = this.parseQuotedPattern();
      if (value == null) return null;
      if (keys.length !== selectors.length) {
        this.pushDiagnostic("variant-key-count-mismatch", "Variant key count must match selector count.", variantStart, this.index);
        return null;
      }
      variants.push({ keys, value });
    }
    if (variants.length === 0) {
      this.pushDiagnostic("missing-match-variants", ".match must include at least one variant.", this.index, this.index);
      return null;
    }
    return { type: "select", declarations, selectors, variants };
  }

  parseVariantKeys(start) {
    const keys = [];
    while (!this.isDone() && !this.startsWith("{{") && this.peekCodePoint() !== code("\n")) {
      const skippedSpace = this.skipSyntaxGap();
      if (this.startsWith("{{") || this.peekCodePoint() === code("\n") || this.isDone()) break;
      if (keys.length > 0 && !skippedSpace) {
        this.pushDiagnostic("missing-variant-key-space", "Variant keys must be separated by whitespace.", start, this.index);
        return null;
      }
      if (this.peekCodePoint() === code("*")) {
        this.advanceCodePoint();
        keys.push({ type: "*" });
        continue;
      }
      if (this.peekCodePoint() === code("|")) {
        const rest = this.source.slice(this.index);
        const split = parseQuotedLiteral(rest);
        if (!split) {
          this.pushDiagnostic("unclosed-quoted-literal", "Quoted variant key is missing closing '|'.", this.index, this.source.length);
          return null;
        }
        this.index += rest.length - split.rest.length;
        keys.push({ type: "literal", value: split.value });
        continue;
      }
      const key = this.takeWhile((codePoint) => !isSyntaxWhitespace(codePoint) && codePoint !== code("{"));
      if (key !== "") keys.push({ type: "literal", value: key });
    }
    return keys;
  }

  parseQuotedPattern() {
    const start = this.index;
    if (!this.consumeString("{{")) {
      this.pushDiagnostic("missing-quoted-pattern", "Expected a quoted pattern starting with '{{'.", start, start);
      return null;
    }
    const contentStart = this.index;
    let scan = this.index;
    let placeholderDepth = 0;
    let inQuote = false;
    while (scan < this.source.length) {
      if (placeholderDepth === 0 && this.source.startsWith("}}", scan)) {
        const content = this.source.slice(contentStart, scan);
        this.index = scan + 2;
        const nested = new Parser(content, this.baseOffset + contentStart);
        const pattern = nested.parsePatternUntilEnd();
        this.diagnostics.push(...nested.diagnostics);
        return pattern;
      }
      const codePoint = this.source.codePointAt(scan);
      if (codePoint === code("\\")) {
        scan += charCount(codePoint);
        if (scan < this.source.length) scan += charCount(this.source.codePointAt(scan));
        continue;
      }
      if (placeholderDepth > 0 && codePoint === code("|")) inQuote = !inQuote;
      else if (!inQuote && codePoint === code("{")) placeholderDepth += 1;
      else if (!inQuote && codePoint === code("}") && placeholderDepth > 0) placeholderDepth -= 1;
      scan += charCount(codePoint);
    }
    this.pushDiagnostic("unclosed-quoted-pattern", "Quoted pattern is missing closing '}}'.", start, this.source.length);
    return null;
  }

  parsePatternUntilEnd() {
    const parts = [];
    let text = "";
    while (!this.isDone()) {
      const codePoint = this.peekCodePoint();
      if (codePoint === code("\\")) {
        text += this.parseEscape();
      } else if (codePoint === code("{")) {
        if (text !== "") {
          parts.push(text);
          text = "";
        }
        const part = this.parseBracedPatternPart();
        if (part) parts.push(part);
      } else if (codePoint === code("}")) {
        const start = this.index;
        this.advanceCodePoint();
        this.pushDiagnostic("unescaped-closing-brace", "Closing brace must be escaped in text.", start, this.index);
      } else {
        text += this.advanceCodePoint();
      }
    }
    if (text !== "") parts.push(text);
    return parts;
  }

  parseEscape() {
    const start = this.index;
    this.advanceCodePoint();
    if (this.isDone()) {
      this.pushDiagnostic("dangling-escape", "Backslash at end of message has no escaped character.", start, start + 1);
      return "";
    }
    const codePoint = this.peekCodePoint();
    if (codePoint === code("{") || codePoint === code("}") || codePoint === code("|") || codePoint === code("\\")) return this.advanceCodePoint();
    return "\\";
  }

  parseBracedPatternPart() {
    const start = this.index;
    const content = this.consumeBracedContent();
    if (content == null) return null;
    const trimmed = stripSyntaxWhitespace(content);
    if (trimmed.startsWith("#") || trimmed.startsWith("/")) {
      return this.parseMarkupContent(trimmed, start, start + content.length + 2);
    }
    return this.parseExpressionContent(trimmed, start, start + content.length + 2);
  }

  parseExpressionPlaceholder() {
    const start = this.index;
    const content = this.consumeBracedContent();
    return content == null ? null : this.parseExpressionContent(stripSyntaxWhitespace(content), start, start + content.length + 2);
  }

  consumeBracedContent() {
    const start = this.index;
    if (this.peekCodePoint() !== code("{")) {
      this.pushDiagnostic("missing-placeholder", "Expected a placeholder starting with '{'.", start, start);
      return null;
    }
    this.advanceCodePoint();
    const contentStart = this.index;
    let inQuote = false;
    while (!this.isDone()) {
      const codePoint = this.peekCodePoint();
      if (inQuote) {
        if (codePoint === code("\\")) {
          this.advanceCodePoint();
          if (!this.isDone()) this.advanceCodePoint();
          continue;
        }
        if (codePoint === code("}")) {
          const content = this.source.slice(contentStart, this.index);
          this.advanceCodePoint();
          return content;
        }
        if (codePoint === code("|")) inQuote = false;
        this.advanceCodePoint();
        continue;
      }
      if (codePoint === code("|")) {
        inQuote = true;
        this.advanceCodePoint();
        continue;
      }
      if (codePoint === code("}")) {
        const content = this.source.slice(contentStart, this.index);
        this.advanceCodePoint();
        return content;
      }
      this.advanceCodePoint();
    }
    this.pushDiagnostic("unclosed-placeholder", "Placeholder is missing a closing brace.", start, this.source.length);
    return null;
  }

  parseExpressionContent(content, start, end) {
    let expression;
    let rest;
    if (content.startsWith("$")) {
      const split = splitName(content.slice(1));
      if (split.name === "") {
        this.pushDiagnostic(variableNameDiagnosticCode(content.slice(1)), "Variable placeholder is missing a name.", start, end);
        return null;
      }
      expression = expressionModel({ type: "variable", name: split.name });
      rest = this.restAfterOperand(split.rest, start, end);
      if (rest == null) return null;
    } else if (content.startsWith("|")) {
      const split = parseQuotedLiteral(content);
      if (!split) {
        this.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end);
        return null;
      }
      expression = expressionModel({ type: "literal", value: split.value });
      rest = this.restAfterOperand(split.rest, start, end);
      if (rest == null) return null;
    } else if (content.startsWith(":")) {
      expression = expressionModel(null);
      rest = content;
    } else {
      const split = splitUnquotedLiteral(content);
      if (!split) {
        this.pushDiagnostic(content === "" ? "missing-expression" : "invalid-literal", "Placeholder literal is invalid.", start, end);
        return null;
      }
      expression = expressionModel({ type: "literal", value: split.value });
      rest = this.restAfterOperand(split.rest, start, end);
      if (rest == null) return null;
    }
    if (rest === "") return expression;
    const tail = this.parseTail(rest, start, end);
    return tail == null ? null : expressionModel(expression.arg, tail.function, tail.attributes);
  }

  restAfterOperand(rest, start, end) {
    if (rest === "") return rest;
    if (!isWhitespace(rest.codePointAt(0))) {
      this.pushDiagnostic("missing-expression-space", "Expression arguments must be separated from functions or attributes by whitespace.", start, end);
      return null;
    }
    return stripLeadingSyntaxWhitespace(rest);
  }

  parseTail(rest, start, end) {
    if (rest.trim() === "") return { function: null, attributes: null };
    const tokens = this.splitTailTokens(rest, start, end);
    if (!tokens) return null;
    let index = 0;
    let functionRef = null;
    const attributes = {};
    if (index < tokens.length && tokens[index].startsWith(":")) {
      const result = this.parseFunctionAnnotation(tokens, index, start, end);
      if (!result) return null;
      functionRef = result.function;
      index = result.nextIndex;
    }
    while (index < tokens.length) {
      const token = tokens[index];
      if (!token.startsWith("@")) {
        this.pushDiagnostic("unsupported-expression", "Expression content after the argument must be a function annotation or attribute.", start, end);
        return null;
      }
      const attribute = this.parseAttributeTokens(tokens, index, start, end);
      if (!attribute) return null;
      index = attribute.nextIndex;
      if (Object.hasOwn(attributes, attribute.name)) {
        this.pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end);
        return null;
      }
      attributes[attribute.name] = attribute.value;
    }
    return { function: functionRef, attributes: omitEmpty(attributes) };
  }

  splitTailTokens(rest, start, end) {
    const tokens = [];
    let tokenStart = -1;
    let inQuote = false;
    for (let index = 0; index < rest.length; ) {
      const codePoint = rest.codePointAt(index);
      if (inQuote && codePoint === code("\\")) {
        if (tokenStart < 0) tokenStart = index;
        index += charCount(codePoint);
        if (index < rest.length) index += charCount(rest.codePointAt(index));
        continue;
      }
      if (codePoint === code("|")) {
        inQuote = !inQuote;
        if (tokenStart < 0) tokenStart = index;
        index += charCount(codePoint);
        continue;
      }
      if (isSyntaxWhitespace(codePoint) && !inQuote) {
        if (tokenStart >= 0) {
          tokens.push(rest.slice(tokenStart, index));
          tokenStart = -1;
        }
        index += charCount(codePoint);
        continue;
      }
      if (tokenStart < 0) tokenStart = index;
      index += charCount(codePoint);
    }
    if (inQuote) {
      this.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end);
      return null;
    }
    if (tokenStart >= 0) tokens.push(rest.slice(tokenStart));
    return tokens;
  }

  parseFunctionAnnotation(tokens, index, start, end) {
    const content = tokens[index].slice(1);
    const split = splitIdentifier(content);
    if (split.name === "") {
      this.pushDiagnostic(content === "" ? "missing-function-name" : "invalid-function-name", "Function annotation is missing a name.", start, end);
      return null;
    }
    if (split.rest !== "") {
      this.pushDiagnostic("unsupported-expression", "Function annotation must separate options with whitespace.", start, end);
      return null;
    }
    const options = {};
    index += 1;
    while (index < tokens.length && !tokens[index].startsWith("@")) {
      const option = this.parseOptionTokens(tokens, index, start, end);
      if (!option) return null;
      index = option.nextIndex;
      if (Object.hasOwn(options, option.name)) {
        this.pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end);
        return null;
      }
      options[option.name] = option.value;
    }
    return { function: functionModel(split.name, omitEmpty(options)), nextIndex: index };
  }

  parseOptionTokens(tokens, index, start, end) {
    const assignment = this.parseRequiredAssignment(tokens, index, start, end);
    if (!assignment) return null;
    const keySplit = splitIdentifier(assignment.key);
    if (keySplit.name === "" || keySplit.rest !== "") {
      this.pushDiagnostic("invalid-function-option", "Option key must be a valid identifier.", start, end);
      return null;
    }
    const value = this.parseOptionValue(assignment.rawValue, start, end);
    if (!value) return null;
    return { name: keySplit.name, value, nextIndex: assignment.nextIndex };
  }

  parseOptionValue(rawValue, start, end) {
    rawValue = stripSyntaxWhitespace(rawValue);
    if (rawValue.startsWith("|")) {
      const split = parseQuotedLiteral(rawValue);
      if (!split) {
        this.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end);
        return null;
      }
      if (split.rest !== "") {
        this.pushDiagnostic("invalid-function-option", "Option value must be a single literal or variable.", start, end);
        return null;
      }
      return { type: "literal", value: split.value };
    }
    if (rawValue.startsWith("$")) {
      const split = splitName(rawValue.slice(1));
      if (split.name !== "" && split.rest === "") return { type: "variable", name: split.name };
      this.pushDiagnostic(
        variableNameDiagnosticCode(rawValue.slice(1)),
        "Option variable value must be a valid variable name.",
        start,
        end,
      );
      return null;
    }
    return parseLiteralOrVariable(rawValue);
  }

  parseRequiredAssignment(tokens, index, start, end) {
    const token = tokens[index];
    const equals = token.indexOf("=");
    if (equals >= 0) return this.finishAssignment(token.slice(0, equals), token.slice(equals + 1), tokens, index + 1, start, end);
    if (index + 1 >= tokens.length || !tokens[index + 1].startsWith("=")) {
      this.pushDiagnostic("invalid-function-option", "Options must use key=value syntax.", start, end);
      return null;
    }
    return this.finishAssignment(token, tokens[index + 1].slice(1), tokens, index + 2, start, end);
  }

  finishAssignment(key, rawValue, tokens, nextIndex, start, end) {
    if (key === "") {
      this.pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end);
      return null;
    }
    if (rawValue !== "") return { key, rawValue, nextIndex };
    if (nextIndex >= tokens.length) {
      this.pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end);
      return null;
    }
    return { key, rawValue: tokens[nextIndex], nextIndex: nextIndex + 1 };
  }

  parseAttributeTokens(tokens, index, start, end) {
    const token = tokens[index];
    const content = token.slice(1);
    if (content === "") {
      this.pushDiagnostic("missing-attribute-name", "Attribute is missing a name.", start, end);
      return null;
    }
    if (!this.hasAttributeAssignment(content, tokens, index)) {
      const split = splitIdentifier(content);
      if (split.name === "" || split.rest !== "") {
        this.pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end);
        return null;
      }
      return { name: split.name, value: true, nextIndex: index + 1 };
    }
    const assignment = this.parseAttributeAssignment(content, tokens, index, start, end);
    if (!assignment) return null;
    const value = this.parseAttributeValue(assignment.rawValue, start, end);
    return value == null ? null : { name: assignment.name, value, nextIndex: assignment.nextIndex };
  }

  hasAttributeAssignment(content, tokens, index) {
    return content.includes("=") || (index + 1 < tokens.length && tokens[index + 1].startsWith("="));
  }

  parseAttributeAssignment(content, tokens, index, start, end) {
    const assignment = this.attributeAssignmentParts(content, tokens, index, start, end);
    if (!assignment) return null;
    const split = splitIdentifier(assignment.key);
    if (split.name === "" || split.rest !== "") {
      this.pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end);
      return null;
    }
    return { name: split.name, rawValue: assignment.rawValue, nextIndex: assignment.nextIndex };
  }

  attributeAssignmentParts(content, tokens, index, start, end) {
    const equals = content.indexOf("=");
    if (equals >= 0) return this.finishAttributeAssignment(content.slice(0, equals), content.slice(equals + 1), tokens, index + 1, start, end);
    if (index + 1 >= tokens.length || !tokens[index + 1].startsWith("=")) return null;
    return this.finishAttributeAssignment(content, tokens[index + 1].slice(1), tokens, index + 2, start, end);
  }

  finishAttributeAssignment(key, rawValue, tokens, nextIndex, start, end) {
    if (key === "") {
      this.pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end);
      return null;
    }
    if (rawValue !== "") return { key, rawValue, nextIndex };
    if (nextIndex >= tokens.length) {
      this.pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end);
      return null;
    }
    return { key, rawValue: tokens[nextIndex], nextIndex: nextIndex + 1 };
  }

  parseAttributeValue(rawValue, start, end) {
    rawValue = stripSyntaxWhitespace(rawValue);
    if (rawValue.startsWith("|") && rawValue.endsWith("|") && rawValue.length >= 2) {
      const split = parseQuotedLiteral(rawValue);
      if (!split) {
        this.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end);
        return null;
      }
      if (split.rest !== "") {
        this.pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end);
        return null;
      }
      return { type: "literal", value: split.value };
    }
    const split = splitUnquotedLiteral(rawValue);
    if (!split || split.rest !== "") {
      this.pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end);
      return null;
    }
    return { type: "literal", value: split.value };
  }

  parseMarkupContent(content, start, end) {
    let kind;
    let rest;
    if (content.startsWith("#")) {
      const trimmed = stripTrailingSyntaxWhitespace(content.slice(1));
      if (trimmed.endsWith("/")) {
        kind = "standalone";
        rest = stripTrailingSyntaxWhitespace(trimmed.slice(0, -1));
      } else {
        kind = "open";
        rest = trimmed;
      }
    } else {
      kind = "close";
      rest = stripSyntaxWhitespace(content.slice(1));
    }
    const split = splitIdentifier(stripLeadingSyntaxWhitespace(rest));
    if (split.name === "") {
      this.pushDiagnostic("missing-markup-name", "Markup placeholder is missing a name.", start, end);
      return null;
    }
    if (stripSyntaxWhitespace(split.rest) === "") return markupModel(kind, split.name);
    const tail = this.parseMarkupTail(split.rest, start, end);
    return tail == null ? null : markupModel(kind, split.name, tail.options, tail.attributes);
  }

  parseMarkupTail(rest, start, end) {
    const tokens = this.splitTailTokens(rest, start, end);
    if (!tokens) return null;
    const options = {};
    const attributes = {};
    let seenAttribute = false;
    let index = 0;
    while (index < tokens.length) {
      const token = tokens[index];
      if (token.startsWith("@")) {
        seenAttribute = true;
        const attribute = this.parseAttributeTokens(tokens, index, start, end);
        if (!attribute) return null;
        index = attribute.nextIndex;
        if (Object.hasOwn(attributes, attribute.name)) {
          this.pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end);
          return null;
        }
        attributes[attribute.name] = attribute.value;
        continue;
      }
      if (seenAttribute) {
        this.pushDiagnostic("unsupported-markup", "Markup options must come before attributes.", start, end);
        return null;
      }
      if (token.startsWith(":")) {
        this.pushDiagnostic("unsupported-markup", "Markup placeholders do not support function annotations.", start, end);
        return null;
      }
      const option = this.parseOptionTokens(tokens, index, start, end);
      if (!option) return null;
      index = option.nextIndex;
      if (Object.hasOwn(options, option.name)) {
        this.pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end);
        return null;
      }
      options[option.name] = option.value;
    }
    return { options: omitEmpty(options), attributes: omitEmpty(attributes) };
  }

  parseVariableName() {
    const start = this.index;
    if (this.peekCodePoint() !== code("$")) {
      this.pushDiagnostic("missing-variable", "Expected a variable starting with '$'.", start, start);
      return null;
    }
    this.advanceCodePoint();
    const scan = scanName(this.source, this.index);
    if (scan.name === "") {
      this.pushDiagnostic(variableNameDiagnosticCode(this.source, this.index), "Variable is missing a name.", start, this.index);
      return null;
    }
    this.index = scan.endIndex;
    return scan.name;
  }

  skipSyntaxWhitespace() {
    const start = this.index;
    while (!this.isDone()) {
      const codePoint = this.peekCodePoint();
      if (!isSyntaxWhitespace(codePoint)) break;
      this.index += charCount(codePoint);
    }
    return this.index !== start;
  }

  skipSyntaxGap() {
    let sawWhitespace = false;
    while (!this.isDone()) {
      const codePoint = this.peekCodePoint();
      if (isWhitespace(codePoint)) {
        sawWhitespace = true;
        this.index += charCount(codePoint);
        continue;
      }
      if (isBidiMarker(codePoint)) {
        this.index += charCount(codePoint);
        continue;
      }
      break;
    }
    return sawWhitespace;
  }

  takeWhile(predicate) {
    const start = this.index;
    while (!this.isDone() && predicate(this.peekCodePoint())) this.advanceCodePoint();
    return this.source.slice(start, this.index);
  }

  startsWith(expected) {
    return this.source.startsWith(expected, this.index);
  }

  consumeString(expected) {
    if (!this.startsWith(expected)) return false;
    this.index += expected.length;
    return true;
  }

  isDone() {
    return this.index >= this.source.length;
  }

  peekCodePoint() {
    return this.source.codePointAt(this.index);
  }

  advanceCodePoint() {
    const codePoint = this.peekCodePoint();
    const value = String.fromCodePoint(codePoint);
    this.index += charCount(codePoint);
    return value;
  }

  pushDiagnostic(code, message, start, end) {
    this.diagnostics.push({ code, message, start: this.baseOffset + start, end: this.baseOffset + end, severity: "error" });
  }
}

function expressionModel(arg, functionRef = null, attributes = null) {
  const output = { type: "expression" };
  if (arg != null) output.arg = arg;
  if (functionRef != null) output.function = functionRef;
  if (attributes && Object.keys(attributes).length > 0) output.attributes = sortKeys(attributes);
  return output;
}

function functionModel(name, options) {
  const output = { type: "function", name };
  if (options && Object.keys(options).length > 0) output.options = sortKeys(options);
  return output;
}

function markupModel(kind, name, options = null, attributes = null) {
  const output = { type: "markup", kind, name };
  if (options && Object.keys(options).length > 0) output.options = sortKeys(options);
  if (attributes && Object.keys(attributes).length > 0) output.attributes = sortKeys(attributes);
  return output;
}

function parseLiteralOrVariable(rawValue) {
  if (rawValue.startsWith("$")) {
    const split = splitName(rawValue.slice(1));
    if (split.name !== "" && split.rest === "") return { type: "variable", name: split.name };
    return { type: "variable", name: rawValue.slice(1) };
  }
  const quoted = parseQuotedLiteral(rawValue);
  if (quoted && quoted.rest === "") return { type: "literal", value: quoted.value };
  return { type: "literal", value: rawValue };
}

function parseQuotedLiteral(input) {
  if (!input.startsWith("|")) return null;
  let output = "";
  for (let index = 1; index < input.length; ) {
    const codePoint = input.codePointAt(index);
    index += charCount(codePoint);
    if (codePoint === code("|")) return { value: output, rest: input.slice(index) };
    if (codePoint === code("\\")) {
      if (index >= input.length) {
        output += "\\";
        break;
      }
      const escaped = input.codePointAt(index);
      if ([code("\\"), code("{"), code("|"), code("}")].includes(escaped)) {
        output += String.fromCodePoint(escaped);
        index += charCount(escaped);
      } else {
        return null;
      }
    } else {
      output += String.fromCodePoint(codePoint);
    }
  }
  return null;
}

function splitUnquotedLiteral(input) {
  let scan = 0;
  let sawChar = false;
  while (scan < input.length) {
    const codePoint = input.codePointAt(scan);
    if (isSyntaxWhitespace(codePoint) || codePoint === code(":") || codePoint === code("@")) break;
    if (!isUnquotedLiteralChar(codePoint)) return null;
    sawChar = true;
    scan += charCount(codePoint);
  }
  return sawChar ? { value: input.slice(0, scan), rest: input.slice(scan) } : null;
}

function isUnquotedLiteralChar(codePoint) {
  if (isControl(codePoint) || isSyntaxWhitespace(codePoint) || isNoncharacter(codePoint)) return false;
  return !["^", "!", "%", "*", "<", ">", "?", "~", "&", "\\", "$"].includes(String.fromCodePoint(codePoint));
}

function variableNameDiagnosticCode(input, offset = 0) {
  if (offset >= input.length) return "missing-variable-name";
  return ["}", " ", "\t", "\n", "\r"].includes(input[offset]) ? "missing-variable-name" : "invalid-variable-name";
}

function splitName(input) {
  const scan = scanName(input, 0);
  return { name: scan.name, rest: input.slice(scan.endIndex), consumedLength: scan.endIndex };
}

function scanName(input, offset) {
  let scan = offset;
  if (scan < input.length && isBidiMarker(input.codePointAt(scan))) scan += charCount(input.codePointAt(scan));
  const nameStart = scan;
  if (nameStart >= input.length) return { name: "", endIndex: offset };
  const first = input.codePointAt(nameStart);
  if (first <= 0x7f) {
    const ascii = scanAsciiName(input, offset, nameStart);
    if (ascii) return ascii;
  }
  if (!isNameStart(first)) return { name: "", endIndex: offset };
  scan += charCount(first);
  while (scan < input.length) {
    const codePoint = input.codePointAt(scan);
    if (!isNameChar(codePoint)) break;
    scan += charCount(codePoint);
  }
  const nameEnd = scan;
  if (scan < input.length && isBidiMarker(input.codePointAt(scan))) scan += charCount(input.codePointAt(scan));
  return { name: input.slice(nameStart, nameEnd), endIndex: scan };
}

function scanAsciiName(input, offset, nameStart) {
  const first = input.charCodeAt(nameStart);
  if (!isAsciiNameStart(first)) return { name: "", endIndex: offset };
  let scan = nameStart + 1;
  while (scan < input.length && isAsciiNameChar(input.charCodeAt(scan))) scan += 1;
  const nameEnd = scan;
  if (scan < input.length) {
    const codePoint = input.codePointAt(scan);
    if (codePoint > 0x7f) {
      if (!isBidiMarker(codePoint)) return null;
      return { name: input.slice(nameStart, nameEnd), endIndex: scan + charCount(codePoint) };
    }
  }
  return { name: input.slice(nameStart, nameEnd), endIndex: scan };
}

function splitIdentifier(input) {
  const namespaceOrName = splitName(input);
  if (namespaceOrName.name === "") return { name: "", rest: input, consumedLength: 0 };
  if (!namespaceOrName.rest.startsWith(":")) return namespaceOrName;
  const name = splitName(namespaceOrName.rest.slice(1));
  if (name.name === "") return namespaceOrName;
  return {
    name: `${namespaceOrName.name}:${name.name}`,
    rest: name.rest,
    consumedLength: namespaceOrName.consumedLength + 1 + name.consumedLength,
  };
}

function isNameStart(codePoint) {
  if (codePoint <= 0x7f) return isAsciiNameStart(codePoint);
  return codePoint >= 0xa1 && codePoint <= 0x10fffd && !isBidiMarker(codePoint) && !isControl(codePoint) && !isSurrogate(codePoint) && !isSyntaxWhitespace(codePoint) && !isUnicodeWhitespace(codePoint) && !isNoncharacter(codePoint);
}

function isNameChar(codePoint) {
  return isNameStart(codePoint) || (codePoint >= code("0") && codePoint <= code("9")) || MARK_RE.test(String.fromCodePoint(codePoint)) || codePoint === code("-") || codePoint === code(".");
}

function isAsciiNameStart(codePoint) {
  return (codePoint >= code("a") && codePoint <= code("z")) || (codePoint >= code("A") && codePoint <= code("Z")) || codePoint === code("+") || codePoint === code("_");
}

function isAsciiNameChar(codePoint) {
  return isAsciiNameStart(codePoint) || (codePoint >= code("0") && codePoint <= code("9")) || codePoint === code("-") || codePoint === code(".");
}

function isBidiMarker(codePoint) {
  return BIDI_MARKERS.has(codePoint);
}

function isSyntaxWhitespace(codePoint) {
  return isWhitespace(codePoint) || isBidiMarker(codePoint);
}

function isWhitespace(codePoint) {
  return codePoint === 0x09 || codePoint === 0x0a || codePoint === 0x0d || codePoint === 0x20 || codePoint === 0x3000;
}

function isUnicodeWhitespace(codePoint) {
  return /\s/u.test(String.fromCodePoint(codePoint));
}

function isControl(codePoint) {
  return (codePoint >= 0 && codePoint <= 0x1f) || (codePoint >= 0x7f && codePoint <= 0x9f);
}

function isSurrogate(codePoint) {
  return codePoint >= 0xd800 && codePoint <= 0xdfff;
}

function isNoncharacter(codePoint) {
  return (codePoint >= 0xfdd0 && codePoint <= 0xfdef) || (codePoint & 0xfffe) === 0xfffe;
}

function stripSyntaxWhitespace(value) {
  return stripTrailingSyntaxWhitespace(stripLeadingSyntaxWhitespace(value));
}

function stripLeadingSyntaxWhitespace(value) {
  let start = 0;
  while (start < value.length) {
    const codePoint = value.codePointAt(start);
    if (!isSyntaxWhitespace(codePoint)) break;
    start += charCount(codePoint);
  }
  return value.slice(start);
}

function stripTrailingSyntaxWhitespace(value) {
  let end = value.length;
  while (end > 0) {
    const index = previousIndex(value, end);
    const codePoint = value.codePointAt(index);
    if (!isSyntaxWhitespace(codePoint)) break;
    end = index;
  }
  return value.slice(0, end);
}

function previousIndex(value, end) {
  const before = value.charCodeAt(end - 1);
  return before >= 0xdc00 && before <= 0xdfff && end >= 2 ? end - 2 : end - 1;
}

function charCount(codePoint) {
  return codePoint > 0xffff ? 2 : 1;
}

function code(value) {
  return value.codePointAt(0);
}

function omitEmpty(value) {
  return Object.keys(value).length === 0 ? null : value;
}

function sortKeys(value) {
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
}
