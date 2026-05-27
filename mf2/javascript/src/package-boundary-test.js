import assert from "node:assert/strict";

import { MF2Error, canonicalLocaleKey, formatMessage, parseToModel } from "@mojito-mf2/core";
import { MF2Error as SubpathError } from "@mojito-mf2/core/errors";
import { canonicalLocaleKey as canonicalLocaleKeyFromSubpath } from "@mojito-mf2/core/locale-key";
import { parseToModel as parseToModelFromSubpath } from "@mojito-mf2/core/parser";
import { selectCardinal } from "@mojito-mf2/core/plural-rules";
import {
  FunctionRegistry,
  formatMessage as formatMessageFromSubpath,
  formatMessageToParts,
} from "@mojito-mf2/core/runtime";

const parsed = parseToModel("Hello {$name}");
assert.equal(parsed.diagnostics.length, 0);
assert.equal(formatMessage(parsed.model, { name: "Mojito" }), "Hello Mojito");
assert.equal(formatMessageFromSubpath(parsed.model, { name: "Core" }), "Hello Core");
assert.deepEqual(formatMessageToParts(parsed.model, { name: "Parts" }), [
  { type: "text", value: "Hello " },
  { type: "expression", value: "Parts" },
]);
assert.equal(parseToModelFromSubpath("Hi {$name}").diagnostics.length, 0);
assert.equal(canonicalLocaleKey("EN_us"), "en-US");
assert.equal(canonicalLocaleKeyFromSubpath("sr_Cyrl_rs"), "sr-Cyrl-RS");
assert.equal(selectCardinal("en", 1), "one");
assert.equal(FunctionRegistry.defaults().hasFormatter({ name: "string" }), true);
assert.equal(MF2Error, SubpathError);

console.log("MF2 JavaScript package boundary test passed");
