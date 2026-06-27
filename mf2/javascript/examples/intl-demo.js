import { FunctionRegistry, formatMessage, parseToModel } from "../src/index.js";
import { createIntlFunctionRegistry } from "../src/intl_functions.js";

const registry = createIntlFunctionRegistry(FunctionRegistry);
const catalog = {
  metrics: `Number {$amount :number maximumFractionDigits=2}; percent {$ratio :percent maximumFractionDigits=1}; currency {$price :currency currency=EUR}`,
  instant: `Date {$instant :date dateStyle=full timeZone=UTC}; time {$instant :time timeStyle=medium timeZone=UTC}; datetime {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}`,
  relative: `Due {$delta :relativeTime unit=day numeric=always}`,
};

const args = {
  amount: 12345.678,
  ratio: 0.1234,
  price: 9876.5,
  instant: "2026-05-21T14:30:15Z",
  delta: -3,
};

for (const locale of ["en-US", "fr-FR", "ja-JP", "ar-EG"]) {
  for (const [id, source] of Object.entries(catalog)) {
    const parsed = parseToModel(source);
    if (parsed.hasDiagnostics) throw new Error(`${id}: ${JSON.stringify(parsed.diagnostics)}`);
    console.log(`${locale} ${id} -> ${formatMessage(parsed.model, args, { locale, functions: registry }).value}`);
  }
}
