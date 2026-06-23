package mf2

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

type Model map[string]any

type Diagnostic struct {
	Code     string `json:"code"`
	Message  string `json:"message"`
	Start    int    `json:"start"`
	End      int    `json:"end"`
	Severity string `json:"severity"`
}

type ParseResult struct {
	Model          Model
	Diagnostics    []Diagnostic
	HasDiagnostics bool
}

var bidiMarkers = map[rune]bool{
	0x061c: true,
	0x200e: true,
	0x200f: true,
	0x2066: true,
	0x2067: true,
	0x2068: true,
	0x2069: true,
}

func ParseToModel(source string) ParseResult {
	parser := newParser(source, 0)
	model := parser.parseMessageModel()
	result := ParseResult{
		Diagnostics:    parser.diagnostics,
		HasDiagnostics: len(parser.diagnostics) > 0,
	}
	if !result.HasDiagnostics {
		result.Model = model
	}
	return result
}

type parser struct {
	source      string
	baseOffset  int
	index       int
	diagnostics []Diagnostic
}

func newParser(source string, baseOffset int) *parser {
	return &parser{source: source, baseOffset: baseOffset}
}

func (p *parser) parseMessageModel() Model {
	messageStart := p.index
	declarations := p.parseDeclarations()
	p.skipSyntaxWhitespace()
	if p.startsWith(".match") {
		return p.parseMatch(declarations)
	}
	if p.startsWith("{{") {
		pattern := p.parseQuotedPattern()
		if pattern == nil {
			return nil
		}
		p.skipSyntaxWhitespace()
		if !p.isDone() {
			p.pushDiagnostic("trailing-content", "Unexpected content after complex message body.", p.index, len(p.source))
		}
		return Model{"type": "message", "declarations": declarations, "pattern": pattern}
	}
	if len(declarations) > 0 {
		p.pushDiagnostic("missing-complex-body", "Complex message declarations must be followed by a quoted pattern or matcher.", p.index, len(p.source))
		return nil
	}
	if p.startsWith(".") {
		p.pushDiagnostic("invalid-simple-start", "Simple messages cannot start with '.'.", p.index, p.index+1)
		return nil
	}
	p.index = messageStart
	return Model{"type": "message", "declarations": declarations, "pattern": p.parsePatternUntilEnd()}
}

func (p *parser) parseDeclarations() []any {
	declarations := []any{}
	for {
		beforePadding := p.index
		p.skipSyntaxWhitespace()
		if p.startsWith(".input") {
			if declaration := p.parseInputDeclaration(); declaration != nil {
				declarations = append(declarations, declaration)
			}
			continue
		}
		if p.startsWith(".local") {
			if declaration := p.parseLocalDeclaration(); declaration != nil {
				declarations = append(declarations, declaration)
			}
			continue
		}
		p.index = beforePadding
		return declarations
	}
}

func (p *parser) parseInputDeclaration() map[string]any {
	p.consumeString(".input")
	p.skipSyntaxWhitespace()
	start := p.index
	value := p.parseExpressionPlaceholder()
	if value == nil {
		return nil
	}
	if arg, ok := objectField(value, "arg"); ok && stringField(arg, "type") == "variable" {
		return map[string]any{"type": "input", "name": stringField(arg, "name"), "value": value}
	}
	p.pushDiagnostic("invalid-input-declaration", ".input declarations must reference a variable expression.", start, p.index)
	return nil
}

func (p *parser) parseLocalDeclaration() map[string]any {
	p.consumeString(".local")
	p.skipSyntaxWhitespace()
	start := p.index
	name, ok := p.parseVariableName()
	if !ok {
		return nil
	}
	p.skipSyntaxWhitespace()
	if p.peekRune() != '=' {
		p.pushDiagnostic("missing-local-equals", ".local declarations must include '='.", start, p.index)
		return nil
	}
	p.advanceRune()
	p.skipSyntaxWhitespace()
	value := p.parseExpressionPlaceholder()
	if value == nil {
		return nil
	}
	return map[string]any{"type": "local", "name": name, "value": value}
}

