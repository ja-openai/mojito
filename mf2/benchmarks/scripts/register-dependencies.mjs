import { registerHooks } from "node:module";

// file: packages may have their own development dependencies. Resolve benchmark
// React imports from one installation to avoid duplicate hook dispatchers, and
// MF2 imports from the current source packages rather than copied dependencies.
const parentURL = new URL("../package.json", import.meta.url).href;
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (
      /^(react|react-dom|react-test-renderer)(\/|$)/.test(specifier) ||
      /^@mojito-mf2\/(core|react)(\/|$)/.test(specifier)
    ) {
      return nextResolve(specifier, { ...context, parentURL });
    }
    return nextResolve(specifier, context);
  },
});
