import { expect, test } from '@playwright/test'
import { criarContaEEntrar, sql } from '../ajudas'

// Provedores falsos: não sabemos qual alternativa é a certa, então o teste escolhe sempre a A e confere que o gabarito aparece depois.
test('gerar, responder (gabarito só depois), revisar no dia agendado e ver o painel @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Termologia')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)
  await page.getByLabel('Quantas questões gerar').fill('3')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(/3 questões gravadas/)).toBeVisible({ timeout: 45_000 })

  // uma por vez, sem gabarito antes de confirmar
  await page.getByRole('button', { name: 'Começar questionário' }).click()
  for (let n = 1; n <= 3; n++) {
    await expect(page.getByText(`${n} / 3`)).toBeVisible()
    await expect(page.getByText('(correta)')).toHaveCount(0)
    await page.locator('.opcao').first().click()
    await expect(page.getByText(/volta para revisão em/)).toHaveCount(0) // só selecionar não grava
    await page.getByRole('button', { name: 'Confirmar resposta' }).click()
    await expect(page.getByText(/volta para revisão em \d\d\/\d\d\/\d{4}/)).toBeVisible()
    await expect(page.getByText('(correta)')).toHaveCount(1)
    await page.getByRole('button', { name: n < 3 ? 'Próxima questão' : 'Ver resultado' }).click()
  }
  await expect(page.getByText(/Você acertou \d de 3\./)).toBeVisible()
  await page.getByRole('button', { name: 'Voltar ao tópico' }).click()
  await expect(page.getByText(/Última resposta:/)).toHaveCount(3)

  // nada vence no mesmo dia; adianta o agendamento pelo banco
  await page.getByRole('button', { name: 'Revisar', exact: true }).click()
  await expect(page.getByText('Nenhuma questão para revisar agora.')).toBeVisible()
  sql("UPDATE progresso_questao SET ultima_revisao = current_date - 3, proxima_revisao = current_date - 1")
  await page.reload()
  await expect(page.getByText(/3 questões nesta sessão/)).toBeVisible()
  await page.getByRole('button', { name: 'Começar revisão' }).click()
  for (let n = 1; n <= 3; n++) {
    await page.locator('.opcao').first().click()
    await page.getByRole('button', { name: 'Confirmar resposta' }).click()
    await page.getByRole('button', { name: n < 3 ? 'Próxima questão' : 'Ver resultado' }).click()
  }
  await expect(page.getByText(/Você acertou \d de 3\./)).toBeVisible()

  // painel de desempenho
  await page.getByRole('button', { name: 'Ver desempenho' }).click()
  await expect(page.getByRole('heading', { name: /Geral: \d+% de acertos/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: /\(\d de 6\)/ })).toBeVisible() // 3 respostas do estudo + 3 da revisão
  await expect(page.getByRole('img', { name: /Evolução semanal/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  // sempre dá para gerar questões novas do mesmo conteúdo, mesmo sem nada vencido
  await page.goto('/revisar')
  await expect(page.getByText('Nenhuma questão para revisar agora.')).toBeVisible()
  await page.getByRole('button', { name: 'Gerar novas questões' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}$/)
  await expect(page.getByLabel('Quantas questões gerar')).toBeFocused()
})

test('dica opcional no questionário e chat com histórico salvo no tópico', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/novo-estudo')
  await page.getByLabel('Tópico', { exact: true }).fill('Optica')
  await page.getByRole('button', { name: 'Registrar tópico' }).click()
  await expect(page).toHaveURL(/\/topicos\/[0-9a-f-]{36}/)

  // chat: pergunta, resposta, e o histórico volta depois de recarregar
  await page.getByLabel('Sua pergunta').fill('O que é refração?')
  await page.getByLabel('Sua pergunta').press('Enter')
  await expect(page.getByRole('log', { name: 'Conversa com o tutor' }).getByText(/Resposta de demonstração/)).toBeVisible()
  await page.reload()
  await expect(page.getByText('O que é refração?', { exact: true })).toBeVisible()
  await expect(page.getByText(/Resposta de demonstração/)).toBeVisible()

  // dica: recolhida por padrão, gera ao clicar
  await expect(page.getByLabel('Média')).toBeChecked()
  await page.getByLabel('Difícil').check()
  await page.getByLabel('Quantas questões gerar').fill('2')
  await page.getByRole('button', { name: 'Gerar questões' }).click()
  await expect(page.getByText(/2 questões gravadas/)).toBeVisible({ timeout: 45_000 })
  await page.getByRole('button', { name: 'Começar questionário' }).click()
  await expect(page.locator('.quiz .nivel')).toHaveText('Difícil')
  await expect(page.getByText(/Dica de demonstração/)).toHaveCount(0)
  await page.getByRole('button', { name: 'Ver dica' }).click()
  await expect(page.getByText(/Dica de demonstração/)).toBeVisible()
  await page.getByRole('button', { name: 'Esconder dica' }).click()
  await expect(page.getByText(/Dica de demonstração/)).toHaveCount(0)
  await page.locator('.opcao').first().click()
  await page.getByRole('button', { name: 'Confirmar resposta' }).click()
  await expect(page.getByRole('button', { name: 'Ver dica' })).toHaveCount(0) // some depois de responder

  // limpar a conversa
  await page.getByRole('button', { name: 'Sair do questionário' }).click()
  await page.getByRole('button', { name: 'Limpar conversa' }).click()
  await page.getByRole('button', { name: 'Apagar conversa' }).click()
  await expect(page.getByText(/Tire dúvidas sobre o conteúdo/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
