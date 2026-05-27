<?php

declare(strict_types=1);

namespace MF2;

const BIDI_MARKERS = [0x061c => true, 0x200e => true, 0x200f => true, 0x2066 => true, 0x2067 => true, 0x2068 => true, 0x2069 => true];

function parse_to_model(mixed $source): array
{
    $parser = new Parser((string) ($source ?? ''), 0);
    $model = $parser->parseMessageModel();
    return [
        'model' => count($parser->diagnostics) === 0 ? $model : null,
        'diagnostics' => $parser->diagnostics,
        'hasDiagnostics' => count($parser->diagnostics) > 0,
    ];
}

final class Parser
{
    public array $diagnostics = [];
    private int $index = 0;

    public function __construct(private string $source, private int $baseOffset)
    {
    }

    public function parseMessageModel(): ?array
    {
        $messageStart = $this->index;
        $declarations = $this->parseDeclarations();
        $this->skipSyntaxWhitespace();
        if ($this->startsWith('.match')) {
            return $this->parseMatch($declarations);
        }
        if ($this->startsWith('{{')) {
            $pattern = $this->parseQuotedPattern();
            if ($pattern === null) {
                return null;
            }
            $this->skipSyntaxWhitespace();
            if (!$this->isDone()) {
                $this->pushDiagnostic('trailing-content', 'Unexpected content after complex message body.', $this->index, strlen($this->source));
            }
            return ['type' => 'message', 'declarations' => $declarations, 'pattern' => $pattern];
        }
        if (count($declarations) > 0) {
            $this->pushDiagnostic('missing-complex-body', 'Complex message declarations must be followed by a quoted pattern or matcher.', $this->index, strlen($this->source));
            return null;
        }
        if ($this->startsWith('.')) {
            $this->pushDiagnostic('invalid-simple-start', "Simple messages cannot start with '.'.", $this->index, $this->index + 1);
            return null;
        }
        $this->index = $messageStart;
        return ['type' => 'message', 'declarations' => $declarations, 'pattern' => $this->parsePatternUntilEnd()];
    }

    private function parseDeclarations(): array
    {
        $declarations = [];
        while (true) {
            $beforePadding = $this->index;
            $this->skipSyntaxWhitespace();
            if ($this->startsWith('.input')) {
                $declaration = $this->parseInputDeclaration();
                if ($declaration !== null) {
                    $declarations[] = $declaration;
                }
                continue;
            }
            if ($this->startsWith('.local')) {
                $declaration = $this->parseLocalDeclaration();
                if ($declaration !== null) {
                    $declarations[] = $declaration;
                }
                continue;
            }
            $this->index = $beforePadding;
            return $declarations;
        }
    }

    private function parseInputDeclaration(): ?array
    {
        $this->consumeString('.input');
        $this->skipSyntaxWhitespace();
        $start = $this->index;
        $value = $this->parseExpressionPlaceholder();
        if ($value === null) {
            return null;
        }
        if (($value['arg']['type'] ?? null) === 'variable') {
            return ['type' => 'input', 'name' => $value['arg']['name'], 'value' => $value];
        }
        $this->pushDiagnostic('invalid-input-declaration', '.input declarations must reference a variable expression.', $start, $this->index);
        return null;
    }

    private function parseLocalDeclaration(): ?array
    {
        $this->consumeString('.local');
        $this->skipSyntaxWhitespace();
        $start = $this->index;
        $name = $this->parseVariableName();
        if ($name === null) {
            return null;
        }
        $this->skipSyntaxWhitespace();
        if ($this->peekCodePoint() !== codepoint('=')) {
            $this->pushDiagnostic('missing-local-equals', ".local declarations must include '='.", $start, $this->index);
            return null;
        }
        $this->advanceCodePoint();
        $this->skipSyntaxWhitespace();
        $value = $this->parseExpressionPlaceholder();
        return $value === null ? null : ['type' => 'local', 'name' => $name, 'value' => $value];
    }

    private function parseMatch(array $declarations): ?array
    {
        $this->consumeString('.match');
        $selectors = [];
        if (!$this->isDone() && $this->peekCodePoint() === codepoint('$')) {
            $this->pushDiagnostic('missing-match-space', '.match selectors must be separated by whitespace.', $this->index, $this->index);
            return null;
        }
        while (true) {
            $skippedSpace = $this->skipSyntaxGap();
            if (!$this->isDone() && $this->peekCodePoint() === codepoint('$')) {
                if (!$skippedSpace && count($selectors) > 0) {
                    $this->pushDiagnostic('missing-match-space', '.match selectors must be separated by whitespace.', $this->index, $this->index);
                    return null;
                }
                $name = $this->parseVariableName();
                if ($name !== null) {
                    $selectors[] = ['type' => 'variable', 'name' => $name];
                }
                if (!$this->isDone() && !is_whitespace_cp($this->peekCodePoint())) {
                    $this->pushDiagnostic('missing-match-space', '.match selectors must be separated from variants by whitespace.', $this->index, $this->index);
                    return null;
                }
                continue;
            }
            break;
        }
        if (count($selectors) === 0) {
            $this->pushDiagnostic('missing-match-selector', '.match must include at least one selector variable.', $this->index, $this->index);
            return null;
        }
        $variants = [];
        while (true) {
            $this->skipSyntaxWhitespace();
            if ($this->isDone()) {
                break;
            }
            $variantStart = $this->index;
            $keys = $this->parseVariantKeys($variantStart);
            if ($keys === null) {
                return null;
            }
            $this->skipSyntaxWhitespace();
            if (!$this->startsWith('{{')) {
                $this->pushDiagnostic('missing-variant-pattern', 'Variant keys must be followed by a quoted pattern.', $variantStart, $this->index);
                return null;
            }
            $value = $this->parseQuotedPattern();
            if ($value === null) {
                return null;
            }
            if (count($keys) !== count($selectors)) {
                $this->pushDiagnostic('variant-key-count-mismatch', 'Variant key count must match selector count.', $variantStart, $this->index);
                return null;
            }
            $variants[] = ['keys' => $keys, 'value' => $value];
        }
        if (count($variants) === 0) {
            $this->pushDiagnostic('missing-match-variants', '.match must include at least one variant.', $this->index, $this->index);
            return null;
        }
        return ['type' => 'select', 'declarations' => $declarations, 'selectors' => $selectors, 'variants' => $variants];
    }