func (p *parser) parseMatch(declarations []any) Model {
	p.consumeString(".match")
	var selectors []any
	if !p.isDone() && p.peekRune() == '$' {
		p.pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", p.index, p.index)
		return nil
	}
	for {
		skippedSpace := p.skipSyntaxGap()
		if !p.isDone() && p.peekRune() == '$' {
			if !skippedSpace && len(selectors) > 0 {
				p.pushDiagnostic("missing-match-space", ".match selectors must be separated by whitespace.", p.index, p.index)
				return nil
			}
			name, ok := p.parseVariableName()
			if ok {
				selectors = append(selectors, map[string]any{"type": "variable", "name": name})
			}
			if !p.isDone() && !isWhitespace(p.peekRune()) {
				p.pushDiagnostic("missing-match-space", ".match selectors must be separated from variants by whitespace.", p.index, p.index)
				return nil
			}
			continue
		}
		break
	}
	if len(selectors) == 0 {
		p.pushDiagnostic("missing-match-selector", ".match must include at least one selector variable.", p.index, p.index)
		return nil
	}
	var variants []any
	for {
		p.skipSyntaxWhitespace()
		if p.isDone() {
			break
		}
		variantStart := p.index
		keys, ok := p.parseVariantKeys(variantStart)
		if !ok {
			return nil
		}
		p.skipSyntaxWhitespace()
		if !p.startsWith("{{") {
			p.pushDiagnostic("missing-variant-pattern", "Variant keys must be followed by a quoted pattern.", variantStart, p.index)
			return nil
		}
		value := p.parseQuotedPattern()
		if value == nil {
			return nil
		}
		if len(keys) != len(selectors) {
			p.pushDiagnostic("variant-key-count-mismatch", "Variant key count must match selector count.", variantStart, p.index)
			return nil
		}
		variants = append(variants, map[string]any{"keys": keys, "value": value})
	}
	if len(variants) == 0 {
		p.pushDiagnostic("missing-match-variants", ".match must include at least one variant.", p.index, p.index)
		return nil
	}
	return Model{"type": "select", "declarations": declarations, "selectors": selectors, "variants": variants}
}

func (p *parser) parseVariantKeys(start int) ([]any, bool) {
	var keys []any
	for !p.isDone() && !p.startsWith("{{") && p.peekRune() != '\n' {
		skippedSpace := p.skipSyntaxGap()
		if p.startsWith("{{") || p.isDone() || p.peekRune() == '\n' {
			break
		}
		if len(keys) > 0 && !skippedSpace {
			p.pushDiagnostic("missing-variant-key-space", "Variant keys must be separated by whitespace.", start, p.index)
			return nil, false
		}
		if p.peekRune() == '*' {
			p.advanceRune()
			keys = append(keys, map[string]any{"type": "*"})
			continue
		}
		if p.peekRune() == '|' {
			split, ok := parseQuotedLiteral(p.source[p.index:])
			if !ok {
				p.pushDiagnostic("unclosed-quoted-literal", "Quoted variant key is missing closing '|'.", p.index, len(p.source))
				return nil, false
			}
			p.index += len(p.source[p.index:]) - len(split.rest)
			keys = append(keys, map[string]any{"type": "literal", "value": split.value})
			continue
		}
		key := p.takeWhile(func(r rune) bool { return !isSyntaxWhitespace(r) && r != '{' })
		if key != "" {
			keys = append(keys, map[string]any{"type": "literal", "value": key})
		}
	}
	return keys, true
}

func (p *parser) parseQuotedPattern() []any {
	start := p.index
	if !p.consumeString("{{") {
		p.pushDiagnostic("missing-quoted-pattern", "Expected a quoted pattern starting with '{{'.", start, start)
		return nil
	}
	contentStart := p.index
	scan := p.index
	placeholderDepth := 0
	inQuote := false
	for scan < len(p.source) {
		if placeholderDepth == 0 && strings.HasPrefix(p.source[scan:], "}}") {
			content := p.source[contentStart:scan]
			p.index = scan + 2
			nested := newParser(content, p.baseOffset+contentStart)
			pattern := nested.parsePatternUntilEnd()
			p.diagnostics = append(p.diagnostics, nested.diagnostics...)
			return pattern
		}
		r, size := utf8.DecodeRuneInString(p.source[scan:])
		if r == '\\' {
			scan += size
			if scan < len(p.source) {
				_, escapedSize := utf8.DecodeRuneInString(p.source[scan:])
				scan += escapedSize
			}
			continue
		}
		if placeholderDepth > 0 && r == '|' {
			inQuote = !inQuote
		} else if !inQuote && r == '{' {
			placeholderDepth++
		} else if !inQuote && r == '}' && placeholderDepth > 0 {
			placeholderDepth--
		}
		scan += size
	}
	p.pushDiagnostic("unclosed-quoted-pattern", "Quoted pattern is missing closing '}}'.", start, len(p.source))
	return nil
}

