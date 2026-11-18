import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'

type Rota = (corpo: any) => [number, unknown?]
const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'
const ADM = '33333333-3333-3333-3333-333333333333'

function servidor(rotas: Record<string, Rota | [number, unknown?]>) {
  const chamadas: { chave: string; corpo: any }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '').split('?')[0]}`
    const corpo = typeof init.body === 'string' ? JSON.parse(init.body) : init.body
    chamadas.push({ chave, corpo })
    const r = rotas[chave]
    const [status, dados] = typeof r === 'function' ? r(corpo) : (r ?? [404, { mensagem: 'sem rota' }])
    return { ok: status < 400, status, json: async () => dados }
  }))
  return chamadas
}
const uso = { creditosUsados: 0, creditosLimite: 200, percentual: 0, renovaEm: '2026-10-19T03:00:00Z', operacoes: 0, limiteOperacoes: 30, janelaOperacoesMinutos: 60, creditosPorQuestaoNova: 6 }
const conta = (id: string, nome: string, status: string, extra: object = {}) => ({
  id, nome, email: `${nome.toLowerCase()}@x.com`, status, admin: false, emailVerificado: true, criadoEm: '2026-11-01T12:00:00Z', ultimoAcessoEm: null,
  cotaPersonalizada: null, creditosLimite: 200, creditosUsados: 50, percentual: 25, ...extra,
})
const lista = [conta(A, 'Ana', 'suspenso'), conta(B, 'Bia', 'ativo'), conta(ADM, 'Chefe', 'ativo', { admin: true })]
const comoAdmin = { 'GET /uso': [200, uso] as [number, unknown], 'POST /auth/refresh': [200, { token: 't', nome: 'Chefe', admin: true }] as [number, unknown] }

beforeEach(() => { localStorage.setItem('nexo-sessao', '1'); window.history.replaceState(null, '', '/admin') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })
const clicar = (nome: string | RegExp, dentro: HTMLElement | undefined = undefined) => fireEvent.click((dentro ? within(dentro) : screen).getByRole('button', { name: nome }))

test('administrador vê o item do menu e a lista com as ações certas por estado', async () => {
  servidor({ ...comoAdmin, 'GET /admin/usuarios': [200, lista] })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Administração' })).toBeTruthy()
  expect(screen.getAllByRole('button', { name: 'Admin' }).length).toBeGreaterThan(0)
  const ana = (await screen.findByText('Ana')).closest('li')!
  expect(within(ana).getByText('Suspensa')).toBeTruthy()
  expect(within(ana).getByRole('button', { name: 'Reativar' })).toBeTruthy()
  expect(within(ana).queryByRole('button', { name: 'Suspender' })).toBeNull()
  const bia = screen.getByText('Bia').closest('li')!
  expect(within(bia).getByRole('button', { name: 'Suspender' })).toBeTruthy()
  const chefe = screen.getByText('Chefe').closest('li')!
  expect(within(chefe).getByText('Administrador')).toBeTruthy()
  expect(within(chefe).queryByRole('button')).toBeNull() // conta de administrador não tem ações
  expect(screen.getAllByText(/25% da cota semanal \(50 de 200 créditos\)/, { selector: 'span' }).length).toBe(3)
})

test('reativar e suspender chamam a rota da conta e recarregam a lista', async () => {
  const chamadas = servidor({ ...comoAdmin, 'GET /admin/usuarios': [200, lista], [`POST /admin/usuarios/${A}/reativar`]: [204], [`POST /admin/usuarios/${B}/suspender`]: [204] })
  render(<App />)
  clicar('Reativar', (await screen.findByText('Ana')).closest('li')!)
  await waitFor(() => expect(chamadas.some((c) => c.chave === `POST /admin/usuarios/${A}/reativar`)).toBe(true))
  await waitFor(() => expect(chamadas.filter((c) => c.chave === 'GET /admin/usuarios').length).toBe(2))
  clicar('Suspender', screen.getByText('Bia').closest('li')!)
  await waitFor(() => expect(chamadas.some((c) => c.chave === `POST /admin/usuarios/${B}/suspender`)).toBe(true))
})

test('a cota é enviada em créditos, e "usar a cota padrão" envia nulo', async () => {
  const chamadas = servidor({ ...comoAdmin, 'GET /admin/usuarios': [200, lista], [`PUT /admin/usuarios/${B}/cota`]: [204] })
  render(<App />)
  const bia = (await screen.findByText('Bia')).closest('li')!
  clicar('Cota', bia)
  fireEvent.change(within(bia).getByLabelText(/Créditos por semana/), { target: { value: '0' } })
  clicar('Salvar cota', bia)
  await waitFor(() => expect(chamadas.find((c) => c.chave === `PUT /admin/usuarios/${B}/cota`)?.corpo).toEqual({ creditos: 0 }))
  clicar('Cota', screen.getByText('Bia').closest('li')!)
  clicar('Usar a cota padrão', screen.getByText('Bia').closest('li')!)
  await waitFor(() => expect(chamadas.filter((c) => c.chave === `PUT /admin/usuarios/${B}/cota`).at(-1)?.corpo).toEqual({ creditos: null }))
})

test('excluir só habilita depois de digitar EXCLUIR e mostra o erro do servidor', async () => {
  const chamadas = servidor({ ...comoAdmin, 'GET /admin/usuarios': [200, lista], [`DELETE /admin/usuarios/${B}`]: [409, { mensagem: 'Contas de administrador não podem ser alteradas pelo painel.' }] })
  render(<App />)
  const bia = (await screen.findByText('Bia')).closest('li')!
  clicar('Excluir', bia)
  const botao = within(bia).getByRole('button', { name: 'Excluir conta' }) as HTMLButtonElement
  expect(botao.disabled).toBe(true)
  fireEvent.change(within(bia).getByLabelText(/Digite EXCLUIR/), { target: { value: 'EXCLUIR' } })
  expect(botao.disabled).toBe(false)
  fireEvent.click(botao)
  expect(await screen.findByText(/não podem ser alteradas/)).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `DELETE /admin/usuarios/${B}`)?.corpo).toEqual({ confirmacao: 'EXCLUIR' })
})

test('quem não é administrador não vê o item do menu e a rota mostra "não encontrada"', async () => {
  const chamadas = servidor({ 'GET /uso': [200, uso], 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] })
  render(<App />)
  expect(await screen.findByText(/não encontrad/i)).toBeTruthy()
  expect(chamadas.some((c) => c.chave === 'GET /admin/usuarios')).toBe(false)
  expect(screen.queryByRole('button', { name: 'Admin' })).toBeNull()
})

test('conta suspensa com a sessão aberta volta ao login com o aviso do servidor', async () => {
  window.history.replaceState(null, '', '/')
  servidor({ 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /uso': [403, { mensagem: 'Esta conta está suspensa. Fale com o administrador.', codigo: 'conta_suspensa' }] })
  render(<App />)
  expect(await screen.findByText(/Esta conta está suspensa/)).toBeTruthy()
  await waitFor(() => expect(window.location.pathname).toBe('/entrar'))
})

test('ao recarregar a página com a conta suspensa, a tela de entrada explica o motivo', async () => {
  window.history.replaceState(null, '', '/')
  servidor({ 'POST /auth/refresh': [403, { mensagem: 'Esta conta está suspensa. Fale com o administrador.', codigo: 'conta_suspensa' }] })
  render(<App />)
  expect(await screen.findByText(/Esta conta está suspensa/)).toBeTruthy()
  await waitFor(() => expect(window.location.pathname).toBe('/entrar'))
})
