import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Same policy nginx sends: one-time tokens in /verify-email and /invitations/accept URLs must never
// reach Referer. The <meta> in index.html cannot cover the dev client Vite injects ahead of it.
const securityHeaders = { 'Referrer-Policy': 'no-referrer' };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  preview: { headers: securityHeaders },
  server: {
    headers: securityHeaders,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/sse': 'http://localhost:8080',
      '/mcp': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
});
