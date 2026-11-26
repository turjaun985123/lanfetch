// LanFetch desktop frontend: a pure static SPA shell for Tauri.
// No SSR adapter, no server endpoints — all state lives in the
// frontend talking to the Rust backend via invoke/events.
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  output: 'static',
  server: { host: '127.0.0.1', port: 4321 },
  vite: { plugins: [tailwindcss()] },
});
