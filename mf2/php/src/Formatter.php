<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class FunctionRegistry
{
    public function __construct(private array $formatters, private array $selectors)
    {
    }

    public static function defaults(): self
    {
        return self::portable();
    }

    public static function portable(): self
    {
        return Internal\portable_function_registry();
    }

    public function withFunction(string $name, callable $formatter): self
    {
        $formatters = $this->formatters;
        $formatters[$name] = $formatter;
        return new self($formatters, $this->selectors);
    }

    public function withSelector(string $name, callable $selector): self
    {
        $selectors = $this->selectors;
        $selectors[$name] = $selector;
        return new self($this->formatters, $selectors);
    }

    public function hasFormatter(?array $functionRef): bool
    {
        return $functionRef !== null && array_key_exists((string) ($functionRef['name'] ?? ''), $this->formatters);
    }

    public function hasSelector(?array $functionRef): bool
    {
        return $functionRef !== null && array_key_exists((string) ($functionRef['name'] ?? ''), $this->selectors);
    }

    public function format(array $call): string
    {
        $name = (string) ($call['function']['name'] ?? '');
        $formatter = $this->formatters[$name] ?? null;
        if ($formatter === null) {
            throw new MF2Error('unsupported-function', "Function :{$name} is not supported by this formatter registry.");
        }
        return (string) $formatter($call);
    }

    public function select(array $match): ?int
    {
        $name = (string) ($match['function']['name'] ?? '');
        $selector = $this->selectors[$name] ?? null;
        return $selector === null ? null : $selector($match);
    }
}

function format_message(array $model, array $arguments = [], array $options = []): array
{
    $result = format_message_to_parts($model, $arguments, $options);
    return [
        'value' => Internal\parts_to_string($result['parts'], Internal\bidi_isolation_option($options)),
        'errors' => $result['errors'],
        'ok' => $result['errors'] === [],
        'hasErrors' => $result['errors'] !== [],
    ];
}

function format_message_to_parts(array $model, array $arguments = [], array $options = []): array
{
    try {
        Internal\validate_model($model);
    } catch (\Throwable $error) {
        return ['parts' => [], 'errors' => [Internal\as_mf2_error($error)], 'ok' => false, 'hasErrors' => true];
    }
    try {
        $context = new Internal\FormatContext(
            $arguments,
            Internal\locale_option($options),
            Internal\functions_option($options),
            true,
            $options['onMissingArgument'] ?? null,
            $options['onFormatError'] ?? null,
        );
    } catch (\Throwable $error) {
        return ['parts' => [], 'errors' => [Internal\as_mf2_error($error)], 'ok' => false, 'hasErrors' => true];
    }
    try {
        $context->applyDeclarations(Internal\model_array_field($model, 'declarations'));
    } catch (\Throwable $error) {
        $context->errors[] = Internal\as_mf2_error($error);
    }
    try {
        $parts = ($model['type'] ?? '') === 'message'
            ? $context->formatPatternToParts(Internal\model_array_field($model, 'pattern'))
            : $context->formatSelectToParts(Internal\model_array_field($model, 'selectors'), Internal\model_array_field($model, 'variants'));
    } catch (\Throwable $error) {
        $context->errors[] = Internal\as_mf2_error($error);
        $parts = [];
    }
    return [
        'parts' => $parts,
        'errors' => $context->errors,
        'ok' => $context->errors === [],
        'hasErrors' => $context->errors !== [],
    ];
}

namespace Mojito\MessageFormat2\Internal;

use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\MF2Error;

function locale_option(array $options): string
{
    try {
        $locale = trim((string) ($options['locale'] ?? 'en'));
    } catch (\Throwable $error) {
        throw MF2Error::badOption($error->getMessage());
    }
    return $locale === '' ? 'en' : $locale;
}

function bidi_isolation_option(array $options): string
{
    $value = $options['bidiIsolation'] ?? 'none';
    return is_string($value) ? $value : 'none';
}

function functions_option(array $options): FunctionRegistry
{
    $value = $options['functions'] ?? FunctionRegistry::defaults();
    if ($value instanceof FunctionRegistry) {
        return $value;
    }
    throw MF2Error::badOption('functions must be a FunctionRegistry.');
}

final class FormatContext
{
    public array $errors = [];
    private array $locals = [];
    private array $failedLocals = [];
    private array $selectorAnnotations = [];

    public function __construct(
        private array $arguments,
        private string $locale,
        private FunctionRegistry $functions,
        private bool $fallback,
        private mixed $onMissingArgument = null,
        private mixed $onFormatError = null,
    ) {
    }

