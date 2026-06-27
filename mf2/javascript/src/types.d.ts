export type MF2Literal = {
  type: "literal";
  value: string;
};

export type MF2Variable = {
  type: "variable";
  name: string;
};

export type MF2LiteralOrVariable = MF2Literal | MF2Variable;

export type MF2Options = Record<string, MF2LiteralOrVariable>;

export type MF2AttributeValue = MF2Literal | true;

export type MF2Attributes = Record<string, MF2AttributeValue>;

export type MF2FunctionRef = {
  type: "function";
  name: string;
  options?: MF2Options;
};

export type MF2Expression = {
  type: "expression";
  arg?: MF2LiteralOrVariable;
  function?: MF2FunctionRef;
  attributes?: MF2Attributes;
};

export type MF2MarkupKind = "open" | "standalone" | "close";

export type MF2Markup = {
  type: "markup";
  kind: MF2MarkupKind;
  name: string;
  options?: MF2Options;
  attributes?: MF2Attributes;
};

export type MF2PatternPart = string | MF2Expression | MF2Markup;

export type MF2Pattern = MF2PatternPart[];

export type MF2InputDeclaration = {
  type: "input";
  name: string;
  value: MF2Expression & { arg: MF2Variable };
};

export type MF2LocalDeclaration = {
  type: "local";
  name: string;
  value: MF2Expression;
};

export type MF2Declaration = MF2InputDeclaration | MF2LocalDeclaration;

export type MF2VariantKey = MF2Literal | { type: "*" };

export type MF2Variant = {
  keys: MF2VariantKey[];
  value: MF2Pattern;
};

export type MF2PatternMessage = {
  type: "message";
  declarations: MF2Declaration[];
  pattern: MF2Pattern;
};

export type MF2SelectMessage = {
  type: "select";
  declarations: MF2Declaration[];
  selectors: MF2Variable[];
  variants: MF2Variant[];
};

export type MF2Message = MF2PatternMessage | MF2SelectMessage;

export type MF2Value = unknown;

export type MF2Arguments = Record<string, MF2Value>;

export type MF2BidiIsolation = "none" | "default";

export type MF2BidiDirection = "auto" | "ltr" | "rtl";

export type MF2TextPart = {
  type: "text";
  value: string;
};

export type MF2ExpressionPart = {
  type: "expression";
  value: string;
  attributes?: MF2Attributes;
  direction?: MF2BidiDirection | null;
};

export type MF2FallbackPart = {
  type: "fallback";
  source: string;
  value?: string;
};

export type MF2MarkupPart = {
  type: "markup";
  kind: MF2MarkupKind;
  name: string;
  options?: MF2Options;
  attributes?: MF2Attributes;
};

export type MF2Part = MF2TextPart | MF2ExpressionPart | MF2FallbackPart | MF2MarkupPart;

export type MF2FormatSource = {
  value: string;
  function: MF2FunctionRef;
  inherited: MF2FormatSource | null;
  optionValue(name: string, fallback?: string | null): string | null;
};

export type MF2FormatterCall = {
  value: string;
  rawValue: MF2Value;
  function: MF2FunctionRef;
  locale: string;
  optionValue(name: string, fallback?: string | null): string | null;
  inheritedSource: MF2FormatSource | null;
};

export type MF2SelectorMatch = {
  value: string;
  rawValue: MF2Value;
  function: MF2FunctionRef;
  key: string;
  locale: string;
  optionValue(name: string, fallback?: string | null): string | null;
  inheritedSource: MF2FormatSource | null;
};

export type MF2Formatter = (call: MF2FormatterCall) => string;

export type MF2Selector = (match: MF2SelectorMatch) => number | null;

export type MF2FormatOptions = {
  locale?: string;
  functions?: {
    hasFormatter(functionRef: MF2FunctionRef): boolean;
    hasSelector(functionRef: MF2FunctionRef): boolean;
    format(call: MF2FormatterCall): string;
    select(match: MF2SelectorMatch): number | null;
  };
  bidiIsolation?: MF2BidiIsolation;
  onMissingArgument?: (context: MF2RecoveryContext) => string | null | undefined;
  onFormatError?: (context: MF2RecoveryContext) => string | null | undefined;
};

export type MF2PartsResult = {
  parts: MF2Part[];
  errors: Error[];
  ok: boolean;
  hasErrors: boolean;
};

export type MF2FormatResult = {
  value: string;
  errors: Error[];
  ok: boolean;
  hasErrors: boolean;
};

export type MF2RecoveryContext = {
  code: string;
  message: string;
  locale: string;
  variableName: string | null;
  functionName: string | null;
  sourceExpression: string;
  fallbackValue: string;
  error: Error;
};

export type MF2ParseDiagnostic = {
  code: string;
  message: string;
  start: number;
  end: number;
  severity: "error";
};

export type MF2ParseResult = {
  model: MF2Message | null;
  diagnostics: MF2ParseDiagnostic[];
  hasDiagnostics: boolean;
};

export type MF2PluralCategory = "zero" | "one" | "two" | "few" | "many" | "other";