    private function parseVariantKeys(int $start): ?array
    {
        $keys = [];
        while (!$this->isDone() && !$this->startsWith('{{') && $this->peekCodePoint() !== codepoint("\n")) {
            $skippedSpace = $this->skipSyntaxGap();
            if ($this->startsWith('{{') || $this->isDone() || $this->peekCodePoint() === codepoint("\n")) {
                break;
            }
            if (count($keys) > 0 && !$skippedSpace) {
                $this->pushDiagnostic('missing-variant-key-space', 'Variant keys must be separated by whitespace.', $start, $this->index);
                return null;
            }
            if ($this->peekCodePoint() === codepoint('*')) {
                $this->advanceCodePoint();
                $keys[] = ['type' => '*'];
                continue;
            }
            if ($this->peekCodePoint() === codepoint('|')) {
                $split = parse_quoted_literal(substr($this->source, $this->index));
                if ($split === null) {
                    $this->pushDiagnostic('unclosed-quoted-literal', "Quoted variant key is missing closing '|'.", $this->index, strlen($this->source));
                    return null;
                }
                $this->index += strlen(substr($this->source, $this->index)) - strlen($split['rest']);
                $keys[] = ['type' => 'literal', 'value' => $split['value']];
                continue;
            }
            $key = $this->takeWhile(static fn(int $cp): bool => !is_syntax_whitespace($cp) && $cp !== codepoint('{'));
            if ($key !== '') {
                $keys[] = ['type' => 'literal', 'value' => $key];
            }
        }
        return $keys;
    }

    private function parseQuotedPattern(): ?array
    {
        $start = $this->index;
        if (!$this->consumeString('{{')) {
            $this->pushDiagnostic('missing-quoted-pattern', "Expected a quoted pattern starting with '{{'.", $start, $start);
            return null;
        }
        $contentStart = $this->index;
        $scan = $this->index;
        $placeholderDepth = 0;
        $inQuote = false;
        while ($scan < strlen($this->source)) {
            if ($placeholderDepth === 0 && str_starts_with(substr($this->source, $scan), '}}')) {
                $content = substr($this->source, $contentStart, $scan - $contentStart);
                $this->index = $scan + 2;
                $nested = new self($content, $this->baseOffset + $contentStart);
                $pattern = $nested->parsePatternUntilEnd();
                array_push($this->diagnostics, ...$nested->diagnostics);
                return $pattern;
            }
            $char = utf8_char_at($this->source, $scan);
            if ($char === null) {
                break;
            }
            $cp = cp($char);
            if ($cp === codepoint('\\')) {
                $scan += strlen($char);
                $next = utf8_char_at($this->source, $scan);
                if ($next !== null) {
                    $scan += strlen($next);
                }
                continue;
            }
            if ($placeholderDepth > 0 && $cp === codepoint('|')) {
                $inQuote = !$inQuote;
            } elseif (!$inQuote && $cp === codepoint('{')) {
                $placeholderDepth += 1;
            } elseif (!$inQuote && $cp === codepoint('}') && $placeholderDepth > 0) {
                $placeholderDepth -= 1;
            }
            $scan += strlen($char);
        }
        $this->pushDiagnostic('unclosed-quoted-pattern', "Quoted pattern is missing closing '}}'.", $start, strlen($this->source));
        return null;
    }

    private function parsePatternUntilEnd(): array
    {
        $parts = [];
        $text = '';
        while (!$this->isDone()) {
            $cp = $this->peekCodePoint();
            if ($cp === codepoint('\\')) {
                $text .= $this->parseEscape();
            } elseif ($cp === codepoint('{')) {
                if ($text !== '') {
                    $parts[] = $text;
                    $text = '';
                }
                $part = $this->parseBracedPatternPart();
                if ($part !== null) {
                    $parts[] = $part;
                }
            } elseif ($cp === codepoint('}')) {
                $start = $this->index;
                $this->advanceCodePoint();
                $this->pushDiagnostic('unescaped-closing-brace', 'Closing brace must be escaped in text.', $start, $this->index);
            } else {
                $text .= $this->advanceCodePoint();
            }
        }
        if ($text !== '') {
            $parts[] = $text;
        }
        return $parts;
    }

