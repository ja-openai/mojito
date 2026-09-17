import { FunctionRegistry, formatMessage } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";

// Shared by prerendering and hydration. Only the build imports the parser.
const functions = createIntlFunctionRegistry(FunctionRegistry);

export function createMessageFormatter(resources, locale) {
  const catalogs = Object.hasOwn(resources, locale)
    ? resources[locale]
    : undefined;
  if (!catalogs) throw new Error(`Missing MF2 resources for ${locale}`);
  return (resource, name, args = {}) => {
    const catalog = Object.hasOwn(catalogs, resource)
      ? catalogs[resource]
      : undefined;
    const model =
      catalog && Object.hasOwn(catalog, name) ? catalog[name] : undefined;
    if (!model)
      throw new Error(`Unknown MF2 message: ${locale}/${resource}#${name}`);
    const result = formatMessage(model, args, { locale, functions });
    if (result.hasErrors) {
      throw new Error(
        `MF2 formatting failed: ${resource}#${name}: ${JSON.stringify(result.errors)}`,
      );
    }
    return result.value;
  };
}