func (p *parser) parsePatternUntilEnd() []any {
	parts := []any{}
	var text strings.Builder
	for !p.isDone() {
		r := p.peekRune()
		switch r {
		case '\\':
			text.WriteString(p.parseEscape())
		case '{':
			if text.Len() > 0 {
				parts = append(parts, text.String())
				text.Reset()
			}
			if part := p.parseBracedPatternPart(); part != nil {
				parts = append(parts, part)
			}
		case '}':
			start := p.index
			p.advanceRune()
			p.pushDiagnostic("unescaped-closing-brace", "Closing brace must be escaped in text.", start, p.index)
		default:
			text.WriteString(p.advanceRune())
		}
	}
	if text.Len() > 0 {
		parts = append(parts, text.String())
	}
	return parts
}

func (p *parser) parseEscape() string {
	start := p.index
	p.advanceRune()
	if p.isDone() {
		p.pushDiagnostic("dangling-escape", "Backslash at end of message has no escaped character.", start, start+1)
		return ""
	}
	r := p.peekRune()
	if r == '{' || r == '}' || r == '|' || r == '\\' {
		return p.advanceRune()
	}
	return "\\"
}

func (p *parser) parseBracedPatternPart() map[string]any {
	start := p.index
	content, ok := p.consumeBracedContent()
	if !ok {
		return nil
	}
	trimmed := stripSyntaxWhitespace(content)
	if strings.HasPrefix(trimmed, "#") || strings.HasPrefix(trimmed, "/") {
		return p.parseMarkupContent(trimmed, start, start+len(content)+2)
	}
	return p.parseExpressionContent(trimmed, start, start+len(content)+2)
}

func (p *parser) parseExpressionPlaceholder() map[string]any {
	start := p.index
	content, ok := p.consumeBracedContent()
	if !ok {
		return nil
	}
	return p.parseExpressionContent(stripSyntaxWhitespace(content), start, start+len(content)+2)
}

func (p *parser) consumeBracedContent() (string, bool) {
	start := p.index
	if p.peekRune() != '{' {
		p.pushDiagnostic("missing-placeholder", "Expected a placeholder starting with '{'.", start, start)
		return "", false
	}
	p.advanceRune()
	contentStart := p.index
	inQuote := false
	for !p.isDone() {
		r := p.peekRune()
		if inQuote {
			if r == '\\' {
				p.advanceRune()
				if !p.isDone() {
					p.advanceRune()
				}
				continue
			}
			if r == '}' {
				content := p.source[contentStart:p.index]
				p.advanceRune()
				return content, true
			}
			if r == '|' {
				inQuote = false
			}
			p.advanceRune()
			continue
		}
		if r == '|' {
			inQuote = true
			p.advanceRune()
			continue
		}
		if r == '}' {
			content := p.source[contentStart:p.index]
			p.advanceRune()
			return content, true
		}
		p.advanceRune()
	}
	p.pushDiagnostic("unclosed-placeholder", "Placeholder is missing a closing brace.", start, len(p.source))
	return "", false
}

func (p *parser) parseExpressionContent(content string, start, end int) map[string]any {
	var expression map[string]any
	var rest string
	switch {
	case strings.HasPrefix(content, "$"):
		split := splitName(content[1:])
		if split.name == "" {
			p.pushDiagnostic(variableNameDiagnosticCode(content[1:], 0), "Variable placeholder is missing a name.", start, end)
			return nil
		}
		expression = expressionModel(map[string]any{"type": "variable", "name": split.name}, nil, nil)
		var ok bool
		rest, ok = p.restAfterOperand(split.rest, start, end)
		if !ok {
			return nil
		}
	case strings.HasPrefix(content, "|"):
		split, ok := parseQuotedLiteral(content)
		if !ok {
			p.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
			return nil
		}
		expression = expressionModel(map[string]any{"type": "literal", "value": split.value}, nil, nil)
		rest, ok = p.restAfterOperand(split.rest, start, end)
		if !ok {
			return nil
		}
	case strings.HasPrefix(content, ":"):
		expression = expressionModel(nil, nil, nil)
		rest = content
	default:
		split, ok := splitUnquotedLiteral(content)
		if !ok {
			code := "invalid-literal"
			if content == "" {
				code = "missing-expression"
			}
			p.pushDiagnostic(code, "Placeholder literal is invalid.", start, end)
			return nil
		}
		expression = expressionModel(map[string]any{"type": "literal", "value": split.value}, nil, nil)
		rest, ok = p.restAfterOperand(split.rest, start, end)
		if !ok {
			return nil
		}
	}
	if rest == "" {
		return expression
	}
	tail, ok := p.parseTail(rest, start, end)
	if !ok {
		return nil
	}
	arg, _ := objectField(expression, "arg")
	return expressionModel(arg, tail.function, tail.attributes)
}

