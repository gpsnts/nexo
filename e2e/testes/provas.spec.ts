import { expect, test } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture } from '../ajudas'

/**
 * Registra um tópico livre e gera questões com o provedor falso.
 * @param page página do Playwright.
 * @param nome nome do tópico.
 * @param quantas questões a gerar.
 */
async function topicoComQuestoes(page: import('@playwright/test').Page, nome: string, quantas: number) {
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill(nome)
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await page.getByLabel('Quantas questões gerar').fill(String(quantas))
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(new RegExp(`${quantas} questões gravadas`))).toBeVisible({ timeout: 45_000 })
}

// Provedores falsos: não sabemos qual alternativa é a certa; o teste responde sempre a A.
test('provas: ordenar tópicos, montar com tópico-limite e tempo, responder, ver o resultado e refazer @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await topicoComQuestoes(page, 'Termologia', 4)
  await topicoComQuestoes(page, 'Óptica', 3)

  // a ordem dos tópicos da pasta decide o que entra "até este tópico"
  await page.goto('/')
  await page.getByRole('button', { name: 'Geral', exact: true }).click()
  await page.getByRole('button', { name: /Organizar os tópicos da pasta Geral/ }).click()
  await expect(page.getByRole('heading', { name: 'Tópicos de Geral' })).toBeVisible()
  const nomes = () => page.locator('.lista-topicos .nome').allTextContents()
  await expect(page.locator('.lista-topicos .nome')).toHaveCount(2)
  expect(await nomes()).toEqual(['1. Termologia', '2. Óptica'])
  await page.getByRole('button', { name: 'Descer Termologia' }).click()
  await expect(page.getByText('"Termologia" agora é o tópico 2.')).toBeVisible()
  await page.reload()
  await expect(page.locator('.lista-topicos .nome')).toHaveCount(2)
  expect(await nomes()).toEqual(['1. Óptica', '2. Termologia'])

  // prova só até o primeiro tópico (Óptica: 3 questões), com limite de tempo
  await page.getByRole('button', { name: 'Provas', exact: true }).click()
  await page.getByRole('button', { name: 'Montar prova' }).click()
  await page.getByRole('checkbox', { name: 'Geral', exact: true }).check()
  await page.getByLabel('Já estudei até o tópico').selectOption({ label: '1. Óptica (3)' })
  await page.getByLabel('Quantidade de questões').fill('5')
  await page.getByRole('button', { name: 'Gerar prova' }).click()
  await expect(page.getByRole('alert')).toContainText('Há só 3 questões')
  await page.getByRole('button', { name: 'Reduzir para 3 questões' }).click()
  await page.getByRole('checkbox', { name: 'Limitar o tempo' }).check()
  await page.getByLabel('Minutos para fazer a prova').fill('10')
  await page.getByRole('button', { name: 'Gerar prova' }).click()

  await expect(page).toHaveURL(/\/provas\/[0-9a-f-]{36}/)
  await expect(page.getByText(/até o tópico "Óptica"/)).toBeVisible()
  await expect(page.getByText(/limite de 10 minutos/)).toBeVisible()
  await page.getByRole('button', { name: 'Começar prova' }).click()
  await expect(page.getByRole('timer')).toHaveText(/^(09|10):\d\d$/)
  for (let n = 1; n <= 3; n++) {
    await expect(page.getByText('(correta)')).toHaveCount(0)
    await page.locator('.opcao').first().click()
    await page.getByRole('button', { name: 'Confirmar resposta' }).click()
    await expect(page.getByText('(correta)')).toHaveCount(1)
    await page.getByRole('button', { name: n < 3 ? 'Próxima questão' : 'Ver resultado' }).click()
  }
  await expect(page.getByRole('heading', { name: 'Revisão das questões' })).toBeVisible()
  await expect(page.getByText(/Você acertou \d de 3\./)).toBeVisible()
  await expect(page.locator('.revisao-prova li')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  // refazer cria outra prova, não iniciada
  await page.getByRole('button', { name: 'Refazer prova' }).click()
  await expect(page.getByRole('heading', { name: 'Pronta para começar' })).toBeVisible()
  await page.getByRole('button', { name: 'Todas as provas', exact: false }).click()
  await expect(page.getByRole('list', { name: 'Provas' }).getByRole('button')).toHaveCount(2)
  await expect(page.getByText('Concluída')).toBeVisible()
  await expect(page.getByText('Não iniciada')).toBeVisible()

  // excluir uma prova: as respostas já dadas continuam no painel
  await page.getByRole('button', { name: /Começar: prova de/ }).click()
  await page.getByRole('button', { name: 'Excluir esta prova' }).click()
  await page.getByRole('button', { name: 'Excluir definitivamente' }).click()
  await expect(page).toHaveURL(/\/provas$/)
  await expect(page.getByRole('list', { name: 'Provas' }).getByRole('button')).toHaveCount(1)
  await page.goto('/desempenho')
  await expect(page.getByText(/Óptica/).first()).toBeVisible()
})

test('vários materiais: fonte rejeitada se já é de outro tópico, e excluir o material mantém o tópico @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  const urlMaterial = page.url()

  // um tópico livre tenta usar trechos que o material já deu a outro tópico
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Reforço')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await page.getByRole('button', { name: 'Acrescentar material' }).click()
  await page.getByLabel('Material').selectOption({ index: 1 })
  await page.getByLabel('Primeiro trecho').fill('1')
  await page.getByLabel('Último trecho').fill('1')
  await page.getByRole('button', { name: 'Acrescentar', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('já pertence ao tópico')

  // excluir o material: confirmação, volta ao início e o estudo some (os tópicos dele ficam como tópicos livres)
  await page.goto(urlMaterial)
  await page.getByRole('button', { name: /Excluir o material/ }).click()
  await expect(page.getByRole('alert').first()).toContainText('não pode ser desfeito')
  await page.getByRole('button', { name: 'Excluir definitivamente' }).click()
  await expect(page.getByRole('heading', { name: /Olá,/ })).toBeVisible()
  await page.goto(urlMaterial)
  await expect(page.getByText('Este material não existe ou não é da sua conta.')).toBeVisible()
  await page.goto('/')
  await expect(page.getByText('Tópico livre').first()).toBeVisible()
})

// Sem @celular de propósito: em tela de toque a alça some e ficam os botões Subir e Descer.
test('arrastar a alça com o mouse reordena os tópicos da pasta e a ordem persiste', async ({ page }) => {
  await criarContaEEntrar(page)
  for (const nome of ['Primeiro', 'Segundo', 'Terceiro']) {
    await page.goto('/novo-estudo')
    await page.getByLabel('Tópico', { exact: true }).fill(nome)
    await page.getByRole('button', { name: 'Registrar tópico' }).click()
    await expect(page).toHaveURL(/\/topicos\//)
  }
  await page.goto('/')
  await page.getByRole('button', { name: 'Geral', exact: true }).click()
  await page.getByRole('button', { name: /Organizar os tópicos da pasta Geral/ }).click()
  const nomes = page.locator('.lista-topicos .nome')
  await expect(nomes).toHaveText(['1. Primeiro', '2. Segundo', '3. Terceiro'])
  const alcas = page.locator('.lista-topicos .alca')
  await alcas.nth(2).dragTo(page.locator('.lista-topicos li').nth(0))   // arrasta o terceiro para o lugar do primeiro
  await expect(nomes).toHaveText(['1. Terceiro', '2. Primeiro', '3. Segundo'])
  await page.reload()
  await expect(nomes).toHaveText(['1. Terceiro', '2. Primeiro', '3. Segundo'])
})
