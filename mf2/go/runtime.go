package mf2

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/text/unicode/norm"
)

type Options struct {
	Locale        string
	BidiIsolation string
	Functions     FunctionRegistry
}

type Part map[string]any

type FallbackResult struct {
	Value  string
	Errors []Error
}

type FallbackPartsResult struct {
	Parts  []Part
	Errors []Error
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
	registry := FunctionRegistry{
		formatters: map[string]Formatter{},
		selectors:  map[string]Selector{},
	}
	registry.formatters["string"] = func(call FunctionCall) (string, error) { return call.Value, nil }
	registry.formatters["number"] = formatNumber
	registry.selectors["number"] = selectNumber
	registry.formatters["percent"] = formatPercent
	registry.selectors["percent"] = selectPercent
	registry.formatters["currency"] = formatCurrency
	registry.selectors["currency"] = func(FunctionMatch) (*int, error) {
		return nil, badSelector("Currency selector is not supported.")
	}
	registry.formatters["integer"] = formatInteger
	registry.selectors["integer"] = selectInteger
	registry.formatters["datetime"] = formatDateTime
	registry.formatters["date"] = formatDate
	registry.formatters["time"] = formatTime
	registry.formatters["offset"] = formatOffset
	registry.selectors["offset"] = selectOffset
	return registry
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

func FormatMessage(model Model, arguments map[string]any, options Options) (string, error) {
	parts, err := FormatMessageToParts(model, arguments, options)
	if err != nil {
		return "", err
	}
	return PartsToString(parts, bidiIsolation(options)), nil
}

func FormatMessageToParts(model Model, arguments map[string]any, options Options) ([]Part, error) {
	if err := validateModel(model); err != nil {
		return nil, err
	}
	context := newFormatContext(arguments, locale(options), functions(options), false)
	if err := context.applyDeclarations(arrayField(map[string]any(model), "declarations")); err != nil {
		return nil, err
	}
	switch stringField(map[string]any(model), "type") {
	case "message":
		return context.formatPatternToParts(arrayField(map[string]any(model), "pattern"))
	case "select":
		return context.formatSelectToParts(arrayField(map[string]any(model), "selectors"), arrayField(map[string]any(model), "variants"))
	default:
		return nil, mf2Error("unsupported-message-type", "Unsupported message type: "+stringField(map[string]any(model), "type"))
	}
}

func FormatMessageWithFallback(model Model, arguments map[string]any, options Options) FallbackResult {
	result := FormatMessageToPartsWithFallback(model, arguments, options)
	return FallbackResult{Value: PartsToString(result.Parts, bidiIsolation(options)), Errors: result.Errors}
}

func FormatMessageToPartsWithFallback(model Model, arguments map[string]any, options Options) FallbackPartsResult {
	if err := validateModel(model); err != nil {
		return FallbackPartsResult{Errors: []Error{asMF2Error(err)}}
	}
	context := newFormatContext(arguments, locale(options), functions(options), true)
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
	return FallbackPartsResult{Parts: parts, Errors: context.errors}
}

type formatContext struct {
	arguments           map[string]any
	locals              map[string]resolvedValue
	failedLocals        map[string]bool
	errors              []Error
	locale              string
	functions           FunctionRegistry
	fallback            bool
	selectorAnnotations map[string]selectorAnnotation
}

type resolvedValue struct {
	rawValue any
	source   *FunctionSource
}

func (r resolvedValue) rendered() string {
	return ValueToString(r.rawValue)
}

type expressionOutput struct {
	value     string
	hadError  bool
	source    *FunctionSource
	direction string
}

func newFormatContext(arguments map[string]any, locale string, functions FunctionRegistry, fallback bool) *formatContext {
	if arguments == nil {
		arguments = map[string]any{}
	}
	return &formatContext{
		arguments:    arguments,
		locals:       map[string]resolvedValue{},
		failedLocals: map[string]bool{},
		locale:       locale,
		functions:    functions,
		fallback:     fallback,
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
		if !c.failedLocals[name] {
			c.errors = append(c.errors, unresolvedVariable(name))
		}
		if hasAnnotation && c.functions.HasSelector(annotation.function) {
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
		if hasAnnotation {
			function = annotation.function
		}
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
				parts = append(parts, Part{"type": "fallback", "source": fallbackSource(object)})
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
				if !c.failedLocals[name] {
					c.errors = append(c.errors, unresolvedVariable(name))
				}
				if _, hasFunction := objectField(expression, "function"); hasFunction {
					c.errors = append(c.errors, badOperand("Function operand is not available."))
				}
				return expressionOutput{value: fallbackSource(expression), hadError: true}, nil
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
		return expressionOutput{value: value, source: source, direction: bidiDirectionFromSource(source)}, nil
	}
	if err := c.recordFunctionResolutionErrors(functionRef, source); err != nil {
		return expressionOutput{}, err
	}
	direction, err := bidiDirectionForFunction(functionRef, source)
	if err != nil {
		return expressionOutput{}, err
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
		c.errors = append(c.errors, fallbackError(err))
		return expressionOutput{value: fallbackSource(expression), hadError: true}, nil
	}
	sourceValue := value
	if source != nil {
		sourceValue = source.Value
	}
	return expressionOutput{
		value:     formatted,
		source:    c.functionSource(sourceValue, functionRef, source),
		direction: direction,
	}, nil
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
	if (selector.exactMatch && literalKeyMatches(value, selector)) || (selector.selectionKey != "" && value == selector.selectionKey) {
		return 1, true, nil
	}
	if selector.function == nil {
		return 0, false, nil
	}
	rank, err := c.functions.Select(FunctionMatch{
		Value:    selector.rendered,
		RawValue: selector.rendered,
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
	return SelectPluralCategory(locale, operand, annotation.numberSelect)
}

func SelectPluralCategory(locale string, value any, selectType string) string {
	if selectType == "ordinal" {
		return SelectOrdinal(locale, value)
	}
	return SelectCardinal(locale, value)
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
		return functionSource(functionRef)
	}
	return ""
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

func PartsToString(parts []Part, bidiIsolation string) string {
	var output strings.Builder
	for _, part := range parts {
		switch part["type"] {
		case "text":
			output.WriteString(ValueToString(part["value"]))
		case "fallback":
			output.WriteRune('{')
			output.WriteString(ValueToString(part["source"]))
			output.WriteRune('}')
		case "expression":
			output.WriteString(isolateExpression(ValueToString(part["value"]), bidiIsolation, ValueToString(part["direction"])))
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

func functionOptionLiteral(functionRef map[string]any, name, fallback string) string {
	option := asObject(asObject(functionRef["options"])[name])
	if stringField(option, "type") == "literal" {
		return stringField(option, "value")
	}
	return fallback
}

func sourceOptionValue(source *FunctionSource, name, fallback string) (string, error) {
	if source == nil {
		return fallback, nil
	}
	if source.OptionValue != nil {
		return source.OptionValue(name, fallback)
	}
	return functionOptionLiteral(source.Function, name, fallback), nil
}

func isNumericFunction(functionRef map[string]any) bool {
	name := stringField(functionRef, "name")
	return name == "number" || name == "integer" || name == "percent" || name == "offset"
}

func isDecimalSourceFunction(functionRef map[string]any) bool {
	return isNumericFunction(functionRef) || stringField(functionRef, "name") == "currency"
}

func numericSelectUsesVariable(functionRef map[string]any) bool {
	return stringField(asObject(asObject(functionRef["options"])["select"]), "type") == "variable"
}

func inheritedExactNumericSource(source *FunctionSource) bool {
	if source == nil {
		return false
	}
	if isNumericFunction(source.Function) {
		value, err := sourceOptionValue(source, "select", "")
		if err == nil && value == "exact" {
			return true
		}
	}
	return inheritedExactNumericSource(source.Inherited)
}

func invalidNumericSelector(functionRef map[string]any, source *FunctionSource) bool {
	selectValue := functionOptionLiteral(functionRef, "select", "")
	return numericSelectUsesVariable(functionRef) || (selectValue != "exact" && inheritedExactNumericSource(source))
}

func ValueToString(value any) string {
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
		return ValueToString(float64(typed))
	default:
		return fmt.Sprint(typed)
	}
}

func formatNumber(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Number function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	minimum, err := minimumFractionDigits(call)
	if err != nil {
		return "", err
	}
	return formatDecimalNumber(value, signDisplayAlways(call.Function), minimum)
}

func selectNumber(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Number selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Number selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalNumber(match.Key)
	if ok && value == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatPercent(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Percent function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	maximum, err := maximumFractionDigits(call)
	if err != nil {
		return "", err
	}
	minimum, err := minimumFractionDigits(call)
	if err != nil {
		return "", err
	}
	formatted := formatDecimalWithMaximumFractionDigits(value*100, maximum)
	if signDisplayAlways(call.Function) && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimum) + "%", nil
}

func selectPercent(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Percent selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Percent selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalNumber(match.Key)
	if ok && value*100 == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatCurrency(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Currency function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	currency := currencyCode(call)
	if currency == "" {
		return "", badOperand("Currency function requires a currency option.")
	}
	digits, err := currencyFractionDigits(call)
	if err != nil {
		return "", err
	}
	number, err := formatDecimalNumber(value, false, 0)
	if err != nil {
		return "", err
	}
	if digits != nil {
		number = strconv.FormatFloat(value, 'f', *digits, 64)
	}
	return currency + " " + number, nil
}

func formatInteger(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Integer function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	integer := math.Trunc(value)
	if signDisplayAlways(call.Function) && integer >= 0 {
		return "+" + strconv.FormatInt(int64(integer), 10), nil
	}
	return strconv.FormatInt(int64(integer), 10), nil
}

func selectInteger(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Integer selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Integer selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseInteger(match.Key)
	if ok && int64(math.Trunc(value)) == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatOffset(call FunctionCall) (string, error) {
	value, ok := parseInteger(call.Value)
	if !ok {
		return "", badOperand("Offset function requires a numeric operand.")
	}
	delta, err := offsetDelta(call)
	if err != nil {
		return "", err
	}
	result := value + delta
	if inheritedSignDisplayAlways(call.InheritedSource) && result >= 0 {
		return "+" + strconv.FormatInt(result, 10), nil
	}
	return strconv.FormatInt(result, 10), nil
}

func selectOffset(match FunctionMatch) (*int, error) {
	value, ok := parseInteger(match.Value)
	if !ok {
		return nil, badSelector("Offset selector requires a numeric operand.")
	}
	key, ok := parseInteger(match.Key)
	if ok && value == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatDateTime(call FunctionCall) (string, error) {
	if isISODate(call.Value) || isISODateTime(call.Value) {
		return call.Value, nil
	}
	return "", badOperand("Datetime function requires a date or datetime operand.")
}

func formatDate(call FunctionCall) (string, error) {
	if isISODate(call.Value) || isISODateTime(call.Value) {
		return call.Value, nil
	}
	return "", badOperand("Date function requires a date or datetime operand.")
}

func formatTime(call FunctionCall) (string, error) {
	if isISODateTime(call.Value) {
		return call.Value, nil
	}
	return "", badOperand("Datetime and time functions require a datetime operand.")
}

var decimalRe = regexp.MustCompile(`^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$`)
var integerRe = regexp.MustCompile(`^[+-]?\d+$`)

func parseCallDecimal(call FunctionCall, message string) (float64, error) {
	if parsed, ok := parseDecimalNumber(call.Value); ok {
		return parsed, nil
	}
	if parsed, ok := parseSourceDecimal(call.InheritedSource); ok {
		return parsed, nil
	}
	return 0, badOperand(message)
}

func parseMatchDecimal(match FunctionMatch, message string) (float64, error) {
	if parsed, ok := parseDecimalNumber(match.Value); ok {
		return parsed, nil
	}
	if parsed, ok := parseSourceDecimal(match.InheritedSource); ok {
		return parsed, nil
	}
	return 0, badSelector(message)
}

func parseSourceDecimal(source *FunctionSource) (float64, bool) {
	if source == nil {
		return 0, false
	}
	if isDecimalSourceFunction(source.Function) {
		return parseDecimalNumber(source.Value)
	}
	return parseSourceDecimal(source.Inherited)
}

func parseDecimalNumber(value string) (float64, bool) {
	if !decimalRe.MatchString(value) {
		return 0, false
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) {
		return 0, false
	}
	return parsed, true
}

func formatDecimalNumber(value float64, signAlways bool, minimumFractionDigits int) (string, error) {
	formatted := strconv.FormatFloat(value, 'f', -1, 64)
	if signAlways && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimumFractionDigits), nil
}

func formatDecimalWithMaximumFractionDigits(value float64, digits *int) string {
	if digits == nil {
		formatted, _ := formatDecimalNumber(value, false, 0)
		return formatted
	}
	formatted := strconv.FormatFloat(value, 'f', *digits, 64)
	for strings.Contains(formatted, ".") && strings.HasSuffix(formatted, "0") {
		formatted = strings.TrimSuffix(formatted, "0")
	}
	return strings.TrimSuffix(formatted, ".")
}

func appendMinimumFractionDigits(formatted string, minimumFractionDigits int) string {
	if minimumFractionDigits == 0 {
		return formatted
	}
	dot := strings.Index(formatted, ".")
	fractionDigits := 0
	if dot >= 0 {
		fractionDigits = len(formatted) - dot - 1
	} else {
		formatted += "."
	}
	for index := fractionDigits; index < minimumFractionDigits; index++ {
		formatted += "0"
	}
	return formatted
}

func minimumFractionDigits(call FunctionCall) (int, error) {
	value, _ := call.OptionValue("minimumFractionDigits", "")
	if value == "" {
		return 0, nil
	}
	return parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.")
}

func maximumFractionDigits(call FunctionCall) (*int, error) {
	value, _ := call.OptionValue("maximumFractionDigits", "")
	if value == "" {
		return nil, nil
	}
	parsed, err := parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.")
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func currencyCode(call FunctionCall) string {
	if value, _ := call.OptionValue("currency", ""); value != "" {
		return value
	}
	return inheritedCurrencyCode(call.InheritedSource)
}

func inheritedCurrencyCode(source *FunctionSource) string {
	if source == nil {
		return ""
	}
	if stringField(source.Function, "name") == "currency" {
		if value, err := sourceOptionValue(source, "currency", ""); err == nil && value != "" {
			return value
		}
	}
	return inheritedCurrencyCode(source.Inherited)
}

func currencyFractionDigits(call FunctionCall) (*int, error) {
	value, _ := call.OptionValue("fractionDigits", "")
	if value == "" || value == "auto" {
		return nil, nil
	}
	parsed, err := parseNonNegativeOption(value, "fractionDigits option must be auto or a non-negative integer.")
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func parseNonNegativeOption(value, message string) (int, error) {
	if value == "" {
		return 0, badOption(message)
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return 0, badOption(message)
		}
	}
	return strconv.Atoi(value)
}

func signDisplayAlways(functionRef map[string]any) bool {
	return functionOptionLiteral(functionRef, "signDisplay", "") == "always"
}

func inheritedSignDisplayAlways(source *FunctionSource) bool {
	if source == nil {
		return false
	}
	name := stringField(source.Function, "name")
	if name == "number" || name == "integer" {
		if value, err := sourceOptionValue(source, "signDisplay", ""); err == nil && value == "always" {
			return true
		}
	}
	return inheritedSignDisplayAlways(source.Inherited)
}

func offsetDelta(call FunctionCall) (int64, error) {
	add, _ := call.OptionValue("add", "")
	subtract, _ := call.OptionValue("subtract", "")
	if (add == "" && subtract == "") || (add != "" && subtract != "") {
		return 0, badOption("Offset function requires exactly one of add or subtract.")
	}
	if add != "" {
		value, ok := parseInteger(add)
		if !ok {
			return 0, badOption("Offset add option must be an integer.")
		}
		return value, nil
	}
	value, ok := parseInteger(subtract)
	if !ok {
		return 0, badOption("Offset subtract option must be an integer.")
	}
	return -value, nil
}

func parseInteger(value string) (int64, bool) {
	if !integerRe.MatchString(value) {
		return 0, false
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	return parsed, err == nil
}

func isISODateTime(value string) bool {
	separator := strings.Index(value, "T")
	return separator >= 0 && isISODate(value[:separator]) && isISOTime(value[separator+1:])
}

func isISODate(value string) bool {
	if len(value) != len("2006-01-02") {
		return false
	}
	for index, r := range value {
		if index == 4 || index == 7 {
			if r != '-' {
				return false
			}
		} else if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func isISOTime(value string) bool {
	if len(value) != len("15:04:05") {
		return false
	}
	for index, r := range value {
		if index == 2 || index == 5 {
			if r != ':' {
				return false
			}
		} else if r < '0' || r > '9' {
			return false
		}
	}
	return true
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
