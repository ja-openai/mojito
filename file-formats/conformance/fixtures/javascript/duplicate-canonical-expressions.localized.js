const duplicateExpressionKeys = {};
duplicateExpressionKeys["a\\b"] = "literal-backslash";
duplicateExpressionKeys["a\b"] = "backspace";
export const duplicateCanonicalExpressions = {
  "duplicate.expressions": `B ${duplicateExpressionKeys["a\b"]} A ${duplicateExpressionKeys["a\\b"]}`,
};
