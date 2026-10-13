import assert from "node:assert/strict";
import { test } from "node:test";
import { FunctionRegistry, formatMessage, parseToModel } from "../src/index.js";

test("flat function snapshots are detached and readonly without caching mutable models", () => {
  const model = parseToModel("{$value :example style=first}").model;
  const snapshots = [];
  const functions = FunctionRegistry.defaults().withFunction("example", (call) => {
    snapshots.push(call.function);
    assert(Object.isFrozen(call.function));
    assert(Object.isFrozen(call.function.options));
    assert(Object.isFrozen(call.function.options.style));
    return call.optionValue("style", "");
  });
  assert.equal(formatMessage(model, { value: "x" }, { functions }).value, "first");
  model.pattern[0].function.options.style.value = "second";
  assert.equal(formatMessage(model, { value: "x" }, { functions }).value, "second");
  assert.equal(snapshots[0].options.style.value, "first");
  assert.notEqual(snapshots[0], snapshots[1]);
});

test("opaque function and operand metadata retain deep-copy ownership", () => {
  const model = parseToModel("{$value :example style=first}").model;
  const annotation = model.pattern[0].function;
  annotation.extension = { nested: ["original"] };
  annotation.options.style.extension = { nested: ["original"] };
  const functions = FunctionRegistry.defaults().withFunction("example", (call) => {
    call.function.extension.nested[0] = "changed";
    call.function.options.style.extension.nested[0] = "changed";
    return call.value;
  });
  assert.equal(formatMessage(model, { value: "x" }, { functions }).value, "x");
  assert.equal(annotation.extension.nested[0], "original");
  assert.equal(annotation.options.style.extension.nested[0], "original");
});

test("symbol metadata stays outside snapshots as with structuredClone", () => {
  const symbol = Symbol("metadata");
  const model = parseToModel("{$value :example style=first}").model;
  const annotation = model.pattern[0].function;
  annotation[symbol] = { value: "original" };
  annotation.options.style[symbol] = { value: "original" };
  const functions = FunctionRegistry.defaults().withFunction("example", (call) => {
    assert.equal(call.function[symbol], undefined);
    assert.equal(call.function.options.style[symbol], undefined);
    return call.value;
  });
  assert.equal(formatMessage(model, { value: "x" }, { functions }).value, "x");
  assert.equal(annotation[symbol].value, "original");
  assert.equal(annotation.options.style[symbol].value, "original");
});