func (p *parser) restAfterOperand(rest string, start, end int) (string, bool) {
	if rest == "" {
		return rest, true
	}
	r, _ := utf8.DecodeRuneInString(rest)
	if !isWhitespace(r) {
		p.pushDiagnostic("missing-expression-space", "Expression arguments must be separated from functions or attributes by whitespace.", start, end)
		return "", false
	}
	return stripLeadingSyntaxWhitespace(rest), true
}

type tailResult struct {
	function   map[string]any
	attributes map[string]any
	options    map[string]any
}

func (p *parser) parseTail(rest string, start, end int) (tailResult, bool) {
	if strings.TrimSpace(rest) == "" {
		return tailResult{}, true
	}
	tokens, ok := p.splitTailTokens(rest, start, end)
	if !ok {
		return tailResult{}, false
	}
	index := 0
	var functionRef map[string]any
	attributes := map[string]any{}
	if index < len(tokens) && strings.HasPrefix(tokens[index], ":") {
		result, next, ok := p.parseFunctionAnnotation(tokens, index, start, end)
		if !ok {
			return tailResult{}, false
		}
		functionRef = result
		index = next
	}
	for index < len(tokens) {
		token := tokens[index]
		if !strings.HasPrefix(token, "@") {
			p.pushDiagnostic("unsupported-expression", "Expression content after the argument must be a function annotation or attribute.", start, end)
			return tailResult{}, false
		}
		name, value, next, ok := p.parseAttributeTokens(tokens, index, start, end)
		if !ok {
			return tailResult{}, false
		}
		index = next
		if _, exists := attributes[name]; exists {
			p.pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end)
			return tailResult{}, false
		}
		attributes[name] = value
	}
	return tailResult{function: functionRef, attributes: omitEmptyMap(attributes)}, true
}

func (p *parser) splitTailTokens(rest string, start, end int) ([]string, bool) {
	var tokens []string
	tokenStart := -1
	inQuote := false
	for index := 0; index < len(rest); {
		r, size := utf8.DecodeRuneInString(rest[index:])
		if inQuote && r == '\\' {
			if tokenStart < 0 {
				tokenStart = index
			}
			index += size
			if index < len(rest) {
				_, escapedSize := utf8.DecodeRuneInString(rest[index:])
				index += escapedSize
			}
			continue
		}
		if r == '|' {
			inQuote = !inQuote
			if tokenStart < 0 {
				tokenStart = index
			}
			index += size
			continue
		}
		if isSyntaxWhitespace(r) && !inQuote {
			if tokenStart >= 0 {
				tokens = append(tokens, rest[tokenStart:index])
				tokenStart = -1
			}
			index += size
			continue
		}
		if tokenStart < 0 {
			tokenStart = index
		}
		index += size
	}
	if inQuote {
		p.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
		return nil, false
	}
	if tokenStart >= 0 {
		tokens = append(tokens, rest[tokenStart:])
	}
	return tokens, true
}

func (p *parser) parseFunctionAnnotation(tokens []string, index, start, end int) (map[string]any, int, bool) {
	content := tokens[index][1:]
	split := splitIdentifier(content)
	if split.name == "" {
		code := "invalid-function-name"
		if content == "" {
			code = "missing-function-name"
		}
		p.pushDiagnostic(code, "Function annotation is missing a name.", start, end)
		return nil, index, false
	}
	if split.rest != "" {
		p.pushDiagnostic("unsupported-expression", "Function annotation must separate options with whitespace.", start, end)
		return nil, index, false
	}
	options := map[string]any{}
	index++
	for index < len(tokens) && !strings.HasPrefix(tokens[index], "@") {
		name, value, next, ok := p.parseOptionTokens(tokens, index, start, end)
		if !ok {
			return nil, index, false
		}
		index = next
		if _, exists := options[name]; exists {
			p.pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end)
			return nil, index, false
		}
		options[name] = value
	}
	return functionModel(split.name, omitEmptyMap(options)), index, true
}

func (p *parser) parseOptionTokens(tokens []string, index, start, end int) (string, any, int, bool) {
	assignment, ok := p.parseRequiredAssignment(tokens, index, start, end)
	if !ok {
		return "", nil, index, false
	}
	keySplit := splitIdentifier(assignment.key)
	if keySplit.name == "" || keySplit.rest != "" {
		p.pushDiagnostic("invalid-function-option", "Option key must be a valid identifier.", start, end)
		return "", nil, index, false
	}
	value, ok := p.parseOptionValue(assignment.rawValue, start, end)
	if !ok {
		return "", nil, index, false
	}
	return keySplit.name, value, assignment.nextIndex, true
}

