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
