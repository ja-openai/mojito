import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const port = Number.parseInt(process.env.PORT ?? '8123', 10);
const root = fileURLToPath(new URL('../fixtures', import.meta.url));

const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

async function sendStaticFile(request, response, url) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405);
    response.end();
    return;
  }

  const requestPath = url.pathname === '/' ? '/ict.html' : url.pathname;
  const normalizedPath = normalize(decodeURIComponent(requestPath))
    .replace(/^[/\\]+/, '')
    .replace(/^(\.\.[/\\])+/, '');
  const filePath = join(root, normalizedPath);

  try {
    const fileStat = await stat(filePath);
    if (!fileStat.isFile()) {
      response.writeHead(404);
      response.end();
      return;
    }
  } catch {
    response.writeHead(404);
    response.end();
    return;
  }

  response.writeHead(200, {
    'cache-control': 'no-store',
    'content-type': contentTypes[extname(filePath)] ?? 'application/octet-stream',
  });

  if (request.method === 'HEAD') {
    response.end();
    return;
  }

  createReadStream(filePath).pipe(response);
}

const server = createServer((request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);

  void sendStaticFile(request, response, url);
});

server.listen(port, () => {
  console.log(`Mojito ICT fixture: http://localhost:${port}/ict.html`);
  console.log(`Loopback URL: http://127.0.0.1:${port}/ict.html`);
});