    private function parseEscape(): string
    {
        $start = $this->index;
        $this->advanceCodePoint();
        if ($this->isDone()) {
            $this->pushDiagnostic('dangling-escape', 'Backslash at end of message has no escaped character.', $start, $start + 1);
            return '';
        }
        $cp = $this->peekCodePoint();
        if ($cp === codepoint('{') || $cp === codepoint('}') || $cp === codepoint('\\')) {
            return $this->advanceCodePoint();
        }
        return '\\';
    }

    private function parseBracedPatternPart(): ?array
    {
        $start = $this->index;
        $content = $this->consumeBracedContent();
        if ($content === null) {
            return null;
        }
        $trimmed = strip_syntax_whitespace($content);
        if (str_starts_with($trimmed, '#') || str_starts_with($trimmed, '/')) {
            return $this->parseMarkupContent($trimmed, $start, $start + strlen($content) + 2);
        }
        return $this->parseExpressionContent($trimmed, $start, $start + strlen($content) + 2);
    }

    private function parseExpressionPlaceholder(): ?array
    {
        $start = $this->index;
        $content = $this->consumeBracedContent();
        return $content === null ? null : $this->parseExpressionContent(strip_syntax_whitespace($content), $start, $start + strlen($content) + 2);
    }

    private function consumeBracedContent(): ?string
    {
        $start = $this->index;
        if ($this->peekCodePoint() !== codepoint('{')) {
            $this->pushDiagnostic('missing-placeholder', "Expected a placeholder starting with '{'.", $start, $start);
            return null;
        }
        $this->advanceCodePoint();
        $contentStart = $this->index;
        $inQuote = false;
        while (!$this->isDone()) {
            $cp = $this->peekCodePoint();
            if ($inQuote) {
                if ($cp === codepoint('\\')) {
                    $this->advanceCodePoint();
                    if (!$this->isDone()) {
                        $this->advanceCodePoint();
                    }
                    continue;
                }
                if ($cp === codepoint('}')) {
                    $content = substr($this->source, $contentStart, $this->index - $contentStart);
                    $this->advanceCodePoint();
                    return $content;
                }
                if ($cp === codepoint('|')) {
                    $inQuote = false;
                }
                $this->advanceCodePoint();
                continue;
            }
            if ($cp === codepoint('|')) {
                $inQuote = true;
                $this->advanceCodePoint();
                continue;
            }
            if ($cp === codepoint('}')) {
                $content = substr($this->source, $contentStart, $this->index - $contentStart);
                $this->advanceCodePoint();
                return $content;
            }
            $this->advanceCodePoint();
        }
        $this->pushDiagnostic('unclosed-placeholder', 'Placeholder is missing a closing brace.', $start, strlen($this->source));
        return null;
    }

    private function parseExpressionContent(string $content, int $start, int $end): ?array
    {
        if (str_starts_with($content, '$')) {
            $split = split_name(substr($content, 1));
            if ($split['name'] === '') {
                $this->pushDiagnostic(variable_name_diagnostic_code(substr($content, 1)), 'Variable placeholder is missing a name.', $start, $end);
                return null;
            }
            $expression = expression_model(['type' => 'variable', 'name' => $split['name']]);
            $rest = $this->restAfterOperand($split['rest'], $start, $end);
        } elseif (str_starts_with($content, '|')) {
            $split = parse_quoted_literal($content);
            if ($split === null) {
                $this->pushDiagnostic('unclosed-quoted-literal', "Quoted literal is missing closing '|'.", $start, $end);
                return null;
            }
            $expression = expression_model(['type' => 'literal', 'value' => $split['value']]);
            $rest = $this->restAfterOperand($split['rest'], $start, $end);
        } elseif (str_starts_with($content, ':')) {
            $expression = expression_model(null);
            $rest = $content;
        } else {
            $split = split_unquoted_literal($content);
            if ($split === null) {
                $this->pushDiagnostic($content === '' ? 'missing-expression' : 'invalid-literal', 'Placeholder literal is invalid.', $start, $end);
                return null;
            }
            $expression = expression_model(['type' => 'literal', 'value' => $split['value']]);
            $rest = $this->restAfterOperand($split['rest'], $start, $end);
        }
        if ($rest === null) {
            return null;
        }
        if ($rest === '') {
            return $expression;
        }
        $tail = $this->parseTail($rest, $start, $end);
        return $tail === null ? null : expression_model($expression['arg'] ?? null, $tail['function'], $tail['attributes']);
    }

    private function restAfterOperand(string $rest, int $start, int $end): ?string
    {
        if ($rest === '') {
            return $rest;
        }
        if (!is_whitespace_cp(cp(utf8_char_at($rest, 0) ?? ''))) {
            $this->pushDiagnostic('missing-expression-space', 'Expression arguments must be separated from functions or attributes by whitespace.', $start, $end);
            return null;
        }
        return strip_leading_syntax_whitespace($rest);
    }

