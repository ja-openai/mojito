import { Buffer } from "node:buffer";

import { build } from "esbuild";

const result = await build({
  absWorkingDir: new URL(".", import.meta.url).pathname,
  bundle: true,
  entryPoints: ["src/mf2-editor/model-smoke-test.ts"],
  format: "esm",
  platform: "node",
  write: false,
});

const source = result.outputFiles[0]?.text;
if (!source) throw new Error("No bundled model smoke test output.");

await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
console.log("MF2 editor model smoke test passed");
