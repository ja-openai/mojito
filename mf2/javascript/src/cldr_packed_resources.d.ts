export type CldrStringMap = Record<string, string>;
export type CldrNestedStringMap2 = Record<string, CldrStringMap>;
export type CldrNestedStringMap3 = Record<string, CldrNestedStringMap2>;

export interface NumberCurrencyData {
  displayName?: string;
  narrowSymbol?: string;
  symbol?: string;
}

export interface NumberCurrencyFractionData {
  digits: number;
  rounding: number;
  source?: string;
}

export interface NumberCurrencySpacingData {
  currencyMatch: string;
  insertBetween: string;
  surroundingMatch: string;
}

export interface NumberLocaleData {
  currencies: Record<string, NumberCurrencyData>;
  currenciesSourceLocale: string;
  currencyPattern: string;
  currencySpacing: {
    beforeCurrency: NumberCurrencySpacingData;
    afterCurrency: NumberCurrencySpacingData;
  };
  decimalPattern: string;
  minimumGroupingDigits: number;
  numberingSystem: string;
  numberingSystemDigits: string | null;
  numbersSourceLocale: string;
  percentPattern: string;
  requestedLocale: string;
  symbols: CldrStringMap;
}

export interface NumberDataResource {
  currencyFractions: Record<string, NumberCurrencyFractionData>;
  locales: Record<string, NumberLocaleData>;
}

export interface PackedNumberDataResource {
  version: number;
  strings: readonly string[];
  currencyFractions: readonly unknown[];
  locales: readonly unknown[];
}

export interface DateTimeLocaleData {
  requestedLocale: string;
  sourceLocale: string;
  numbersSourceLocale: string;
  calendar: string;
  numberingSystem: string;
  numberingSystemDigits: string | null;
  decimalSeparator: string;
  allowedHourFormats: string;
  firstDayOfWeek: number;
  minDaysInFirstWeek: number;
  dateFormats: CldrStringMap;
  timeFormats: CldrStringMap;
  dateTimeFormats: CldrStringMap;
  availableFormats: CldrStringMap;
  appendItems: CldrStringMap;
  fieldNames: CldrStringMap;
  timeZoneNames: CldrStringMap;
  months: CldrNestedStringMap3;
  quarters: CldrNestedStringMap3;
  weekdays: CldrNestedStringMap3;
  eras: CldrNestedStringMap2;
  dayPeriods: CldrNestedStringMap3;
  dayPeriodRules: string;
}

export interface DateTimeDataResource {
  locales: Record<string, DateTimeLocaleData>;
}

export interface PackedDateTimeDataResource {
  version: number;
  styleOrder?: readonly string[];
  strings: readonly string[];
  locales: readonly unknown[];
}

export function decodeNumberDataResource(resource: unknown): NumberDataResource;

export function decodeDateTimeDataResource(resource: unknown): DateTimeDataResource;
