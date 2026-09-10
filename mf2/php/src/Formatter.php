<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class FunctionRegistry
{
    public function __construct(private array $formatters, private array $selectors, private array $numericFormatters = [])
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
        return new self($formatters, $this->selectors, array_diff_key($this->numericFormatters, [$name => true]));
    }

    public function withSelector(string $name, callable $selector): self
    {
        $selectors = $this->selectors;
        $selectors[$name] = $selector;
        return new self($this->formatters, $selectors, $this->numericFormatters);
    }

    /** Registers numeric output with the locale's direction. Ordinary overrides clear the guarantee. */
    public function withNumericFunction(string $name, callable $formatter): self
    {
        $formatters = $this->formatters;
        $formatters[$name] = $formatter;
        return new self($formatters, $this->selectors, $this->numericFormatters + [$name => true]);
    }

    public function isNumericFormatter(?array $function): bool
    {
        return isset($this->numericFormatters[$function['name'] ?? '']);
    }

    /** @internal Numeric category bounds apply to the built-in selectors. */
    public function isPortableNumericSelector(?array $functionRef): bool
    {
        $name = $functionRef['name'] ?? '';
        return in_array($name, ['number', 'integer', 'percent', 'offset'], true)
            && ($this->selectors[$name] ?? null) === 'Mojito\\MessageFormat2\\Internal\\select_' . $name;
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
    $result = Internal\render_message($model, $arguments, $options);
    return [
        'value' => Internal\parts_to_string($result['parts'], $options['bidiIsolation'] ?? 'none', $result['expressionIsolation'] ?? []),
        'errors' => $result['errors'],
        'ok' => $result['errors'] === [],
        'hasErrors' => $result['errors'] !== [],
    ];
}

function format_message_to_parts(array $model, array $arguments = [], array $options = []): array
{
    $result = Internal\render_message($model, $arguments, $options);
    unset($result['expressionIsolation']);
    return $result;
}

namespace Mojito\MessageFormat2\Internal;

use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\MF2Error;

function render_message(array $model, array $arguments, array $options): array
{
    try {
        validate_model($model);
    } catch (\Throwable $error) {
        return ['parts' => [], 'errors' => [as_mf2_error($error)], 'ok' => false, 'hasErrors' => true];
    }
    $context = new FormatContext(
        $arguments,
        locale_option($options),
        functions_option($options),
        true,
        $options['onMissingArgument'] ?? null,
        $options['onFormatError'] ?? null,
    );
    try {
        $context->applyDeclarations($model['declarations'] ?? []);
    } catch (\Throwable $error) {
        $context->errors[] = as_mf2_error($error);
    }
    try {
        $parts = ($model['type'] ?? '') === 'message'
            ? $context->formatPatternToParts($model['pattern'] ?? [])
            : $context->formatSelectToParts($model['selectors'] ?? [], $model['variants'] ?? []);
    } catch (\Throwable $error) {
        $context->errors[] = as_mf2_error($error);
        $parts = [];
    }
    return [
        'parts' => $parts,
        'expressionIsolation' => $context->expressionIsolation,
        'errors' => $context->errors,
        'ok' => $context->errors === [],
        'hasErrors' => $context->errors !== [],
    ];
}
function locale_option(array $options): string
{
    $locale = trim((string) ($options['locale'] ?? 'en'));
    return $locale === '' ? 'en' : $locale;
}

function functions_option(array $options): FunctionRegistry
{
    return $options['functions'] ?? FunctionRegistry::defaults();
}

final class FormatContext
{
    public array $errors = [];
    public array $expressionIsolation = [];
    private bool $localeIsLtr;
    private bool $memoizeSources;
    private array $locals = [];
    private array $failedLocals = [];
    private array $failedSelectors = [];
    private array $selectorAnnotations = [];

