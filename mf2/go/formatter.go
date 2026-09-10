package mf2

import (
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"

	"golang.org/x/text/unicode/norm"
)

type Options struct {
	Locale            string
	BidiIsolation     string
	Functions         FunctionRegistry
	OnMissingArgument RecoveryHandler
	OnFormatError     RecoveryHandler
}

type Part map[string]any

type RecoveryContext struct {
	Code             string
	Message          string
	Locale           string
	VariableName     string
	FunctionName     string
	SourceExpression string
	FallbackValue    string
	Error            Error
}

type RecoveryHandler func(RecoveryContext) (string, bool)

type FormatResult struct {
	Value  string
	Errors []Error
}

type PartsResult struct {
	Parts  []Part
	Errors []Error
}

func (result FormatResult) Ok() bool {
	return len(result.Errors) == 0
}

func (result FormatResult) HasErrors() bool {
	return len(result.Errors) > 0
}

func (result PartsResult) Ok() bool {
	return len(result.Errors) == 0
}

func (result PartsResult) HasErrors() bool {
	return len(result.Errors) > 0
}

type Formatter func(FunctionCall) (string, error)
type Selector func(FunctionMatch) (*int, error)

type FunctionRegistry struct {
	formatters        map[string]Formatter
	selectors         map[string]Selector
	numericFormatters map[string]bool
	customFormatters  map[string]bool
	customSelectors   map[string]bool
}

type FunctionCall struct {
	Value           string
	RawValue        any
	Function        map[string]any
	Locale          string
	OptionValue     func(string, string) (string, error)
	InheritedSource *FunctionSource
}

type FunctionMatch struct {
	Value           string
	RawValue        any
	Function        map[string]any
	Key             string
	Locale          string
	OptionValue     func(string, string) (string, error)
	InheritedSource *FunctionSource
}

type FunctionSource struct {
	cache       *sourceCache
	Value       string
	Function    map[string]any
	OptionValue func(string, string) (string, error)
	Inherited   *FunctionSource
}

func DefaultFunctionRegistry() FunctionRegistry {
	return PortableFunctionRegistry()
}

func (r FunctionRegistry) WithFunction(name string, formatter Formatter) FunctionRegistry {
	next := FunctionRegistry{formatters: map[string]Formatter{}, selectors: r.selectors, numericFormatters: map[string]bool{}, customFormatters: map[string]bool{}, customSelectors: r.customSelectors}
	for key, value := range r.customFormatters {
		next.customFormatters[key] = value
	}
	next.customFormatters[name] = true
	for key, value := range r.numericFormatters {
		if key != name {
			next.numericFormatters[key] = value
		}
	}
	for key, value := range r.formatters {
		next.formatters[key] = value
	}
	next.formatters[name] = formatter
	return next
}

func (r FunctionRegistry) WithSelector(name string, selector Selector) FunctionRegistry {
	next := FunctionRegistry{formatters: r.formatters, selectors: map[string]Selector{}, numericFormatters: r.numericFormatters, customFormatters: r.customFormatters, customSelectors: map[string]bool{}}
	for key, value := range r.customSelectors {
		next.customSelectors[key] = value
	}
	next.customSelectors[name] = true
	for key, value := range r.selectors {
		next.selectors[key] = value
	}
	next.selectors[name] = selector
	return next
}

func (r FunctionRegistry) HasFormatter(function map[string]any) bool {
	_, ok := r.formatters[stringField(function, "name")]
	return ok
}

func (r FunctionRegistry) HasSelector(function map[string]any) bool {
	_, ok := r.selectors[stringField(function, "name")]
	return ok
}

func (r FunctionRegistry) Format(call FunctionCall) (string, error) {
	formatter := r.formatters[stringField(call.Function, "name")]
	if formatter == nil {
		return "", unsupportedFunction(stringField(call.Function, "name"))
	}
	if r.customFormatters[stringField(call.Function, "name")] {
		call.Function = detachedFunction(call.Function)
		call.InheritedSource = detachedFunctionSource(call.InheritedSource)
	}
	return formatter(call)
}

func (r FunctionRegistry) Select(match FunctionMatch) (*int, error) {
	selector := r.selectors[stringField(match.Function, "name")]
	if selector == nil {
		return nil, nil
	}
	if r.customSelectors[stringField(match.Function, "name")] {
		match.Function = detachedFunction(match.Function)
		match.InheritedSource = detachedFunctionSource(match.InheritedSource)
	}
	return selector(match)
}

func FormatMessage(model Model, arguments map[string]any, options Options) FormatResult {
	result, isolation := renderMessage(model, arguments, options)
	return FormatResult{Value: renderPartsToString(result.Parts, bidiIsolation(options), isolation), Errors: result.Errors}
}

func FormatMessageToParts(model Model, arguments map[string]any, options Options) PartsResult {
	result, _ := renderMessage(model, arguments, options)
	return result
}