    private function parseTail(string $rest, int $start, int $end): ?array
    {
        if (trim($rest) === '') {
            return ['function' => null, 'attributes' => null];
        }
        $tokens = $this->splitTailTokens($rest, $start, $end);
        if ($tokens === null) {
            return null;
        }
        $index = 0;
        $functionRef = null;
        $attributes = [];
        if ($index < count($tokens) && str_starts_with($tokens[$index], ':')) {
            $result = $this->parseFunctionAnnotation($tokens, $index, $start, $end);
            if ($result === null) {
                return null;
            }
            $functionRef = $result['function'];
            $index = $result['nextIndex'];
        }
        while ($index < count($tokens)) {
            $token = $tokens[$index];
            if (!str_starts_with($token, '@')) {
                $this->pushDiagnostic('unsupported-expression', 'Expression content after the argument must be a function annotation or attribute.', $start, $end);
                return null;
            }
            $attribute = $this->parseAttributeTokens($tokens, $index, $start, $end);
            if ($attribute === null) {
                return null;
            }
            $index = $attribute['nextIndex'];
            if (array_key_exists($attribute['name'], $attributes)) {
                $this->pushDiagnostic('duplicate-attribute-name', 'Attribute names must be unique within an expression or markup placeholder.', $start, $end);
                return null;
            }
            $attributes[$attribute['name']] = $attribute['value'];
        }
        return ['function' => $functionRef, 'attributes' => omit_empty($attributes)];
    }

    private function splitTailTokens(string $rest, int $start, int $end): ?array
    {
        $tokens = [];
        $tokenStart = -1;
        $inQuote = false;
        for ($index = 0; $index < strlen($rest);) {
            $char = utf8_char_at($rest, $index);
            if ($char === null) {
                break;
            }
            $cp = cp($char);
            if ($inQuote && $cp === codepoint('\\')) {
                if ($tokenStart < 0) {
                    $tokenStart = $index;
                }
                $index += strlen($char);
                $next = utf8_char_at($rest, $index);
                if ($next !== null) {
                    $index += strlen($next);
                }
                continue;
            }
            if ($cp === codepoint('|')) {
                $inQuote = !$inQuote;
                if ($tokenStart < 0) {
                    $tokenStart = $index;
                }
                $index += strlen($char);
                continue;
            }
            if (is_syntax_whitespace($cp) && !$inQuote) {
                if ($tokenStart >= 0) {
                    $tokens[] = substr($rest, $tokenStart, $index - $tokenStart);
                    $tokenStart = -1;
                }
                $index += strlen($char);
                continue;
            }
            if ($tokenStart < 0) {
                $tokenStart = $index;
            }
            $index += strlen($char);
        }
        if ($inQuote) {
            $this->pushDiagnostic('unclosed-quoted-literal', "Quoted literal is missing closing '|'.", $start, $end);
            return null;
        }
        if ($tokenStart >= 0) {
            $tokens[] = substr($rest, $tokenStart);
        }
        return $tokens;
    }

    private function parseFunctionAnnotation(array $tokens, int $index, int $start, int $end): ?array
    {
        $content = substr($tokens[$index], 1);
        $split = split_identifier($content);
        if ($split['name'] === '') {
            $this->pushDiagnostic($content === '' ? 'missing-function-name' : 'invalid-function-name', 'Function annotation is missing a name.', $start, $end);
            return null;
        }
        if ($split['rest'] !== '') {
            $this->pushDiagnostic('unsupported-expression', 'Function annotation must separate options with whitespace.', $start, $end);
            return null;
        }
        $options = [];
        $index += 1;
        while ($index < count($tokens) && !str_starts_with($tokens[$index], '@')) {
            $option = $this->parseOptionTokens($tokens, $index, $start, $end);
            if ($option === null) {
                return null;
            }
            $index = $option['nextIndex'];
            if (array_key_exists($option['name'], $options)) {
                $this->pushDiagnostic('duplicate-option-name', 'Option names must be unique within a function or markup placeholder.', $start, $end);
                return null;
            }
            $options[$option['name']] = $option['value'];
        }
        return ['function' => function_model($split['name'], omit_empty($options)), 'nextIndex' => $index];
    }

    private function parseOptionTokens(array $tokens, int $index, int $start, int $end): ?array
    {
        $assignment = $this->parseRequiredAssignment($tokens, $index, $start, $end);
        if ($assignment === null) {
            return null;
        }
        $keySplit = split_identifier($assignment['key']);
        if ($keySplit['name'] === '' || $keySplit['rest'] !== '') {
            $this->pushDiagnostic('invalid-function-option', 'Option key must be a valid identifier.', $start, $end);
            return null;
        }
        return ['name' => $keySplit['name'], 'value' => parse_literal_or_variable(strip_syntax_whitespace($assignment['rawValue'])), 'nextIndex' => $assignment['nextIndex']];
    }

    private function parseRequiredAssignment(array $tokens, int $index, int $start, int $end): ?array
    {
        $token = $tokens[$index];
        $equals = strpos($token, '=');
        if ($equals !== false) {
            return $this->finishAssignment(substr($token, 0, $equals), substr($token, $equals + 1), $tokens, $index + 1, $start, $end);
        }
        if ($index + 1 >= count($tokens) || !str_starts_with($tokens[$index + 1], '=')) {
            $this->pushDiagnostic('invalid-function-option', 'Options must use key=value syntax.', $start, $end);
            return null;
        }
        return $this->finishAssignment($token, substr($tokens[$index + 1], 1), $tokens, $index + 2, $start, $end);
    }

    private function finishAssignment(string $key, string $rawValue, array $tokens, int $nextIndex, int $start, int $end): ?array
    {
        if ($key === '') {
            $this->pushDiagnostic('invalid-function-option', 'Option key and value must be non-empty.', $start, $end);
            return null;
        }
        if ($rawValue !== '') {
            return ['key' => $key, 'rawValue' => $rawValue, 'nextIndex' => $nextIndex];
        }
        if ($nextIndex >= count($tokens)) {
            $this->pushDiagnostic('invalid-function-option', 'Option key and value must be non-empty.', $start, $end);
            return null;
        }
        return ['key' => $key, 'rawValue' => $tokens[$nextIndex], 'nextIndex' => $nextIndex + 1];
    }