    public function applyDeclarations(array $declarations): void
    {
        $this->selectorAnnotations = selector_annotations($declarations);
        foreach ($declarations as $declaration) {
            if (($declaration['type'] ?? '') === 'input') {
                $this->applyInputDeclaration($declaration);
            }
            if (($declaration['type'] ?? '') === 'local') {
                $output = $this->formatExpressionOutput($declaration['value'] ?? []);
                $name = (string) ($declaration['name'] ?? '');
                if ($output['hadError']) {
                    $this->failedLocals[$name] = true;
                    unset($this->locals[$name]);
                } else {
                    $this->locals[$name] = ['rawValue' => $output['rawValue'], 'source' => $output['source']];
                }
            }
        }
    }

    private function applyInputDeclaration(array $input): void
    {
        $functionRef = $input['value']['function'] ?? null;
        if ($functionRef === null || !$this->functions->hasFormatter($functionRef) || !$this->functions->hasSelector($functionRef)) {
            return;
        }
        $name = (string) ($input['name'] ?? '');
        if (!$this->hasValue($name)) {
            if (!$this->fallback) {
                throw MF2Error::missingArgument($name);
            }
            $this->failedLocals[$name] = true;
            $this->errors[] = unresolved_variable($name);
            $this->errors[] = MF2Error::badOperand('Function operand is not available.');
            return;
        }
        $inputValue = $this->value($name);
        $this->recordFunctionResolutionErrors($functionRef, $inputValue['source']);
        try {
            $rendered = operand_value_to_string($inputValue['rawValue']);
            $formatted = $this->functions->format([
                'value' => $rendered,
                'rawValue' => $inputValue['rawValue'],
                'function' => $functionRef,
                'locale' => $this->locale,
                'optionValue' => fn(string $optionName, mixed $fallback): mixed => $this->optionValue($functionRef, $optionName, $fallback),
                'inheritedSource' => $inputValue['source'],
            ]);
            $sourceValue = $inputValue['source']['value'] ?? $rendered;
            $this->locals[$name] = ['rawValue' => $formatted, 'source' => $this->functionSource($sourceValue, $functionRef, $inputValue['source'])];
        } catch (\Throwable $error) {
            if (!$this->fallback) {
                throw $error;
            }
            $this->errors[] = fallback_error($error);
            $this->failedLocals[$name] = true;
        }
    }

    public function formatSelectToParts(array $selectors, array $variants): array
    {
        $selectorValues = array_map(fn(array $selector): array => $this->selectorValue($selector), $selectors);
        $signatures = [];
        $fallback = null;
        $selected = null;
        $selectedRank = null;
        foreach ($variants as $variant) {
            $this->validateVariant($variant, $selectorValues, $signatures);
            if ($fallback === null && array_reduce($variant['keys'] ?? [], static fn(bool $ok, array $key): bool => $ok && ($key['type'] ?? '') === '*', true)) {
                $fallback = $variant;
            }
            $rank = $this->variantMatchRank($variant, $selectorValues);
            if ($rank !== null && ($selectedRank === null || compare_rank($rank, $selectedRank) > 0)) {
                $selected = $variant;
                $selectedRank = $rank;
            }
        }
        if ($fallback === null) {
            throw new MF2Error('missing-fallback-variant', 'Select messages must include a catch-all fallback variant.');
        }
        return $this->formatPatternToParts(($selected ?? $fallback)['value'] ?? []);
    }

    private function selectorValue(array $selector): array
    {
        $name = (string) ($selector['name'] ?? '');
        $annotation = $this->selectorAnnotations[$name] ?? null;
        if (!$this->hasValue($name)) {
            if (!$this->fallback) {
                throw MF2Error::missingArgument($name);
            }
            $failedLocal = isset($this->failedLocals[$name]);
            if (!$failedLocal) {
                $this->errors[] = unresolved_variable($name);
            }
            if ($annotation !== null && ($failedLocal || $this->functions->hasSelector($annotation->function))) {
                if (!$failedLocal) {
                    $this->errors[] = MF2Error::badOperand('Selector operand is not available.');
                }
                $this->errors[] = new MF2Error('bad-selector', 'Selector operand is not available.');
            }
            return ['rendered' => '', 'rawValue' => '', 'normalizedRendered' => $annotation?->isString() ? normalize_string_key('') : null, 'exactMatch' => false, 'selectionKey' => null, 'function' => $annotation?->function, 'source' => null];
        }
        $value = $this->value($name);
        try {
            $rendered = operand_value_to_string($value['rawValue']);
            $selectionKey = selection_key($this->locale, $annotation, $value);
        } catch (\Throwable $error) {
            if (!$this->fallback) {
                throw $error;
            }
            $this->errors[] = fallback_error($error);
            $this->errors[] = new MF2Error('bad-selector', 'Selector operand is not available.');
            return [
                'rendered' => '',
                'rawValue' => '',
                'normalizedRendered' => $annotation?->isString() ? normalize_string_key('') : null,
                'exactMatch' => false,
                'selectionKey' => null,
                'function' => null,
                'source' => null,
            ];
        }
        $this->recordSelectorResolutionErrors($annotation);
        return [
            'rendered' => $rendered,
            'rawValue' => $value['rawValue'],
            'normalizedRendered' => $annotation?->isString() ? normalize_string_key($rendered) : null,
            'exactMatch' => $annotation === null || $annotation->exactMatch(),
            'selectionKey' => $selectionKey,
            'function' => $annotation?->function,
            'source' => $value['source'],
        ];
    }

