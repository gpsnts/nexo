import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture } from '../ajudas'

// A CSP do app proíbe script injetado; o axe precisa dela desligada, só aqui no teste.
test.use({ bypassCSP: true })

const auditar = async (page: Page, onde: string) => {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'best-practice']).analyze()
  const resumo = r.violations.map((v) => `${v.id} (${v.impact}, ${v.nodes.length}x): ${v.nodes[0].html.slice(0, 100)}`)
  expect(resumo, `${onde}:\n${resumo.join('\n')}`).toEqual([])
}

for (const tema of ['light', 'dark'] as const) {
  test(`telas internas sem violações de acessibilidade no tema ${tema}`, async ({ page }) => {
    await criarContaEEntrar(page)
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await auditar(page, `início (${tema})`)
    await page.getByRole('button', { name: /Uso de IA/ }).click()   // painel de detalhes da barra de uso aberto
    await expect(page.getByRole('region', { name: 'Detalhes do uso de IA' })).toBeVisible()
    await auditar(page, `início com a barra de uso aberta (${tema})`)
    await page.keyboard.press('Escape')

    await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
    await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await page.getByText('Ver trechos do tópico 2', { exact: true }).click()
    await page.getByLabel('Nome do tópico 1', { exact: true }).fill('Alterado') // barra de salvar e aviso visíveis
    // os botões saem de "desabilitado" com uma transição de opacidade: medir contraste no meio dela dá falso positivo
    await expect.poll(() => page.getByRole('button', { name: 'Desfazer alterações' }).evaluate((el) => getComputedStyle(el).opacity)).toBe('1')
    await auditar(page, `revisão (${tema})`)

    await page.goto('/novo-estudo')
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema)
    await page.getByRole('button', { name: 'Enviar material' }).click() // mostra o erro de arquivo
    await auditar(page, `novo estudo com erro (${tema})`)
  })
}