func (p *parser) parseOptionValue(rawValue string, start, end int) (any, bool) {
	rawValue = stripSyntaxWhitespace(rawValue)
	if strings.HasPrefix(rawValue, "|") {
		split, ok := parseQuotedLiteral(rawValue)
		if !ok {
			p.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
			return nil, false
		}
		if split.rest != "" {
			p.pushDiagnostic("invalid-function-option", "Option value must be a single literal or variable.", start, end)
			return nil, false
		}
		return map[string]any{"type": "literal", "value": split.value}, true
	}
	if strings.HasPrefix(rawValue, "$") {
		split := splitName(rawValue[1:])
		if split.name != "" && split.rest == "" {
			return map[string]any{"type": "variable", "name": split.name}, true
		}
		p.pushDiagnostic(variableNameDiagnosticCode(rawValue[1:], 0), "Option variable value must be a valid variable name.", start, end)
		return nil, false
	}
	return parseLiteralOrVariable(rawValue), true
}

type assignmentParts struct {
	key       string
	rawValue  string
	nextIndex int
}

func (p *parser) parseRequiredAssignment(tokens []string, index, start, end int) (assignmentParts, bool) {
	token := tokens[index]
	if equals := strings.Index(token, "="); equals >= 0 {
		return p.finishAssignment(token[:equals], token[equals+1:], tokens, index+1, start, end)
	}
	if index+1 >= len(tokens) || !strings.HasPrefix(tokens[index+1], "=") {
		p.pushDiagnostic("invalid-function-option", "Options must use key=value syntax.", start, end)
		return assignmentParts{}, false
	}
	return p.finishAssignment(token, tokens[index+1][1:], tokens, index+2, start, end)
}

func (p *parser) finishAssignment(key, rawValue string, tokens []string, nextIndex, start, end int) (assignmentParts, bool) {
	if key == "" {
		p.pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
		return assignmentParts{}, false
	}
	if rawValue != "" {
		return assignmentParts{key: key, rawValue: rawValue, nextIndex: nextIndex}, true
	}
	if nextIndex >= len(tokens) {
		p.pushDiagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
		return assignmentParts{}, false
	}
	return assignmentParts{key: key, rawValue: tokens[nextIndex], nextIndex: nextIndex + 1}, true
}

func (p *parser) parseAttributeTokens(tokens []string, index, start, end int) (string, any, int, bool) {
	token := tokens[index]
	content := token[1:]
	if content == "" {
		p.pushDiagnostic("missing-attribute-name", "Attribute is missing a name.", start, end)
		return "", nil, index, false
	}
	if !p.hasAttributeAssignment(content, tokens, index) {
		split := splitIdentifier(content)
		if split.name == "" || split.rest != "" {
			p.pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
			return "", nil, index, false
		}
		return split.name, true, index + 1, true
	}
	assignment, ok := p.parseAttributeAssignment(content, tokens, index, start, end)
	if !ok {
		return "", nil, index, false
	}
	value, ok := p.parseAttributeValue(assignment.rawValue, start, end)
	if !ok {
		return "", nil, index, false
	}
	return assignment.key, value, assignment.nextIndex, true
}

func (p *parser) hasAttributeAssignment(content string, tokens []string, index int) bool {
	return strings.Contains(content, "=") || (index+1 < len(tokens) && strings.HasPrefix(tokens[index+1], "="))
}

func (p *parser) parseAttributeAssignment(content string, tokens []string, index, start, end int) (assignmentParts, bool) {
	assignment, ok := p.attributeAssignmentParts(content, tokens, index, start, end)
	if !ok {
		return assignmentParts{}, false
	}
	split := splitIdentifier(assignment.key)
	if split.name == "" || split.rest != "" {
		p.pushDiagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
		return assignmentParts{}, false
	}
	assignment.key = split.name
	return assignment, true
}

func (p *parser) attributeAssignmentParts(content string, tokens []string, index, start, end int) (assignmentParts, bool) {
	if equals := strings.Index(content, "="); equals >= 0 {
		return p.finishAttributeAssignment(content[:equals], content[equals+1:], tokens, index+1, start, end)
	}
	if index+1 >= len(tokens) || !strings.HasPrefix(tokens[index+1], "=") {
		return assignmentParts{}, false
	}
	return p.finishAttributeAssignment(content, tokens[index+1][1:], tokens, index+2, start, end)
}

func (p *parser) finishAttributeAssignment(key, rawValue string, tokens []string, nextIndex, start, end int) (assignmentParts, bool) {
	if key == "" {
		p.pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
		return assignmentParts{}, false
	}
	if rawValue != "" {
		return assignmentParts{key: key, rawValue: rawValue, nextIndex: nextIndex}, true
	}
	if nextIndex >= len(tokens) {
		p.pushDiagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
		return assignmentParts{}, false
	}
	return assignmentParts{key: key, rawValue: tokens[nextIndex], nextIndex: nextIndex + 1}, true
}

