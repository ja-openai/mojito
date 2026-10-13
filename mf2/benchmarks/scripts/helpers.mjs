import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";

// All benchmark lanes use this lock so CPU and heap runs do not overlap.
const lockPath = process.env.MF2_BENCHMARK_LOCK ?? "/tmp/mojito-mf2-benchmark.lock";

export async function acquireLock() {
  let acquired = false;
  while (!acquired) {
    try {
      await mkdir(lockPath);
      acquired = true;
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      let owner;
      try {
        owner = JSON.parse(await readFile(`${lockPath}/owner.json`, "utf8"));
      } catch {
        // Another process may be between mkdir and writeFile. Never steal it.
      }
      if (owner) {
        try {
          process.kill(owner.pid, 0);
        } catch (error) {
          if (error.code === "ESRCH") {
            await rm(lockPath, { recursive: true, force: true });
            continue;
          }
          if (error.code !== "EPERM") throw error;
          // Sandboxed processes may not signal a live owner in another session.
        }
      }
      await delay(250);
    }
  }
  await writeFile(`${lockPath}/owner.json`, JSON.stringify({ pid: process.pid }));
  let released = false;
  return async function releaseLock() {
    if (released) return;
    released = true;
    await rm(lockPath, { recursive: true, force: true });
  };
}

export function distribution(samples) {
  const sorted = [...samples].sort((left, right) => left - right);
  return {
    median: sorted[Math.floor(sorted.length / 2)],
    p95: sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)],
    min: sorted[0],
    max: sorted.at(-1),
  };
}
