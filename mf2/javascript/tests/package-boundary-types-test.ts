import {
  FunctionRegistry,
  MF2Error,
  formatMessage,
  formatMessageToParts,
  parseToModel,
  type MF2FormatResult,
  type MF2FormatOptions,
  type MF2PatternMessage,
  type MF2Part,
  type MF2PartsResult,
  type MF2Formatter,
  type MF2RecoveryContext,
  type MF2ParseDiagnostic,
} from "@mojito-mf2/core";

const model: MF2PatternMessage = {
  type: "message",
  declarations: [],
  pattern: ["Hello ", { type: "expression", arg: { type: "variable", name: "name" } }],
};

const options: MF2FormatOptions = {
  locale: "en",
  functions: FunctionRegistry.defaults().withFunction("upper", ((call) => call.value.toUpperCase()) satisfies MF2Formatter),
  bidiIsolation: "default",
};
const portableRegistry: FunctionRegistry = FunctionRegistry.portable();

const parsed = parseToModel("Hello {$name}");
const diagnostics: MF2ParseDiagnostic[] = parseToModel("Hello {$name").diagnostics;
const parts: MF2Part[] = formatMessageToParts(parsed.model ?? model, { name: "Mojito" }, options).parts;
const output: string = formatMessage(model, { name: "Mojito" }, options).value;
const safeOutput: MF2FormatResult = formatMessage(model, { name: "Safe" }, options);
const safeParts: MF2PartsResult = formatMessageToParts(model, { name: "Safe Parts" }, options);
const recovery: MF2RecoveryContext | null = null;
const error = new MF2Error("test", "test");

void diagnostics;
void output;
void safeOutput;
void safeParts;
void recovery;
void parts;
void error;
void portableRegistry;