    private function parseAttributeTokens(array $tokens, int $index, int $start, int $end): ?array
    {
        $token = $tokens[$index];
        $content = substr($token, 1);
        if ($content === '') {
            $this->pushDiagnostic('missing-attribute-name', 'Attribute is missing a name.', $start, $end);
            return null;
        }
        if (!$this->hasAttributeAssignment($content, $tokens, $index)) {
            $split = split_identifier($content);
            if ($split['name'] === '' || $split['rest'] !== '') {
                $this->pushDiagnostic('invalid-attribute', 'Attribute name must be a valid identifier.', $start, $end);
                return null;
            }
            return ['name' => $split['name'], 'value' => true, 'nextIndex' => $index + 1];
        }
        $assignment = $this->parseAttributeAssignment($content, $tokens, $index, $start, $end);
        if ($assignment === null) {
            return null;
        }
        $value = $this->parseAttributeValue($assignment['rawValue'], $start, $end);
        return $value === null ? null : ['name' => $assignment['name'], 'value' => $value, 'nextIndex' => $assignment['nextIndex']];
    }

    private function hasAttributeAssignment(string $content, array $tokens, int $index): bool
    {
        return str_contains($content, '=') || ($index + 1 < count($tokens) && str_starts_with($tokens[$index + 1], '='));
    }

    private function parseAttributeAssignment(string $content, array $tokens, int $index, int $start, int $end): ?array
    {
        $assignment = $this->attributeAssignmentParts($content, $tokens, $index, $start, $end);
        if ($assignment === null) {
            return null;
        }
        $split = split_identifier($assignment['key']);
        if ($split['name'] === '' || $split['rest'] !== '') {
            $this->pushDiagnostic('invalid-attribute', 'Attribute name must be a valid identifier.', $start, $end);
            return null;
        }
        return ['name' => $split['name'], 'rawValue' => $assignment['rawValue'], 'nextIndex' => $assignment['nextIndex']];
    }

    private function attributeAssignmentParts(string $content, array $tokens, int $index, int $start, int $end): ?array
    {
        $equals = strpos($content, '=');
        if ($equals !== false) {
            return $this->finishAttributeAssignment(substr($content, 0, $equals), substr($content, $equals + 1), $tokens, $index + 1, $start, $end);
        }
        if ($index + 1 >= count($tokens) || !str_starts_with($tokens[$index + 1], '=')) {
            return null;
        }
        return $this->finishAttributeAssignment($content, substr($tokens[$index + 1], 1), $tokens, $index + 2, $start, $end);
    }

    private function finishAttributeAssignment(string $key, string $rawValue, array $tokens, int $nextIndex, int $start, int $end): ?array
    {
        if ($key === '') {
            $this->pushDiagnostic('invalid-attribute', 'Attribute key and value must be non-empty.', $start, $end);
            return null;
        }
        if ($rawValue !== '') {
            return ['key' => $key, 'rawValue' => $rawValue, 'nextIndex' => $nextIndex];
        }
        if ($nextIndex >= count($tokens)) {
            $this->pushDiagnostic('invalid-attribute', 'Attribute key and value must be non-empty.', $start, $end);
            return null;
        }
        return ['key' => $key, 'rawValue' => $tokens[$nextIndex], 'nextIndex' => $nextIndex + 1];
    }

    private function parseAttributeValue(string $rawValue, int $start, int $end): ?array
    {
        $rawValue = strip_syntax_whitespace($rawValue);
        if (str_starts_with($rawValue, '|') && str_ends_with($rawValue, '|') && strlen($rawValue) >= 2) {
            $split = parse_quoted_literal($rawValue);
            if ($split === null) {
                $this->pushDiagnostic('unclosed-quoted-literal', "Quoted literal is missing closing '|'.", $start, $end);
                return null;
            }
            if ($split['rest'] !== '') {
                $this->pushDiagnostic('invalid-attribute', 'Attribute value must be a single literal.', $start, $end);
                return null;
            }
            return ['type' => 'literal', 'value' => $split['value']];
        }
        $split = split_unquoted_literal($rawValue);
        if ($split === null || $split['rest'] !== '') {
            $this->pushDiagnostic('invalid-attribute', 'Attribute value must be a single literal.', $start, $end);
            return null;
        }
        return ['type' => 'literal', 'value' => $split['value']];
    }

    private function parseMarkupContent(string $content, int $start, int $end): ?array
    {
        if (str_starts_with($content, '#')) {
            $trimmed = strip_trailing_syntax_whitespace(substr($content, 1));
            if (str_ends_with($trimmed, '/')) {
                $kind = 'standalone';
                $rest = strip_trailing_syntax_whitespace(substr($trimmed, 0, -1));
            } else {
                $kind = 'open';
                $rest = $trimmed;
            }
        } else {
            $kind = 'close';
            $rest = strip_syntax_whitespace(substr($content, 1));
        }
        $split = split_identifier(strip_leading_syntax_whitespace($rest));
        if ($split['name'] === '') {
            $this->pushDiagnostic('missing-markup-name', 'Markup placeholder is missing a name.', $start, $end);
            return null;
        }
        if (strip_syntax_whitespace($split['rest']) === '') {
            return markup_model($kind, $split['name']);
        }
        $tail = $this->parseMarkupTail($split['rest'], $start, $end);
        return $tail === null ? null : markup_model($kind, $split['name'], $tail['options'], $tail['attributes']);
    }

