package mf2

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/text/unicode/norm"
)

var sourceDecimalPattern = regexp.MustCompile(`^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$`)

const (
	maxSourceDecimalExponent  = 1000000
	maxSourceDecimalKeyLength = 4096
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
	formatters map[string]Formatter
	selectors  map[string]Selector
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
	Value       string
	Function    map[string]any
	OptionValue func(string, string) (string, error)
	Inherited   *FunctionSource
}

func DefaultFunctionRegistry() FunctionRegistry {
	return PortableFunctionRegistry()
}

func (r FunctionRegistry) WithFunction(name string, formatter Formatter) FunctionRegistry {
	next := FunctionRegistry{formatters: map[string]Formatter{}, selectors: r.selectors}
	for key, value := range r.formatters {
		next.formatters[key] = value
	}
	next.formatters[name] = formatter
	return next
}

func (r FunctionRegistry) WithSelector(name string, selector Selector) FunctionRegistry {
	next := FunctionRegistry{formatters: r.formatters, selectors: map[string]Selector{}}
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
	return formatter(call)
}

func (r FunctionRegistry) Select(match FunctionMatch) (*int, error) {
	selector := r.selectors[stringField(match.Function, "name")]
	if selector == nil {
		return nil, nil
	}
	return selector(match)
}

func FormatMessage(model Model, arguments map[string]any, options Options) FormatResult {
	result := FormatMessageToParts(model, arguments, options)
	return FormatResult{Value: partsToString(result.Parts, bidiIsolation(options)), Errors: result.Errors}
}

