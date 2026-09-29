const COUNT_FORMATTER = new Intl.NumberFormat('en-US');

export function formatIntegerCount(value: number): string {
  return COUNT_FORMATTER.format(value);
}
