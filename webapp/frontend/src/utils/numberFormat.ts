const COUNT_FORMATTER = new Intl.NumberFormat('en-US');

export function formatIntegerCount(value: number): string {
  return COUNT_FORMATTER.format(value);
}

export function formatIntegerCountWithLabel(
  value: number,
  singularLabel: string,
  pluralLabel = `${singularLabel}s`,
): string {
  return `${formatIntegerCount(value)} ${value === 1 ? singularLabel : pluralLabel}`;
}