func renderMessage(model Model, arguments map[string]any, options Options) (PartsResult, []bool) {
	if err := validateModel(model); err != nil {
		return PartsResult{Errors: []Error{asMF2Error(err)}}, nil
	}
	context := newFormatContext(
		arguments,
		locale(options),
		functions(options),
		true,
		options.OnMissingArgument,
		options.OnFormatError,
	)
	if err := context.applyDeclarations(arrayField(map[string]any(model), "declarations")); err != nil {
		context.errors = append(context.errors, asMF2Error(err))
	}
	var parts []Part
	var err error
	if stringField(map[string]any(model), "type") == "message" {
		parts, err = context.formatPatternToParts(arrayField(map[string]any(model), "pattern"))
	} else {
		parts, err = context.formatSelectToParts(arrayField(map[string]any(model), "selectors"), arrayField(map[string]any(model), "variants"))
	}
	if err != nil {
		context.errors = append(context.errors, asMF2Error(err))
	}
	return PartsResult{Parts: parts, Errors: context.errors}, context.expressionIsolation
}

type bidiState struct {
	resolvedDirection string
	direction         string
	force             bool
}

type formatContext struct {
	sourceBidi          map[*FunctionSource]bidiState
	expressionIsolation []bool
	localeIsLTR         bool
	failedSelectors     map[int]bool
	arguments           map[string]any
	locals              map[string]resolvedValue
	failedLocals        map[string]bool
	errors              []Error
	locale              string
	functions           FunctionRegistry
	fallback            bool
	onMissingArgument   RecoveryHandler
	onFormatError       RecoveryHandler
	selectorAnnotations map[string]selectorAnnotation
}

type resolvedValue struct {
	rawValue any
	source   *FunctionSource
}

func (r resolvedValue) rendered() string {
	return valueToString(r.rawValue)
}

type expressionOutput struct {
	resolvedDirection string
	forceIsolation    bool
	value             string
	hadError          bool
	source            *FunctionSource
	direction         string
	fallbackSource    string
}

func newFormatContext(
	arguments map[string]any,
	locale string,
	functions FunctionRegistry,
	fallback bool,
	onMissingArgument RecoveryHandler,
	onFormatError RecoveryHandler,
) *formatContext {
	snapshot := make(map[string]any, len(arguments))
	for name, value := range arguments {
		snapshot[normalizeStringKey(name)] = value
	}
	arguments = snapshot
	return &formatContext{
		sourceBidi:        map[*FunctionSource]bidiState{},
		arguments:         arguments,
		locals:            map[string]resolvedValue{},
		failedLocals:      map[string]bool{},
		locale:            locale,
		localeIsLTR:       localeIsLtr(locale),
		functions:         functions,
		fallback:          fallback,
		onMissingArgument: onMissingArgument,
		onFormatError:     onFormatError,
	}
}

func (c *formatContext) applyDeclarations(declarations []any) error {
	c.selectorAnnotations = selectorAnnotations(declarations)
	for _, raw := range declarations {
		declaration := asObject(raw)
		switch stringField(declaration, "type") {
		case "input":
			if err := c.applyInputDeclaration(declaration); err != nil {
				return err
			}
		case "local":
			output, err := c.formatExpressionOutput(asObject(declaration["value"]))
			if err != nil {
				return err
			}
			name := normalizeStringKey(stringField(declaration, "name"))
			if output.hadError {
				c.failedLocals[name] = true
				delete(c.locals, name)
			} else {
				c.locals[name] = resolvedValue{rawValue: output.value, source: output.source}
			}
		}
	}
	return nil
}

func (c *formatContext) applyInputDeclaration(input map[string]any) error {
	value := asObject(input["value"])
	functionRef, ok := objectField(value, "function")
	if !ok || !c.functions.HasFormatter(functionRef) || !c.functions.HasSelector(functionRef) {
		return nil
	}
	name := normalizeStringKey(stringField(input, "name"))
	if !c.hasValue(name) {
		if !c.fallback {
			return missingArgument(name)
		}
		c.failedLocals[name] = true
		c.errors = append(c.errors, unresolvedVariable(name), badOperand("Function operand is not available."))
		return nil
	}
	inputValue := c.value(name)
	if err := c.recordFunctionResolutionErrors(functionRef, inputValue.source); err != nil {
		return err
	}
	bidi := c.resolveBidi(functionRef, inputValue.source)
	rendered := inputValue.rendered()
	formatted, err := c.functions.Format(FunctionCall{
		Value:    rendered,
		RawValue: inputValue.rawValue,
		Function: functionRef,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.resolvedOptionValue(functionRef, inputValue.source, optionName, defaultValue)
		},
		InheritedSource: inputValue.source,
	})
	if err != nil {
		if !c.fallback {
			return err
		}
		c.errors = append(c.errors, fallbackError(err))
		c.failedLocals[name] = true
		return nil
	}
	sourceValue := rendered
	if inputValue.source != nil {
		sourceValue = inputValue.source.Value
	}
	c.locals[name] = resolvedValue{rawValue: formatted, source: c.functionSource(sourceValue, functionRef, inputValue.source, bidi)}
	return nil
}

func (c *formatContext) formatSelectToParts(selectors []any, variants []any) ([]Part, error) {
	c.failedSelectors = map[int]bool{}
	selectorValues := make([]selectorValue, 0, len(selectors))
	for _, raw := range selectors {
		value, err := c.selectorValue(asObject(raw))
		if err != nil {
			return nil, err
		}
		selectorValues = append(selectorValues, value)
	}
	signatures := map[string]bool{}
	var fallback map[string]any
	var selected map[string]any
	var selectedRank []int
	for _, raw := range variants {
		variant := asObject(raw)
		if err := c.validateVariant(variant, selectorValues, signatures); err != nil {
			return nil, err
		}
		if fallback == nil && isFallbackVariant(variant) {
			fallback = variant
		}
		rank, ok, err := c.variantMatchRank(variant, selectorValues)
		if err != nil {
			return nil, err
		}
		if ok && (selectedRank == nil || compareRank(rank, selectedRank) > 0) {
			selected = variant
			selectedRank = rank
		}
	}
	if fallback == nil {
		return nil, mf2Error("missing-fallback-variant", "Select messages must include a catch-all fallback variant.")
	}
	if selected == nil {
		selected = fallback
	}
	return c.formatPatternToParts(arrayField(selected, "value"))
}