    public function formatPatternToParts(array $pattern): array
    {
        $parts = [];
        foreach ($pattern as $part) {
            if (is_string($part)) {
                $parts[] = ['type' => 'text', 'value' => $part];
                continue;
            }
            if (($part['type'] ?? '') === 'expression') {
                $output = $this->formatExpressionOutput($part);
                if ($output['hadError']) {
                    $source = $output['fallbackSource'] ?? fallback_source($part);
                    $fallbackPart = ['type' => 'fallback', 'source' => $source];
                    if ($output['value'] !== fallback_value($source)) {
                        $fallbackPart['value'] = $output['value'];
                    }
                    $parts[] = $fallbackPart;
                } else {
                    $expressionPart = ['type' => 'expression', 'value' => $output['value']];
                    if (isset($part['attributes']) && count($part['attributes']) > 0) {
                        $expressionPart['attributes'] = $part['attributes'];
                    }
                    if ($output['direction'] !== null) {
                        $expressionPart['direction'] = $output['direction'];
                    }
                    $parts[] = $expressionPart;
                }
                continue;
            }
            if (($part['type'] ?? '') === 'markup') {
                if (isset($part['options']['u:dir'])) {
                    $error = new MF2Error('bad-option', 'u:dir is not valid on markup.');
                    if (!$this->fallback) {
                        throw $error;
                    }
                    $this->errors[] = $error;
                }
                $markup = ['type' => 'markup', 'kind' => $part['kind'] ?? '', 'name' => $part['name'] ?? ''];
                if (isset($part['options']) && count($part['options']) > 0) {
                    $markup['options'] = $part['options'];
                }
                if (isset($part['attributes']) && count($part['attributes']) > 0) {
                    $markup['attributes'] = $part['attributes'];
                }
                $parts[] = $markup;
                continue;
            }
            throw new MF2Error('unsupported-pattern-part', 'Unsupported pattern part: ' . ($part['type'] ?? ''));
        }
        return $parts;
    }

    private function formatExpressionOutput(array $expression): array
    {
        $source = null;
        $arg = $expression['arg'] ?? null;
        if ($arg === null) {
            $value = '';
            $rawValue = '';
        } elseif (($arg['type'] ?? '') === 'literal') {
            $value = (string) ($arg['value'] ?? '');
            $rawValue = $value;
        } elseif (($arg['type'] ?? '') === 'variable') {
            $name = (string) ($arg['name'] ?? '');
            if (!$this->hasValue($name)) {
                if (!$this->fallback) {
                    throw MF2Error::missingArgument($name);
                }
                $error = unresolved_variable($name);
                if (!isset($this->failedLocals[$name])) {
                    $this->errors[] = $error;
                }
                if (isset($expression['function'])) {
                    $this->errors[] = MF2Error::badOperand('Function operand is not available.');
                }
                $source = fallback_source($expression);
                return [
                    'value' => $this->recoverMissingArgument($expression, $name, $source, $error),
                    'hadError' => true,
                    'source' => null,
                    'direction' => null,
                    'fallbackSource' => $source,
                ];
            }
            $resolved = $this->value($name);
            $rawValue = $resolved['rawValue'];
            $value = null;
            $source = $resolved['source'];
        } else {
            throw new MF2Error('unsupported-expression-arg', 'Unsupported expression arg: ' . ($arg['type'] ?? ''));
        }
        $functionRef = $expression['function'] ?? null;
        if ($functionRef === null) {
            try {
                $value = primitive_value_to_string($rawValue, $this->locale);
            } catch (\Throwable $error) {
                if (!$this->fallback) {
                    throw $error;
                }
                $recoverable = fallback_error($error);
                $this->errors[] = $recoverable;
                $source = fallback_source($expression);
                return [
                    'value' => $this->recoverFormatError($expression, $source, $recoverable),
                    'hadError' => true,
                    'source' => null,
                    'direction' => null,
                    'fallbackSource' => $source,
                ];
            }
            return ['value' => $value, 'rawValue' => $rawValue, 'hadError' => false, 'source' => $source, 'direction' => bidi_direction_from_source($source)];
        }
        $this->recordFunctionResolutionErrors($functionRef, $source);
        try {
            $direction = bidi_direction_for_function($functionRef, $source);
            $value ??= operand_value_to_string($rawValue);
            $formatted = $this->functions->format([
                'value' => $value,
                'rawValue' => $rawValue,
                'function' => $functionRef,
                'locale' => $this->locale,
                'optionValue' => fn(string $name, mixed $fallback): mixed => $this->optionValue($functionRef, $name, $fallback),
                'inheritedSource' => $source,
            ]);
            $sourceValue = $source['value'] ?? $value;
            return ['value' => $formatted, 'rawValue' => $formatted, 'hadError' => false, 'source' => $this->functionSource($sourceValue, $functionRef, $source), 'direction' => $direction];
        } catch (\Throwable $error) {
            if (!$this->fallback) {
                throw $error;
            }
            $recoverable = fallback_error($error);
            $this->errors[] = $recoverable;
            $source = fallback_source($expression);
            return [
                'value' => $this->recoverFormatError($expression, $source, $recoverable),
                'hadError' => true,
                'source' => null,
                'direction' => null,
                'fallbackSource' => $source,
            ];
        }
    }

