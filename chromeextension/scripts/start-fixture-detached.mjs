import { spawn } from 'node:child_process';
import { closeSync, openSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const logFile = process.argv[2];

if (!logFile) {
  console.error('Usage: node scripts/start-fixture-detached.mjs <log-file>');
  process.exit(2);
}

const logFd = openSync(logFile, 'w');

try {
  const child = spawn(process.execPath, ['scripts/fixture-server.mjs'], {
    cwd: root,
    detached: true,
    env: {
      ...process.env,
      PORT: process.env.PORT ?? '8123',
    },
    stdio: ['ignore', logFd, logFd],
  });

  child.unref();
  console.log(child.pid);
} finally {
  closeSync(logFd);
}