func (c *formatContext) selectorValue(selector map[string]any) (selectorValue, error) {
	name := normalizeStringKey(stringField(selector, "name"))
	annotation, hasAnnotation := c.selectorAnnotations[name]
	if !c.hasValue(name) {
		if !c.fallback {
			return selectorValue{}, missingArgument(name)
		}
		if !c.failedLocals[name] {
			c.errors = append(c.errors, unresolvedVariable(name))
		}
		if hasAnnotation && !annotation.isString() {
			if !c.failedLocals[name] {
				c.errors = append(c.errors, badOperand("Selector operand is not available."))
			}
			c.errors = append(c.errors, mf2Error("bad-selector", "Selector operand is not available."))
		}
		normalized := ""
		if !hasAnnotation || !annotation.isString() {
			normalized = "\x00"
		}
		function := map[string]any(nil)

		return selectorValue{rendered: "", normalizedRendered: normalized, exactMatch: false, function: function}, nil
	}
	value := c.value(name)
	rendered := value.rendered()
	if err := c.recordSelectorResolutionErrors(annotation, hasAnnotation); err != nil {
		return selectorValue{}, err
	}
	normalized := "\x00"
	if hasAnnotation && annotation.isString() {
		normalized = normalizeStringKey(rendered)
	}
	selectionKey := ""
	if hasAnnotation {
		selectionKey = selectionKeyFor(c.locale, annotation, value)
		if annotation.isNumeric() && annotation.numberSelect != "exact" && selectionKey == "" {
			c.errors = append(c.errors, badSelector("Numeric plural operand is outside the supported range."))
		}
	}
	var function map[string]any
	if hasAnnotation {
		function = annotation.function
	}
	return selectorValue{
		rendered:           rendered,
		normalizedRendered: normalized,
		exactMatch:         !hasAnnotation || annotation.exactMatch(),
		selectionKey:       selectionKey,
		function:           function,
		source:             value.source,
	}, nil
}

func (c *formatContext) formatPatternToParts(pattern []any) ([]Part, error) {
	parts := make([]Part, 0, len(pattern))
	for _, part := range pattern {
		if text, ok := part.(string); ok {
			parts = append(parts, Part{"type": "text", "value": text})
			continue
		}
		object := asObject(part)
		switch stringField(object, "type") {
		case "expression":
			output, err := c.formatExpressionOutput(object)
			if err != nil {
				return nil, err
			}
			if output.hadError {
				source := output.fallbackSource
				if source == "" {
					source = fallbackSource(object)
				}
				part := Part{"type": "fallback", "source": source}
				if output.value != fallbackValue(source) {
					part["value"] = output.value
				}
				parts = append(parts, part)
			} else {
				c.expressionIsolation = append(c.expressionIsolation, !(c.localeIsLTR && !output.forceIsolation && output.resolvedDirection == "ltr"))
				expressionPart := Part{"type": "expression", "value": output.value}
				if attrs := asObject(object["attributes"]); len(attrs) > 0 {
					expressionPart["attributes"] = detachedModelFields(attrs)
				}
				if output.direction != "" {
					expressionPart["direction"] = output.direction
				}
				parts = append(parts, expressionPart)
			}
		case "markup":
			if options := asObject(object["options"]); options["u:dir"] != nil {
				err := badOption("u:dir is not valid on markup.")
				if !c.fallback {
					return nil, err
				}
				c.errors = append(c.errors, err)
			}
			markup := Part{"type": "markup", "kind": stringField(object, "kind"), "name": stringField(object, "name")}
			if options := asObject(object["options"]); len(options) > 0 {
				markup["options"] = detachedModelFields(options)
			}
			if attrs := asObject(object["attributes"]); len(attrs) > 0 {
				markup["attributes"] = detachedModelFields(attrs)
			}
			parts = append(parts, markup)
		default:
			return nil, mf2Error("unsupported-pattern-part", "Unsupported pattern part: "+stringField(object, "type"))
		}
	}
	return parts, nil
}

