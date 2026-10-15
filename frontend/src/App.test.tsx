import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

type Rota = (corpo: any, headers: Record<string, string>) => [number, unknown?]

// fetch falso por "METODO /caminho"; rota ausente = 404. Cada chamada fica em `chamadas`.
function servidor(rotas: Record<string, Rota | [number, unknown?]>) {
  const chamadas: { chave: string; corpo: any; headers: Record<string, string> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '')}`
    const corpo = init.body ? JSON.parse(init.body as string) : undefined
    const headers = (init.headers ?? {}) as Record<string, string>
    chamadas.push({ chave, corpo, headers })
    const r = rotas[chave]
    const [status, dados] = typeof r === 'function' ? r(corpo, headers) : (r ?? [404, { mensagem: 'sem rota' }])
    return { ok: status < 400, status, json: async () => dados }
  }))
  return chamadas
}

const semSessao: [number, unknown] = [401, { mensagem: 'Sessão expirada. Entre novamente.' }]
const digitar = (rotulo: string, valor: string) => fireEvent.change(screen.getByLabelText(rotulo), { target: { value: valor } })
const clicar = (nome: string) => fireEvent.click(screen.getByRole('button', { name: nome }))
const geral: [number, unknown] = [200, [{ id: '1', nome: 'Geral', padrao: true }]]

afterEach(() => {
  cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/')
  localStorage.clear(); document.documentElement.removeAttribute('data-theme')
})
const jaEntrouAntes = () => localStorage.setItem('nexo-sessao', '1')

test('quem nunca entrou vê o login sem sondar o refresh', async () => {
  const chamadas = servidor({ 'POST /auth/refresh': semSessao })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect(chamadas.length).toBe(0)
})

test('com indicador de sessão e refresh recusado, mostra o login e limpa o indicador', async () => {
  jaEntrouAntes()
  servidor({ 'POST /auth/refresh': semSessao })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect(localStorage.getItem('nexo-sessao')).toBeNull()
})

test('login com erro mostra a mensagem do servidor (UC01 A1)', async () => {
  servidor({ 'POST /auth/refresh': semSessao, 'POST /auth/login': [401, { mensagem: 'E-mail ou senha incorretos, ou acesso temporariamente bloqueado.' }] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'a@x.com'); digitar('Senha', 'qualquer-senha'); clicar('Entrar')
  expect((await screen.findByRole('alert')).textContent).toContain('temporariamente bloqueado')
})

test('cadastro não inicia sessão: pede para confirmar o e-mail e permite reenviar (UC01 A2)', async () => {
  const chamadas = servidor({
    'POST /auth/cadastro': [201, { mensagem: 'Conta criada.' }],
    'POST /auth/reenviar-verificacao': [202],
  })
  render(<App />)
  clicar('Criar conta')
  digitar('Nome', 'Ana'); digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Criar conta')
  expect(await screen.findByRole('heading', { name: 'Confira seu e-mail' })).toBeTruthy()
  expect(screen.getByText('ana@x.com')).toBeTruthy()
  expect(chamadas.some((c) => c.chave === 'GET /assuntos')).toBe(false)
  clicar('Reenviar e-mail')
  expect((await screen.findByRole('status')).textContent).toContain('enviamos um novo link')
  expect(chamadas.find((c) => c.chave === 'POST /auth/reenviar-verificacao')!.corpo).toEqual({ email: 'ana@x.com' })
})

test('login de conta não verificada oferece reenviar a confirmação', async () => {
  const chamadas = servidor({
    'POST /auth/login': [403, { mensagem: 'Confirme seu e-mail para entrar.', codigo: 'email_nao_verificado' }],
    'POST /auth/reenviar-verificacao': [202],
  })
  render(<App />)
  digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Entrar')
  expect((await screen.findByRole('alert')).textContent).toContain('Confirme seu e-mail')
  clicar('Reenviar e-mail de confirmação')
  expect((await screen.findByRole('status')).textContent).toContain('enviamos um novo link')
  expect(chamadas.find((c) => c.chave === 'POST /auth/reenviar-verificacao')!.corpo).toEqual({ email: 'ana@x.com' })
})

test('link de verificação ativa a conta', async () => {
  window.history.pushState(null, '', '/verificar-email?token=xyz')
  const chamadas = servidor({ 'POST /auth/verificar-email': [204] })
  render(<App />)
  clicar('Ativar conta')
  expect((await screen.findByRole('status')).textContent).toContain('conta está ativa')
  expect(chamadas.find((c) => c.chave === 'POST /auth/verificar-email')!.corpo).toEqual({ token: 'xyz' })
})

test('e-mail repetido mostra a mensagem de conflito (UC01 A3)', async () => {
  servidor({ 'POST /auth/refresh': semSessao, 'POST /auth/cadastro': [409, { mensagem: 'Este e-mail já está cadastrado. Entre com a sua conta.' }] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  clicar('Criar conta')
  digitar('Nome', 'Ana'); digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Criar conta')
  expect((await screen.findByRole('alert')).textContent).toContain('Entre com a sua conta')
})

test('ao recarregar, o cookie de refresh restaura a sessão', async () => {
  jaEntrouAntes()
  servidor({ 'POST /auth/refresh': [200, { token: 'novo', nome: 'Ana' }], 'GET /assuntos': geral })
  render(<App />)
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
})

test('token de acesso vencido: renova uma vez e repete a chamada', async () => {
  jaEntrouAntes()
  let assuntos = 0
  const chamadas = servidor({
    'POST /auth/refresh': [200, { token: 'novo', nome: 'Ana' }],
    'GET /assuntos': () => (++assuntos === 1 ? [401, {}] : geral),
  })
  render(<App />)
  expect(await screen.findByText('Geral')).toBeTruthy()
  expect(chamadas.filter((c) => c.chave === 'POST /auth/refresh').length).toBe(2) // inicial + renovação
})

test('esqueci a senha mostra aviso neutro', async () => {
  const chamadas = servidor({ 'POST /auth/refresh': semSessao, 'POST /auth/esqueci-senha': [202] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  clicar('Esqueci minha senha')
  digitar('E-mail', 'a@x.com'); clicar('Enviar link')
  expect((await screen.findByRole('status')).textContent).toContain('Se este e-mail tiver conta')
  expect(chamadas.find((c) => c.chave === 'POST /auth/esqueci-senha')!.corpo).toEqual({ email: 'a@x.com' })
})

test('link de redefinição lê o token da URL e envia a nova senha', async () => {
  window.history.pushState(null, '', '/redefinir-senha?token=abc')
  const chamadas = servidor({ 'POST /auth/redefinir-senha': [204] })
  render(<App />)
  digitar('Nova senha', 'nova-senha-2'); clicar('Salvar nova senha')
  expect((await screen.findByRole('status')).textContent).toContain('Senha alterada')
  expect(chamadas.find((c) => c.chave === 'POST /auth/redefinir-senha')!.corpo).toEqual({ token: 'abc', novaSenha: 'nova-senha-2' })
})

test('excluir conta pede senha e confirmação e volta ao login', async () => {
  jaEntrouAntes()
  const chamadas = servidor({
    'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral,
    'GET /perfil': [200, { nome: 'Ana', email: 'ana@x.com' }], 'DELETE /perfil': [204], 'GET /perfil/sessoes': [200, []],
  })
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  expect(await screen.findByText('ana@x.com')).toBeTruthy()
  const senha = screen.getAllByLabelText('Senha atual').at(-1)!
  fireEvent.change(senha, { target: { value: 'senha-forte-1' } })
  digitar('Digite EXCLUIR para confirmar', 'EXCLUIR')
  clicar('Excluir minha conta')
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Entrar' })).toBeTruthy())
  expect(chamadas.find((c) => c.chave === 'DELETE /perfil')!.corpo).toEqual({ senha: 'senha-forte-1', confirmacao: 'EXCLUIR' })
})

test('sessão perdida no meio do uso volta ao login explicando o motivo', async () => {
  jaEntrouAntes()
  let refreshes = 0
  servidor({
    'POST /auth/refresh': () => (++refreshes === 1 ? [200, { token: 't', nome: 'Ana' }] : semSessao),
    'GET /assuntos': [401, {}],
  })
  render(<App />)
  expect(await screen.findByText('Sua sessão expirou. Entre novamente.')).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Entrar' })).toBeTruthy()
})

test('limite de taxa mostra a mensagem do servidor', async () => {
  servidor({ 'POST /auth/login': [429, { mensagem: 'Muitas tentativas. Aguarde 30 segundos e tente de novo.' }] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'a@x.com'); digitar('Senha', 'qualquer-senha'); clicar('Entrar')
  expect((await screen.findByRole('alert')).textContent).toContain('Aguarde 30 segundos')
})

test('o botão fica travado enquanto envia', async () => {
  let liberar: (v: unknown) => void = () => {}
  vi.stubGlobal('fetch', vi.fn(() => new Promise((r) => { liberar = r })))
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'a@x.com'); digitar('Senha', 'qualquer-senha'); clicar('Entrar')
  const botao = (await screen.findByRole('button', { name: 'Aguarde…' })) as HTMLButtonElement
  expect(botao.disabled).toBe(true)
  liberar({ ok: false, status: 401, json: async () => ({ mensagem: 'x' }) })
})

test('mostrar e ocultar a senha', async () => {
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  const campo = screen.getByLabelText('Senha') as HTMLInputElement
  expect(campo.type).toBe('password')
  fireEvent.click(screen.getByRole('button', { name: 'Mostrar senha' }))
  expect(campo.type).toBe('text')
  fireEvent.click(screen.getByRole('button', { name: 'Ocultar senha' }))
  expect(campo.type).toBe('password')
})

test('a senha digitada não acompanha a troca entre entrar e criar conta', async () => {
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('Senha', 'segredo-123')
  clicar('Criar conta')
  expect((screen.getByLabelText('Senha') as HTMLInputElement).value).toBe('')
})

test('seletor de tema força claro e escuro, volta ao sistema e lembra a escolha', async () => {
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  fireEvent.click(screen.getByRole('button', { name: 'Tema escuro' }))
  expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  expect(localStorage.getItem('nexo-tema')).toBe('escuro')
  fireEvent.click(screen.getByRole('button', { name: 'Tema claro' }))
  expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  fireEvent.click(screen.getByRole('button', { name: 'Tema do sistema' }))
  expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  expect(localStorage.getItem('nexo-tema')).toBeNull()
})

test('sessões ativas: marca o dispositivo atual, encerra uma e sai das outras', async () => {
  jaEntrouAntes()
  const agora = '2026-10-10T15:00:00Z'
  let sessoes = [
    { id: 'a', dispositivo: 'Firefox em Linux', ip: '10.0.0.1', inicio: agora, ultimaAtividade: agora, atual: true },
    { id: 'b', dispositivo: 'Chrome em Windows', ip: '10.0.0.2', inicio: agora, ultimaAtividade: agora, atual: false },
    { id: 'c', dispositivo: 'Safari em iOS', ip: '10.0.0.3', inicio: agora, ultimaAtividade: agora, atual: false },
  ]
  const chamadas = servidor({
    'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral, 'GET /perfil': [200, { nome: 'Ana', email: 'ana@x.com' }],
    'GET /perfil/sessoes': () => [200, sessoes],
    'DELETE /perfil/sessoes/b': () => { sessoes = sessoes.filter((s) => s.id !== 'b'); return [204] },
    'DELETE /perfil/sessoes': () => { sessoes = sessoes.filter((s) => s.atual); return [204] },
  })
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  expect(await screen.findByText('Firefox em Linux')).toBeTruthy()
  expect(screen.getByText('Este dispositivo')).toBeTruthy()
  expect(screen.getAllByRole('button', { name: 'Encerrar' }).length).toBe(2) // nenhuma para o dispositivo atual
  fireEvent.click(screen.getAllByRole('button', { name: 'Encerrar' })[0])
  await waitFor(() => expect(screen.queryByText('Chrome em Windows')).toBeNull())
  expect(chamadas.some((c) => c.chave === 'DELETE /perfil/sessoes/b')).toBe(true)
  clicar('Sair de todos os outros dispositivos')
  await waitFor(() => expect(screen.queryByText('Safari em iOS')).toBeNull())
  expect(screen.queryByRole('button', { name: 'Sair de todos os outros dispositivos' })).toBeNull() // só resta a atual
})

test('validação por campo: mostra os erros no lugar, marca aria-invalid, foca o primeiro e não chama o servidor', async () => {
  const chamadas = servidor({})
  render(<App />)
  clicar('Criar conta')
  digitar('E-mail', 'a@b'); digitar('Senha', 'curta'); clicar('Criar conta')
  expect(await screen.findByText('Informe o nome.')).toBeTruthy()
  expect(screen.getByText(/e-mail válido/)).toBeTruthy()
  expect(screen.getByText('A senha precisa ter pelo menos 8 caracteres.')).toBeTruthy()
  expect(screen.getByLabelText('Nome').getAttribute('aria-invalid')).toBe('true')
  await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Nome')))
  expect(chamadas.length).toBe(0)
  digitar('Nome', 'Ana') // digitar limpa o erro daquele campo
  expect(screen.queryByText('Informe o nome.')).toBeNull()
})

test('a mensagem antiga do servidor some quando a validação local acusa erro', async () => {
  servidor({ 'POST /auth/login': [401, { mensagem: 'E-mail ou senha incorretos.' }] })
  render(<App />)
  digitar('E-mail', 'a@x.com'); digitar('Senha', 'qualquer-senha'); clicar('Entrar')
  expect((await screen.findByRole('alert')).textContent).toBe('E-mail ou senha incorretos.')
  digitar('E-mail', 'a@b'); clicar('Entrar')
  expect(await screen.findByText(/e-mail válido/)).toBeTruthy()
  expect(screen.queryByText('E-mail ou senha incorretos.')).toBeNull()
})

test('o campo de e-mail pede o teclado certo no celular', async () => {
  render(<App />)
  const campo = screen.getByLabelText('E-mail')
  expect(campo.getAttribute('autocapitalize')).toBe('none')
  expect(campo.getAttribute('inputmode')).toBe('email')
  expect(campo.getAttribute('spellcheck')).toBe('false')
})

test('o título da aba acompanha a tela', async () => {
  render(<App />)
  expect(document.title).toBe('Entrar · Nexo')
  clicar('Criar conta')
  expect(document.title).toBe('Criar conta · Nexo')
})

const sessaoAberta = () => {
  jaEntrouAntes()
  return servidor({
    'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral,
    'GET /perfil': [200, { nome: 'Ana', email: 'ana@x.com' }], 'GET /perfil/sessoes': [200, []], 'POST /auth/logout': [204],
  })
}

test('o Perfil tem endereço próprio e o botão Voltar do navegador funciona', async () => {
  sessaoAberta()
  render(<App />)
  await screen.findByText('Olá, Ana')
  expect(document.title).toBe('Início · Nexo')
  clicar('Perfil')
  expect(window.location.pathname).toBe('/perfil')
  expect(await screen.findByRole('heading', { name: 'Meu perfil' })).toBeTruthy()
  expect(document.title).toBe('Meu perfil · Nexo')
  await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Meu perfil' }))) // foco no título
  window.history.back()
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
  await waitFor(() => expect(window.location.pathname).toBe('/'))
})

test('abrir /perfil direto leva ao Perfil depois do login', async () => {
  window.history.pushState(null, '', '/perfil')
  sessaoAberta()
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Meu perfil' })).toBeTruthy()
})

test('sair em outra aba derruba esta; entrar em outra abre a sessão aqui', async () => {
  sessaoAberta()
  render(<App />)
  await screen.findByText('Olá, Ana')
  localStorage.removeItem('nexo-sessao')
  window.dispatchEvent(new StorageEvent('storage', { key: 'nexo-sessao', newValue: null }))
  expect(await screen.findByText('Você saiu em outra aba.')).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Entrar' })).toBeTruthy()
  localStorage.setItem('nexo-sessao', '1')
  window.dispatchEvent(new StorageEvent('storage', { key: 'nexo-sessao', newValue: '1' }))
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
})

test('os formulários de senha do Perfil têm o campo de usuário para o gerenciador de senhas', async () => {
  sessaoAberta()
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  await screen.findByText('ana@x.com')
  const ocultos = document.querySelectorAll<HTMLInputElement>('input[autocomplete="username"]')
  expect(ocultos.length).toBe(3) // e-mail, senha e exclusão
  await waitFor(() => ocultos.forEach((o) => expect(o.value).toBe('ana@x.com')))
})

test('excluir conta só habilita com a senha e a palavra EXCLUIR', async () => {
  sessaoAberta()
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  const botao = (await screen.findByRole('button', { name: 'Excluir minha conta' })) as HTMLButtonElement
  expect(botao.disabled).toBe(true)
  fireEvent.change(screen.getAllByLabelText('Senha atual').at(-1)!, { target: { value: 'senha-forte-1' } })
  expect(botao.disabled).toBe(true)
  digitar('Digite EXCLUIR para confirmar', 'EXCLU')
  expect(botao.disabled).toBe(true)
  digitar('Digite EXCLUIR para confirmar', 'EXCLUIR')
  expect(botao.disabled).toBe(false)
})

test('troca de senha no Perfil valida o tamanho antes de enviar', async () => {
  const chamadas = sessaoAberta()
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  await screen.findByText('ana@x.com')
  const [atual, nova] = [screen.getAllByLabelText('Senha atual')[1], screen.getByLabelText('Nova senha')]
  fireEvent.change(atual, { target: { value: 'senha-forte-1' } })
  fireEvent.change(nova, { target: { value: 'curta' } })
  clicar('Alterar senha')
  expect(await screen.findByText('A senha precisa ter pelo menos 8 caracteres.')).toBeTruthy()
  expect(chamadas.some((c) => c.chave === 'PUT /perfil/senha')).toBe(false)
})

test('sessões: mostra carregando, falha com tentar de novo e tempo relativo', async () => {
  jaEntrouAntes()
  let tentativas = 0
  const ha5min = new Date(Date.now() - 5 * 60 * 1000).toISOString()
  servidor({
    'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral, 'GET /perfil': [200, { nome: 'Ana', email: 'ana@x.com' }],
    'GET /perfil/sessoes': () => (++tentativas === 1 ? [500, {}] : [200, [{ id: 'a', dispositivo: 'Firefox em Linux', ip: '1.1.1.1', inicio: ha5min, ultimaAtividade: ha5min, atual: true }]]),
  })
  render(<App />)
  await screen.findByText('Olá, Ana')
  clicar('Perfil')
  expect((await screen.findByText(/Não foi possível carregar as sessões/)).textContent).toBeTruthy()
  clicar('Tentar de novo')
  expect(await screen.findByText('Firefox em Linux')).toBeTruthy()
  expect(screen.getByText('há 5 minutos')).toBeTruthy()
})

const fechado = () => servidor({ 'POST /auth/refresh': semSessao })

test('rota desconhecida mostra a página 404 com caminho de volta', async () => {
  window.history.pushState(null, '', '/rota-que-nao-existe')
  fechado()
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeTruthy()
  expect(document.title).toBe('Página não encontrada · Nexo')
  clicar('Ir para o login')
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect(window.location.pathname).toBe('/entrar')
})

test('sem sessão, / e /perfil levam a /entrar e o destino é lembrado', async () => {
  window.history.pushState(null, '', '/perfil')
  fechado()
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect(window.location.pathname).toBe('/entrar')
  expect(window.location.search).toBe('?proximo=%2Fperfil')
})

test('depois do login volta ao destino pedido (/perfil) e não aceita destino de fora', async () => {
  window.history.pushState(null, '', '/perfil')
  servidor({
    'POST /auth/login': [200, { token: 't', nome: 'Ana' }], 'GET /perfil': [200, { nome: 'Ana', email: 'ana@x.com' }], 'GET /perfil/sessoes': [200, []],
  })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Entrar')
  expect(await screen.findByRole('heading', { name: 'Meu perfil' })).toBeTruthy()
  expect(window.location.pathname).toBe('/perfil')
  expect(window.location.search).toBe('')
})

test('destino proximo apontando para fora é ignorado', async () => {
  window.history.pushState(null, '', '/entrar?proximo=https%3A%2F%2Fmal.example%2F')
  servidor({ 'POST /auth/login': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral })
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Entrar')
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
  await waitFor(() => expect(window.location.pathname).toBe('/'))
})

test('cada modo tem endereço, o Voltar troca de modo e o e-mail digitado acompanha', async () => {
  fechado()
  render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  digitar('E-mail', 'ana@x.com')
  clicar('Criar conta')
  expect(window.location.pathname).toBe('/criar-conta')
  expect((screen.getByLabelText('E-mail') as HTMLInputElement).value).toBe('ana@x.com')
  clicar('Já tenho conta')
  clicar('Esqueci minha senha')
  expect(window.location.pathname).toBe('/recuperar-senha')
  expect(screen.getByRole('heading', { name: 'Recuperar senha' })).toBeTruthy()
  window.history.back()
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect(window.location.pathname).toBe('/entrar')
})

test('com sessão, abrir /entrar leva ao início', async () => {
  jaEntrouAntes()
  window.history.pushState(null, '', '/entrar')
  servidor({ 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral })
  render(<App />)
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
  await waitFor(() => expect(window.location.pathname).toBe('/')) // a URL é corrigida logo depois de a tela aparecer
})

test('o token do link sai da URL e recarregar a página mostra link incompleto', async () => {
  window.history.pushState(null, '', '/redefinir-senha?token=segredo123')
  servidor({ 'POST /auth/redefinir-senha': [204] })
  const { unmount } = render(<App />)
  await screen.findByRole('heading', { name: 'Nova senha' })
  expect(window.location.search).toBe('')
  expect(window.location.href).not.toContain('segredo123')
  unmount() // "recarregar": o token não está mais na URL
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Link incompleto')
  clicar('Pedir um novo link')
  expect(await screen.findByRole('heading', { name: 'Recuperar senha' })).toBeTruthy()
})

test('link inválido ou vencido oferece pedir outro', async () => {
  window.history.pushState(null, '', '/redefinir-senha?token=velho')
  servidor({ 'POST /auth/redefinir-senha': [400, { mensagem: 'Link inválido ou expirado. Peça um novo.', codigo: 'link_invalido' }] })
  render(<App />)
  digitar('Nova senha', 'cavalo-bateria-grampo'); clicar('Salvar nova senha')
  expect((await screen.findByRole('alert')).textContent).toContain('Link inválido ou expirado')
  clicar('Pedir um novo link')
  expect(await screen.findByRole('heading', { name: 'Recuperar senha' })).toBeTruthy()
})

test('e-mail já cadastrado oferece entrar ou recuperar a senha (UC01 A3)', async () => {
  servidor({ 'POST /auth/cadastro': [409, { mensagem: 'Este e-mail já está cadastrado. Entre com a sua conta.', codigo: 'email_em_uso' }] })
  render(<App />)
  clicar('Criar conta')
  digitar('Nome', 'Ana'); digitar('E-mail', 'ana@x.com'); digitar('Senha', 'senha-forte-1'); clicar('Criar conta')
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: 'Entrar com este e-mail' }))
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
  expect((screen.getByLabelText('E-mail') as HTMLInputElement).value).toBe('ana@x.com')
})

test('limite de tentativas: o botão mostra a contagem regressiva e volta ao normal', async () => {
  servidor({ 'POST /auth/login': [429, { mensagem: 'Muitas tentativas. Aguarde 2 segundos e tente de novo.', codigo: 'limite_taxa', esperaSegundos: 2 }] })
  render(<App />)
  digitar('E-mail', 'a@x.com'); digitar('Senha', 'qualquer-senha'); clicar('Entrar')
  const botao = (await screen.findByRole('button', { name: 'Tente de novo em 2 s' })) as HTMLButtonElement
  expect(botao.disabled).toBe(true)
  expect(await screen.findByRole('button', { name: 'Tente de novo em 1 s' }, { timeout: 2500 })).toBeTruthy()
  expect(((await screen.findByRole('button', { name: 'Entrar' }, { timeout: 2500 })) as HTMLButtonElement).disabled).toBe(false)
})

test('o Início mostra carregando e depois o estado vazio de materiais; há link para pular ao conteúdo', async () => {
  jaEntrouAntes()
  servidor({ 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }], 'GET /assuntos': geral, 'GET /materiais': [200, []] })
  render(<App />)
  expect(await screen.findByText('Olá, Ana')).toBeTruthy()
  expect(await screen.findByText('Geral')).toBeTruthy()
  expect(await screen.findByText(/ainda não enviou nenhum material/)).toBeTruthy()
  fireEvent.click(screen.getByText('Pular para o conteúdo'))
  expect(document.activeElement?.id).toBe('conteudo')
})
