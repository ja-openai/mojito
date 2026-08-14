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
import { formatMessage as formatMessageFromFormatter } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { parseToModel as parseToModelFromParser } from "@mojito-mf2/core/parser";
import { createPortableFunctionRegistry } from "@mojito-mf2/core/portable";
// @ts-expect-error Inflection runtime types stay out of the root package until a product API is approved.
import type { MF2CompiledTermPack } from "@mojito-mf2/core";
// @ts-expect-error No inflection subpath is published while JavaScript remains release-validation-only.
import type {} from "@mojito-mf2/core/inflection";
// @ts-expect-error No M2IF subpath is published while JavaScript remains release-validation-only.
import type {} from "@mojito-mf2/core/m2if";
// @ts-expect-error No compiled term-pack subpath is published while JavaScript remains release-validation-only.
import type {} from "@mojito-mf2/core/compiled-term-pack";

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
const portableRegistryFromSubpath: FunctionRegistry = createPortableFunctionRegistry(FunctionRegistry);
const intlRegistry: FunctionRegistry = createIntlFunctionRegistry(FunctionRegistry);

const parsed = parseToModel("Hello {$name}");
const parsedFromSubpath = parseToModelFromParser("Hello {$name}");
const diagnostics: MF2ParseDiagnostic[] = parseToModel("Hello {$name").diagnostics;
const parts: MF2Part[] = formatMessageToParts(parsed.model ?? model, { name: "Mojito" }, options).parts;
const output: string = formatMessage(model, { name: "Mojito" }, options).value;
const formatterOutput: string = formatMessageFromFormatter(model, { name: "Mojito" }, { functions: intlRegistry }).value;
const safeOutput: MF2FormatResult = formatMessage(model, { name: "Safe" }, options);
const safeParts: MF2PartsResult = formatMessageToParts(model, { name: "Safe Parts" }, options);
const recovery: MF2RecoveryContext | null = null;
const error = new MF2Error("test", "test");

void diagnostics;
void output;
void formatterOutput;
void safeOutput;
void safeParts;
void recovery;
void parts;
void error;
void portableRegistry;
void portableRegistryFromSubpath;
void parsedFromSubpath;
