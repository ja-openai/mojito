import assert from "node:assert/strict";

import { compileMessageCatalog } from "./index.js";
import { createCompiledMessageCatalog } from "./runtime.js";

const sources = {
  greeting: "Hello {$name}",
  cart: `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
};

const compiled = compileMessageCatalog(sources);

if (process.argv.includes("--check")) {
  assert.equal(compiled.greeting.source, sources.greeting);
  assert.equal(compiled.greeting.model.type, "message");
  assert.equal(compiled.greeting.diagnostics.length, 0);
  assert.equal(compiled.cart.model.type, "select");
  assert.equal(createCompiledMessageCatalog(compiled).ids().join(","), "greeting,cart");
} else {
  console.log(JSON.stringify(compiled, null, 2));
}