    private function recoverMissingArgument(array $expression, string $variableName, string $source, MF2Error $error): string
    {
        return recover_value($this->onMissingArgument, [
            'code' => $error->mf2Code,
            'message' => $error->getMessage(),
            'locale' => $this->locale,
            'variableName' => $variableName,
            'functionName' => $expression['function']['name'] ?? null,
            'sourceExpression' => expression_source($expression),
            'fallbackValue' => fallback_value($source),
            'error' => $error,
        ]);
    }

    private function recoverFormatError(array $expression, string $source, MF2Error $error): string
    {
        $arg = $expression['arg'] ?? [];
        return recover_value($this->onFormatError, [
            'code' => $error->mf2Code,
            'message' => $error->getMessage(),
            'locale' => $this->locale,
            'variableName' => ($arg['type'] ?? '') === 'variable' ? ($arg['name'] ?? null) : null,
            'functionName' => $expression['function']['name'] ?? null,
            'sourceExpression' => expression_source($expression),
            'fallbackValue' => fallback_value($source),
            'error' => $error,
        ]);
    }

    private function optionValue(array $functionRef, string $optionName, mixed $fallback): mixed
    {
        $option = $functionRef['options'][$optionName] ?? null;
        if ($option === null) {
            return $fallback;
        }
        if (($option['type'] ?? '') === 'literal') {
            return (string) ($option['value'] ?? '');
        }
        if (($option['type'] ?? '') === 'variable') {
            $name = (string) ($option['name'] ?? '');
            if (!$this->hasValue($name)) {
                throw MF2Error::missingArgument($name);
            }
            return option_value_to_string($this->value($name)['rawValue']);
        }
        return $fallback;
    }

    private function hasValue(string $name): bool
    {
        return !isset($this->failedLocals[$name]) && (array_key_exists($name, $this->locals) || array_key_exists($name, $this->arguments));
    }

    private function value(string $name): array
    {
        return $this->locals[$name] ?? ['rawValue' => $this->arguments[$name], 'source' => null];
    }

    private function recordFunctionResolutionErrors(array $functionRef, ?array $source): void
    {
        if (!is_numeric_function($functionRef)) {
            return;
        }
        if (!numeric_select_uses_variable($functionRef) && !inherited_exact_numeric_source($source)) {
            return;
        }
        $error = new MF2Error('bad-option', 'Numeric select option is not valid in this context.');
        if (!$this->fallback) {
            throw $error;
        }
        $this->errors[] = $error;
    }

    private function recordSelectorResolutionErrors(?SelectorAnnotation $annotation): void
    {
        if (($annotation?->function['name'] ?? '') !== 'currency') {
            return;
        }
        $error = new MF2Error('bad-selector', 'Currency selector is not supported.');
        if (!$this->fallback) {
            throw $error;
        }
        $this->errors[] = $error;
    }

    private function functionSource(string $value, array $functionRef, ?array $inherited): array
    {
        return [
            'value' => $value,
            'function' => $functionRef,
            'inherited' => $inherited,
            'optionValue' => fn(string $name, mixed $fallback): mixed => $this->optionValue($functionRef, $name, $fallback),
        ];
    }

    private function validateVariant(array $variant, array $selectorValues, array &$signatures): void
    {
        if (count($variant['keys'] ?? []) !== count($selectorValues)) {
            throw new MF2Error('variant-key-count-mismatch', 'Variant key count must match selector count.');
        }
        $signature = json_encode(variant_key_signature($variant['keys'] ?? [], $selectorValues), JSON_UNESCAPED_UNICODE);
        if (isset($signatures[$signature])) {
            throw new MF2Error('duplicate-variant', 'Select variants must have unique key tuples.');
        }
        $signatures[$signature] = true;
    }

    private function variantMatchRank(array $variant, array $selectorValues): ?array
    {
        if (count($variant['keys'] ?? []) !== count($selectorValues)) {
            return null;
        }
        $rank = [];
        foreach (($variant['keys'] ?? []) as $index => $key) {
            $itemRank = $this->keyMatchRank($key, $selectorValues[$index]);
            if ($itemRank === null) {
                return null;
            }
            $rank[] = $itemRank;
        }
        return $rank;
    }

