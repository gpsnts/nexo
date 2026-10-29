import { expect, test } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture } from '../ajudas'

// Provedores falsos: a "IA" sugere "Estudo de demonstração: <primeiro tópico>".
test('pastas: criar, estudar dentro da pasta, renomear o estudo (com sugestão da IA), mover e excluir a pasta @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.getByRole('button', { name: '+ Nova pasta' }).click()
  await page.getByLabel('Nome da nova pasta').fill('História')
  await page.getByRole('button', { name: 'Criar pasta' }).click()
  await expect(page.getByRole('button', { name: 'História', exact: true })).toHaveAttribute('aria-pressed', 'true')

  // o estudo nasce na pasta escolhida
  await page.goto('/novo-estudo')
  await page.getByLabel('Pasta (opcional)').first().selectOption({ label: 'História' })
  await page.getByLabel('Arquivo').setInputFiles(fixture('apostila-50-paginas.pdf'))
  await page.getByRole('button', { name: 'Enviar material' }).click()
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await expect(page.getByLabel('Pasta', { exact: true })).toHaveValue(/[0-9a-f-]{36}/)

  // nome do estudo: à mão e sugerido pela IA
  await page.getByRole('button', { name: 'Renomear estudo' }).click()
  await page.getByRole('button', { name: 'Sugerir nome com IA' }).click()
  await expect(page.getByLabel('Nome do estudo')).toHaveValue(/Estudo de demonstração/)
  await page.getByLabel('Nome do estudo').fill('Segunda Guerra Mundial')
  await page.getByRole('button', { name: 'Salvar nome' }).click()
  await expect(page.getByRole('heading', { name: 'Segunda Guerra Mundial' })).toBeVisible()
  await expect(page.getByText('Arquivo: apostila-50-paginas.pdf')).toBeVisible()

  // na tela inicial: o nome do estudo e o filtro por pasta
  await page.getByRole('button', { name: 'Início', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Segunda Guerra Mundial' })).toBeVisible()
  await page.getByRole('button', { name: 'Geral', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Segunda Guerra Mundial' })).toHaveCount(0)
  await page.getByRole('button', { name: 'História', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Segunda Guerra Mundial' })).toBeVisible()

  // excluir a pasta não apaga o estudo: ele volta para Geral
  await page.getByRole('button', { name: /Excluir a pasta História/ }).click()
  await page.getByRole('button', { name: 'Excluir pasta', exact: true }).click()
  await page.getByRole('button', { name: 'Geral', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Segunda Guerra Mundial' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