    private function parseMarkupTail(string $rest, int $start, int $end): ?array
    {
        $tokens = $this->splitTailTokens($rest, $start, $end);
        if ($tokens === null) {
            return null;
        }
        $options = [];
        $attributes = [];
        $seenAttribute = false;
        $index = 0;
        while ($index < count($tokens)) {
            $token = $tokens[$index];
            if (str_starts_with($token, '@')) {
                $seenAttribute = true;
                $attribute = $this->parseAttributeTokens($tokens, $index, $start, $end);
                if ($attribute === null) {
                    return null;
                }
                $index = $attribute['nextIndex'];
                if (array_key_exists($attribute['name'], $attributes)) {
                    $this->pushDiagnostic('duplicate-attribute-name', 'Attribute names must be unique within an expression or markup placeholder.', $start, $end);
                    return null;
                }
                $attributes[$attribute['name']] = $attribute['value'];
                continue;
            }
            if ($seenAttribute) {
                $this->pushDiagnostic('unsupported-markup', 'Markup options must come before attributes.', $start, $end);
                return null;
            }
            if (str_starts_with($token, ':')) {
                $this->pushDiagnostic('unsupported-markup', 'Markup placeholders do not support function annotations.', $start, $end);
                return null;
            }
            $option = $this->parseOptionTokens($tokens, $index, $start, $end);
            if ($option === null) {
                return null;
            }
            $index = $option['nextIndex'];
            if (array_key_exists($option['name'], $options)) {
                $this->pushDiagnostic('duplicate-option-name', 'Option names must be unique within a function or markup placeholder.', $start, $end);
                return null;
            }
            $options[$option['name']] = $option['value'];
        }
        return ['options' => omit_empty($options), 'attributes' => omit_empty($attributes)];
    }

    private function parseVariableName(): ?string
    {
        $start = $this->index;
        if ($this->peekCodePoint() !== codepoint('$')) {
            $this->pushDiagnostic('missing-variable', "Expected a variable starting with '$'.", $start, $start);
            return null;
        }
        $this->advanceCodePoint();
        $scan = scan_name($this->source, $this->index);
        if ($scan['name'] === '') {
            $this->pushDiagnostic(variable_name_diagnostic_code($this->source, $this->index), 'Variable is missing a name.', $start, $this->index);
            return null;
        }
        $this->index = $scan['endIndex'];
        return $scan['name'];
    }

    private function skipSyntaxWhitespace(): bool
    {
        $start = $this->index;
        while (!$this->isDone()) {
            $cp = $this->peekCodePoint();
            if (!is_syntax_whitespace($cp)) {
                break;
            }
            $this->index += strlen(utf8_char_at($this->source, $this->index) ?? '');
        }
        return $this->index !== $start;
    }

    private function skipSyntaxGap(): bool
    {
        $sawWhitespace = false;
        while (!$this->isDone()) {
            $cp = $this->peekCodePoint();
            if (is_whitespace_cp($cp)) {
                $sawWhitespace = true;
                $this->index += strlen(utf8_char_at($this->source, $this->index) ?? '');
                continue;
            }
            if (is_bidi_marker($cp)) {
                $this->index += strlen(utf8_char_at($this->source, $this->index) ?? '');
                continue;
            }
            break;
        }
        return $sawWhitespace;
    }

    private function takeWhile(callable $predicate): string
    {
        $start = $this->index;
        while (!$this->isDone() && $predicate($this->peekCodePoint())) {
            $this->advanceCodePoint();
        }
        return substr($this->source, $start, $this->index - $start);
    }

    private function startsWith(string $expected): bool
    {
        return str_starts_with(substr($this->source, $this->index), $expected);
    }

    private function consumeString(string $expected): bool
    {
        if (!$this->startsWith($expected)) {
            return false;
        }
        $this->index += strlen($expected);
        return true;
    }

    private function isDone(): bool
    {
        return $this->index >= strlen($this->source);
    }

    private function peekCodePoint(): int
    {
        return cp(utf8_char_at($this->source, $this->index) ?? '');
    }

    private function advanceCodePoint(): string
    {
        $char = utf8_char_at($this->source, $this->index) ?? '';
        $this->index += strlen($char);
        return $char;
    }

    private function pushDiagnostic(string $code, string $message, int $start, int $end): void
    {
        $this->diagnostics[] = ['code' => $code, 'message' => $message, 'start' => $this->baseOffset + $start, 'end' => $this->baseOffset + $end, 'severity' => 'error'];
    }
}

function expression_model(?array $arg, ?array $functionRef = null, ?array $attributes = null): array
{
    $output = ['type' => 'expression'];
    if ($arg !== null) {
        $output['arg'] = $arg;
    }
    if ($functionRef !== null) {
        $output['function'] = $functionRef;
    }
    if ($attributes !== null && count($attributes) > 0) {
        $output['attributes'] = sort_keys_recursive($attributes);
    }
    return $output;
}

function function_model(string $name, ?array $options): array
{
    $output = ['type' => 'function', 'name' => $name];
    if ($options !== null && count($options) > 0) {
        $output['options'] = sort_keys_recursive($options);
    }
    return $output;
}