    private function keyMatchRank(array $key, array $selector): ?int
    {
        if (($key['type'] ?? '') === '*') {
            return 0;
        }
        $value = (string) ($key['value'] ?? '');
        if ($selector['exactMatch'] && numeric_literal_key_matches_source($value, $selector)) {
            return 3;
        }
        if ($selector['exactMatch'] && !is_numeric_function($selector['function']) && literal_key_matches($value, $selector)) {
            return 2;
        }
        if (($key['value'] ?? null) === $selector['selectionKey']) {
            return 1;
        }
        if ($selector['function'] === null) {
            return null;
        }
        try {
            return $this->functions->select([
                'value' => $selector['rendered'],
                'rawValue' => $selector['rawValue'],
                'function' => $selector['function'],
                'key' => $value,
                'locale' => $this->locale,
                'optionValue' => fn(string $name, mixed $fallback): mixed => $this->optionValue($selector['function'], $name, $fallback),
                'inheritedSource' => $selector['source'],
            ]);
        } catch (\Throwable $error) {
            if (!$this->fallback) {
                throw $error;
            }
            $this->errors[] = fallback_error($error);
            $this->errors[] = new MF2Error('bad-selector', 'Selector failed to match.');
            return null;
        }
    }
}

function validate_model(array $model): void
{
    $type = (string) ($model['type'] ?? '');
    $declarations = model_object_entries(model_array_field($model, 'declarations'), 'declarations');
    validate_declarations($declarations);
    if ($type === 'message') {
        validate_pattern(model_array_field($model, 'pattern'));
    } elseif ($type === 'select') {
        validate_selector_annotations($declarations, model_object_entries(model_array_field($model, 'selectors'), 'selectors'));
        foreach (model_object_entries(model_array_field($model, 'variants'), 'variants') as $variant) {
            model_object_entries(model_array_field($variant, 'keys'), 'variant keys');
            validate_pattern(model_array_field($variant, 'value'));
        }
    } else {
        throw new MF2Error('unsupported-message-type', "Unsupported message type: {$type}.");
    }
}

function model_array_field(array $model, string $name): array
{
    if (!array_key_exists($name, $model) || $model[$name] === null) {
        return [];
    }
    if (is_array($model[$name])) {
        return $model[$name];
    }
    throw MF2Error::badOption("{$name} must be an array.");
}

function model_object_entries(array $values, string $name): array
{
    foreach ($values as $value) {
        if (!is_array($value)) {
            throw MF2Error::badOption("{$name} entries must be objects.");
        }
    }
    return $values;
}

function validate_declarations(array $declarations): void
{
    $names = [];
    foreach ($declarations as $declaration) {
        if (($declaration['value'] ?? null) !== null) {
            validate_expression(model_object($declaration['value'], 'Expression'));
        }
        $name = (string) ($declaration['name'] ?? '');
        if (($declaration['type'] ?? '') === 'input') {
            validate_input_declaration($declaration);
        }
        if (isset($names[$name])) {
            throw new MF2Error('duplicate-declaration', "Declaration \${$name} is defined more than once.");
        }
        $names[$name] = true;
    }
    validate_local_references($declarations);
}

function validate_local_references(array $declarations): void
{
    $forbidden = [];
    for ($index = count($declarations) - 1; $index >= 0; $index -= 1) {
        $declaration = $declarations[$index];
        if (($declaration['type'] ?? '') !== 'local') {
            continue;
        }
        $forbidden[(string) ($declaration['name'] ?? '')] = true;
        if (expression_references_any($declaration['value'] ?? [], $forbidden)) {
            throw new MF2Error('duplicate-declaration', 'Local declaration $' . ($declaration['name'] ?? '') . ' must not reference itself or later local declarations.');
        }
    }
}

function expression_references_any(array $expression, array $names): bool
{
    if (arg_references_any($expression['arg'] ?? null, $names)) {
        return true;
    }
    foreach ($expression['function']['options'] ?? [] as $option) {
        if (arg_references_any($option, $names)) {
            return true;
        }
    }
    return false;
}

function arg_references_any(?array $arg, array $names): bool
{
    return ($arg['type'] ?? '') === 'variable' && isset($names[(string) ($arg['name'] ?? '')]);
}

function validate_input_declaration(array $declaration): void
{
    $arg = $declaration['value']['arg'] ?? null;
    if (($arg['type'] ?? '') === 'variable' && ($arg['name'] ?? '') === ($declaration['name'] ?? '')) {
        return;
    }
    throw new MF2Error('invalid-input-declaration', 'Input declaration $' . ($declaration['name'] ?? '') . ' must bind the same variable name.');
}