func (p *parser) parseAttributeValue(rawValue string, start, end int) (any, bool) {
	rawValue = stripSyntaxWhitespace(rawValue)
	if strings.HasPrefix(rawValue, "|") && strings.HasSuffix(rawValue, "|") && len(rawValue) >= 2 {
		split, ok := parseQuotedLiteral(rawValue)
		if !ok {
			p.pushDiagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
			return nil, false
		}
		if split.rest != "" {
			p.pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
			return nil, false
		}
		return map[string]any{"type": "literal", "value": split.value}, true
	}
	split, ok := splitUnquotedLiteral(rawValue)
	if !ok || split.rest != "" {
		p.pushDiagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
		return nil, false
	}
	return map[string]any{"type": "literal", "value": split.value}, true
}

func (p *parser) parseMarkupContent(content string, start, end int) map[string]any {
	var kind string
	var rest string
	if strings.HasPrefix(content, "#") {
		trimmed := stripTrailingSyntaxWhitespace(content[1:])
		if strings.HasSuffix(trimmed, "/") {
			kind = "standalone"
			rest = stripTrailingSyntaxWhitespace(trimmed[:len(trimmed)-1])
		} else {
			kind = "open"
			rest = trimmed
		}
	} else {
		kind = "close"
		rest = stripSyntaxWhitespace(content[1:])
	}
	split := splitIdentifier(stripLeadingSyntaxWhitespace(rest))
	if split.name == "" {
		p.pushDiagnostic("missing-markup-name", "Markup placeholder is missing a name.", start, end)
		return nil
	}
	if stripSyntaxWhitespace(split.rest) == "" {
		return markupModel(kind, split.name, nil, nil)
	}
	tail, ok := p.parseMarkupTail(split.rest, start, end)
	if !ok {
		return nil
	}
	return markupModel(kind, split.name, tail.options, tail.attributes)
}

func (p *parser) parseMarkupTail(rest string, start, end int) (tailResult, bool) {
	tokens, ok := p.splitTailTokens(rest, start, end)
	if !ok {
		return tailResult{}, false
	}
	options := map[string]any{}
	attributes := map[string]any{}
	seenAttribute := false
	index := 0
	for index < len(tokens) {
		token := tokens[index]
		if strings.HasPrefix(token, "@") {
			seenAttribute = true
			name, value, next, ok := p.parseAttributeTokens(tokens, index, start, end)
			if !ok {
				return tailResult{}, false
			}
			index = next
			if _, exists := attributes[name]; exists {
				p.pushDiagnostic("duplicate-attribute-name", "Attribute names must be unique within an expression or markup placeholder.", start, end)
				return tailResult{}, false
			}
			attributes[name] = value
			continue
		}
		if seenAttribute {
			p.pushDiagnostic("unsupported-markup", "Markup options must come before attributes.", start, end)
			return tailResult{}, false
		}
		if strings.HasPrefix(token, ":") {
			p.pushDiagnostic("unsupported-markup", "Markup placeholders do not support function annotations.", start, end)
			return tailResult{}, false
		}
		name, value, next, ok := p.parseOptionTokens(tokens, index, start, end)
		if !ok {
			return tailResult{}, false
		}
		index = next
		if _, exists := options[name]; exists {
			p.pushDiagnostic("duplicate-option-name", "Option names must be unique within a function or markup placeholder.", start, end)
			return tailResult{}, false
		}
		options[name] = value
	}
	return tailResult{options: omitEmptyMap(options), attributes: omitEmptyMap(attributes)}, true
}

func (p *parser) parseVariableName() (string, bool) {
	start := p.index
	if p.peekRune() != '$' {
		p.pushDiagnostic("missing-variable", "Expected a variable starting with '$'.", start, start)
		return "", false
	}
	p.advanceRune()
	scan := scanName(p.source, p.index)
	if scan.name == "" {
		p.pushDiagnostic(variableNameDiagnosticCode(p.source, p.index), "Variable is missing a name.", start, p.index)
		return "", false
	}
	p.index = scan.endIndex
	return scan.name, true
}

func (p *parser) skipSyntaxWhitespace() bool {
	start := p.index
	for !p.isDone() {
		r := p.peekRune()
		if !isSyntaxWhitespace(r) {
			break
		}
		p.advanceRune()
	}
	return p.index != start
}

func (p *parser) skipSyntaxGap() bool {
	sawWhitespace := false
	for !p.isDone() {
		r := p.peekRune()
		if isWhitespace(r) {
			sawWhitespace = true
			p.advanceRune()
			continue
		}
		if isBidiMarker(r) {
			p.advanceRune()
			continue
		}
		break
	}
	return sawWhitespace
}

