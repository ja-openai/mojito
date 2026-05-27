import { writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { build } from "esbuild";

const result = await build({
  absWorkingDir: new URL(".", import.meta.url).pathname,
  bundle: true,
  entryPoints: ["src/mf2-editor/component-smoke-test.tsx"],
  format: "cjs",
  platform: "node",
  write: false,
});

const source = result.outputFiles[0]?.text;
if (!source) throw new Error("No bundled component smoke test output.");

const bundledTest = join(tmpdir(), `mojito-mf2-component-smoke-${process.pid}.cjs`);
await writeFile(bundledTest, source);
try {
  await import(bundledTest);
} finally {
  await rm(bundledTest, { force: true });
}
console.log("MF2 editor component smoke test passed");
