package mf2

// validModelShape checks shared-schema fields without discarding extension properties.
func validModelShape(model Model) bool {
	obj := map[string]any(model)
	declarations, ok := obj["declarations"].([]any)
	if !ok {
		return false
	}
	for _, raw := range declarations {
		d := asObject(raw)
		if (d["type"] != "input" && d["type"] != "local") || !modelString(d["name"]) || !validModelExpression(d["value"]) {
			return false
		}
	}
	switch obj["type"] {
	case "message":
		return validModelPattern(obj["pattern"])
	case "select":
		selectors, ok := obj["selectors"].([]any)
		if !ok {
			return false
		}
		for _, raw := range selectors {
			s := asObject(raw)
			if s["type"] != "variable" || !modelString(s["name"]) {
				return false
			}
		}
		variants, ok := obj["variants"].([]any)
		if !ok {
			return false
		}
		for _, raw := range variants {
			v := asObject(raw)
			keys, ok := v["keys"].([]any)
			if !ok || !validModelPattern(v["value"]) {
				return false
			}
			for _, rawKey := range keys {
				k := asObject(rawKey)
				if k["type"] == "*" {
					if value, exists := k["value"]; exists && !modelString(value) {
						return false
					}
				} else if !validModelArgument(k, true) {
					return false
				}
			}
		}
		return true
	default:
		return false
	}
}

func modelString(value any) bool { _, ok := value.(string); return ok }
func validModelArgument(value any, literalOnly bool) bool {
	item := asObject(value)
	if item["type"] == "literal" {
		return modelString(item["value"])
	}
	return !literalOnly && item["type"] == "variable" && modelString(item["name"])
}
func validModelFields(item map[string]any, name string, attributes bool) bool {
	raw, exists := item[name]
	if !exists {
		return true
	}
	values, ok := raw.(map[string]any)
	if !ok {
		return false
	}
	for _, value := range values {
		if attributes {
			if present, ok := value.(bool); ok && present {
				continue
			}
		}
		if !validModelArgument(value, attributes) {
			return false
		}
	}
	return true
}
func validModelExpression(value any) bool {
	item := asObject(value)
	if item["type"] != "expression" {
		return false
	}
	arg, hasArg := item["arg"]
	function, hasFunction := item["function"]
	if !hasArg && !hasFunction {
		return false
	}
	if hasArg && !validModelArgument(arg, false) {
		return false
	}
	if hasFunction {
		f := asObject(function)
		if f["type"] != "function" || !modelString(f["name"]) || !validModelFields(f, "options", false) {
			return false
		}
	}
	return validModelFields(item, "attributes", true)
}
func validModelPattern(value any) bool {
	parts, ok := value.([]any)
	if !ok {
		return false
	}
	for _, part := range parts {
		if modelString(part) {
			continue
		}
		p := asObject(part)
		switch p["type"] {
		case "expression":
			if !validModelExpression(p) {
				return false
			}
		case "markup":
			if !modelString(p["kind"]) || !modelString(p["name"]) || !validModelFields(p, "options", false) || !validModelFields(p, "attributes", true) {
				return false
			}
		default:
			return false
		}
	}
	return true
}

func detachedModelFields(fields map[string]any) map[string]any {
	result := make(map[string]any, len(fields))
	for name, value := range fields {
		if object, ok := value.(map[string]any); ok {
			copy := make(map[string]any, len(object))
			for key, child := range object {
				copy[key] = child
			}
			result[name] = copy
		} else {
			result[name] = value
		}
	}
	return result
}
