const duplicateExpressionKeys = {};
duplicateExpressionKeys["a\\b"] = "literal-backslash";
duplicateExpressionKeys["a\b"] = "backspace";
export const duplicateCanonicalExpressions = {
  "duplicate.expressions": `A ${duplicateExpressionKeys["a\\b"]} B ${duplicateExpressionKeys["a\b"]}`,
};