func (c *formatContext) formatExpressionOutput(expression map[string]any) (expressionOutput, error) {
	var value string
	var rawValue any
	var source *FunctionSource
	if arg, ok := objectField(expression, "arg"); !ok {
		value = ""
		rawValue = ""
	} else {
		switch stringField(arg, "type") {
		case "literal":
			value = stringField(arg, "value")
			rawValue = value
		case "variable":
			name := normalizeStringKey(stringField(arg, "name"))
			if !c.hasValue(name) {
				if !c.fallback {
					return expressionOutput{}, missingArgument(name)
				}
				err := unresolvedVariable(name)
				if !c.failedLocals[name] {
					c.errors = append(c.errors, err)
				}
				if function, hasFunction := objectField(expression, "function"); hasFunction {
					if c.functions.HasFormatter(function) {
						c.errors = append(c.errors, badOperand("Function operand is not available."))
					} else {
						c.errors = append(c.errors, mf2Error("unknown-function", "Unknown function."))
					}
				}
				source := fallbackSource(expression)
				return expressionOutput{
					value:          c.recoverMissingArgument(expression, name, source, err),
					hadError:       true,
					fallbackSource: source,
				}, nil
			}
			resolved := c.value(name)
			rawValue = resolved.rawValue
			value = resolved.rendered()
			source = resolved.source
		default:
			return expressionOutput{}, mf2Error("unsupported-expression-arg", "Unsupported expression arg: "+stringField(arg, "type"))
		}
	}
	functionRef, hasFunction := objectField(expression, "function")
	if !hasFunction && source == nil {
		switch rawValue.(type) {
		case float32, float64, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
			functionRef = map[string]any{"type": "function", "name": "number"}
			hasFunction = true
		}
	}
	if !hasFunction {
		bidi := c.sourceBidi[source]
		return expressionOutput{value: value, source: source, direction: bidi.direction, forceIsolation: bidi.force, resolvedDirection: bidi.resolvedDirection}, nil
	}
	if err := c.recordFunctionResolutionErrors(functionRef, source); err != nil {
		return expressionOutput{}, err
	}
	bidi := c.resolveBidi(functionRef, source)
	formatted, err := c.functions.Format(FunctionCall{
		Value:    value,
		RawValue: rawValue,
		Function: functionRef,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.resolvedOptionValue(functionRef, source, optionName, defaultValue)
		},
		InheritedSource: source,
	})
	if err != nil {
		if !c.fallback {
			return expressionOutput{}, err
		}
		recoverable := fallbackError(err)
		c.errors = append(c.errors, recoverable)
		source := fallbackSource(expression)
		return expressionOutput{
			value:          c.recoverFormatError(expression, source, recoverable),
			hadError:       true,
			fallbackSource: source,
		}, nil
	}
	sourceValue := value
	if source != nil {
		sourceValue = source.Value
	}
	return expressionOutput{
		value:             formatted,
		source:            c.functionSource(sourceValue, functionRef, source, bidi),
		direction:         bidi.direction,
		forceIsolation:    bidi.force,
		resolvedDirection: bidi.resolvedDirection,
	}, nil
}

func (c *formatContext) recoverMissingArgument(expression map[string]any, variableName, source string, err Error) string {
	return recoverValue(c.onMissingArgument, RecoveryContext{
		Code:             err.Code,
		Message:          err.Message,
		Locale:           c.locale,
		VariableName:     variableName,
		FunctionName:     stringField(asObject(expression["function"]), "name"),
		SourceExpression: expressionSource(expression),
		FallbackValue:    fallbackValue(source),
		Error:            err,
	})
}

func (c *formatContext) recoverFormatError(expression map[string]any, source string, err Error) string {
	var variableName string
	if arg, ok := objectField(expression, "arg"); ok && stringField(arg, "type") == "variable" {
		variableName = stringField(arg, "name")
	}
	return recoverValue(c.onFormatError, RecoveryContext{
		Code:             err.Code,
		Message:          err.Message,
		Locale:           c.locale,
		VariableName:     variableName,
		FunctionName:     stringField(asObject(expression["function"]), "name"),
		SourceExpression: expressionSource(expression),
		FallbackValue:    fallbackValue(source),
		Error:            err,
	})
}

func (c *formatContext) optionValue(functionRef map[string]any, optionName, fallback string) (string, error) {
	options := asObject(functionRef["options"])
	option := asObject(options[optionName])
	if len(option) == 0 {
		return fallback, nil
	}
	switch stringField(option, "type") {
	case "literal":
		return stringField(option, "value"), nil
	case "variable":
		name := normalizeStringKey(stringField(option, "name"))
		if !c.hasValue(name) {
			return "", missingArgument(name)
		}
		return c.value(name).rendered(), nil
	default:
		return fallback, nil
	}
}

func (c *formatContext) resolvedOptionValue(functionRef map[string]any, source *FunctionSource, optionName, fallback string) (string, error) {
	if optionName == "u:dir" {
		return fallback, nil
	}
	if hasOwn(asObject(functionRef["options"]), optionName) {
		return c.optionValue(functionRef, optionName, fallback)
	}
	return inheritedNumericOptionValue(stringField(functionRef, "name"), source, optionName, fallback)
}

func inheritedNumericOptionValue(targetFunction string, source *FunctionSource, optionName, fallback string) (string, error) {
	type visit struct {
		cache *sourceCache
		key   string
	}
	var visited []visit
	result := cachedOption{}
	for source != nil && !numericOptionIsDiscarded(targetFunction, optionName) {
		cache := cacheForSource(source)
		key := targetFunction + ":" + optionName
		if cache != nil && cache.cacheable {
			if cached, found := cache.options[key]; found {
				result = cached
				break
			}
			visited = append(visited, visit{cache, key})
		}
		sourceFunction := stringField(source.Function, "name")
		if !inheritsNumericOptionsFrom(targetFunction, sourceFunction) || numericOptionIsDiscarded(sourceFunction, optionName) {
			break
		}
		if hasOwn(asObject(source.Function["options"]), optionName) {
			value, err := sourceOptionValue(source, optionName, "")
			if err != nil {
				return "", err
			}
			result = cachedOption{value: value, found: true}
			break
		}
		targetFunction, source = sourceFunction, source.Inherited
	}
	for _, item := range visited {
		if len(item.cache.options) < 64 {
			item.cache.options[item.key] = result
		}
	}
	if result.found {
		return result.value, nil
	}
	return fallback, nil
}

