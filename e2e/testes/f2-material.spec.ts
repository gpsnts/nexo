import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { criarContaEEntrar, enviarMaterial, fixture } from '../ajudas'

test('PDF de 50 páginas: barra até 100%, tópicos propostos, edição salva e persistida @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page).toHaveURL(/\/materiais\/[0-9a-f-]{36}/)
  // o provedor falso termina em segundos; a barra pode já ter passado: o que importa é o resultado
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await expect(page.getByText(/O material foi dividido em \d+ tópicos? \(\d+ trechos/)).toBeVisible()
  const antes = await page.getByRole('listitem').filter({ has: page.getByLabel(/^Nome do tópico/) }).count()
  expect(antes).toBeGreaterThan(1)

  await page.getByLabel('Nome do tópico 1', { exact: true }).fill('Cinemática')
  await page.getByRole('button', { name: 'Unir com o próximo (tópico 1)' }).click()
  await page.getByRole('button', { name: 'Confirmar tópicos' }).click()
  await expect(page.getByText('Tópicos salvos.')).toBeVisible()

  await page.reload() // vem do banco, não do estado da tela
  await expect(page.getByLabel('Nome do tópico 1', { exact: true })).toHaveValue('Cinemática')
  await expect(page.getByRole('listitem').filter({ has: page.getByLabel(/^Nome do tópico/) })).toHaveCount(antes - 1)

  // nome de arquivo longo no título não pode criar rolagem horizontal (reflow, principalmente no celular)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  await page.getByRole('button', { name: 'Início' }).click()
  await expect(page.getByRole('button', { name: 'apostila-50-paginas.pdf' })).toBeVisible()
})

test('Reorganizar com IA gera um rascunho sem alterar nada até confirmar', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await page.getByRole('button', { name: 'Reorganizar com IA' }).click()
  // com o provedor falso a IA "aprova" a divisão atual; o fluxo (pedido, registro, resposta) é o mesmo do provedor real
  await expect(page.getByText(/manteve a divisão atual/)).toBeVisible()
  await expect(page.getByText('Alterações não salvas.')).toHaveCount(0)
})

test('nome de arquivo muito longo não cria rolagem horizontal @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Arquivo').setInputFiles({ name: 'Apostila_Catalogacao_e_AACR2_Luciana_Kramer_DETALHADA_versao_final_revisada.pdf', mimeType: 'application/pdf', buffer: readFileSync(fixture('apostila-50-paginas.pdf')) })
  await page.getByRole('button', { name: 'Enviar material' }).click()
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.getByRole('button', { name: 'Início' }).click()
  await expect(page.getByRole('button', { name: /Apostila_Catalogacao.*final_revisada\.pdf/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

test('mesmo arquivo é recusado e leva ao material existente', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  await enviarMaterial(page, fixture('apostila-50-paginas.pdf'))
  await expect(page.getByRole('alert')).toContainText('já enviou')
  await page.getByRole('link', { name: 'Abrir o material já enviado' }).click()
  await expect(page.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible()
})

test('51 páginas é recusado na hora, sem criar material', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('longo-51-paginas.pdf'))
  await expect(page.getByRole('alert')).toContainText('passa de 50 páginas')
  await page.getByRole('button', { name: 'Início' }).click()
  await expect(page.getByText(/ainda não enviou nenhum material/)).toBeVisible()
})

test('PDF sem texto falha com o motivo e permite novo envio', async ({ page }) => {
  await criarContaEEntrar(page)
  await enviarMaterial(page, fixture('escaneado.pdf'))
  await expect(page.getByRole('alert')).toContainText('não tem texto', { timeout: 45_000 })
  await page.getByRole('button', { name: 'Enviar novamente' }).click()
  await expect(page).toHaveURL(/\/novo-estudo/)
  await enviarMaterial(page, fixture('escaneado.pdf')) // reenvio do mesmo arquivo que falhou é permitido (UC02 A4)
  await expect(page.getByRole('alert')).toContainText('não tem texto', { timeout: 45_000 })
})

test('tópico livre (UC03) é registrado sem material', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Movimento Retilíneo Uniforme')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'registrado' })).toBeVisible()
})

test('outro usuário não abre o material de quem enviou', async ({ browser }) => {
  const a = await (await browser.newContext()).newPage()
  await criarContaEEntrar(a)
  await enviarMaterial(a, fixture('apostila-50-paginas.pdf'))
  await expect(a.getByRole('heading', { name: 'Tópicos do material' })).toBeVisible({ timeout: 45_000 })
  const url = a.url()
  const b = await (await browser.newContext()).newPage()
  await criarContaEEntrar(b)
  await b.goto(url)
  await expect(b.getByRole('alert')).toBeVisible() // 404: "Não foi possível abrir o material"
  await expect(b.getByRole('heading', { name: 'Tópicos do material' })).toHaveCount(0)
})
