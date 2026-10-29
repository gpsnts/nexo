import { expect, test } from '@playwright/test'
import { criarContaEEntrar, SENHA, tokenDoEmail } from '../ajudas'

test('cadastro, confirmação por link, login, recarregar mantém a sessão e sair @celular', async ({ page }) => {
  const { nome } = await criarContaEEntrar(page)
  await page.reload()
  await expect(page.getByRole('heading', { name: `Olá, ${nome}` })).toBeVisible() // o cookie de refresh devolve a sessão
  await page.getByRole('button', { name: 'Sair' }).click()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  await page.goto('/perfil')
  await expect(page).toHaveURL(/\/entrar/) // sem sessão, tela interna leva ao login
})

test('recuperar senha por link e entrar com a nova senha', async ({ page }) => {
  const { email } = await criarContaEEntrar(page)
  await page.getByRole('button', { name: 'Sair' }).click()
  await page.goto('/recuperar-senha')
  await page.getByLabel('E-mail').fill(email)
  await page.getByRole('button', { name: 'Enviar link' }).click()
  const token = await tokenDoEmail(email, 'senha')
  await page.goto(`/redefinir-senha?token=${token}`)
  await page.getByLabel('Nova senha').fill('outra-senha-9')
  await page.getByRole('button', { name: 'Salvar nova senha' }).click()
  await expect(page.getByRole('status')).toContainText('Senha alterada')
  await page.getByRole('button', { name: 'Ir para o login' }).click()
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Senha', { exact: true }).fill('outra-senha-9')
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  await expect(page.getByRole('heading', { name: /^Olá, / })).toBeVisible()
})

test('excluir a conta apaga tudo e o login deixa de funcionar', async ({ page }) => {
  const { email } = await criarContaEEntrar(page)
  await page.getByRole('button', { name: 'Perfil' }).click()
  await page.getByLabel('Senha atual').last().fill(SENHA)
  await page.getByLabel('Digite EXCLUIR para confirmar').fill('EXCLUIR')
  await page.getByRole('button', { name: 'Excluir minha conta' }).click()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Senha', { exact: true }).fill(SENHA)
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
})

test('login recusado: o foco volta ao botão e o alerta é anunciado (navegador de verdade) @celular', async ({ page }) => {
  await page.goto('/entrar')
  await page.getByLabel('E-mail').fill('ninguem@exemplo.com')
  await page.getByLabel('Senha', { exact: true }).fill('senha-errada-1')
  const botao = page.getByRole('button', { name: 'Entrar', exact: true })
  await botao.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('alert')).toContainText('E-mail ou senha incorretos')
  await expect(botao).toBeFocused() // sem isto o foco ia para o body e o teclado recomeçava do topo
})

test('perfil: o índice leva a cada seção @celular', async ({ page }) => {
  await criarContaEEntrar(page)
  await page.goto('/perfil')
  const indice = page.getByRole('navigation', { name: 'Seções do perfil' })
  await expect(indice.getByRole('link')).toHaveCount(5)
  await indice.getByRole('link', { name: 'Excluir conta' }).click()
  await expect(page.getByRole('heading', { name: 'Excluir conta' })).toBeInViewport()
})
