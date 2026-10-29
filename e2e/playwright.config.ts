import { defineConfig, devices } from '@playwright/test'

// Testes de ponta a ponta contra o `docker compose up`. Requisito do curso: Chrome e Firefox; celular em viewport pequeno.
export default defineConfig({
  testDir: './testes',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false, // o limite por IP das rotas públicas (20 por minuto) e o estado do compose pedem execução em série
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['dot'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL: process.env.NEXO_URL ?? 'http://localhost:3000', trace: 'retain-on-failure' },
  projects: [
    // NEXO_E2E_CANAL=chrome usa o Chrome instalado (o Playwright não baixa Chromium em algumas distribuições).
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: process.env.NEXO_E2E_CANAL } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'celular', use: { ...devices['Pixel 7'], channel: process.env.NEXO_E2E_CANAL }, grep: /@celular/ },
  ],
})