function validate_pattern(array $pattern): void
{
    foreach ($pattern as $part) {
        if (is_string($part)) {
            if ($part === '') {
                throw new MF2Error('invalid-pattern-text', 'Pattern text parts must be non-empty.');
            }
            continue;
        }
        if (!is_array($part)) {
            throw new MF2Error('unsupported-pattern-part', 'Unsupported pattern part: ');
        }
        if (($part['type'] ?? '') === 'expression') {
            validate_expression($part);
            continue;
        }
        if (($part['type'] ?? '') === 'markup') {
            validate_markup($part);
            continue;
        }
        throw new MF2Error('unsupported-pattern-part', 'Unsupported pattern part: ' . ($part['type'] ?? ''));
    }
}

function model_object(mixed $value, string $label): array
{
    if (is_array($value)) {
        return $value;
    }
    throw MF2Error::badOption("{$label} must be an object.");
}

function validate_expression(array $expression): void
{
    if (isset($expression['arg']) && !is_array($expression['arg'])) {
        throw new MF2Error('unsupported-expression-arg', 'Unsupported expression arg: ');
    }
    if (isset($expression['function'])) {
        validate_function_ref(model_object($expression['function'], 'Function reference'));
    }
}

function validate_function_ref(array $functionRef): void
{
    validate_options_map($functionRef['options'] ?? null, 'function options');
}

function validate_options_map(mixed $options, string $label): void
{
    if ($options === null) {
        return;
    }
    if (!is_array($options)) {
        throw MF2Error::badOption("{$label} must be an object.");
    }
    foreach ($options as $option) {
        if (!is_array($option)) {
            throw MF2Error::badOption("{$label} values must be objects.");
        }
    }
}

function validate_markup(array $markup): void
{
    validate_options_map($markup['options'] ?? null, 'markup options');
    if (in_array($markup['kind'] ?? '', ['open', 'standalone', 'close'], true)) {
        return;
    }
    throw new MF2Error('invalid-markup-kind', 'Markup kind must be open, standalone, or close.');
}

function validate_selector_annotations(array $declarations, array $selectors): void
{
    $annotations = selector_annotations($declarations);
    foreach ($selectors as $selector) {
        if (!isset($annotations[(string) ($selector['name'] ?? '')])) {
            throw new MF2Error('missing-selector-annotation', 'Selector $' . ($selector['name'] ?? '') . ' must reference a declaration with a function.');
        }
    }
}

function selector_annotations(array $declarations): array
{
    $expressions = [];
    $annotations = [];
    foreach ($declarations as $declaration) {
        $name = (string) ($declaration['name'] ?? '');
        $expressions[$name] = $declaration['value'] ?? [];
        if (isset($declaration['value']['function'])) {
            $annotations[$name] = SelectorAnnotation::from($declaration['value']['function']);
        }
    }
    $changed = true;
    while ($changed) {
        $changed = false;
        foreach ($expressions as $name => $expression) {
            if (isset($annotations[$name]) || ($expression['arg']['type'] ?? '') !== 'variable') {
                continue;
            }
            $sourceName = (string) ($expression['arg']['name'] ?? '');
            if (isset($annotations[$sourceName])) {
                $annotations[$name] = $annotations[$sourceName];
                $changed = true;
            }
        }
    }
    return $annotations;
}

final class SelectorAnnotation
{
    private function __construct(public array $function, public string $numberSelect)
    {
    }

    public static function from(array $functionRef): self
    {
        $option = $functionRef['options']['select'] ?? null;
        $select = ($option['type'] ?? '') === 'literal' ? (string) ($option['value'] ?? '') : 'plural';
        return new self($functionRef, in_array($select, ['ordinal', 'exact'], true) ? $select : 'plural');
    }

    public function exactMatch(): bool
    {
        return ($this->function['name'] ?? '') === 'string' || ($this->isNumeric() && $this->numberSelect === 'exact');
    }

    public function isString(): bool
    {
        return ($this->function['name'] ?? '') === 'string';
    }

    public function isNumeric(): bool
    {
        return in_array($this->function['name'] ?? '', ['number', 'integer', 'percent', 'offset'], true);
    }
}

function selection_key(string $locale, ?SelectorAnnotation $annotation, array $resolvedValue): ?string
{
    if ($annotation === null || !$annotation->isNumeric() || $annotation->numberSelect === 'exact') {
        return null;
    }
    $operand = operand_value_to_string($resolvedValue['rawValue']);
    if (($annotation->function['name'] ?? '') === 'percent') {
        $operand = str_ends_with($operand, '%') ? substr($operand, 0, -1) : value_to_string(((float) $operand) * 100);
    }
    return select_plural_category($locale, $operand, $annotation->numberSelect);
}

function select_plural_category(string $locale, mixed $value, string $select = 'plural'): ?string
{
    try {
        return $select === 'ordinal' ? select_ordinal($locale, $value) : select_cardinal($locale, $value);
    } catch (\Throwable) {
        return null;
    }
}

