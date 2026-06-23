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
import {
  decodeDateTimeDataResource,
  decodeNumberDataResource,
  type DateTimeDataResource,
  type NumberDataResource,
} from "@mojito-mf2/core/cldr-packed";
import {
  createDateTimeCoreFunctionRegistry,
  formatDateCore,
  formatDateCoreToParts,
  formatDateTimeCore,
  formatDateTimeCoreToParts,
  formatTimeCore,
  formatTimeCoreToParts,
} from "@mojito-mf2/core/date-time-core";
import { formatNumberCore, formatNumberCoreToParts } from "@mojito-mf2/core/number-core";
import { parseToModel as parseToModelFromParser } from "@mojito-mf2/core/parser";
import { createPortableFunctionRegistry } from "@mojito-mf2/core/portable";
import {
  createRelativeTimeCoreFunctionRegistry,
  formatRelativeTimeCore,
  formatRelativeTimeCoreToParts,
  type RelativeTimeDataResource,
} from "@mojito-mf2/core/relative-time-core";

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
const dateTimeCoreRegistry: FunctionRegistry = createDateTimeCoreFunctionRegistry(FunctionRegistry);
const relativeTimeData: RelativeTimeDataResource = {
  localeMap: {},
  patternSets: [],
};
const relativeTimeCoreRegistry: FunctionRegistry = createRelativeTimeCoreFunctionRegistry(FunctionRegistry, relativeTimeData);

const parsed = parseToModel("Hello {$name}");
const parsedFromSubpath = parseToModelFromParser("Hello {$name}");
const diagnostics: MF2ParseDiagnostic[] = parseToModel("Hello {$name").diagnostics;
const parts: MF2Part[] = formatMessageToParts(parsed.model ?? model, { name: "Mojito" }, options).parts;
const output: string = formatMessage(model, { name: "Mojito" }, options).value;
const formatterOutput: string = formatMessageFromFormatter(model, { name: "Mojito" }, { functions: intlRegistry }).value;
const numberCoreOutput: string = formatNumberCore(1234.5, {
  locale: "en-US",
  style: "currency",
  currency: "USD",
});
const numberCoreParts: Array<{ type: "text"; value: string }> = formatNumberCoreToParts(1234.5, {
  locale: "en-US",
  style: "currency",
  currency: "USD",
});
const dateCoreOutput: string = formatDateCore("2026-05-21T14:30:15Z", {
  locale: "en-US",
  dateStyle: "medium",
  timeZone: "UTC",
});
const dateCoreParts: Array<{ type: "text"; value: string }> = formatDateCoreToParts("2026-05-21T14:30:15Z", {
  locale: "en-US",
  dateStyle: "medium",
  timeZone: "UTC",
});
const timeCoreOutput: string = formatTimeCore("2026-05-21T14:30:15Z", {
  locale: "en-US",
  timeStyle: "short",
  timeZone: "UTC",
});
const timeCoreParts: Array<{ type: "text"; value: string }> = formatTimeCoreToParts("2026-05-21T14:30:15Z", {
  locale: "en-US",
  timeStyle: "short",
  timeZone: "UTC",
});
const dateTimeCoreOutput: string = formatDateTimeCore("2026-05-21T14:30:15Z", {
  locale: "en-US",
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "UTC",
});
const dateTimeCoreParts: Array<{ type: "text"; value: string }> = formatDateTimeCoreToParts("2026-05-21T14:30:15Z", {
  locale: "en-US",
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "UTC",
});
const relativeTimeCoreOutput: string = formatRelativeTimeCore(60, {
  locale: "en",
  unit: "minute",
  data: relativeTimeData,
});
const relativeTimeCoreParts: Array<{ type: "text"; value: string }> = formatRelativeTimeCoreToParts(60, {
  locale: "en",
  unit: "minute",
  data: relativeTimeData,
});
const decodedDateTimeData: DateTimeDataResource = decodeDateTimeDataResource({
  version: 6,
  strings: [],
  locales: [],
});
const decodedNumberData: NumberDataResource = decodeNumberDataResource({
  version: 1,
  strings: [],
  currencyFractions: [],
  locales: [],
});
const safeOutput: MF2FormatResult = formatMessage(model, { name: "Safe" }, options);
const safeParts: MF2PartsResult = formatMessageToParts(model, { name: "Safe Parts" }, options);
const recovery: MF2RecoveryContext | null = null;
const error = new MF2Error("test", "test");

void diagnostics;
void output;
void formatterOutput;
void numberCoreOutput;
void numberCoreParts;
void dateCoreOutput;
void dateCoreParts;
void timeCoreOutput;
void timeCoreParts;
void dateTimeCoreOutput;
void dateTimeCoreParts;
void relativeTimeCoreOutput;
void relativeTimeCoreParts;
void decodedDateTimeData;
void decodedNumberData;
void safeOutput;
void safeParts;
void recovery;
void parts;
void error;
void portableRegistry;
void portableRegistryFromSubpath;
void parsedFromSubpath;
void dateTimeCoreRegistry;
void relativeTimeCoreRegistry;
