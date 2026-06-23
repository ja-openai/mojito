const NUMBER_RESOURCE_VERSION = 1;
const NUMBER_LOCALE_COLUMN_COUNT = 12;
const DEFAULT_CURRENCY_SPACING = Object.freeze({
  beforeCurrency: Object.freeze({
    currencyMatch: "[[:^S:]&[:^Z:]]",
    insertBetween: "\u00a0",
    surroundingMatch: "[:digit:]",
  }),
  afterCurrency: Object.freeze({
    currencyMatch: "[[:^S:]&[:^Z:]]",
    insertBetween: "\u00a0",
    surroundingMatch: "[:digit:]",
  }),
});
const DATE_TIME_RESOURCE_VERSION = 9;
const DATE_TIME_LOCALE_COLUMN_COUNT = 25;
const DEFAULT_STYLE_ORDER = Object.freeze(["full", "long", "medium", "short"]);

export function decodeNumberDataResource(resource) {
  if (resource == null || typeof resource !== "object") {
    throw new TypeError("Packed number resource must be an object.");
  }
  if (resource.version !== NUMBER_RESOURCE_VERSION) {
    throw new Error(`Unsupported packed number resource version: ${String(resource.version)}.`);
  }
  const strings = arrayField(resource, "strings");
  const currencyFractions = {};
  for (const row of arrayField(resource, "currencyFractions")) {
    if (!Array.isArray(row) || row.length !== 4) {
      throw new TypeError("Packed number currency fraction rows must use the version 1 layout.");
    }
    const currency = requiredStringById(strings, row[0], "currencyFractions.currency");
    const source = stringById(strings, row[3], `currencyFractions.${currency}.source`);
    currencyFractions[currency] = {
      digits: numberField(row[1], `currencyFractions.${currency}.digits`),
      rounding: numberField(row[2], `currencyFractions.${currency}.rounding`),
    };
    if (source != null) currencyFractions[currency].source = source;
  }

  const locales = {};
  for (const row of arrayField(resource, "locales")) {
    if (!Array.isArray(row) || row.length < NUMBER_LOCALE_COLUMN_COUNT) {
      throw new TypeError("Packed number locale rows must use the version 1 layout.");
    }
    const locale = requiredStringById(strings, row[0], "locale");
    locales[locale] = {
      currencies: currenciesMap(strings, row[11], "currencies"),
      currenciesSourceLocale: requiredStringById(strings, row[3], "currenciesSourceLocale"),
      currencyPattern: requiredStringById(strings, row[10], "currencyPattern"),
      currencySpacing: cloneCurrencySpacing(),
      decimalPattern: requiredStringById(strings, row[8], "decimalPattern"),
      minimumGroupingDigits: numberField(row[6], "minimumGroupingDigits"),
      numberingSystem: requiredStringById(strings, row[4], "numberingSystem"),
      numberingSystemDigits: stringById(strings, row[5], "numberingSystemDigits"),
      numbersSourceLocale: requiredStringById(strings, row[2], "numbersSourceLocale"),
      percentPattern: requiredStringById(strings, row[9], "percentPattern"),
      requestedLocale: requiredStringById(strings, row[1], "requestedLocale"),
      symbols: stringMap(strings, row[7], "symbols"),
    };
  }
  return { currencyFractions, locales };
}

export function decodeDateTimeDataResource(resource) {
  if (resource == null || typeof resource !== "object") {
    throw new TypeError("Packed date/time resource must be an object.");
  }
  if (resource.version !== DATE_TIME_RESOURCE_VERSION) {
    throw new Error(`Unsupported packed date/time resource version: ${String(resource.version)}.`);
  }
  const strings = arrayField(resource, "strings");
  const styleOrder = resource.styleOrder == null ? DEFAULT_STYLE_ORDER : arrayField(resource, "styleOrder");
  const locales = {};
  for (const row of arrayField(resource, "locales")) {
    if (!Array.isArray(row) || row.length < DATE_TIME_LOCALE_COLUMN_COUNT) {
      throw new TypeError("Packed date/time locale rows must use the version 9 layout.");
    }
    const stringAt = (index, field) => {
      const id = row[index];
      const value = stringById(strings, id, field);
      if (value == null) throw new TypeError(`Packed date/time locale row is missing ${field}.`);
      return value;
    };
    const locale = stringAt(0, "locale");
    locales[locale] = {
      requestedLocale: stringAt(1, "requestedLocale"),
      sourceLocale: stringAt(2, "sourceLocale"),
      numbersSourceLocale: stringAt(3, "numbersSourceLocale"),
      calendar: stringAt(4, "calendar"),
      numberingSystem: stringAt(5, "numberingSystem"),
      numberingSystemDigits: stringById(strings, row[6], "numberingSystemDigits"),
      allowedHourFormats: stringAt(7, "allowedHourFormats"),
      firstDayOfWeek: numberField(row[8], "firstDayOfWeek"),
      minDaysInFirstWeek: numberField(row[9], "minDaysInFirstWeek"),
      dateFormats: styleMap(strings, row[10], styleOrder, "dateFormats"),
      timeFormats: styleMap(strings, row[11], styleOrder, "timeFormats"),
      dateTimeFormats: styleMap(strings, row[12], styleOrder, "dateTimeFormats"),
      dateTimeStyleJoinFormats: styleMap(strings, row[13], styleOrder, "dateTimeStyleJoinFormats"),
      availableFormats: stringMap(strings, row[14], "availableFormats"),
      appendItems: stringMap(strings, row[15], "appendItems"),
      fieldNames: stringMap(strings, row[16], "fieldNames"),
      timeZoneNames: stringMap(strings, row[17], "timeZoneNames"),
      months: nestedStringMap3(strings, row[18], "months"),
      quarters: nestedStringMap3(strings, row[19], "quarters"),
      weekdays: nestedStringMap3(strings, row[20], "weekdays"),
      eras: nestedStringMap2(strings, row[21], "eras"),
      dayPeriods: nestedStringMap3(strings, row[22], "dayPeriods"),
      dayPeriodRules: stringAt(23, "dayPeriodRules"),
      decimalSeparator: stringAt(24, "decimalSeparator"),
    };
  }
  return { locales };
}

