import { expect, test, type Page } from '@playwright/test'
import { criarContaEEntrar, SENHA } from '../ajudas'

// Precisa do compose com NEXO_ADMIN_EMAILS=<e-mail do admin>. Uso: NEXO_E2E_ADMIN_EMAIL=<e-mail do admin> npx playwright test admin
const ADMIN = process.env.NEXO_E2E_ADMIN_EMAIL
test.skip(!ADMIN, 'defina NEXO_E2E_ADMIN_EMAIL (e suba o compose com NEXO_ADMIN_EMAILS igual a ele)')

/**
 * Entra como administrador; cria a conta na primeira vez.
 * @param page página do Playwright.
 */
async function entrarComoAdmin(page: Page) {
  await page.goto('/entrar')
  await page.getByLabel('E-mail').fill(ADMIN!)
  await page.getByLabel('Senha', { exact: true }).fill(SENHA)
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  const entrou = await page.getByRole('heading', { name: /^Olá, / }).waitFor({ timeout: 4000 }).then(() => true, () => false)
  if (entrou) return
  await criarContaEEntrar(page, ADMIN!, 'Admin Teste')
}

test('conta nova entra na hora; o admin limita a cota, suspende e a pessoa é expulsa', async ({ browser }) => {
  const pessoa = await browser.newPage()
  const { email } = await criarContaEEntrar(pessoa, undefined, 'Pessoa Nova')

  const adm = await browser.newPage()
  await entrarComoAdmin(adm)
  await adm.getByRole('button', { name: 'Admin' }).first().click()
  const linha = adm.getByRole('listitem').filter({ hasText: email })
  await expect(linha.getByText('Ativa')).toBeVisible()
  await linha.getByRole('button', { name: 'Cota' }).click()
  await linha.getByLabel(/Créditos por semana/).fill('0')
  await linha.getByRole('button', { name: 'Salvar cota' }).click()
  await expect(linha.getByText(/0 de 0 créditos/)).toBeVisible()

  await linha.getByRole('button', { name: 'Suspender' }).click()
  await expect(linha.getByText('Suspensa')).toBeVisible()
  await pessoa.goto('/revisar') // qualquer chamada autenticada: o servidor corta na hora
  await expect(pessoa.getByText(/conta está suspensa/)).toBeVisible()
})
