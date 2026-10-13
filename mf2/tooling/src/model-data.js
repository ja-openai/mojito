// Reject non-data input and bound traversal before reading or normalizing a model.
export function validateModelData(model, limits) {
  const seen = new WeakSet();
  let nodes = 0;
  let characters = 0;
  function check(value, depth = 0) {
    if (++nodes > limits.parts * 16 + limits.declarations * 16 || depth > 16)
      throw new Error("Model exceeds its node/depth budget.");
    if (typeof value === "string") {
      characters += value.length;
      if (characters > limits.sourceLength * 8)
        throw new Error("Model exceeds its text budget.");
      return;
    }
    if (value === true || value === null) return;
    if (!value || typeof value !== "object")
      throw new Error("Model contains an invalid value.");
    if (
      !Array.isArray(value) &&
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))
    )
      throw new Error("Model must contain plain data objects.");
    if (seen.has(value))
      throw new Error("Model contains shared or cyclic nodes.");
    seen.add(value);
    if (
      Array.isArray(value) &&
      value.length > limits.parts * 16 + limits.declarations * 16
    )
      throw new Error("Model exceeds its array budget.");
    const keys = Reflect.ownKeys(value);
    if (Array.isArray(value) && keys.length !== value.length + 1)
      throw new Error("Model arrays must be dense.");
    for (const key of keys) {
      if (Array.isArray(value) && key === "length") continue;
      const property = Object.getOwnPropertyDescriptor(value, key);
      if (
        typeof key !== "string" ||
        !property?.enumerable ||
        !Object.hasOwn(property, "value")
      )
        throw new Error("Model contains an accessor.");
      characters += key.length;
      if (characters > limits.sourceLength * 8)
        throw new Error("Model exceeds its text budget.");
      check(property.value, depth + 1);
    }
  }
  check(model);
}