function arrayField(value, field) {
  const item = value[field];
  if (!Array.isArray(item)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  return item;
}

function numberField(value, field) {
  if (!Number.isInteger(value)) throw new TypeError(`Packed CLDR resource field ${field} must be an integer.`);
  return value;
}

function stringById(strings, id, field) {
  if (id === -1 || id == null) return null;
  if (!Number.isInteger(id) || id < 0 || id >= strings.length) {
    throw new TypeError(`Packed CLDR resource field ${field} references an invalid string id.`);
  }
  const value = strings[id];
  if (typeof value !== "string") {
    throw new TypeError(`Packed CLDR resource field ${field} references a non-string value.`);
  }
  return value;
}

function requiredStringById(strings, id, field) {
  const value = stringById(strings, id, field);
  if (value == null) throw new TypeError(`Packed CLDR resource field ${field} cannot be null.`);
  return value;
}

function styleMap(strings, values, styleOrder, field) {
  if (!Array.isArray(values)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  const decoded = {};
  for (let index = 0; index < styleOrder.length; index += 1) {
    const style = styleOrder[index];
    if (typeof style !== "string") {
      throw new TypeError("Packed date/time resource styleOrder must contain strings.");
    }
    const value = stringById(strings, values[index], `${field}.${style}`);
    if (value != null) decoded[style] = value;
  }
  return decoded;
}

function stringMap(strings, entries, field) {
  if (!Array.isArray(entries)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  const decoded = {};
  for (const entry of entries) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new TypeError(`Packed CLDR resource field ${field} entries must be key/value pairs.`);
    }
    const key = stringById(strings, entry[0], `${field}.key`);
    const value = stringById(strings, entry[1], `${field}.${String(key)}`);
    if (key == null || value == null) {
      throw new TypeError(`Packed CLDR resource field ${field} entries cannot contain null strings.`);
    }
    decoded[key] = value;
  }
  return decoded;
}

function nestedStringMap2(strings, entries, field) {
  if (!Array.isArray(entries)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  const decoded = {};
  for (const entry of entries) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new TypeError(`Packed CLDR resource field ${field} entries must be key/value pairs.`);
    }
    const key = stringById(strings, entry[0], `${field}.key`);
    if (key == null) throw new TypeError(`Packed CLDR resource field ${field} entries cannot contain null keys.`);
    decoded[key] = stringMap(strings, entry[1], `${field}.${key}`);
  }
  return decoded;
}

function nestedStringMap3(strings, entries, field) {
  if (!Array.isArray(entries)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  const decoded = {};
  for (const entry of entries) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new TypeError(`Packed CLDR resource field ${field} entries must be key/value pairs.`);
    }
    const key = stringById(strings, entry[0], `${field}.key`);
    if (key == null) throw new TypeError(`Packed CLDR resource field ${field} entries cannot contain null keys.`);
    decoded[key] = nestedStringMap2(strings, entry[1], `${field}.${key}`);
  }
  return decoded;
}

function currenciesMap(strings, entries, field) {
  if (!Array.isArray(entries)) throw new TypeError(`Packed CLDR resource field ${field} must be an array.`);
  const decoded = {};
  for (const entry of entries) {
    if (!Array.isArray(entry) || entry.length !== 4) {
      throw new TypeError(`Packed CLDR resource field ${field} entries must use the version 1 layout.`);
    }
    const currency = requiredStringById(strings, entry[0], `${field}.currency`);
    const symbol = stringById(strings, entry[1], `${field}.${currency}.symbol`);
    const narrowSymbol = stringById(strings, entry[2], `${field}.${currency}.narrowSymbol`);
    const displayName = stringById(strings, entry[3], `${field}.${currency}.displayName`);
    decoded[currency] = {};
    if (displayName != null) decoded[currency].displayName = displayName;
    if (narrowSymbol != null) decoded[currency].narrowSymbol = narrowSymbol;
    if (symbol != null) decoded[currency].symbol = symbol;
  }
  return decoded;
}

function cloneCurrencySpacing() {
  return {
    beforeCurrency: { ...DEFAULT_CURRENCY_SPACING.beforeCurrency },
    afterCurrency: { ...DEFAULT_CURRENCY_SPACING.afterCurrency },
  };
}
