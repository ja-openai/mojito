package mf2

// Callback metadata is detached from semantic model fields and cached sources.
// Unknown extension payloads are not interpreted by the formatter.
func detachedFunction(function map[string]any) map[string]any {
	if function == nil {
		return nil
	}
	copy := make(map[string]any, len(function))
	for key, value := range function {
		copy[key] = value
	}
	if options, ok := function["options"].(map[string]any); ok {
		copy["options"] = detachedModelFields(options)
	}
	return copy
}

func detachedFunctionSource(source *FunctionSource) *FunctionSource {
	if source == nil {
		return nil
	}
	seen := map[*FunctionSource]*FunctionSource{}
	var head, tail *FunctionSource
	for current := source; current != nil; current = current.Inherited {
		if copy, ok := seen[current]; ok {
			tail.Inherited = copy
			break
		}
		copy := &FunctionSource{Value: current.Value, Function: detachedFunction(current.Function), OptionValue: current.OptionValue}
		seen[current] = copy
		if tail == nil {
			head = copy
		} else {
			tail.Inherited = copy
		}
		tail = copy
	}
	return head
}