func inheritsNumericOptionsFrom(targetFunction, sourceFunction string) bool {
	switch targetFunction {
	case "number", "integer", "percent", "offset":
		return sourceFunction == "number" || sourceFunction == "integer" || sourceFunction == "percent" || sourceFunction == "offset"
	case "currency":
		return sourceFunction == "currency"
	default:
		return false
	}
}

func numericOptionIsDiscarded(functionName, optionName string) bool {
	switch functionName {
	case "integer":
		return optionName == "minimumFractionDigits" || optionName == "maximumFractionDigits" || optionName == "minimumSignificantDigits"
	case "percent":
		return optionName == "minimumIntegerDigits" || optionName == "roundingIncrement" || optionName == "select"
	case "offset":
		return optionName == "add" || optionName == "subtract"
	default:
		return false
	}
}

func (c *formatContext) hasValue(name string) bool {
	return !c.failedLocals[name] && (c.locals[name].rawValue != nil || hasOwn(c.locals, name) || hasOwnAny(c.arguments, name))
}

func (c *formatContext) value(name string) resolvedValue {
	if value, ok := c.locals[name]; ok {
		return value
	}
	return resolvedValue{rawValue: c.arguments[name]}
}

func (c *formatContext) recordFunctionResolutionErrors(functionRef map[string]any, source *FunctionSource) error {
	if !isNumericFunction(functionRef) || (!numericSelectUsesVariable(functionRef) && !inheritedExactNumericSource(source, stringField(functionRef, "name"))) {
		return nil
	}
	err := badOption("Numeric select option is not valid in this context.")
	if !c.fallback {
		return err
	}
	c.errors = append(c.errors, err)
	return nil
}

func (c *formatContext) recordSelectorResolutionErrors(annotation selectorAnnotation, ok bool) error {
	if !ok || stringField(annotation.function, "name") != "currency" {
		return nil
	}
	err := mf2Error("bad-selector", "Currency selector is not supported.")
	if !c.fallback {
		return err
	}
	c.errors = append(c.errors, err)
	return nil
}

func (c *formatContext) functionSource(value string, functionRef map[string]any, inherited *FunctionSource, bidi bidiState) *FunctionSource {
	source := &FunctionSource{
		Value:       value,
		Function:    functionRef,
		OptionValue: func(name, fallback string) (string, error) { return c.optionValue(functionRef, name, fallback) },
		Inherited:   inherited,
	}
	source.cache = makeSourceCache(source)
	c.sourceBidi[source] = bidi
	return source
}

func (c *formatContext) validateVariant(variant map[string]any, selectorValues []selectorValue, signatures map[string]bool) error {
	keys := arrayField(variant, "keys")
	if len(keys) != len(selectorValues) {
		return mf2Error("variant-key-count-mismatch", "Variant key count must match selector count.")
	}
	signature := variantSignatureKey(keys, selectorValues)
	if signatures[signature] {
		return mf2Error("duplicate-variant", "Select variants must have unique key tuples.")
	}
	signatures[signature] = true
	return nil
}

func (c *formatContext) variantMatchRank(variant map[string]any, selectorValues []selectorValue) ([]int, bool, error) {
	keys := arrayField(variant, "keys")
	if len(keys) != len(selectorValues) {
		return nil, false, nil
	}
	rank := make([]int, 0, len(keys))
	for index, rawKey := range keys {
		itemRank, ok, err := c.keyMatchRank(asObject(rawKey), selectorValues[index], index)
		if err != nil || !ok {
			return nil, false, err
		}
		rank = append(rank, itemRank)
	}
	return rank, true, nil
}

func (c *formatContext) keyMatchRank(key map[string]any, selector selectorValue, index int) (int, bool, error) {
	if stringField(key, "type") == "*" {
		return 0, true, nil
	}
	value := stringField(key, "value")
	if (selector.exactMatch && literalKeyMatches(value, selector)) || (selector.selectionKey != "" && value == selector.selectionKey) {
		return 1, true, nil
	}
	if c.failedSelectors[index] || selector.function == nil {
		return 0, false, nil
	}
	rank, err := c.functions.Select(FunctionMatch{
		Value:    selector.rendered,
		RawValue: selector.rendered,
		Function: selector.function,
		Key:      value,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.resolvedOptionValue(selector.function, selector.source, optionName, defaultValue)
		},
		InheritedSource: selector.source,
	})
	if err != nil {
		if !c.fallback {
			return 0, false, err
		}
		recoverable := fallbackError(err)
		c.errors = append(c.errors, recoverable)
		if recoverable.Code != "bad-variant-key" {
			c.failedSelectors[index] = true
			if recoverable.Code != "bad-selector" {
				c.errors = append(c.errors, badSelector("Selector failed to match."))
			}
		}
		return 0, false, nil
	}
	if rank == nil {
		return 0, false, nil
	}
	return *rank, true, nil
}