function markup_model(string $kind, string $name, ?array $options = null, ?array $attributes = null): array
{
    $output = ['type' => 'markup', 'kind' => $kind, 'name' => $name];
    if ($options !== null && count($options) > 0) {
        $output['options'] = sort_keys_recursive($options);
    }
    if ($attributes !== null && count($attributes) > 0) {
        $output['attributes'] = sort_keys_recursive($attributes);
    }
    return $output;
}

function parse_literal_or_variable(string $rawValue): array
{
    if (str_starts_with($rawValue, '$')) {
        $split = split_name(substr($rawValue, 1));
        if ($split['name'] !== '' && $split['rest'] === '') {
            return ['type' => 'variable', 'name' => $split['name']];
        }
        return ['type' => 'variable', 'name' => substr($rawValue, 1)];
    }
    $quoted = parse_quoted_literal($rawValue);
    if ($quoted !== null && $quoted['rest'] === '') {
        return ['type' => 'literal', 'value' => $quoted['value']];
    }
    return ['type' => 'literal', 'value' => $rawValue];
}

function parse_quoted_literal(string $input): ?array
{
    if (!str_starts_with($input, '|')) {
        return null;
    }
    $output = '';
    for ($index = 1; $index < strlen($input);) {
        $char = utf8_char_at($input, $index);
        if ($char === null) {
            break;
        }
        $index += strlen($char);
        $cp = cp($char);
        if ($cp === codepoint('|')) {
            return ['value' => $output, 'rest' => substr($input, $index)];
        }
        if ($cp === codepoint('\\')) {
            if ($index >= strlen($input)) {
                $output .= '\\';
                break;
            }
            $escaped = utf8_char_at($input, $index) ?? '';
            $escapedCp = cp($escaped);
            if (in_array($escapedCp, [codepoint('\\'), codepoint('{'), codepoint('|'), codepoint('}')], true)) {
                $output .= $escaped;
                $index += strlen($escaped);
            } else {
                $output .= '\\';
            }
        } else {
            $output .= $char;
        }
    }
    return null;
}

function split_unquoted_literal(string $input): ?array
{
    $scan = 0;
    $sawChar = false;
    while ($scan < strlen($input)) {
        $char = utf8_char_at($input, $scan);
        if ($char === null) {
            break;
        }
        $cp = cp($char);
        if (is_syntax_whitespace($cp) || $cp === codepoint(':') || $cp === codepoint('@')) {
            break;
        }
        if (!is_unquoted_literal_char($cp)) {
            return null;
        }
        $sawChar = true;
        $scan += strlen($char);
    }
    return $sawChar ? ['value' => substr($input, 0, $scan), 'rest' => substr($input, $scan)] : null;
}

function is_unquoted_literal_char(int $cp): bool
{
    if (is_control_cp($cp) || is_syntax_whitespace($cp) || is_noncharacter($cp)) {
        return false;
    }
    return !in_array(cp_to_char($cp), ['^', '!', '%', '*', '<', '>', '?', '~', '&', '\\', '$'], true);
}

function variable_name_diagnostic_code(string $input, int $offset = 0): string
{
    if ($offset >= strlen($input)) {
        return 'missing-variable-name';
    }
    $char = utf8_char_at($input, $offset) ?? '';
    return in_array($char, ['}', ' ', "\t", "\n", "\r"], true) ? 'missing-variable-name' : 'invalid-variable-name';
}

function split_name(string $input): array
{
    $scan = scan_name($input, 0);
    return ['name' => $scan['name'], 'rest' => substr($input, $scan['endIndex']), 'consumedLength' => $scan['endIndex']];
}

function scan_name(string $input, int $offset): array
{
    $scan = $offset;
    $firstChar = utf8_char_at($input, $scan);
    if ($firstChar !== null && is_bidi_marker(cp($firstChar))) {
        $scan += strlen($firstChar);
    }
    $nameStart = $scan;
    if ($nameStart >= strlen($input)) {
        return ['name' => '', 'endIndex' => $offset];
    }
    $first = cp(utf8_char_at($input, $nameStart) ?? '');
    if ($first <= 0x7f) {
        $ascii = scan_ascii_name($input, $offset, $nameStart);
        if ($ascii !== null) {
            return $ascii;
        }
    }
    if (!is_name_start($first)) {
        return ['name' => '', 'endIndex' => $offset];
    }
    $scan += strlen(utf8_char_at($input, $scan) ?? '');
    while ($scan < strlen($input)) {
        $char = utf8_char_at($input, $scan);
        if ($char === null || !is_name_char(cp($char))) {
            break;
        }
        $scan += strlen($char);
    }
    $nameEnd = $scan;
    $next = utf8_char_at($input, $scan);
    if ($next !== null && is_bidi_marker(cp($next))) {
        $scan += strlen($next);
    }
    return ['name' => substr($input, $nameStart, $nameEnd - $nameStart), 'endIndex' => $scan];
}

