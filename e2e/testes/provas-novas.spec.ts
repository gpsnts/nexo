import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { criarContaEEntrar } from '../ajudas'

// A CSP do app proíbe script injetado; o axe precisa dela desligada, só aqui no teste.
test.use({ bypassCSP: true })

/**
 * Audita a tela com o axe e confere que não há rolagem horizontal; com NEXO_E2E_CAPTURAS grava uma captura.
 * @param page página do Playwright.
 * @param onde nome da tela, para a mensagem e o nome da captura.
 */
async function auditar(page: Page, onde: string) {
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; animation: none !important; }' })
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'best-practice']).analyze()
  const resumo = r.violations.map((v) => `${v.id} (${v.impact}, ${v.nodes.length}x): ${v.nodes[0].html.slice(0, 100)}`)
  expect(resumo, `${onde}:\n${resumo.join('\n')}`).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), `${onde}: rolagem horizontal`).toBe(true)
  if (process.env.NEXO_E2E_CAPTURAS) await page.screenshot({ path: `${process.env.NEXO_E2E_CAPTURAS}/${onde.replace(/[^a-z0-9]+/gi, '-')}.png`, fullPage: true })
}

// Provedores de IA falsos: o fluxo é o do provedor real; o conteúdo é só um eco.
for (const tema of ['light', 'dark'] as const) {
  test(`prova só com questões novas e orientação: monta em segundo plano, fica pronta e guarda a orientação (${tema}) @celular @ia`, async ({ page }, info) => {
    await criarContaEEntrar(page)
    await page.goto('/novo-estudo')
    await page.getByLabel('Tópico', { exact: true }).fill('Movimento Retilíneo Uniforme')
    await page.getByRole('button', { name: 'Registrar tópico' }).click()
    await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)

    await page.goto('/provas')
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await page.getByRole('button', { name: 'Montar prova' }).click()
    await page.getByRole('checkbox', { name: 'Geral' }).check()
    await page.getByLabel('Quantidade de questões').fill('4')
    await expect(page.getByLabel(/Orientação para as questões novas/)).toHaveCount(0)  // só aparece quando se escolhe gerar questões novas
    await page.getByRole('radio', { name: /Questões novas geradas pela IA/ }).check()
    await page.getByLabel(/Orientação para as questões novas/).fill('no estilo da banca FCC, com foco em velocidade média')
    await expect(page.getByText(/Custo estimado: cerca de 24 créditos/)).toBeVisible()
    await expect(page.getByText(/não consulta provas reais da banca/)).toBeVisible()
    await auditar(page, `formulario de prova com questoes novas ${tema} ${info.project.name}`)

    await page.getByRole('button', { name: 'Gerar prova' }).click()
    await expect(page).toHaveURL(/\/provas\/[0-9a-f-]{36}/)
    await expect(page.getByRole('heading', { name: 'Pronta para começar' })).toBeVisible({ timeout: 90_000 })
    await expect(page.getByText(/4 questões novas geradas pela IA para esta prova/)).toBeVisible()
    await expect(page.getByText(/Orientação dada às questões novas: “no estilo da banca FCC/)).toBeVisible()
    await auditar(page, `prova pronta com questoes novas ${tema} ${info.project.name}`)

    await page.getByRole('button', { name: 'Começar prova' }).click()
    await expect(page.getByText(/Pergunta|Questão|\?/).first()).toBeVisible()
  })
}

test('custo acima do que resta na cota recusa a prova antes de gerar @ia', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Cinemática')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await page.goto('/provas')
  await page.getByRole('button', { name: 'Montar prova' }).click()
  await page.getByRole('checkbox', { name: 'Geral' }).check()
  await page.getByLabel('Quantidade de questões').fill('50')
  await page.getByRole('radio', { name: /Questões novas geradas pela IA/ }).check()  // 50 x 6 = 300 créditos, mais que a cota semanal
  await page.getByRole('button', { name: 'Gerar prova' }).click()
  await expect(page.getByRole('alert').filter({ hasText: /restam .* na sua cota desta semana/ })).toBeVisible()
})