func validateModel(model Model) error {
	if !validModelShape(model) {
		return mf2Error("invalid-model", "Message model does not match the shared model schema.")
	}
	declarations := arrayField(map[string]any(model), "declarations")
	if err := validateDeclarations(declarations); err != nil {
		return err
	}
	switch stringField(map[string]any(model), "type") {
	case "message":
		return validatePattern(arrayField(map[string]any(model), "pattern"))
	case "select":
		if err := validateSelectorAnnotations(declarations, arrayField(map[string]any(model), "selectors")); err != nil {
			return err
		}
		annotations := selectorAnnotations(declarations)
		var selectors []selectorValue
		for _, raw := range arrayField(map[string]any(model), "selectors") {
			annotation := annotations[normalizeStringKey(stringField(asObject(raw), "name"))]
			normalized := "\x00"
			if annotation.isString() {
				normalized = ""
			}
			selectors = append(selectors, selectorValue{normalizedRendered: normalized})
		}
		signatures := map[string]bool{}
		fallback := false
		for _, raw := range arrayField(map[string]any(model), "variants") {
			variant := asObject(raw)
			keys := arrayField(variant, "keys")
			if len(keys) != len(selectors) {
				return mf2Error("variant-key-count-mismatch", "Variant key count must match selector count.")
			}
			signature := variantSignatureKey(keys, selectors)
			if signatures[signature] {
				return mf2Error("duplicate-variant", "Select variants must have unique key tuples.")
			}
			signatures[signature] = true
			fallback = fallback || isFallbackVariant(variant)
		}
		if !fallback {
			return mf2Error("missing-fallback-variant", "Select messages must include a catch-all fallback variant.")
		}

		for _, raw := range arrayField(map[string]any(model), "variants") {
			if err := validatePattern(arrayField(asObject(raw), "value")); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateDeclarations(declarations []any) error {
	names := map[string]bool{}
	for _, raw := range declarations {
		declaration := asObject(raw)
		name := normalizeStringKey(stringField(declaration, "name"))
		if stringField(declaration, "type") == "input" {
			if err := validateInputDeclaration(declaration); err != nil {
				return err
			}
		}
		if names[name] {
			return mf2Error("duplicate-declaration", "Declaration $"+name+" is defined more than once.")
		}
		names[name] = true
	}
	return validateLocalReferences(declarations)
}

func validateLocalReferences(declarations []any) error {
	forbidden := map[string]bool{}
	for index := len(declarations) - 1; index >= 0; index-- {
		declaration := asObject(declarations[index])
		if stringField(declaration, "type") != "local" {
			continue
		}
		name := normalizeStringKey(stringField(declaration, "name"))
		forbidden[name] = true
		if expressionReferencesAny(asObject(declaration["value"]), forbidden) {
			return mf2Error("duplicate-declaration", "Local declaration $"+name+" must not reference itself or later local declarations.")
		}
	}
	return nil
}

func expressionReferencesAny(expression map[string]any, names map[string]bool) bool {
	if argReferencesAny(asObject(expression["arg"]), names) {
		return true
	}
	for _, option := range asObject(asObject(expression["function"])["options"]) {
		if argReferencesAny(asObject(option), names) {
			return true
		}
	}
	return false
}

func argReferencesAny(arg map[string]any, names map[string]bool) bool {
	return stringField(arg, "type") == "variable" && names[normalizeStringKey(stringField(arg, "name"))]
}

func validateInputDeclaration(declaration map[string]any) error {
	arg := asObject(asObject(declaration["value"])["arg"])
	if stringField(arg, "type") == "variable" && normalizeStringKey(stringField(arg, "name")) == normalizeStringKey(stringField(declaration, "name")) {
		return nil
	}
	return mf2Error("invalid-input-declaration", "Input declaration $"+stringField(declaration, "name")+" must bind the same variable name.")
}

func validatePattern(pattern []any) error {
	for _, part := range pattern {
		if text, ok := part.(string); ok && text == "" {
			return mf2Error("invalid-pattern-text", "Pattern text parts must be non-empty.")
		}
		object := asObject(part)
		if len(object) > 0 && stringField(object, "type") == "markup" {
			if err := validateMarkup(object); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateMarkup(markup map[string]any) error {
	switch stringField(markup, "kind") {
	case "open", "standalone", "close":
		return nil
	default:
		return mf2Error("invalid-markup-kind", "Markup kind must be open, standalone, or close.")
	}
}

func validateSelectorAnnotations(declarations []any, selectors []any) error {
	annotations := selectorAnnotations(declarations)
	for _, selector := range selectors {
		name := normalizeStringKey(stringField(asObject(selector), "name"))
		if _, ok := annotations[name]; !ok {
			return mf2Error("missing-selector-annotation", "Selector $"+name+" must reference a declaration with a function.")
		}
	}
	return nil
}

type selectorAnnotation struct {
	function     map[string]any
	numberSelect string
}

func selectorAnnotations(declarations []any) map[string]selectorAnnotation {
	aliases := map[string][]string{}
	annotations := map[string]selectorAnnotation{}
	var pending []string
	for _, raw := range declarations {
		declaration := asObject(raw)
		name := normalizeStringKey(stringField(declaration, "name"))
		value := asObject(declaration["value"])
		if function, ok := objectField(value, "function"); ok {
			annotations[name] = newSelectorAnnotation(function)
			pending = append(pending, name)
		} else if arg := asObject(value["arg"]); stringField(arg, "type") == "variable" {
			source := normalizeStringKey(stringField(arg, "name"))
			aliases[source] = append(aliases[source], name)
		}
	}
	for index := 0; index < len(pending); index++ {
		source := pending[index]
		for _, alias := range aliases[source] {
			if _, exists := annotations[alias]; !exists {
				annotations[alias] = annotations[source]
				pending = append(pending, alias)
			}
		}
	}
	return annotations
}

func newSelectorAnnotation(functionRef map[string]any) selectorAnnotation {
	selectValue := "plural"
	if option := asObject(asObject(functionRef["options"])["select"]); stringField(option, "type") == "literal" {
		selectValue = stringField(option, "value")
	}
	if selectValue != "ordinal" && selectValue != "exact" {
		selectValue = "plural"
	}
	return selectorAnnotation{function: functionRef, numberSelect: selectValue}
}

func (a selectorAnnotation) exactMatch() bool {
	return stringField(a.function, "name") == "string" || (a.isNumeric() && a.numberSelect == "exact")
}

func (a selectorAnnotation) isString() bool {
	return stringField(a.function, "name") == "string"
}

func (a selectorAnnotation) isNumeric() bool {
	return isNumericFunction(a.function)
}

type selectorValue struct {
	rendered           string
	normalizedRendered string
	exactMatch         bool
	selectionKey       string
	function           map[string]any
	source             *FunctionSource
}

func selectionKeyFor(locale string, annotation selectorAnnotation, value resolvedValue) string {
	if !annotation.isNumeric() || annotation.numberSelect == "exact" {
		return ""
	}
	operand := value.rendered()
	if stringField(annotation.function, "name") == "percent" {
		if strings.HasSuffix(operand, "%") {
			operand = strings.TrimSuffix(operand, "%")
		} else {
			sourceValue := operand
			if value.source != nil {
				sourceValue = value.source.Value
			}
			parsed, err := strconv.ParseFloat(sourceValue, 64)
			if err != nil {
				return ""
			}
			scaled, err := scaledPercent(parsed)
			if err != nil {
				return ""
			}
			operand = strconv.FormatFloat(scaled, 'f', -1, 64)
		}
	}
	return selectPluralCategory(locale, operand, annotation.numberSelect)
}

func selectPluralCategory(locale string, value any, selectType string) string {
	if !supportedPluralOperand(valueToString(value)) {
		return ""
	}
	if selectType == "ordinal" {
		return selectOrdinal(locale, value)
	}
	return selectCardinal(locale, value)
}

func variantSignatureKey(keys []any, selectors []selectorValue) string {
	encoded, _ := json.Marshal(variantKeySignature(keys, selectors))
	return string(encoded)
}

func variantKeySignature(keys []any, selectorValues []selectorValue) []string {
	signature := make([]string, 0, len(keys))
	for index, rawKey := range keys {
		key := asObject(rawKey)
		if stringField(key, "type") == "*" {
			signature = append(signature, "*")
			continue
		}
		value := stringField(key, "value")
		if selectorValues[index].normalizedRendered != "\x00" {
			value = normalizeStringKey(value)
		}
		signature = append(signature, "="+value)
	}
	return signature
}

func compareRank(left, right []int) int {
	length := len(left)
	if len(right) < length {
		length = len(right)
	}
	for index := 0; index < length; index++ {
		if left[index] != right[index] {
			return left[index] - right[index]
		}
	}
	return len(left) - len(right)
}

func literalKeyMatches(value string, selector selectorValue) bool {
	if selector.normalizedRendered == "\x00" {
		return value == selector.rendered
	}
	return normalizeStringKey(value) == selector.normalizedRendered
}

func normalizeStringKey(value string) string {
	return norm.NFC.String(value)
}

func unresolvedVariable(name string) Error {
	return mf2Error("unresolved-variable", "Variable $"+name+" could not be resolved.")
}

func fallbackError(err error) Error {
	mf2 := asMF2Error(err)
	if mf2.Code == "unsupported-function" {
		return mf2Error("unknown-function", mf2.Message)
	}
	return mf2
}

func fallbackSource(expression map[string]any) string {
	if arg, ok := objectField(expression, "arg"); ok {
		return expressionArgSource(arg)
	}
	if functionRef, ok := objectField(expression, "function"); ok {
		return ":" + stringField(functionRef, "name")
	}
	return ""
}

func fallbackValue(source string) string {
	return "{" + source + "}"
}

func recoverValue(handler RecoveryHandler, context RecoveryContext) string {
	if handler != nil {
		if value, ok := handler(context); ok {
			return value
		}
	}
	return context.FallbackValue
}

func expressionSource(expression map[string]any) string {
	items := []string{}
	if arg, ok := objectField(expression, "arg"); ok {
		items = append(items, expressionArgSource(arg))
	}
	if functionRef, ok := objectField(expression, "function"); ok {
		items = append(items, functionSource(functionRef))
	}
	return "{" + strings.Join(items, " ") + "}"
}

func expressionArgSource(arg map[string]any) string {
	if stringField(arg, "type") == "variable" {
		return "$" + stringField(arg, "name")
	}
	return quoteLiteralSource(stringField(arg, "value"))
}

func functionSource(functionRef map[string]any) string {
	source := ":" + stringField(functionRef, "name")
	for name, value := range asObject(functionRef["options"]) {
		source += " " + name + "=" + expressionArgSource(asObject(value))
	}
	return source
}

func quoteLiteralSource(value string) string {
	var out strings.Builder
	out.WriteRune('|')
	for _, r := range value {
		if r == '\\' || r == '|' {
			out.WriteRune('\\')
		}
		out.WriteRune(r)
	}
	out.WriteRune('|')
	return out.String()
}

func partsToString(parts []Part, bidiIsolation string) string {
	return renderPartsToString(parts, bidiIsolation, nil)
}

func renderPartsToString(parts []Part, bidiIsolation string, isolation []bool) string {
	expressionIndex := 0
	var output strings.Builder
	for _, part := range parts {
		switch part["type"] {
		case "text":
			output.WriteString(valueToString(part["value"]))
		case "fallback":
			if value, ok := part["value"]; ok {
				output.WriteString(valueToString(value))
			} else {
				output.WriteString(fallbackValue(valueToString(part["source"])))
			}
		case "expression":
			mode := bidiIsolation
			if isolation != nil && !isolation[expressionIndex] {
				mode = "none"
			}
			expressionIndex++
			output.WriteString(isolateExpression(valueToString(part["value"]), mode, valueToString(part["direction"])))
		}
	}
	return output.String()
}

func isolateExpression(value string, bidiIsolation string, direction string) string {
	if bidiIsolation == "default" {
		return bidiMarker(direction) + value + "\u2069"
	}
	return value
}

func bidiMarker(direction string) string {
	switch direction {
	case "ltr":
		return "\u2066"
	case "rtl":
		return "\u2067"
	default:
		return "\u2068"
	}
}

func (c *formatContext) resolveBidi(function map[string]any, source *FunctionSource) bidiState {
	inherited := c.sourceBidi[source].direction
	resolved := c.sourceBidi[source].resolvedDirection
	if inherited == "" && resolved == "" && c.localeIsLTR && c.functions.numericFormatters[stringField(function, "name")] {
		resolved = "ltr"
	}
	option, exists := asObject(function["options"])["u:dir"]
	if !exists {
		return bidiState{direction: inherited, resolvedDirection: resolved}
	}
	value := asObject(option)
	var raw any
	if stringField(value, "type") == "variable" {
		name := normalizeStringKey(stringField(value, "name"))
		if !c.hasValue(name) {
			c.errors = append(c.errors, unresolvedVariable(name), badOption("u:dir option must resolve to ltr, rtl, auto, or inherit."))
			return bidiState{direction: inherited, resolvedDirection: resolved}
		}
		raw = c.value(name).rawValue
	} else {
		raw = value["value"]
	}
	if text, ok := raw.(string); ok {
		if text == "inherit" {
			return bidiState{direction: inherited, resolvedDirection: resolved}
		}
		if text == "ltr" || text == "rtl" || text == "auto" {
			resolved = text
			if text == "auto" {
				resolved = ""
			}
			return bidiState{direction: text, force: true, resolvedDirection: resolved}
		}
	}
	c.errors = append(c.errors, badOption("u:dir option must resolve to ltr, rtl, auto, or inherit."))
	return bidiState{direction: inherited, resolvedDirection: resolved}
}

func valueToString(value any) string {
	switch typed := value.(type) {
	case nil:
		return ""
	case string:
		return typed
	case bool:
		if typed {
			return "true"
		}
		return "false"
	case int:
		return strconv.Itoa(typed)
	case int64:
		return strconv.FormatInt(typed, 10)
	case float64:
		if math.IsInf(typed, 0) || math.IsNaN(typed) {
			return strconv.FormatFloat(typed, 'f', -1, 64)
		}
		return strconv.FormatFloat(typed, 'f', -1, 64)
	case float32:
		return valueToString(float64(typed))
	default:
		return fmt.Sprint(typed)
	}
}

func locale(options Options) string {
	if strings.TrimSpace(options.Locale) == "" {
		return "en"
	}
	return options.Locale
}

func bidiIsolation(options Options) string {
	if options.BidiIsolation == "" {
		return "none"
	}
	return options.BidiIsolation
}

func functions(options Options) FunctionRegistry {
	if options.Functions.formatters == nil {
		return DefaultFunctionRegistry()
	}
	return options.Functions
}

func asMF2Error(err error) Error {
	if err == nil {
		return Error{}
	}
	if mf2, ok := err.(Error); ok {
		return mf2
	}
	return mf2Error("error", err.Error())
}

func isFallbackVariant(variant map[string]any) bool {
	for _, raw := range arrayField(variant, "keys") {
		if stringField(asObject(raw), "type") != "*" {
			return false
		}
	}
	return true
}

func asObject(value any) map[string]any {
	if value == nil {
		return nil
	}
	if object, ok := value.(map[string]any); ok {
		return object
	}
	if model, ok := value.(Model); ok {
		return map[string]any(model)
	}
	if part, ok := value.(Part); ok {
		return map[string]any(part)
	}
	return nil
}

func arrayField(object map[string]any, name string) []any {
	if object == nil {
		return nil
	}
	raw := object[name]
	if values, ok := raw.([]any); ok {
		return values
	}
	return nil
}

func hasOwn[T any](values map[string]T, name string) bool {
	_, ok := values[name]
	return ok
}

func hasOwnAny(values map[string]any, name string) bool {
	_, ok := values[name]
	return ok
}

func supportedPluralOperand(value string) bool {
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) || math.Abs(parsed) >= math.Ldexp(1, strconv.IntSize-1) {
		return false
	}
	mantissa := strings.SplitN(strings.ToLower(value), "e", 2)[0]
	if dot := strings.IndexByte(mantissa, '.'); dot >= 0 {
		fraction := mantissa[dot+1:]
		if fraction != "" {
			if _, err := strconv.Atoi(fraction); err != nil {
				return false
			}
		}
	}
	return true
}