func (p *parser) takeWhile(predicate func(rune) bool) string {
	start := p.index
	for !p.isDone() && predicate(p.peekRune()) {
		p.advanceRune()
	}
	return p.source[start:p.index]
}

func (p *parser) startsWith(expected string) bool {
	return strings.HasPrefix(p.source[p.index:], expected)
}

func (p *parser) consumeString(expected string) bool {
	if !p.startsWith(expected) {
		return false
	}
	p.index += len(expected)
	return true
}

func (p *parser) isDone() bool {
	return p.index >= len(p.source)
}

func (p *parser) peekRune() rune {
	if p.isDone() {
		return -1
	}
	r, _ := utf8.DecodeRuneInString(p.source[p.index:])
	return r
}

func (p *parser) advanceRune() string {
	r, size := utf8.DecodeRuneInString(p.source[p.index:])
	p.index += size
	return string(r)
}

func (p *parser) pushDiagnostic(code, message string, start, end int) {
	p.diagnostics = append(p.diagnostics, Diagnostic{
		Code:     code,
		Message:  message,
		Start:    p.baseOffset + start,
		End:      p.baseOffset + end,
		Severity: "error",
	})
}

func expressionModel(arg map[string]any, functionRef map[string]any, attributes map[string]any) map[string]any {
	output := map[string]any{"type": "expression"}
	if arg != nil {
		output["arg"] = arg
	}
	if functionRef != nil {
		output["function"] = functionRef
	}
	if len(attributes) > 0 {
		output["attributes"] = attributes
	}
	return output
}

func functionModel(name string, options map[string]any) map[string]any {
	output := map[string]any{"type": "function", "name": name}
	if len(options) > 0 {
		output["options"] = options
	}
	return output
}

func markupModel(kind, name string, options map[string]any, attributes map[string]any) map[string]any {
	output := map[string]any{"type": "markup", "kind": kind, "name": name}
	if len(options) > 0 {
		output["options"] = options
	}
	if len(attributes) > 0 {
		output["attributes"] = attributes
	}
	return output
}

func parseLiteralOrVariable(rawValue string) map[string]any {
	if strings.HasPrefix(rawValue, "$") {
		split := splitName(rawValue[1:])
		if split.name != "" && split.rest == "" {
			return map[string]any{"type": "variable", "name": split.name}
		}
		return map[string]any{"type": "variable", "name": rawValue[1:]}
	}
	if quoted, ok := parseQuotedLiteral(rawValue); ok && quoted.rest == "" {
		return map[string]any{"type": "literal", "value": quoted.value}
	}
	return map[string]any{"type": "literal", "value": rawValue}
}

type literalSplit struct {
	value string
	rest  string
}

func parseQuotedLiteral(input string) (literalSplit, bool) {
	if !strings.HasPrefix(input, "|") {
		return literalSplit{}, false
	}
	var output strings.Builder
	for index := 1; index < len(input); {
		r, size := utf8.DecodeRuneInString(input[index:])
		index += size
		if r == '|' {
			return literalSplit{value: output.String(), rest: input[index:]}, true
		}
		if r == '\\' {
			if index >= len(input) {
				output.WriteRune('\\')
				break
			}
			escaped, escapedSize := utf8.DecodeRuneInString(input[index:])
			if escaped == '\\' || escaped == '{' || escaped == '|' || escaped == '}' {
				output.WriteRune(escaped)
				index += escapedSize
			} else {
				return literalSplit{}, false
			}
		} else {
			output.WriteRune(r)
		}
	}
	return literalSplit{}, false
}

func splitUnquotedLiteral(input string) (literalSplit, bool) {
	scan := 0
	sawChar := false
	for scan < len(input) {
		r, size := utf8.DecodeRuneInString(input[scan:])
		if isSyntaxWhitespace(r) || r == ':' || r == '@' {
			break
		}
		if !isUnquotedLiteralChar(r) {
			return literalSplit{}, false
		}
		sawChar = true
		scan += size
	}
	if !sawChar {
		return literalSplit{}, false
	}
	return literalSplit{value: input[:scan], rest: input[scan:]}, true
}

func isUnquotedLiteralChar(r rune) bool {
	if isControl(r) || isSyntaxWhitespace(r) || isNoncharacter(r) {
		return false
	}
	return !strings.ContainsRune("^!%*<>?~&\\$", r)
}

func variableNameDiagnosticCode(input string, offset int) string {
	if offset >= len(input) {
		return "missing-variable-name"
	}
	r, _ := utf8.DecodeRuneInString(input[offset:])
	if r == '}' || r == ' ' || r == '\t' || r == '\n' || r == '\r' {
		return "missing-variable-name"
	}
	return "invalid-variable-name"
}

