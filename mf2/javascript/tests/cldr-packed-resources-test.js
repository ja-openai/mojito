import assert from "node:assert/strict";

import {
  decodeDateTimeDataResource,
  decodeNumberDataResource,
} from "@mojito-mf2/core/cldr-packed";

import { DATE_TIME_DATA_PACKED } from "../../cldr/generated/experimental-datetime/javascript/date_time_data_packed.js";
import { DATE_TIME_DATA_PACKED_LOCALE as DATE_TIME_DATA_PACKED_EN_US } from "../../cldr/generated/experimental-datetime/javascript/packed-locales/en-US.js";
import { NUMBER_DATA_PACKED } from "../../cldr/generated/experimental-number/javascript/number_data_packed.js";
import { NUMBER_DATA_PACKED_LOCALE as NUMBER_DATA_PACKED_EN_US } from "../../cldr/generated/experimental-number/javascript/packed-locales/en-US.js";
import { DATE_TIME_DATA } from "../src/cldr_date_time_data.js";
import { NUMBER_DATA } from "../src/cldr_number_data.js";

const decodedNumbers = decodeNumberDataResource(NUMBER_DATA_PACKED);
assert.deepEqual(decodedNumbers.currencyFractions, NUMBER_DATA.currencyFractions);
assert.deepEqual(decodedNumbers.locales, NUMBER_DATA.locales);
assert.equal(Object.keys(decodedNumbers.locales).length, 5);
assert.equal(decodedNumbers.locales["en-US"].currencyPattern, "\u00a4#,##0.00");
assert.equal(decodedNumbers.locales["fr-FR"].currencies.USD.symbol, "$US");

const decodedNumbersEnUs = decodeNumberDataResource(NUMBER_DATA_PACKED_EN_US);
assert.deepEqual(decodedNumbersEnUs.currencyFractions, NUMBER_DATA.currencyFractions);
assert.deepEqual(decodedNumbersEnUs.locales, {
  "en-US": NUMBER_DATA.locales["en-US"],
});
assert.equal(NUMBER_DATA_PACKED_EN_US.locales.length, 1);

const decodedDateTimes = decodeDateTimeDataResource(DATE_TIME_DATA_PACKED);
assert.deepEqual(decodedDateTimes.locales, DATE_TIME_DATA.locales);
assert.equal(Object.keys(decodedDateTimes.locales).length, 5);
assert.equal(decodedDateTimes.locales["en-US"].availableFormats.yMMMd, "MMM d, y");
assert.equal(decodedDateTimes.locales["fr-FR"].decimalSeparator, ",");
assert.equal(
  decodedDateTimes.locales["ar-EG"].numberingSystemDigits,
  "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669",
);
assert.equal(decodedDateTimes.locales["fr-FR"].appendItems.Era, "{1} {0}");

const decodedDateTimeEnUs = decodeDateTimeDataResource(DATE_TIME_DATA_PACKED_EN_US);
assert.deepEqual(decodedDateTimeEnUs.locales, {
  "en-US": DATE_TIME_DATA.locales["en-US"],
});
assert.equal(DATE_TIME_DATA_PACKED_EN_US.locales.length, 1);

assert.throws(
  () => decodeNumberDataResource({ ...NUMBER_DATA_PACKED, version: 0 }),
  /Unsupported packed number resource version: 0/,
);
assert.throws(
  () => decodeNumberDataResource({ ...NUMBER_DATA_PACKED, locales: [[999]] }),
  /version 1 layout/,
);

assert.throws(
  () => decodeDateTimeDataResource({ ...DATE_TIME_DATA_PACKED, version: 5 }),
  /Unsupported packed date\/time resource version: 5/,
);
assert.throws(
  () => decodeDateTimeDataResource({ ...DATE_TIME_DATA_PACKED, locales: [[999]] }),
  /version 9 layout/,
);
assert.throws(
  () =>
    decodeDateTimeDataResource({
      ...DATE_TIME_DATA_PACKED,
      locales: [
        [
          999,
          0,
          0,
          0,
          0,
          0,
          -1,
          0,
          0,
          0,
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          [],
          0,
          0,
        ],
      ],
    }),
  /invalid string id/,
);

console.log("MF2 JavaScript packed CLDR resource test passed");
