package mf2

// Source caches belong to one formatting context and are never stored on model maps.
type cachedOption struct {
	value string
	found bool
}
type sourceCache struct {
	owner           *FunctionSource
	cacheable       bool
	operandResolved bool
	operand         string
	operandOK       bool
	options         map[string]cachedOption
}

func cacheForSource(source *FunctionSource) *sourceCache {
	if source != nil && source.cache != nil && source.cache.owner == source {
		return source.cache
	}
	return nil
}

func makeSourceCache(source *FunctionSource) *sourceCache {
	cacheable := true
	for _, value := range asObject(source.Function["options"]) {
		if stringField(asObject(value), "type") != "literal" {
			cacheable = false
			break
		}
	}
	if source.Inherited != nil {
		inherited := cacheForSource(source.Inherited)
		cacheable = cacheable && inherited != nil && inherited.cacheable
	}
	return &sourceCache{owner: source, cacheable: cacheable, options: map[string]cachedOption{}}
}

// NewFunctionSource constructs caller-owned source metadata. Keyed FunctionSource
// literals are also supported; its private per-format cache is never shared by copies.
func NewFunctionSource(value string, function map[string]any, optionValue func(string, string) (string, error), inherited *FunctionSource) *FunctionSource {
	return &FunctionSource{Value: value, Function: function, OptionValue: optionValue, Inherited: inherited}
}
