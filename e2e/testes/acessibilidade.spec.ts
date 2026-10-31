import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture } from '../ajudas'

// A CSP do app proíbe script injetado; o axe precisa dela desligada, só aqui no teste.
test.use({ bypassCSP: true })

const auditar = async (page: Page, onde: string) => {
  // transições de cor (ao trocar o tema ou habilitar um botão) fariam o axe medir contraste no meio da animação: falso positivo
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; animation: none !important; }' })
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

for (const tema of ['light', 'dark'] as const) {
  test(`provas, tópicos da pasta, histórico, fontes e exclusão sem violações de acessibilidade no tema ${tema} @celular`, async ({ page }) => {
    const erros: string[] = []
    page.on('console', (m) => { if (m.type() === 'error') erros.push(m.text()) })
    page.on('pageerror', (e) => erros.push(String(e)))
    /**
     * Aplica o tema e audita a tela.
     * @param onde nome da tela, para a mensagem.
     */
    const ver = async (onde: string) => { await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), tema); await auditar(page, `${onde} (${tema})`) }
    await criarContaEEntrar(page)
    for (const [nome, n] of [['Termologia', 3], ['Óptica', 2]] as const) {
      await page.goto('/novo-estudo')
      await page.getByLabel('Tópico', { exact: true }).fill(nome)
      await page.getByRole('button', { name: 'Registrar tópico' }).click()
      await expect(page).toHaveURL(/\/topicos\//)
      await page.getByLabel('Quantas questões gerar').fill(String(n))
      await page.getByRole('button', { name: 'Gerar questões' }).click()
      await expect(page.getByText(new RegExp(`${n} questões gravadas`))).toBeVisible({ timeout: 45_000 })
    }
    // um tópico sem conteúdo: aceita fontes e é candidato a receber trechos de fora
    await page.goto('/novo-estudo')
    await page.getByLabel('Tópico', { exact: true }).fill('Reforço')
    await page.getByRole('button', { name: 'Registrar tópico' }).click()
    await expect(page.getByRole('heading', { name: 'Fontes' })).toBeVisible()
    await page.getByRole('button', { name: 'Acrescentar material' }).click()
    await ver('tópico com o formulário de fontes')

    await page.goto('/provas')
    await expect(page.getByRole('heading', { name: 'Nenhuma prova ainda' })).toBeVisible()
    await ver('provas vazias')
    await page.getByRole('button', { name: 'Montar prova' }).click()
    await page.getByRole('checkbox', { name: 'Geral', exact: true }).check()
    await page.getByRole('checkbox', { name: 'Limitar o tempo' }).check()
    await expect(page.getByText(/questões disponíveis/)).toBeVisible()
    await ver('montar prova')
    await page.getByLabel('Quantidade de questões').fill('9')
    await page.getByRole('button', { name: 'Gerar prova' }).click()
    await expect(page.getByRole('button', { name: /Reduzir para/ })).toBeVisible()
    await ver('montar prova com questões insuficientes')
    await page.getByRole('button', { name: /Reduzir para/ }).click()
    await page.getByRole('button', { name: 'Gerar prova' }).click()
    await expect(page.getByRole('heading', { name: 'Pronta para começar' })).toBeVisible()
    await ver('prova não iniciada')
    await page.getByRole('button', { name: 'Começar prova' }).click()
    await expect(page.getByRole('timer')).toBeVisible()
    await ver('prova em andamento')
    await page.locator('.opcao').first().click()
    await page.getByRole('button', { name: 'Confirmar resposta' }).click()
    await ver('prova com a resposta conferida')
    for (let n = 1; n <= 5; n++) {
      await page.getByRole('button', { name: n < 5 ? 'Próxima questão' : 'Ver resultado' }).click()
      if (n < 5) { await page.locator('.opcao').first().click(); await page.getByRole('button', { name: 'Confirmar resposta' }).click() }
    }
    await expect(page.getByRole('heading', { name: 'Revisão das questões' })).toBeVisible()
    await ver('resultado da prova')
    await page.getByRole('button', { name: 'Excluir esta prova' }).click()
    await ver('confirmar exclusão da prova')
    await page.goto('/provas')
    await expect(page.getByRole('list', { name: 'Provas' })).toBeVisible()
    await ver('lista de provas')

    await page.goto('/')
    await page.getByRole('button', { name: 'Geral', exact: true }).click()
    await page.getByRole('button', { name: /Organizar os tópicos da pasta Geral/ }).click()
    await expect(page.locator('.lista-topicos .nome').first()).toBeVisible()
    await ver('tópicos da pasta')
    await page.goto('/historico')
    await expect(page.getByRole('list', { name: 'Tópicos' })).toBeVisible()
    await ver('histórico')

    // material com trechos de fora, candidato a receber e confirmação de exclusão
    await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
    await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
    await page.getByRole('button', { name: /Tirar o tópico \d+ do estudo/ }).last().click()
    await page.getByRole('button', { name: 'Confirmar tópicos' }).click()
    await expect(page.getByLabel(/Ou use estes trechos em um tópico que já existe/)).toBeVisible()
    await ver('trechos de fora com tópico candidato')
    await page.getByRole('button', { name: /Excluir o material/ }).click()
    await ver('confirmar exclusão do material')

    // o 409 de "questões insuficientes" é provocado de propósito acima; o navegador o registra como falha de recurso
    const inesperados = erros.filter((e) => !/status of 409/.test(e))
    expect(inesperados, `erros de console:\n${inesperados.join('\n')}`).toEqual([])
  })
}