    public function __construct(
        private array $arguments,
        private string $locale,
        private FunctionRegistry $functions,
        private bool $fallback,
        private mixed $onMissingArgument = null,
        private mixed $onFormatError = null,
    ) {
        $this->localeIsLtr = locale_is_ltr($locale);
        $this->memoizeSources = isset(source_memo_registries()[$functions]);
        $snapshot = [];
        foreach ($this->arguments as $name => $value) $snapshot[normalize_string_key((string) $name)] = $value;
        $this->arguments = $snapshot;
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
                $name = normalize_string_key((string) ($declaration['name'] ?? ''));
                if ($output['hadError']) {
                    $this->failedLocals[$name] = true;
                    unset($this->locals[$name]);
                } else {
                    $this->locals[$name] = ['rawValue' => $output['value'], 'source' => $output['source']];
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
        $name = normalize_string_key((string) ($input['name'] ?? ''));
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
        $bidi = $this->resolveBidi($functionRef, $inputValue['source']);
        try {
            $rendered = value_to_string($inputValue['rawValue']);
            $formatted = $this->functions->format([
                'value' => $rendered,
                'rawValue' => $inputValue['rawValue'],
                'function' => $functionRef,
                'locale' => $this->locale,
                'optionValue' => fn(string $optionName, mixed $fallback): mixed => $this->resolvedOptionValue($functionRef, $inputValue['source'], $optionName, $fallback),
                'inheritedSource' => $inputValue['source'],
            ]);
            $sourceValue = $inputValue['source']['value'] ?? $rendered;
            $this->locals[$name] = ['rawValue' => $formatted, 'source' => $this->functionSource($sourceValue, $functionRef, $inputValue['source'], $bidi)];
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
        $name = normalize_string_key((string) ($selector['name'] ?? ''));
        $annotation = $this->selectorAnnotations[$name] ?? null;
        if (!$this->hasValue($name)) {
            if (!$this->fallback) {
                throw MF2Error::missingArgument($name);
            }
            if (!isset($this->failedLocals[$name])) {
                $this->errors[] = unresolved_variable($name);
            }
            if ($annotation !== null && !$annotation->isString()) {
                if (!isset($this->failedLocals[$name])) {
                    $this->errors[] = MF2Error::badOperand('Selector operand is not available.');
                }
                $this->errors[] = new MF2Error('bad-selector', 'Selector operand is not available.');
            }
            return ['rendered' => '', 'normalizedRendered' => $annotation?->isString() ? normalize_string_key('') : null, 'exactMatch' => false, 'selectionKey' => null, 'function' => null, 'source' => null];
        }
        $value = $this->value($name);
        $rendered = value_to_string($value['rawValue']);
        $this->recordSelectorResolutionErrors($annotation);
        $selectionKey = null;
        $failed = false;
        try {
            $selectionKey = selection_key(
                $this->locale,
                $this->functions->isPortableNumericSelector($annotation?->function) ? $annotation : null,
                $value,
                fn(string $optionName, mixed $fallback): mixed => $annotation === null
                    ? $fallback
                    : $this->resolvedOptionValue($annotation->function, $value['source'], $optionName, $fallback),
            );
        } catch (\Throwable $error) {
            if (!$this->fallback) throw $error;
            $recoverable = fallback_error($error);
            $this->errors[] = $recoverable;
            if ($recoverable->mf2Code !== 'bad-selector') $this->errors[] = MF2Error::badSelector('Selector failed to resolve.');
            $failed = true;
        }
        return [
            'rendered' => $rendered,
            'normalizedRendered' => $annotation?->isString() ? normalize_string_key($rendered) : null,
            'exactMatch' => !$failed && ($annotation === null || $annotation->exactMatch()),
            'selectionKey' => $selectionKey,
            'function' => $failed ? null : $annotation?->function,
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
                    $this->expressionIsolation[] = !$this->localeIsLtr || ($output['forceIsolation'] ?? false) || ($output['resolvedDirection'] ?? null) !== 'ltr';
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
            $name = normalize_string_key((string) ($arg['name'] ?? ''));
            if (!$this->hasValue($name)) {
                if (!$this->fallback) {
                    throw MF2Error::missingArgument($name);
                }
                $error = unresolved_variable($name);
                if (!isset($this->failedLocals[$name])) {
                    $this->errors[] = $error;
                }
                if (isset($expression['function'])) {
                    $this->errors[] = $this->functions->hasFormatter($expression['function']) ? MF2Error::badOperand('Function operand is not available.') : new MF2Error('unknown-function', 'Unknown function.');
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
            $value = value_to_string($rawValue);
            $source = $resolved['source'];
        } else {
            throw new MF2Error('unsupported-expression-arg', 'Unsupported expression arg: ' . ($arg['type'] ?? ''));
        }
        $functionRef = $expression['function'] ?? null;
        if ($functionRef === null && $source === null && (is_int($rawValue) || is_float($rawValue))) $functionRef = ['type' => 'function', 'name' => 'number'];
        if ($functionRef === null) {
            return ['value' => $value, 'hadError' => false, 'source' => $source, 'direction' => $source['bidi']['direction'] ?? null, 'resolvedDirection' => $source['bidi']['resolvedDirection'] ?? null, 'forceIsolation' => $source['bidi']['force'] ?? false];
        }
        $this->recordFunctionResolutionErrors($functionRef, $source);
        $bidi = $this->resolveBidi($functionRef, $source);
        try {
            $formatted = $this->functions->format([
                'value' => $value,
                'rawValue' => $rawValue,
                'function' => $functionRef,
                'locale' => $this->locale,
                'optionValue' => fn(string $name, mixed $fallback): mixed => $this->resolvedOptionValue($functionRef, $source, $name, $fallback),
                'inheritedSource' => $source,
            ]);
            $sourceValue = $source['value'] ?? $value;
            return ['value' => $formatted, 'hadError' => false, 'source' => $this->functionSource($sourceValue, $functionRef, $source, $bidi), 'direction' => $bidi['direction'], 'resolvedDirection' => $bidi['resolvedDirection'], 'forceIsolation' => $bidi['force']];
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

    private function resolveBidi(array $function, ?array $source): array
    {
        $inherited = $source['bidi']['direction'] ?? null;
        $resolved = $source['bidi']['resolvedDirection'] ?? null;
        if ($inherited === null && $resolved === null && $this->localeIsLtr && $this->functions->isNumericFormatter($function)) $resolved = 'ltr';
        $default = ['direction' => $inherited, 'resolvedDirection' => $resolved, 'force' => false];
        $option = $function['options']['u:dir'] ?? null;
        if ($option === null) return $default;
        if (($option['type'] ?? '') === 'variable') {
            $name = normalize_string_key($option['name']);
            if (!$this->hasValue($name)) {
                $this->errors[] = unresolved_variable($name);
                $this->errors[] = MF2Error::badOption('u:dir option must resolve to ltr, rtl, auto, or inherit.');
                return $default;
            }
            $raw = $this->value($name)['rawValue'];
        } else $raw = $option['value'] ?? null;
        if ($raw === 'inherit') return $default;
        if (is_string($raw) && in_array($raw, ['ltr', 'rtl', 'auto'], true)) return ['direction' => $raw, 'resolvedDirection' => $raw === 'auto' ? null : $raw, 'force' => true];
        $this->errors[] = MF2Error::badOption('u:dir option must resolve to ltr, rtl, auto, or inherit.');
        return $default;
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
            $name = normalize_string_key((string) ($option['name'] ?? ''));
            if (!$this->hasValue($name)) {
                throw MF2Error::missingArgument($name);
            }
            return value_to_string($this->value($name)['rawValue']);
        }
        return $fallback;
    }

    private function resolvedOptionValue(array $functionRef, ?array $source, string $optionName, mixed $fallback): mixed
    {
        if ($optionName === 'u:dir') return $fallback;
        if (array_key_exists($optionName, $functionRef['options'] ?? [])) {
            return $this->optionValue($functionRef, $optionName, $fallback);
        }
        return $this->inheritedNumericOptionValue((string) ($functionRef['name'] ?? ''), $source, $optionName, $fallback);
    }

    private function inheritedNumericOptionValue(string $targetFunction, ?array $source, string $optionName, mixed $fallback): mixed
    {
        if (numeric_option_is_discarded($targetFunction, $optionName)) return $fallback;
        $visited = [];
        $result = [false, null];
        while ($source !== null) {
            $sourceFunction = (string) ($source['function']['name'] ?? '');
            if (!inherits_numeric_options_from($targetFunction, $sourceFunction) || numeric_option_is_discarded($sourceFunction, $optionName)) break;
            $memo = $source['_memo'] ?? null;
            $cached = $memo?->option($optionName);
            if ($cached !== null) {
                $result = $cached;
                break;
            }
            if ($memo !== null) $visited[] = $memo;
            if (array_key_exists($optionName, $source['function']['options'] ?? [])) {
                $result = [true, ($source['optionValue'])($optionName, $fallback)];
                break;
            }
            $targetFunction = $sourceFunction;
            $source = $source['inherited'];
        }
        foreach ($visited as $memo) $memo->rememberOption($optionName, $result);
        return $result[0] ? $result[1] : $fallback;
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
        if (!numeric_select_uses_variable($functionRef)
            && !inherited_exact_numeric_source($source, (string) ($functionRef['name'] ?? ''))) {
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

    private function functionSource(string $value, array $functionRef, ?array $inherited, array $bidi): array
    {
        $cacheable = $this->memoizeSources && ($inherited === null || ($inherited['_memo'] ?? null) !== null);
        foreach ($functionRef['options'] ?? [] as $option) {
            if (($option['type'] ?? '') !== 'literal') $cacheable = false;
        }
        return [
            'value' => $value,
            'function' => $functionRef,
            'inherited' => $inherited,
            'bidi' => $bidi,
            '_memo' => $cacheable ? new SourceMemo() : null,
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
            $itemRank = $this->keyMatchRank($key, $selectorValues[$index], $index);
            if ($itemRank === null) {
                return null;
            }
            $rank[] = $itemRank;
        }
        return $rank;
    }

    private function keyMatchRank(array $key, array $selector, int $index): ?int
    {
        if (($key['type'] ?? '') === '*') {
            return 0;
        }
        if (($selector['exactMatch'] && literal_key_matches((string) ($key['value'] ?? ''), $selector)) || (($key['value'] ?? null) === $selector['selectionKey'])) {
            return 1;
        }
        if (isset($this->failedSelectors[$index]) || $selector['function'] === null) {
            return null;
        }
        try {
            return $this->functions->select([
                'value' => $selector['rendered'],
                'rawValue' => $selector['rendered'],
                'function' => $selector['function'],
                'key' => (string) ($key['value'] ?? ''),
                'locale' => $this->locale,
                'optionValue' => fn(string $name, mixed $fallback): mixed => $this->resolvedOptionValue(
                    $selector['function'],
                    $selector['source'],
                    $name,
                    $fallback,
                ),
                'inheritedSource' => $selector['source'],
            ]);
        } catch (\Throwable $error) {
            if (!$this->fallback) {
                throw $error;
            }
            $recoverable = fallback_error($error);
            $this->errors[] = $recoverable;
            if ($recoverable->mf2Code !== 'bad-variant-key') {
                $this->failedSelectors[$index] = true;
                if ($recoverable->mf2Code !== 'bad-selector') $this->errors[] = new MF2Error('bad-selector', 'Selector failed to match.');
            }
            return null;
        }
    }
}

function valid_model_argument(mixed $value, bool $literalOnly = false): bool
{
    return is_array($value) && (($value['type'] ?? null) === 'literal'
        ? is_string($value['value'] ?? null)
        : (!$literalOnly && ($value['type'] ?? null) === 'variable' && is_string($value['name'] ?? null)));
}

function valid_model_fields(array $item, string $field, bool $attributes): bool
{
    if (!array_key_exists($field, $item)) return true;
    if (!is_array($item[$field]) || ($item[$field] !== [] && array_is_list($item[$field]))) return false;
    foreach ($item[$field] as $value) {
        if ($attributes && $value === true) continue;
        if (!valid_model_argument($value, $attributes)) return false;
    }
    return true;
}

function valid_model_expression(mixed $value): bool
{
    if (!is_array($value) || ($value['type'] ?? null) !== 'expression') return false;
    $arg = array_key_exists('arg', $value);
    $function = array_key_exists('function', $value);
    if (!$arg && !$function) return false;
    if ($arg && !valid_model_argument($value['arg'])) return false;
    if ($function) {
        $ref = $value['function'];
        if (!is_array($ref) || ($ref['type'] ?? null) !== 'function' || !is_string($ref['name'] ?? null) || !valid_model_fields($ref, 'options', false)) return false;
    }
    return valid_model_fields($value, 'attributes', true);
}

function valid_model_pattern(mixed $value): bool
{
    if (!is_array($value) || !array_is_list($value)) return false;
    foreach ($value as $part) {
        if (is_string($part)) continue;
        if (!is_array($part)) return false;
        if (($part['type'] ?? null) === 'expression') {
            if (!valid_model_expression($part)) return false;
        } elseif (($part['type'] ?? null) === 'markup') {
            if (!is_string($part['kind'] ?? null) || !is_string($part['name'] ?? null) || !valid_model_fields($part, 'options', false) || !valid_model_fields($part, 'attributes', true)) return false;
        } else return false;
    }
    return true;
}

function valid_model_shape(array $model): bool
{
    $declarations = $model['declarations'] ?? null;
    if (!is_array($declarations) || !array_is_list($declarations)) return false;
    foreach ($declarations as $declaration) {
        if (!is_array($declaration) || !in_array($declaration['type'] ?? null, ['input', 'local'], true)
            || !is_string($declaration['name'] ?? null) || !valid_model_expression($declaration['value'] ?? null)) return false;
    }
    if (($model['type'] ?? null) === 'message') return valid_model_pattern($model['pattern'] ?? null);
    if (($model['type'] ?? null) !== 'select') return false;
    $selectors = $model['selectors'] ?? null;
    $variants = $model['variants'] ?? null;
    if (!is_array($selectors) || !array_is_list($selectors) || !is_array($variants) || !array_is_list($variants)) return false;
    foreach ($selectors as $selector) {
        if (!is_array($selector) || ($selector['type'] ?? null) !== 'variable' || !is_string($selector['name'] ?? null)) return false;
    }
    foreach ($variants as $variant) {
        if (!is_array($variant) || !is_array($variant['keys'] ?? null) || !array_is_list($variant['keys']) || !valid_model_pattern($variant['value'] ?? null)) return false;
        foreach ($variant['keys'] as $key) {
            if (is_array($key) && ($key['type'] ?? null) === '*') {
                if (array_key_exists('value', $key) && !is_string($key['value'])) return false;
            } elseif (!valid_model_argument($key, true)) return false;
        }
    }
    return true;
}

function validate_model(array $model): void
{
    if (!valid_model_shape($model)) throw new MF2Error('invalid-model', 'Message model does not match the shared model schema.');
    validate_declarations($model['declarations'] ?? []);
    if (($model['type'] ?? '') === 'message') {
        validate_pattern($model['pattern'] ?? []);
    } elseif (($model['type'] ?? '') === 'select') {
        validate_selector_annotations($model['declarations'] ?? [], $model['selectors'] ?? []);
        $annotations = selector_annotations($model['declarations']);
        $selectors = array_map(static fn(array $selector): array => ['normalizedRendered' => ($annotations[normalize_string_key($selector['name'])] ?? null)?->isString() ? '' : null], $model['selectors']);
        $signatures = [];
        $fallback = false;
        foreach ($model['variants'] as $variant) {
            if (count($variant['keys']) !== count($selectors)) throw new MF2Error('variant-key-count-mismatch', 'Variant key count must match selector count.');
            $signature = json_encode(variant_key_signature($variant['keys'], $selectors), JSON_THROW_ON_ERROR);
            if (isset($signatures[$signature])) throw new MF2Error('duplicate-variant', 'Select variants must have unique key tuples.');
            $signatures[$signature] = true;
            $fallback = $fallback || count(array_filter($variant['keys'], static fn(array $key): bool => $key['type'] !== '*')) === 0;
        }
        if (!$fallback) throw new MF2Error('missing-fallback-variant', 'Select messages must include a catch-all fallback variant.');
        foreach ($model['variants'] ?? [] as $variant) {
            validate_pattern($variant['value'] ?? []);
        }
    }
}

function validate_declarations(array $declarations): void
{
    $names = [];
    foreach ($declarations as $declaration) {
        $name = normalize_string_key((string) ($declaration['name'] ?? ''));
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
        $forbidden[normalize_string_key((string) ($declaration['name'] ?? ''))] = true;
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
    return ($arg['type'] ?? '') === 'variable' && isset($names[normalize_string_key((string) ($arg['name'] ?? ''))]);
}

function validate_input_declaration(array $declaration): void
{
    $arg = $declaration['value']['arg'] ?? null;
    if (($arg['type'] ?? '') === 'variable' && normalize_string_key((string) ($arg['name'] ?? '')) === normalize_string_key((string) ($declaration['name'] ?? ''))) {
        return;
    }
    throw new MF2Error('invalid-input-declaration', 'Input declaration $' . ($declaration['name'] ?? '') . ' must bind the same variable name.');
}

function validate_pattern(array $pattern): void
{
    foreach ($pattern as $part) {
        if (is_string($part) && $part === '') {
            throw new MF2Error('invalid-pattern-text', 'Pattern text parts must be non-empty.');
        }
        if (is_array($part) && ($part['type'] ?? '') === 'markup') {
            validate_markup($part);
        }
    }
}

function validate_markup(array $markup): void
{
    if (in_array($markup['kind'] ?? '', ['open', 'standalone', 'close'], true)) {
        return;
    }
    throw new MF2Error('invalid-markup-kind', 'Markup kind must be open, standalone, or close.');
}

function validate_selector_annotations(array $declarations, array $selectors): void
{
    $annotations = selector_annotations($declarations);
    foreach ($selectors as $selector) {
        if (!isset($annotations[normalize_string_key((string) ($selector['name'] ?? ''))])) {
            throw new MF2Error('missing-selector-annotation', 'Selector $' . ($selector['name'] ?? '') . ' must reference a declaration with a function.');
        }
    }
}

function selector_annotations(array $declarations): array
{
    $expressions = [];
    $annotations = [];
    foreach ($declarations as $declaration) {
        $name = normalize_string_key((string) ($declaration['name'] ?? ''));
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
            $sourceName = normalize_string_key((string) ($expression['arg']['name'] ?? ''));
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

function selection_key(string $locale, ?SelectorAnnotation $annotation, array $resolvedValue, callable $optionValue): ?string
{
    if ($annotation === null || !$annotation->isNumeric() || $annotation->numberSelect === 'exact') {
        return null;
    }
    $operand = numeric_selection_operand($resolvedValue, $annotation->function, $optionValue);
    if ($operand === null) {
        return null;
    }
    return select_plural_category($locale, $operand, $annotation->numberSelect);
}

function select_plural_category(string $locale, mixed $value, string $select = 'plural'): ?string
{
    return $select === 'ordinal' ? select_ordinal($locale, $value) : select_cardinal($locale, $value);
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
        return ':' . $expression['function']['name'];
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

function quote_literal_source(string $value): string
{
    return '|' . str_replace(['\\', '|'], ['\\\\', '\\|'], $value) . '|';
}

function parts_to_string(array $parts, string $bidiIsolation = 'none', ?array $isolation = null): string
{
    $output = '';
    $expressionIndex = 0;
    foreach ($parts as $part) {
        if (($part['type'] ?? '') === 'text') {
            $output .= (string) ($part['value'] ?? '');
        } elseif (($part['type'] ?? '') === 'fallback') {
            $output .= array_key_exists('value', $part)
                ? (string) $part['value']
                : fallback_value((string) ($part['source'] ?? ''));
        } elseif (($part['type'] ?? '') === 'expression') {
            $mode = ($isolation[$expressionIndex++] ?? true) ? $bidiIsolation : 'none';
            $output .= isolate_expression((string) ($part['value'] ?? ''), $mode, $part['direction'] ?? null);
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
        if (!is_finite($value)) return (string) $value;
        // Preserve the float's shortest round-trippable decimal; native integer
        // casts wrap large values and fixed fraction widths erase small values.
        return canonical_decimal_operand(json_encode($value, JSON_THROW_ON_ERROR)) ?? (string) $value;
    }
    return (string) $value;
}
