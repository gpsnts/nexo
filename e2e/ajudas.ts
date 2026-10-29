import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, type Page } from '@playwright/test'

export const SENHA = 'senha-forte-1'
export const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
/**
 * Caminho de um arquivo de teste em `e2e/fixtures`.
 * @param nome nome do arquivo.
 * @returns o caminho absoluto.
 */
export const fixture = (nome: string) => path.join(RAIZ, 'e2e', 'fixtures', nome)
/**
 * Gera um e-mail único para cada teste.
 * @returns um endereço novo.
 */
export const emailNovo = () => `e2e${Date.now()}${Math.floor(Math.random() * 1e6)}@exemplo.com`

/**
 * O provedor de e-mail `falso` escreve o e-mail no log do backend: o link do e-mail é lido de lá.
 * @param para destinatário do e-mail.
 * @param assuntoContem trecho do assunto que identifica o e-mail.
 * @returns o token do link, quando o e-mail aparecer (até 15 s).
 */
export async function tokenDoEmail(para: string, assuntoContem: string): Promise<string> {
  const padrao = new RegExp(`para ${para.replace(/[.+]/g, '\\$&')}: [^\\n]*${assuntoContem}[^\\n]*\\n((?:(?!provedor falso)[^\\n]*\\n)*?)[^\\n]*token=([\\w-]+)`, 'g')
  for (let i = 0; i < 30; i++) {
    const log = execFileSync('docker', ['compose', 'logs', '--no-color', 'backend'], { cwd: RAIZ, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 })
    const achados = [...log.matchAll(padrao)]
    if (achados.length) return achados[achados.length - 1][2]
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`e-mail "${assuntoContem}" para ${para} não apareceu no log do backend`)
}

/**
 * Cadastro, confirmação do e-mail pelo link e login. Termina na tela inicial.
 * @param page página do Playwright.
 * @param email e-mail da conta; por padrão, um novo.
 * @param nome nome da conta.
 * @returns o e-mail e o nome usados.
 */
export async function criarContaEEntrar(page: Page, email = emailNovo(), nome = 'Ana Teste') {
  await page.goto('/criar-conta')
  await page.getByLabel('Nome').fill(nome)
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Senha', { exact: true }).fill(SENHA)
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page.getByRole('heading', { name: 'Confira seu e-mail' })).toBeVisible()
  const token = await tokenDoEmail(email, 'Confirme')
  await page.goto(`/verificar-email?token=${token}`)
  await page.getByRole('button', { name: 'Ativar conta' }).click()
  await expect(page.getByRole('status')).toContainText('E-mail confirmado')
  await page.getByRole('button', { name: 'Ir para o login' }).click()
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Senha', { exact: true }).fill(SENHA)
  await page.getByRole('button', { name: 'Entrar', exact: true }).click()
  await expect(page.getByRole('heading', { name: `Olá, ${nome}` })).toBeVisible()
  return { email, nome }
}

/**
 * Escolhe o arquivo no formulário de envio e envia.
 * @param page página do Playwright.
 * @param arquivo caminho do arquivo a enviar.
 */
export async function enviarMaterial(page: Page, arquivo: string) {
  await page.goto('/novo-estudo')
  await page.getByLabel('Arquivo').setInputFiles(arquivo)
  await page.getByRole('button', { name: 'Enviar material' }).click()
}

/**
 * Reinicia o backend do compose e espera ele voltar saudável (derruba tarefas em segundo plano: simula uma queda no meio de uma geração).
 * @returns promessa resolvida quando o backend volta saudável.
 */
export async function reiniciarBackend() {
  execFileSync('docker', ['compose', 'restart', 'backend'], { cwd: RAIZ, stdio: 'pipe' })
  for (let i = 0; i < 60; i++) {
    const status = execFileSync('docker', ['compose', 'ps', 'backend', '--format', '{{.Status}}'], { cwd: RAIZ, encoding: 'utf8' })
    if (status.includes('(healthy)')) return
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error('o backend não voltou saudável em 60 s')
}

/**
 * Roda SQL no banco do compose como dono (ignora a RLS): prepara dados que a tela não cria sozinha.
 * @param consulta comando SQL.
 * @returns a saída do psql.
 */
export function sql(consulta: string) {
  try {
    return execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-U', 'nexo_dono', '-d', 'nexo', '-v', 'ON_ERROR_STOP=1', '-c', consulta], { cwd: RAIZ, stdio: 'pipe' })
  } catch (e) {
    throw new Error(`SQL falhou: ${(e as { stderr?: Buffer }).stderr?.toString() ?? e}`)
  }
}
