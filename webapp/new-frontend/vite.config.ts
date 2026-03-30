import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Build into Spring Boot's static resources so it serves at /
export default defineConfig({
  base: '/',
  plugins: [react()],
  server: {
    port: Number(process.env.VITE_PORT ?? 5173),
    strictPort: true,
    proxy: {
      // Forward API calls to the Spring Boot backend
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        headers: {
          // Login as the configured dev user (defaults to admin), requires backend to have HEADER auth on
          'x-forwarded-user': process.env.VITE_X_FORWARD_USER ?? 'admin',
        },
      },
    },
  },
  build: {
    // Output to webapp/target/classes/public so Spring Boot serves it from the web root.
    // Keep existing legacy assets because some routes still use the old frontend.
    outDir: '../target/classes/public',
    emptyOutDir: false,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: true,
  },
});
