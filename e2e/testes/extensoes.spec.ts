import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { criarContaEEntrar, enviarMaterial, fixture, sql } from '../ajudas'

// A CSP do app proíbe script injetado; o axe precisa dela desligada, só aqui no teste.
test.use({ bypassCSP: true })

// Provedores de IA falsos: a descrição da figura é uma frase de demonstração; o fluxo é o do provedor real.
test('PDF com tabela e figura gera trechos desses tipos e o material fica pronto sem aviso @ia', async ({ page }) => {
  const { email } = await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('tabela-e-figura.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await expect(page.getByText(/de fora do conteúdo/)).toHaveCount(0)
  const tipos = sql(`SELECT string_agg(t.tipo_conteudo, ',' ORDER BY t.ordem) FROM trecho t JOIN usuario u ON u.id = t.usuario_id WHERE u.email = '${email.toLowerCase()}'`).toString()
  expect(tipos).toContain('tabela')
  expect(tipos).toContain('figura')
  const tabela = sql(`SELECT t.conteudo FROM trecho t JOIN usuario u ON u.id = t.usuario_id WHERE u.email = '${email.toLowerCase()}' AND t.tipo_conteudo = 'tabela'`).toString()
  expect(tabela).toContain('| Cidade | Habitantes |')
  expect(tabela).toContain('| Londrina | 580000 |')
})

test('figuras além do limite por material ficam de fora e a tela avisa @ia', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('figuras-demais.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 90_000 })
  await expect(page.getByText(/2 figuras ficaram de fora do conteúdo \(2 passaram do limite de figuras por material\)/)).toBeVisible()
})

test('exportar dados: sem dados avisa; com questões baixa o CSV @celular @ia', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Baixar arquivo' }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'Não há dados para exportar.' })).toBeVisible()

  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Movimento Retilíneo Uniforme')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await page.getByLabel('Quantas questões gerar').fill('2')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(/2 questões gravadas/)).toBeVisible({ timeout: 45_000 })

  await page.goto('/perfil')
  await page.getByLabel('Minhas questões').check()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Baixar arquivo' }).click()
  const arquivo = await download
  expect(arquivo.suggestedFilename()).toBe('nexo-questoes.csv')
  const texto = readFileSync(await arquivo.path(), 'utf8')
  expect(texto).toContain('assunto,topico,questao_id,enunciado,alternativas,resposta_correta,dificuldade,criada_em')
  expect(texto.trim().split('\r\n')).toHaveLength(3) // cabeçalho e as 2 questões
  await expect(page.getByText('Arquivo baixado.')).toBeVisible()
})

/**
 * Audita a tela com o axe (regras WCAG A e AA e boas práticas) e, se NEXO_E2E_CAPTURAS apontar uma pasta, grava uma captura dela.
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

for (const tema of ['light', 'dark'] as const) {
  test(`exportação e aviso de figuras sem violações de acessibilidade no tema ${tema} @celular @ia`, async ({ page }, info) => {
    await criarContaEEntrar(page)
    await enviarMaterial(page, fixture('figuras-demais.pdf'))
    await expect(page.getByText(/figuras ficaram de fora do conteúdo/)).toBeVisible({ timeout: 90_000 })
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await auditar(page, `material com aviso ${tema} ${info.project.name}`)
    await page.goto('/perfil')
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await page.getByRole('button', { name: 'Baixar arquivo' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Não há dados para exportar.' })).toBeVisible()
    await auditar(page, `perfil com exportação e erro ${tema} ${info.project.name}`)
  })
}
