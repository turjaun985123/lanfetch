// Vitest for pure-logic unit tests only (docs/FRONTEND.md §Testing):
// node environment, no jsdom — DOM behavior is not claimed from
// these tests; layout/interaction truth stays with the human
// RESPONSIVE-QA.md checklist (headless CI ban stands).
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