function variant_key_signature(array $keys, array $selectorValues): array
{
    $output = [];
    foreach ($keys as $index => $key) {
        if (($key['type'] ?? '') === '*') {
            $output[] = ['*', ''];
            continue;
        }
        $selector = $selectorValues[$index];
        $output[] = ['=', $selector['normalizedRendered'] === null ? (string) ($key['value'] ?? '') : normalize_string_key((string) ($key['value'] ?? ''))];
    }
    return $output;
}

function compare_rank(array $left, array $right): int
{
    $length = min(count($left), count($right));
    for ($index = 0; $index < $length; $index += 1) {
        if ($left[$index] !== $right[$index]) {
            return $left[$index] - $right[$index];
        }
    }
    return count($left) - count($right);
}

function literal_key_matches(string $value, array $selector): bool
{
    return $selector['normalizedRendered'] === null ? $value === $selector['rendered'] : normalize_string_key($value) === $selector['normalizedRendered'];
}

function numeric_literal_key_matches_source(string $value, array $selector): bool
{
    $sourceKey = preferred_numeric_source_key($selector);
    return $sourceKey !== null && $value === $sourceKey && parse_decimal_operand($value) !== null;
}

function preferred_numeric_source_key(array $selector): ?string
{
    $functionName = (string) ($selector['function']['name'] ?? '');
    if ($functionName !== 'number' && $functionName !== 'percent') {
        return null;
    }
    $sourceValue = numeric_source_value($selector['source'], $functionName);
    if ($sourceValue === null) {
        return null;
    }
    $operand = parse_preferred_source_decimal($sourceValue);
    if ($operand === null) {
        return null;
    }
    if ($functionName === 'percent') {
        $operand['scale'] -= 2;
        return render_preferred_source_decimal($operand, false);
    }
    return $operand['hasExponent'] ? render_preferred_source_decimal($operand, true) : $sourceValue;
}

function numeric_source_value(?array $source, string $functionName): ?string
{
    for ($current = $source; $current !== null; $current = $current['inherited'] ?? null) {
        if (($current['function']['name'] ?? '') === $functionName) {
            return (string) ($current['value'] ?? '');
        }
    }
    return null;
}

function parse_preferred_source_decimal(string $value): ?array
{
    if (preg_match('/^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/', $value, $matches) !== 1) {
        return null;
    }
    $exponent = parse_preferred_source_exponent($matches[4] ?? '');
    if ($exponent === null) {
        return null;
    }
    $fraction = $matches[3] ?? '';
    $digits = ltrim($matches[2] . $fraction, '0');
    if ($digits === '') {
        $digits = '0';
    }
    return [
        'negative' => $matches[1] === '-' && $digits !== '0',
        'digits' => $digits,
        'scale' => strlen($fraction) - $exponent,
        'hasExponent' => ($matches[4] ?? '') !== '',
    ];
}

function parse_preferred_source_exponent(string $value): ?int
{
    if ($value === '') {
        return 0;
    }
    $negative = str_starts_with($value, '-');
    $unsigned = $negative || str_starts_with($value, '+') ? substr($value, 1) : $value;
    $digits = ltrim($unsigned, '0');
    if ($digits === '') {
        $digits = '0';
    }
    if (strlen($digits) > 7) {
        return null;
    }
    $parsed = intval($digits);
    if ($parsed > 1000000) {
        return null;
    }
    return $negative ? -$parsed : $parsed;
}

function render_preferred_source_decimal(array $operand, bool $trimFractionZeros): ?string
{
    $digits = (string) $operand['digits'];
    $scale = (int) $operand['scale'];
    $extraLength = $scale > strlen($digits) ? $scale - strlen($digits) : max(-$scale, 0);
    if (strlen($digits) + $extraLength + 2 > 4096) {
        return null;
    }
    if ($scale <= 0) {
        $text = $digits . str_repeat('0', -$scale);
    } elseif ($scale >= strlen($digits)) {
        $text = '0.' . str_repeat('0', $scale - strlen($digits)) . $digits;
    } else {
        $split = strlen($digits) - $scale;
        $text = substr($digits, 0, $split) . '.' . substr($digits, $split);
    }
    if ($trimFractionZeros && str_contains($text, '.')) {
        $text = rtrim(rtrim($text, '0'), '.');
    }
    return $operand['negative'] ? "-{$text}" : $text;
}

function normalize_string_key(string $value): string
{
    return \Normalizer::normalize($value, \Normalizer::FORM_C) ?: $value;
}

function unresolved_variable(string $name): MF2Error
{
    return new MF2Error('unresolved-variable', "Variable \${$name} could not be resolved.");
}

function fallback_error(\Throwable $error): MF2Error
{
    $mf2 = as_mf2_error($error);
    return $mf2->mf2Code === 'unsupported-function' ? new MF2Error('unknown-function', $mf2->getMessage()) : $mf2;
}

function as_mf2_error(\Throwable $error): MF2Error
{
    return $error instanceof MF2Error ? $error : new MF2Error('error', $error->getMessage());
}

