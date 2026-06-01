export function hasSameSet<T>(
  left: T[],
  right: T[],
  normalize: (value: T) => string | number = (value) => String(value),
) {
  if (left.length !== right.length) {
    return false;
  }

  const rightSet = new Set(right.map(normalize));
  return left.every((value) => rightSet.has(normalize(value)));
}
