// Metadata belongs to a registry instance and cannot outlive it. This is not a
// compiled-message cache and does not change the public function callback API.
const numericFunctions = new WeakMap();
const numericSelectionGuards = new WeakMap();

export function markNumericFunctions(registry, names) {
  numericFunctions.set(registry, new Set(names));
  return registry;
}

export function copyNumericFunctions(source, target, replacedName, replacedSelector) {
  const names = new Set(numericFunctions.get(source) ?? []);
  if (replacedName != null) names.delete(replacedName);
  const guards = new Map(numericSelectionGuards.get(source) ?? []);
  guards.delete(replacedName ?? replacedSelector);
  numericSelectionGuards.set(target, guards);
  return markNumericFunctions(target, names);
}

export function hasNumericDirection(registry, name) {
  return numericFunctions.get(registry)?.has(name) ?? false;
}

// Adapter-specific selection limitations are private to that registry. Replacing
// either the formatter or selector transfers responsibility to the caller.
export function markNumericSelectionGuard(registry, names, guard) {
  numericSelectionGuards.set(registry, new Map(names.map(name => [name, guard])));
  return registry;
}

export function checkNumericSelection(registry, call) {
  numericSelectionGuards.get(registry)?.get(call.function.name)?.(call);
}