function fallback_source(array $expression): string
{
    if (isset($expression['arg'])) {
        return expression_arg_source($expression['arg']);
    }
    if (isset($expression['function'])) {
        return function_name_source($expression['function']);
    }
    return '';
}

function fallback_value(string $source): string
{
    return '{' . $source . '}';
}

function recover_value(mixed $handler, array $context): string
{
    if (is_callable($handler)) {
        $value = $handler($context);
        if ($value !== null) {
            return (string) $value;
        }
    }
    return (string) $context['fallbackValue'];
}

function expression_source(array $expression): string
{
    $items = [];
    if (isset($expression['arg'])) {
        $items[] = expression_arg_source($expression['arg']);
    }
    if (isset($expression['function'])) {
        $items[] = function_source($expression['function']);
    }
    return '{' . implode(' ', $items) . '}';
}

function expression_arg_source(array $arg): string
{
    if (($arg['type'] ?? '') === 'variable') {
        return '$' . ($arg['name'] ?? '');
    }
    return quote_literal_source((string) ($arg['value'] ?? ''));
}

function function_source(array $functionRef): string
{
    $source = ':' . ($functionRef['name'] ?? '');
    foreach ($functionRef['options'] ?? [] as $name => $value) {
        $source .= ' ' . $name . '=' . expression_arg_source($value);
    }
    return $source;
}

function function_name_source(array $functionRef): string
{
    return ':' . ($functionRef['name'] ?? '');
}

function quote_literal_source(string $value): string
{
    return '|' . str_replace(['\\', '|'], ['\\\\', '\\|'], $value) . '|';
}

function parts_to_string(array $parts, string $bidiIsolation = 'none'): string
{
    $output = '';
    foreach ($parts as $part) {
        if (($part['type'] ?? '') === 'text') {
            $output .= (string) ($part['value'] ?? '');
        } elseif (($part['type'] ?? '') === 'fallback') {
            $output .= array_key_exists('value', $part)
                ? (string) $part['value']
                : fallback_value((string) ($part['source'] ?? ''));
        } elseif (($part['type'] ?? '') === 'expression') {
            $output .= isolate_expression((string) ($part['value'] ?? ''), $bidiIsolation, $part['direction'] ?? null);
        }
    }
    return $output;
}

function isolate_expression(string $value, string $bidiIsolation, ?string $direction): string
{
    return $bidiIsolation === 'default' ? bidi_marker($direction) . $value . "\u{2069}" : $value;
}

function bidi_marker(?string $direction): string
{
    return match ($direction) {
        'ltr' => "\u{2066}",
        'rtl' => "\u{2067}",
        default => "\u{2068}",
    };
}

function bidi_direction_for_function(array $functionRef, ?array $source): ?string
{
    $value = function_option_literal($functionRef, 'u:dir', null);
    if ($value !== null) {
        return parse_bidi_direction($value);
    }
    return bidi_direction_from_source($source);
}

function bidi_direction_from_source(?array $source): ?string
{
    if ($source === null) {
        return null;
    }
    $value = function_option_literal($source['function'], 'u:dir', null);
    if ($value !== null) {
        return parse_bidi_direction($value);
    }
    return bidi_direction_from_source($source['inherited']);
}

function parse_bidi_direction(string $value): string
{
    if (in_array($value, ['auto', 'ltr', 'rtl'], true)) {
        return $value;
    }
    throw new MF2Error('bad-option', 'u:dir option must be auto, ltr, or rtl.');
}

function value_to_string(mixed $value): string
{
    if ($value === null) {
        return '';
    }
    if ($value instanceof \DateTimeInterface) {
        return $value->format(\DateTimeInterface::ATOM);
    }
    if (is_bool($value)) {
        return $value ? 'true' : 'false';
    }
    if (is_int($value)) {
        return (string) $value;
    }
    if (is_float($value)) {
        if (is_finite($value) && floor($value) === $value) {
            if ($value >= PHP_INT_MIN && $value <= PHP_INT_MAX) {
                return (string) (int) $value;
            }
            return sprintf('%.0F', $value);
        }
        return rtrim(rtrim(sprintf('%.14F', $value), '0'), '.');
    }
    return (string) $value;
}

function operand_value_to_string(mixed $value): string
{
    try {
        return value_to_string($value);
    } catch (\Throwable $error) {
        if ($error instanceof MF2Error) {
            throw $error;
        }
        throw MF2Error::badOperand($error->getMessage());
    }
}

function option_value_to_string(mixed $value): string
{
    try {
        return value_to_string($value);
    } catch (\Throwable $error) {
        throw MF2Error::badOption($error->getMessage());
    }
}

function primitive_value_to_string(mixed $value, string $locale): string
{
    if (is_int($value) || is_float($value)) {
        return \Mojito\MessageFormat2\NumberCore::format($value, ['locale' => $locale]);
    }
    return operand_value_to_string($value);
}