func FormatMessageToParts(model Model, arguments map[string]any, options Options) PartsResult {
	if err := validateModel(model); err != nil {
		return PartsResult{Errors: []Error{asMF2Error(err)}}
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
	return PartsResult{Parts: parts, Errors: context.errors}
}

type formatContext struct {
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
	value          string
	rawValue       any
	hadError       bool
	source         *FunctionSource
	direction      string
	fallbackSource string
}

func newFormatContext(
	arguments map[string]any,
	locale string,
	functions FunctionRegistry,
	fallback bool,
	onMissingArgument RecoveryHandler,
	onFormatError RecoveryHandler,
) *formatContext {
	if arguments == nil {
		arguments = map[string]any{}
	}
	return &formatContext{
		arguments:         arguments,
		locals:            map[string]resolvedValue{},
		failedLocals:      map[string]bool{},
		locale:            locale,
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
			name := stringField(declaration, "name")
			if output.hadError {
				c.failedLocals[name] = true
				delete(c.locals, name)
			} else {
				c.locals[name] = resolvedValue{rawValue: output.rawValue, source: output.source}
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
	name := stringField(input, "name")
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
	rendered := inputValue.rendered()
	formatted, err := c.functions.Format(FunctionCall{
		Value:    rendered,
		RawValue: inputValue.rawValue,
		Function: functionRef,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.optionValue(functionRef, optionName, defaultValue)
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
	c.locals[name] = resolvedValue{rawValue: formatted, source: c.functionSource(sourceValue, functionRef, inputValue.source)}
	return nil
}

func (c *formatContext) formatSelectToParts(selectors []any, variants []any) ([]Part, error) {
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
	name := stringField(selector, "name")
	annotation, hasAnnotation := c.selectorAnnotations[name]
	if !c.hasValue(name) {
		if !c.fallback {
			return selectorValue{}, missingArgument(name)
		}
		failedLocal := c.failedLocals[name]
		if !failedLocal {
			c.errors = append(c.errors, unresolvedVariable(name))
		}
		if hasAnnotation && (failedLocal || c.functions.HasSelector(annotation.function)) {
			if !failedLocal {
				c.errors = append(c.errors, badOperand("Selector operand is not available."))
			}
			c.errors = append(c.errors, mf2Error("bad-selector", "Selector operand is not available."))
		}
		normalized := ""
		if !hasAnnotation || !annotation.isString() {
			normalized = "\x00"
		}
		function := map[string]any(nil)
		if hasAnnotation {
			function = annotation.function
		}
		return selectorValue{rendered: "", rawValue: "", normalizedRendered: normalized, exactMatch: false, function: function}, nil
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
	}
	var function map[string]any
	if hasAnnotation {
		function = annotation.function
	}
	return selectorValue{
		rendered:           rendered,
		rawValue:           value.rawValue,
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
				expressionPart := Part{"type": "expression", "value": output.value}
				if attrs := asObject(object["attributes"]); len(attrs) > 0 {
					expressionPart["attributes"] = attrs
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
				markup["options"] = options
			}
			if attrs := asObject(object["attributes"]); len(attrs) > 0 {
				markup["attributes"] = attrs
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
			name := stringField(arg, "name")
			if !c.hasValue(name) {
				if !c.fallback {
					return expressionOutput{}, missingArgument(name)
				}
				err := unresolvedVariable(name)
				if !c.failedLocals[name] {
					c.errors = append(c.errors, err)
				}
				if _, hasFunction := objectField(expression, "function"); hasFunction {
					c.errors = append(c.errors, badOperand("Function operand is not available."))
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
	if !hasFunction {
		displayValue, err := primitiveValueToString(rawValue, value, c.locale)
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
		return expressionOutput{value: displayValue, rawValue: rawValue, source: source, direction: bidiDirectionFromSource(source)}, nil
	}
	if err := c.recordFunctionResolutionErrors(functionRef, source); err != nil {
		return expressionOutput{}, err
	}
	direction, err := bidiDirectionForFunction(functionRef, source)
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
	formatted, err := c.functions.Format(FunctionCall{
		Value:    value,
		RawValue: rawValue,
		Function: functionRef,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.optionValue(functionRef, optionName, defaultValue)
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
		value:     formatted,
		rawValue:  formatted,
		source:    c.functionSource(sourceValue, functionRef, source),
		direction: direction,
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
		name := stringField(option, "name")
		if !c.hasValue(name) {
			return "", missingArgument(name)
		}
		return c.value(name).rendered(), nil
	default:
		return fallback, nil
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
	if !isNumericFunction(functionRef) || (!numericSelectUsesVariable(functionRef) && !inheritedExactNumericSource(source)) {
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

func (c *formatContext) functionSource(value string, functionRef map[string]any, inherited *FunctionSource) *FunctionSource {
	return &FunctionSource{
		Value:       value,
		Function:    functionRef,
		OptionValue: func(name, fallback string) (string, error) { return c.optionValue(functionRef, name, fallback) },
		Inherited:   inherited,
	}
}

func (c *formatContext) validateVariant(variant map[string]any, selectorValues []selectorValue, signatures map[string]bool) error {
	keys := arrayField(variant, "keys")
	if len(keys) != len(selectorValues) {
		return mf2Error("variant-key-count-mismatch", "Variant key count must match selector count.")
	}
	signature := strings.Join(variantKeySignature(keys, selectorValues), "\x1f")
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
		itemRank, ok, err := c.keyMatchRank(asObject(rawKey), selectorValues[index])
		if err != nil || !ok {
			return nil, false, err
		}
		rank = append(rank, itemRank)
	}
	return rank, true, nil
}

func (c *formatContext) keyMatchRank(key map[string]any, selector selectorValue) (int, bool, error) {
	if stringField(key, "type") == "*" {
		return 0, true, nil
	}
	value := stringField(key, "value")
	if selector.exactMatch && numericLiteralKeyMatchesSource(value, selector) {
		return 3, true, nil
	}
	if selector.exactMatch && (selector.function == nil || !isNumericFunction(selector.function)) && literalKeyMatches(value, selector) {
		return 2, true, nil
	}
	if selector.selectionKey != "" && value == selector.selectionKey {
		return 1, true, nil
	}
	if selector.function == nil {
		return 0, false, nil
	}
	rank, err := c.functions.Select(FunctionMatch{
		Value:    selector.rendered,
		RawValue: selector.rawValue,
		Function: selector.function,
		Key:      value,
		Locale:   c.locale,
		OptionValue: func(optionName, defaultValue string) (string, error) {
			return c.optionValue(selector.function, optionName, defaultValue)
		},
		InheritedSource: selector.source,
	})
	if err != nil {
		if !c.fallback {
			return 0, false, err
		}
		c.errors = append(c.errors, fallbackError(err), mf2Error("bad-selector", "Selector failed to match."))
		return 0, false, nil
	}
	if rank == nil {
		return 0, false, nil
	}
	return *rank, true, nil
}

func validateModel(model Model) error {
	modelObject := map[string]any(model)
	declarations, err := modelArrayField(modelObject, "declarations")
	if err != nil {
		return err
	}
	if err := validateDeclarations(declarations); err != nil {
		return err
	}
	messageType := stringField(modelObject, "type")
	switch messageType {
	case "message":
		pattern, err := modelArrayField(modelObject, "pattern")
		if err != nil {
			return err
		}
		return validatePattern(pattern)
	case "select":
		selectors, err := modelArrayField(modelObject, "selectors")
		if err != nil {
			return err
		}
		if err := validateSelectorAnnotations(declarations, selectors); err != nil {
			return err
		}
		variants, err := modelArrayField(modelObject, "variants")
		if err != nil {
			return err
		}
		for _, raw := range variants {
			value, err := modelArrayField(asObject(raw), "value")
			if err != nil {
				return err
			}
			if err := validatePattern(value); err != nil {
				return err
			}
		}
	default:
		return mf2Error("unsupported-message-type", "Unsupported message type: "+messageType+".")
	}
	return nil
}

func modelArrayField(object map[string]any, name string) ([]any, error) {
	if object == nil {
		return nil, nil
	}
	raw, ok := object[name]
	if !ok || raw == nil {
		return nil, nil
	}
	if values, ok := raw.([]any); ok {
		return values, nil
	}
	return nil, badOption(name + " must be an array.")
}

func validateDeclarations(declarations []any) error {
	names := map[string]bool{}
	for _, raw := range declarations {
		declaration := asObject(raw)
		name := stringField(declaration, "name")
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
		name := stringField(declaration, "name")
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
	return stringField(arg, "type") == "variable" && names[stringField(arg, "name")]
}

func validateInputDeclaration(declaration map[string]any) error {
	arg := asObject(asObject(declaration["value"])["arg"])
	if stringField(arg, "type") == "variable" && stringField(arg, "name") == stringField(declaration, "name") {
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
		name := stringField(asObject(selector), "name")
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
	expressions := map[string]map[string]any{}
	annotations := map[string]selectorAnnotation{}
	for _, raw := range declarations {
		declaration := asObject(raw)
		name := stringField(declaration, "name")
		value := asObject(declaration["value"])
		expressions[name] = value
		if functionRef, ok := objectField(value, "function"); ok {
			annotations[name] = newSelectorAnnotation(functionRef)
		}
	}
	changed := true
	for changed {
		changed = false
		for name, expression := range expressions {
			if _, exists := annotations[name]; exists {
				continue
			}
			arg := asObject(expression["arg"])
			if stringField(arg, "type") != "variable" {
				continue
			}
			if annotation, ok := annotations[stringField(arg, "name")]; ok {
				annotations[name] = annotation
				changed = true
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
	rawValue           any
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
			operand = strconv.FormatFloat(parsed*100, 'f', -1, 64)
		}
	}
	return selectPluralCategory(locale, operand, annotation.numberSelect)
}

func selectPluralCategory(locale string, value any, selectType string) string {
	if selectType == "ordinal" {
		return selectOrdinal(locale, value)
	}
	return selectCardinal(locale, value)
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

func numericLiteralKeyMatchesSource(value string, selector selectorValue) bool {
	sourceKey, ok := preferredNumericSourceKey(selector)
	if !ok || value != sourceKey {
		return false
	}
	_, ok = parseDecimalOperand(value)
	return ok
}

func preferredNumericSourceKey(selector selectorValue) (string, bool) {
	functionName := stringField(selector.function, "name")
	if functionName != "number" && functionName != "percent" {
		return "", false
	}
	sourceValue, ok := numericSourceValue(selector.source, functionName)
	if !ok {
		return "", false
	}
	operand, ok := parsePreferredSourceDecimal(sourceValue)
	if !ok {
		return "", false
	}
	if functionName == "percent" {
		operand.scale -= 2
		return renderPreferredSourceDecimal(operand, false)
	}
	if operand.hasExponent {
		return renderPreferredSourceDecimal(operand, true)
	}
	return sourceValue, true
}

func numericSourceValue(source *FunctionSource, functionName string) (string, bool) {
	for current := source; current != nil; current = current.Inherited {
		if stringField(current.Function, "name") == functionName {
			return current.Value, true
		}
	}
	return "", false
}

type preferredSourceDecimal struct {
	negative    bool
	digits      string
	scale       int
	hasExponent bool
}

func parsePreferredSourceDecimal(value string) (preferredSourceDecimal, bool) {
	match := sourceDecimalPattern.FindStringSubmatch(value)
	if match == nil {
		return preferredSourceDecimal{}, false
	}
	exponent, ok := parsePreferredSourceExponent(match[4])
	if !ok {
		return preferredSourceDecimal{}, false
	}
	digits := strings.TrimLeft(match[2]+match[3], "0")
	if digits == "" {
		digits = "0"
	}
	return preferredSourceDecimal{
		negative:    match[1] == "-" && digits != "0",
		digits:      digits,
		scale:       len(match[3]) - exponent,
		hasExponent: match[4] != "",
	}, true
}

func parsePreferredSourceExponent(value string) (int, bool) {
	if value == "" {
		return 0, true
	}
	negative := strings.HasPrefix(value, "-")
	unsigned := value
	if negative || strings.HasPrefix(value, "+") {
		unsigned = value[1:]
	}
	digits := strings.TrimLeft(unsigned, "0")
	if digits == "" {
		digits = "0"
	}
	if len(digits) > 7 {
		return 0, false
	}
	parsed, err := strconv.Atoi(digits)
	if err != nil || parsed > maxSourceDecimalExponent {
		return 0, false
	}
	if negative {
		return -parsed, true
	}
	return parsed, true
}

func renderPreferredSourceDecimal(operand preferredSourceDecimal, trimFractionZeros bool) (string, bool) {
	extraLength := 0
	if operand.scale > len(operand.digits) {
		extraLength = operand.scale - len(operand.digits)
	} else if operand.scale < 0 {
		extraLength = -operand.scale
	}
	if len(operand.digits)+extraLength+2 > maxSourceDecimalKeyLength {
		return "", false
	}
	var text string
	switch {
	case operand.scale <= 0:
		text = operand.digits + strings.Repeat("0", -operand.scale)
	case operand.scale >= len(operand.digits):
		text = "0." + strings.Repeat("0", operand.scale-len(operand.digits)) + operand.digits
	default:
		split := len(operand.digits) - operand.scale
		text = operand.digits[:split] + "." + operand.digits[split:]
	}
	if trimFractionZeros && strings.Contains(text, ".") {
		text = strings.TrimRight(strings.TrimRight(text, "0"), ".")
	}
	if operand.negative {
		text = "-" + text
	}
	return text, true
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
		return functionNameSource(functionRef)
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

func functionNameSource(functionRef map[string]any) string {
	return ":" + stringField(functionRef, "name")
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
			output.WriteString(isolateExpression(valueToString(part["value"]), bidiIsolation, valueToString(part["direction"])))
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

func bidiDirectionForFunction(functionRef map[string]any, source *FunctionSource) (string, error) {
	if value := functionOptionLiteral(functionRef, "u:dir", ""); value != "" {
		return parseBidiDirection(value)
	}
	return bidiDirectionFromSource(source), nil
}

func bidiDirectionFromSource(source *FunctionSource) string {
	if source == nil {
		return ""
	}
	if value := functionOptionLiteral(source.Function, "u:dir", ""); value != "" {
		direction, err := parseBidiDirection(value)
		if err == nil {
			return direction
		}
	}
	return bidiDirectionFromSource(source.Inherited)
}

func parseBidiDirection(value string) (string, error) {
	switch value {
	case "auto", "ltr", "rtl":
		return value, nil
	default:
		return "", badOption("u:dir option must be auto, ltr, or rtl.")
	}
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
		if math.Trunc(typed) == typed {
			return strconv.FormatInt(int64(typed), 10)
		}
		return strconv.FormatFloat(typed, 'f', -1, 64)
	case float32:
		return valueToString(float64(typed))
	default:
		return fmt.Sprint(typed)
	}
}

func primitiveValueToString(value any, fallback string, locale string) (string, error) {
	switch value.(type) {
	case int, int64, float64, float32:
		return FormatNumberCore(value, NumberCoreOptions{Locale: locale})
	default:
		return fallback, nil
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
