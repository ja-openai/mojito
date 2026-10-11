import { createMF2 } from "@mojito-mf2/react";

// Strict build-time sample checks use the same parser-free formatter as React.
export function createMessageFormatter(resources, locale) {
  return createMF2({
    resources,
    locale,
    sourceLocale: "en",
    timeZone: "UTC",
    onError(error) {
      throw new Error(`MF2 formatting failed: ${error.message}`);
    },
  }).format;
}
