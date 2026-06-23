import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const backendUrl = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080';

// Build into Spring Boot's static resources so it serves at /
export default defineConfig({
  base: '/',
  plugins: [react()],
  server: {
    port: Number(process.env.VITE_PORT ?? 5173),
    strictPort: true,
    cors: {
      origin: [/^chrome-extension:\/\//, /^http:\/\/localhost:\d+$/, /^http:\/\/127\.0\.0\.1:\d+$/],
      credentials: true,
    },
    proxy: {
      // Forward API calls to the Spring Boot backend
      '/api': {
        target: backendUrl,
        changeOrigin: true,
        headers: {
          // Login as the configured dev user (defaults to admin), requires backend to have HEADER auth on
          'x-forwarded-user': process.env.VITE_X_FORWARD_USER ?? 'admin',
        },
        configure(proxy) {
          proxy.on('proxyRes', (proxyResponse) => {
            delete proxyResponse.headers['set-cookie'];
          });
        },
      },
    },
  },
  build: {
    // Output to webapp/target/classes/public so Spring Boot serves it from the web root.
    outDir: '../target/classes/public',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: true,
  },
});