type nameSplit struct {
	name          string
	rest          string
	consumedBytes int
	endIndex      int
}

func splitName(input string) nameSplit {
	scan := scanName(input, 0)
	return nameSplit{name: scan.name, rest: input[scan.endIndex:], consumedBytes: scan.endIndex, endIndex: scan.endIndex}
}

func scanName(input string, offset int) nameSplit {
	scan := offset
	if scan < len(input) {
		r, size := utf8.DecodeRuneInString(input[scan:])
		if isBidiMarker(r) {
			scan += size
		}
	}
	nameStart := scan
	if nameStart >= len(input) {
		return nameSplit{endIndex: offset}
	}
	first, size := utf8.DecodeRuneInString(input[nameStart:])
	if !isNameStart(first) {
		return nameSplit{endIndex: offset}
	}
	scan += size
	for scan < len(input) {
		r, size := utf8.DecodeRuneInString(input[scan:])
		if !isNameChar(r) {
			break
		}
		scan += size
	}
	nameEnd := scan
	if scan < len(input) {
		r, size := utf8.DecodeRuneInString(input[scan:])
		if isBidiMarker(r) {
			scan += size
		}
	}
	return nameSplit{name: input[nameStart:nameEnd], endIndex: scan}
}

func splitIdentifier(input string) nameSplit {
	namespaceOrName := splitName(input)
	if namespaceOrName.name == "" {
		return nameSplit{rest: input}
	}
	if !strings.HasPrefix(namespaceOrName.rest, ":") {
		return namespaceOrName
	}
	name := splitName(namespaceOrName.rest[1:])
	if name.name == "" {
		return namespaceOrName
	}
	return nameSplit{
		name:          namespaceOrName.name + ":" + name.name,
		rest:          name.rest,
		consumedBytes: namespaceOrName.consumedBytes + 1 + name.consumedBytes,
	}
}

func isNameStart(r rune) bool {
	if r <= 0x7f {
		return isASCIINameStart(r)
	}
	return r >= 0xa1 && r <= 0x10fffd && !isBidiMarker(r) && !isControl(r) && !isSurrogate(r) && !isSyntaxWhitespace(r) && !unicode.IsSpace(r) && !isNoncharacter(r)
}

func isNameChar(r rune) bool {
	return isNameStart(r) || (r >= '0' && r <= '9') || unicode.Is(unicode.M, r) || r == '-' || r == '.'
}

func isASCIINameStart(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '+' || r == '_'
}

func isBidiMarker(r rune) bool {
	return bidiMarkers[r]
}

func isSyntaxWhitespace(r rune) bool {
	return isWhitespace(r) || isBidiMarker(r)
}

func isWhitespace(r rune) bool {
	return r == '\t' || r == '\n' || r == '\r' || r == ' ' || r == '\u3000'
}

func isControl(r rune) bool {
	return (r >= 0 && r <= 0x1f) || (r >= 0x7f && r <= 0x9f)
}

func isSurrogate(r rune) bool {
	return r >= 0xd800 && r <= 0xdfff
}

func isNoncharacter(r rune) bool {
	return (r >= 0xfdd0 && r <= 0xfdef) || (r&0xfffe) == 0xfffe
}

func stripSyntaxWhitespace(value string) string {
	return stripTrailingSyntaxWhitespace(stripLeadingSyntaxWhitespace(value))
}

func stripLeadingSyntaxWhitespace(value string) string {
	start := 0
	for start < len(value) {
		r, size := utf8.DecodeRuneInString(value[start:])
		if !isSyntaxWhitespace(r) {
			break
		}
		start += size
	}
	return value[start:]
}

func stripTrailingSyntaxWhitespace(value string) string {
	end := len(value)
	for end > 0 {
		index := previousIndex(value, end)
		r, _ := utf8.DecodeRuneInString(value[index:])
		if !isSyntaxWhitespace(r) {
			break
		}
		end = index
	}
	return value[:end]
}

func previousIndex(value string, end int) int {
	_, size := utf8.DecodeLastRuneInString(value[:end])
	return end - size
}

func omitEmptyMap(value map[string]any) map[string]any {
	if len(value) == 0 {
		return nil
	}
	return value
}

func objectField(value map[string]any, name string) (map[string]any, bool) {
	raw, ok := value[name]
	if !ok {
		return nil, false
	}
	object, ok := raw.(map[string]any)
	return object, ok
}

func stringField(value map[string]any, name string) string {
	raw, _ := value[name].(string)
	return raw
}