function scan_ascii_name(string $input, int $offset, int $nameStart): ?array
{
    $first = ord($input[$nameStart]);
    if (!is_ascii_name_start($first)) {
        return ['name' => '', 'endIndex' => $offset];
    }
    $scan = $nameStart + 1;
    while ($scan < strlen($input) && ord($input[$scan]) <= 0x7f && is_ascii_name_char(ord($input[$scan]))) {
        $scan += 1;
    }
    $nameEnd = $scan;
    if ($scan < strlen($input)) {
        $char = utf8_char_at($input, $scan);
        $cp = cp($char ?? '');
        if ($cp > 0x7f) {
            if (!is_bidi_marker($cp)) {
                return null;
            }
            return ['name' => substr($input, $nameStart, $nameEnd - $nameStart), 'endIndex' => $scan + strlen($char ?? '')];
        }
    }
    return ['name' => substr($input, $nameStart, $nameEnd - $nameStart), 'endIndex' => $scan];
}

function split_identifier(string $input): array
{
    $namespaceOrName = split_name($input);
    if ($namespaceOrName['name'] === '') {
        return ['name' => '', 'rest' => $input, 'consumedLength' => 0];
    }
    if (!str_starts_with($namespaceOrName['rest'], ':')) {
        return $namespaceOrName;
    }
    $name = split_name(substr($namespaceOrName['rest'], 1));
    if ($name['name'] === '') {
        return $namespaceOrName;
    }
    return [
        'name' => $namespaceOrName['name'] . ':' . $name['name'],
        'rest' => $name['rest'],
        'consumedLength' => $namespaceOrName['consumedLength'] + 1 + $name['consumedLength'],
    ];
}

function is_name_start(int $cp): bool
{
    if ($cp <= 0x7f) {
        return is_ascii_name_start($cp);
    }
    return $cp >= 0xa1 && $cp <= 0x10fffd && !is_bidi_marker($cp) && !is_control_cp($cp) && !is_surrogate($cp) && !is_syntax_whitespace($cp) && !is_noncharacter($cp);
}

function is_name_char(int $cp): bool
{
    return is_name_start($cp) || ($cp >= codepoint('0') && $cp <= codepoint('9')) || is_mark_cp($cp) || $cp === codepoint('-') || $cp === codepoint('.');
}

function is_ascii_name_start(int $cp): bool
{
    return ($cp >= codepoint('a') && $cp <= codepoint('z')) || ($cp >= codepoint('A') && $cp <= codepoint('Z')) || $cp === codepoint('+') || $cp === codepoint('_');
}

function is_ascii_name_char(int $cp): bool
{
    return is_ascii_name_start($cp) || ($cp >= codepoint('0') && $cp <= codepoint('9')) || $cp === codepoint('-') || $cp === codepoint('.');
}

function is_bidi_marker(int $cp): bool
{
    return BIDI_MARKERS[$cp] ?? false;
}

function is_syntax_whitespace(int $cp): bool
{
    return is_whitespace_cp($cp) || is_bidi_marker($cp);
}

function is_whitespace_cp(int $cp): bool
{
    return preg_match('/^\s$/u', cp_to_char($cp)) === 1;
}

function is_mark_cp(int $cp): bool
{
    return preg_match('/^\p{M}$/u', cp_to_char($cp)) === 1;
}

function is_control_cp(int $cp): bool
{
    return ($cp >= 0 && $cp <= 0x1f) || ($cp >= 0x7f && $cp <= 0x9f);
}

function is_surrogate(int $cp): bool
{
    return $cp >= 0xd800 && $cp <= 0xdfff;
}

function is_noncharacter(int $cp): bool
{
    return ($cp >= 0xfdd0 && $cp <= 0xfdef) || (($cp & 0xfffe) === 0xfffe);
}

function strip_syntax_whitespace(string $value): string
{
    return strip_trailing_syntax_whitespace(strip_leading_syntax_whitespace($value));
}

function strip_leading_syntax_whitespace(string $value): string
{
    $start = 0;
    while ($start < strlen($value)) {
        $char = utf8_char_at($value, $start);
        if ($char === null || !is_syntax_whitespace(cp($char))) {
            break;
        }
        $start += strlen($char);
    }
    return substr($value, $start);
}

function strip_trailing_syntax_whitespace(string $value): string
{
    $end = strlen($value);
    while ($end > 0) {
        $index = previous_utf8_index($value, $end);
        $char = utf8_char_at($value, $index);
        if ($char === null || !is_syntax_whitespace(cp($char))) {
            break;
        }
        $end = $index;
    }
    return substr($value, 0, $end);
}

function previous_utf8_index(string $value, int $end): int
{
    $index = $end - 1;
    while ($index > 0 && (ord($value[$index]) & 0xc0) === 0x80) {
        $index -= 1;
    }
    return $index;
}

function utf8_char_at(string $value, int $index): ?string
{
    if ($index < 0 || $index >= strlen($value)) {
        return null;
    }
    if (preg_match('/./us', $value, $match, 0, $index) !== 1) {
        return null;
    }
    return $match[0];
}

function cp(string $char): int
{
    return $char === '' ? -1 : \IntlChar::ord($char);
}

function cp_to_char(int $cp): string
{
    return $cp < 0 ? '' : \IntlChar::chr($cp);
}

function codepoint(string $value): int
{
    return cp($value);
}

function omit_empty(array $value): ?array
{
    return count($value) === 0 ? null : $value;
}

function sort_keys_recursive(array $value): array
{
    if (array_is_list($value)) {
        return array_map(static fn($item) => is_array($item) ? sort_keys_recursive($item) : $item, $value);
    }
    ksort($value, SORT_STRING);
    foreach ($value as $key => $item) {
        if (is_array($item)) {
            $value[$key] = sort_keys_recursive($item);
        }
    }
    return $value;
}
