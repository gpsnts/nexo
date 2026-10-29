import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { config } from './estudo'

type Rota = (corpo: any, init: RequestInit) => [number, unknown?]
const ID = '11111111-1111-1111-1111-111111111111'

function servidor(rotas: Record<string, Rota | [number, unknown?]>) {
  const chamadas: { chave: string; corpo: any; init: RequestInit }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '').split('?')[0]}`
    const corpo = typeof init.body === 'string' ? JSON.parse(init.body) : init.body
    chamadas.push({ chave, corpo, init })
    const r = rotas[chave]
    const [status, dados] = typeof r === 'function' ? r(corpo, init) : (r ?? [404, { mensagem: 'sem rota' }])
    return { ok: status < 400, status, json: async () => dados }
  }))
  return chamadas
}

const geral: [number, unknown] = [200, [{ id: 'g', nome: 'Geral', padrao: true }, { id: 'f', nome: 'Física', padrao: false }]]
const usoPadrao = { creditosUsados: 39, creditosLimite: 300, percentual: 13, renovaEm: '2026-10-19T03:00:00Z', operacoes: 4, limiteOperacoes: 30, janelaOperacoesMinutos: 60 }
const base = { 'GET /uso': [200, usoPadrao] as [number, unknown], 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] as [number, unknown], 'GET /assuntos': geral, 'GET /materiais': [200, []] as [number, unknown] }
const topicosIniciais = [
  { id: 'a', assuntoId: 'g', nome: 'Introdução', ordem: 1, inicio: 1, fim: 4, previa: 'x', temConteudo: false },
  { id: 'b', assuntoId: 'g', nome: 'Desenvolvimento', ordem: 2, inicio: 5, fim: 10, previa: 'y', temConteudo: false },
]
const material = (status: string, extra = {}) => [200, { id: ID, nomeArquivo: 'apostila.pdf', status, motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z', ...extra }] as [number, unknown]

beforeEach(() => { config.intervaloAndamentoMs = 5; localStorage.setItem('nexo-sessao', '1') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })

const irPara = (caminho: string) => window.history.replaceState(null, '', caminho)
const clicar = (nome: string | RegExp) => fireEvent.click(screen.getByRole('button', { name: nome }))
const arquivo = (nome: string, tamanho = 5) => new File([new Uint8Array(tamanho)], nome)

test('arquivo com formato inválido é barrado no cliente, sem chamar a API', async () => {
  irPara('/novo-estudo')
  const chamadas = servidor(base)
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('virus.exe')] } })
  clicar('Enviar material')
  expect((await screen.findByRole('alert')).textContent).toContain('Formato não aceito')
  expect(chamadas.some((c) => c.chave === 'POST /materiais')).toBe(false)
})

test('envia multipart com o assunto, abre o material e a barra sobe até os tópicos', async () => {
  irPara('/novo-estudo')
  let n = 0
  let liberar = false // o processamento só termina depois que o teste viu a barra: com poll de 5 ms ela some rápido demais e o teste ficava intermitente
  const chamadas = servidor({
    ...base,
    'POST /materiais': [202, { materialId: ID }],
    [`GET /materiais/${ID}`]: () => (!liberar ? material('processando') : material('pronto')),
    [`GET /materiais/${ID}/andamento`]: () => { n++; return !liberar ? [200, { status: 'processando', etapa: 'embeddings', percentual: Math.min(90, 40 * n), motivoFalha: null }] : [200, { status: 'pronto', etapa: 'concluido', percentual: 100, motivoFalha: null }] },
    [`GET /materiais/${ID}/topicos`]: [200, topicosIniciais],
  })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('apostila.pdf')] } })
  fireEvent.change(screen.getAllByLabelText('Pasta (opcional)')[0], { target: { value: 'f' } })
  clicar('Enviar material')

  const barra = await screen.findByRole('progressbar', { name: 'Andamento do processamento' })
  expect(barra.getAttribute('aria-label')).toBe('Andamento do processamento')
  liberar = true
  const envio = chamadas.find((c) => c.chave === 'POST /materiais')!
  expect(envio.corpo).toBeInstanceOf(FormData)
  expect((envio.corpo as FormData).get('assuntoId')).toBe('f')
  expect(((envio.corpo as FormData).get('arquivo') as File).name).toBe('apostila.pdf')
  expect(Object.keys(envio.init.headers as object)).not.toContain('Content-Type') // o navegador monta o boundary
  expect(window.location.pathname).toBe(`/materiais/${ID}`)

  expect(await screen.findByRole('heading', { name: 'Tópicos do material' })).toBeTruthy()
  expect((screen.getByLabelText('Nome do tópico 1') as HTMLInputElement).value).toBe('Introdução')
})

test('falha mostra o motivo e leva a um novo envio', async () => {
  irPara(`/materiais/${ID}`)
  servidor({
    ...base,
    [`GET /materiais/${ID}`]: material('falhou', { motivoFalha: 'sem_texto' }),
    [`GET /materiais/${ID}/andamento`]: [200, { status: 'falhou', etapa: 'falhou', percentual: 10, motivoFalha: 'sem_texto' }],
  })
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('não tem texto')
  clicar('Enviar novamente')
  await waitFor(() => expect(window.location.pathname).toBe('/novo-estudo'))
})

test('falha ao consultar o andamento permite tentar de novo', async () => {
  irPara(`/materiais/${ID}`)
  let falhar = true
  servidor({
    ...base,
    [`GET /materiais/${ID}`]: material('processando'),
    [`GET /materiais/${ID}/andamento`]: () => (falhar ? [500, { mensagem: 'x' }] : [200, { status: 'processando', etapa: 'extracao', percentual: 20, motivoFalha: null }]),
  })
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Não foi possível consultar')
  falhar = false
  clicar('Tentar de novo')
  expect((await screen.findByRole('progressbar', { name: 'Andamento do processamento' })).getAttribute('aria-label')).toBeTruthy()
})

test('arquivo repetido oferece abrir o material existente', async () => {
  irPara('/novo-estudo')
  servidor({ ...base, 'POST /materiais': [409, { mensagem: 'Você já enviou este arquivo.', codigo: 'material_repetido', materialId: ID }] })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('a.pdf')] } })
  clicar('Enviar material')
  expect((await screen.findByRole('alert')).textContent).toContain('já enviou')
  expect(await screen.findByRole('link', { name: 'Abrir o material já enviado' })).toBeTruthy()
})

test('tópico livre: nome vazio é recusado e depois é registrado e abre o tópico', async () => {
  irPara('/novo-estudo')
  const chamadas = servidor({ ...base, 'POST /topicos': [201, { id: '22222222-2222-2222-2222-222222222222', nome: 'MRU', ordem: 1 }] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Informar tópico' })
  clicar('Registrar tópico')
  expect((await screen.findByRole('alert')).textContent).toContain('Informe o nome do tópico')
  expect(chamadas.some((c) => c.chave === 'POST /topicos')).toBe(false)
  fireEvent.change(screen.getByLabelText('Tópico'), { target: { value: ' MRU ' } })
  clicar('Registrar tópico')
  await waitFor(() => expect(window.location.pathname).toBe('/topicos/22222222-2222-2222-2222-222222222222')) // o próximo passo (gerar resumo e questões) é no tópico
  expect(chamadas.find((c) => c.chave === 'POST /topicos')!.corpo).toEqual({ nome: 'MRU', assuntoId: null, orientacao: null })
})

function telaDeRevisao(put?: Rota, topicos: unknown = topicosIniciais) {
  irPara(`/materiais/${ID}`)
  return servidor({
    ...base,
    [`GET /materiais/${ID}`]: material('pronto'),
    [`GET /materiais/${ID}/topicos`]: [200, topicos],
    [`GET /materiais/${ID}/paginas`]: [200, [1, 1, 2, 2, 3, 3, 4, 4, 5, 5]],
    [`GET /materiais/${ID}/trechos`]: [200, [{ ordem: 5, numeroPagina: 2, previa: 'Início do trecho cinco' }]],
    [`PUT /materiais/${ID}/topicos`]: put ?? ((c) => [200, c.topicos.map((t: any, i: number) => ({ ...t, id: t.id ?? `novo${i}`, assuntoId: 'g', ordem: i + 1, previa: null }))]),
  })
}

test('revisão: renomear, ajustar limite e confirmar manda a lista completa de uma vez', async () => {
  const chamadas = telaDeRevisao()
  render(<App />)
  const nome = await screen.findByLabelText('Nome do tópico 1')
  expect((screen.getByRole('button', { name: 'Confirmar tópicos' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(nome, { target: { value: 'Conceitos' } })
  fireEvent.change(screen.getByLabelText('Último trecho do tópico 1'), { target: { value: '6' } })
  expect(screen.getByText('Trechos 7 a 10')).toBeTruthy()
  clicar('Confirmar tópicos')
  expect((await screen.findByRole('status')).textContent).toBe('Tópicos salvos.')
  const put = chamadas.find((c) => c.chave.startsWith('PUT'))!
  expect(put.corpo.topicos).toEqual([
    { id: 'a', nome: 'Conceitos', inicio: 1, fim: 6 },
    { id: 'b', nome: 'Desenvolvimento', inicio: 7, fim: 10 },
  ])
  expect(chamadas.filter((c) => c.chave.startsWith('PUT'))).toHaveLength(1)
})

test('revisão: mostra as páginas de cada tópico, avisa alterações não salvas e protege contra sair da página', async () => {
  telaDeRevisao()
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  expect(await screen.findByText(/págs\. 1 a 2/)).toBeTruthy()   // tópico 1: trechos 1 a 4 -> páginas 1 e 2
  expect(screen.queryByText(/Alterações não salvas/)).toBeNull()
  const antes = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(antes)
  expect(antes.defaultPrevented).toBe(false)   // sem alterações, sair não pergunta nada
  fireEvent.change(screen.getByLabelText('Nome do tópico 1'), { target: { value: 'Novo nome' } })
  expect(screen.getByText(/Alterações não salvas/)).toBeTruthy()
  const depois = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(depois)
  expect(depois.defaultPrevented).toBe(true)   // com alterações, o navegador pergunta
})

test('envio: mostra nome e tamanho do arquivo escolhido', async () => {
  irPara('/novo-estudo')
  servidor(base)
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('a.pdf', 2048)] } })
  expect(await screen.findByText('a.pdf · 2 KB')).toBeTruthy()
})

test('revisão: unir, dividir e desfazer', async () => {
  const chamadas = telaDeRevisao()
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Unir com o próximo (tópico 1)')
  expect(screen.queryByLabelText('Nome do tópico 2')).toBeNull()
  expect(screen.getByText('Trechos 1 a 10')).toBeTruthy()
  clicar('Desfazer alterações')
  expect(screen.getByLabelText('Nome do tópico 2')).toBeTruthy()

  clicar('Dividir (tópico 2)')
  fireEvent.change(screen.getByLabelText('Dividir depois do trecho'), { target: { value: '7' } })
  clicar('Dividir aqui')
  expect(screen.getByLabelText('Nome do tópico 3')).toBeTruthy()
  clicar('Confirmar tópicos')
  await screen.findByText('Tópicos salvos.')
  const topicos = chamadas.find((c) => c.chave.startsWith('PUT'))!.corpo.topicos
  expect(topicos.map((t: any) => [t.inicio, t.fim])).toEqual([[1, 4], [5, 7], [8, 10]])
  expect(topicos[2].id).toBeNull()
})

test('revisão: nome vazio não salva e erro do servidor aparece', async () => {
  const chamadas = telaDeRevisao(() => [422, { mensagem: 'Faltam os trechos 9 a 10 no fim do material.', codigo: 'cobertura_invalida' }])
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Nome do tópico 1'), { target: { value: '  ' } })
  clicar('Confirmar tópicos')
  expect((await screen.findByRole('alert')).textContent).toContain('Informe o nome do tópico')
  expect(chamadas.some((c) => c.chave.startsWith('PUT'))).toBe(false)
  await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Nome do tópico 1'))) // foco no primeiro campo inválido
  fireEvent.change(screen.getByLabelText('Nome do tópico 1'), { target: { value: 'Ok' } })
  clicar('Confirmar tópicos')
  await waitFor(() => expect(screen.getAllByRole('alert').map((a) => a.textContent).join()).toContain('Faltam os trechos'))
})

test('revisão: tratar como um tópico só e ver os trechos de um tópico', async () => {
  const chamadas = telaDeRevisao()
  render(<App />)
  await screen.findByLabelText('Nome do tópico 2')
  const detalhes = screen.getAllByText('Ver trechos do tópico 2')[0].closest('details')!
  detalhes.open = true
  fireEvent(detalhes, new Event('toggle'))
  expect(await within(detalhes).findByText(/Início do trecho cinco/)).toBeTruthy()
  clicar('Tratar como um tópico só')
  expect(screen.queryByLabelText('Nome do tópico 2')).toBeNull()
  expect(chamadas.some((c) => c.chave.startsWith('PUT'))).toBe(false)
})

test('revisão: tirar um tópico do estudo mostra os trechos de fora, permite voltar a usá-los e manda a lista com lacuna', async () => {
  const chamadas = telaDeRevisao()
  render(<App />)
  await screen.findByLabelText('Nome do tópico 2')
  clicar('Tirar o tópico 1 do estudo')
  expect(screen.getByText(/Fora dos tópicos.*trechos 1 a 4/)).toBeTruthy()
  clicar('Voltar a usar os trechos 1 a 4')
  expect(screen.queryByText(/Fora dos tópicos/)).toBeNull()
  expect((screen.getByLabelText('Nome do tópico 1') as HTMLInputElement).value).toBe('Trechos 1 a 4')
  clicar('Desfazer alterações')
  clicar('Tirar o tópico 1 do estudo')
  expect((screen.getByRole('button', { name: 'Tirar o tópico 1 do estudo' }) as HTMLButtonElement).disabled).toBe(true) // sobrou um só
  clicar('Confirmar tópicos')
  expect(await screen.findByText('Tópicos salvos.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave.startsWith('PUT'))!.corpo.topicos).toEqual([{ id: 'b', nome: 'Desenvolvimento', inicio: 5, fim: 10 }])
})

test('revisão: tópico com conteúdo gerado trava limites, unir e dividir, mas aceita novo nome', async () => {
  const chamadas = telaDeRevisao(undefined, topicosIniciais.map((t) => (t.id === 'a' ? { ...t, temConteudo: true } : t)))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Nome do tópico 1'), { target: { value: 'Renomeado' } })
  expect(screen.getByText(/só o nome pode mudar/)).toBeTruthy()
  expect((screen.getByLabelText('Último trecho do tópico 1') as HTMLInputElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Unir com o próximo (tópico 1)' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByRole('button', { name: 'Dividir (tópico 1)' })).toBeNull()
  expect(screen.getByRole('button', { name: 'Dividir (tópico 2)' })).toBeTruthy() // o tópico sem conteúdo continua editável
  expect((screen.getByRole('button', { name: 'Tratar como um tópico só' }) as HTMLButtonElement).disabled).toBe(true)
  clicar('Confirmar tópicos')
  await screen.findByText('Tópicos salvos.')
  expect(chamadas.find((c) => c.chave.startsWith('PUT'))!.corpo.topicos[0]).toEqual({ id: 'a', nome: 'Renomeado', inicio: 1, fim: 4 })
})

test('envio acima do limite do nginx (413 sem corpo JSON) mostra uma mensagem clara', async () => {
  irPara('/novo-estudo')
  servidor({ ...base, 'POST /materiais': [413] })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('a.pdf')] } })
  clicar('Enviar material')
  expect((await screen.findByRole('alert')).textContent).toContain('grande demais')
})

const REORG = `POST /materiais/${ID}/topicos/reorganizar`
const rascunho = (topicos: unknown[], mudou = true) => [200, { topicos, mudou, modelo: 'linguagem-falso', tokensEntrada: 10, tokensSaida: 5 }] as [number, unknown]

test('IA: rascunho com faixa nova e faixa mantida, aviso e confirmação pelo PUT de sempre', async () => {
  const chamadas = telaDeRevisao()
  const base = (globalThis.fetch as any).getMockImplementation()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (`${init.method ?? 'GET'} ${url.replace('/api', '')}` === REORG) {
      chamadas.push({ chave: REORG, corpo: undefined, init })
      const [status, dados] = rascunho([
        { id: 'a', nome: 'Introdução e conceitos', inicio: 1, fim: 4, temConteudo: false },
        { id: null, nome: 'Aplicações', inicio: 5, fim: 7, temConteudo: false },
        { id: null, nome: 'Fechamento', inicio: 8, fim: 10, temConteudo: false },
      ])
      return { ok: true, status, json: async () => dados }
    }
    return base(url, init)
  }))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  expect((screen.getByRole('button', { name: 'Confirmar tópicos' }) as HTMLButtonElement).disabled).toBe(true)
  clicar('Reorganizar com IA')
  expect((await screen.findByText(/Proposta da IA carregada como rascunho/)).textContent).toContain('de 2 para 3 tópicos')
  expect(screen.getByText(/Confirme ou desfaça o rascunho/)).toBeTruthy()   // a dica não culpa o usuário por alterações que ele não fez
  expect(screen.queryByText(/Salve ou desfaça suas alterações/)).toBeNull()
  expect((screen.getByLabelText('Nome do tópico 2') as HTMLInputElement).value).toBe('Aplicações')
  expect(screen.getByLabelText('Nome do tópico 3')).toBeTruthy()
  expect(screen.getByText(/Alterações não salvas/)).toBeTruthy()
  expect(chamadas.some((c) => c.chave.startsWith('PUT'))).toBe(false) // rascunho: nada gravado
  clicar('Confirmar tópicos')
  await screen.findByText('Tópicos salvos.')
  expect(chamadas.find((c) => c.chave.startsWith('PUT'))!.corpo.topicos.map((t: any) => [t.id, t.inicio, t.fim])).toEqual([['a', 1, 4], [null, 5, 7], [null, 8, 10]])
})

test('IA: enquanto analisa mostra o estado e trava o botão', async () => {
  telaDeRevisao()
  const antes = (globalThis.fetch as any).getMockImplementation()
  let liberar: () => void = () => {}
  const pendente = new Promise<void>((r) => { liberar = r })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (`${init.method ?? 'GET'} ${url.replace('/api', '')}` !== REORG) return antes(url, init)
    await pendente
    return { ok: true, status: 200, json: async () => rascunho([], false)[1] }
  }))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Reorganizar com IA')
  const botao = (await screen.findByRole('button', { name: 'A IA está analisando…' })) as HTMLButtonElement
  expect(botao.disabled).toBe(true)
  expect(screen.getByText(/A IA está analisando o material/)).toBeTruthy()
  liberar()
  expect(await screen.findByText(/manteve a divisão atual/)).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Reorganizar com IA' }) as HTMLButtonElement).disabled).toBe(false)
})

test('IA: sem mudança mostra aviso e não deixa alteração pendente', async () => {
  const base = telaDeRevisao()
  const antes = (globalThis.fetch as any).getMockImplementation()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => (`${init.method ?? 'GET'} ${url.replace('/api', '')}` === REORG
    ? { ok: true, status: 200, json: async () => rascunho([], false)[1] } : antes(url, init))))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Reorganizar com IA')
  expect((await screen.findByText(/manteve a divisão atual/)).textContent).toBeTruthy()
  expect(screen.queryByText(/Alterações não salvas/)).toBeNull()
  void base
})

test('IA: indisponível ou proposta inválida mostra a mensagem e mantém a divisão', async () => {
  telaDeRevisao()
  const antes = (globalThis.fetch as any).getMockImplementation()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => (`${init.method ?? 'GET'} ${url.replace('/api', '')}` === REORG
    ? { ok: false, status: 422, json: async () => ({ mensagem: 'A IA não devolveu uma divisão válida. Nada foi alterado.', codigo: 'proposta_ia_invalida' }) } : antes(url, init))))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Reorganizar com IA')
  expect((await screen.findByRole('alert')).textContent).toContain('Nada foi alterado')
  expect((screen.getByLabelText('Nome do tópico 1') as HTMLInputElement).value).toBe('Introdução')
  expect(screen.queryByText(/Alterações não salvas/)).toBeNull()
})

test('IA: botão fica desabilitado com alterações não salvas e explica por quê', async () => {
  telaDeRevisao()
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Nome do tópico 1'), { target: { value: 'Mudei' } })
  expect((screen.getByRole('button', { name: 'Reorganizar com IA' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.getByText(/Salve ou desfaça suas alterações antes de usar a IA/)).toBeTruthy()
  clicar('Desfazer alterações')
  expect((screen.getByRole('button', { name: 'Reorganizar com IA' }) as HTMLButtonElement).disabled).toBe(false)
})

test('IA: tópico com conteúdo vindo no rascunho continua congelado', async () => {
  telaDeRevisao()
  const antes = (globalThis.fetch as any).getMockImplementation()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => (`${init.method ?? 'GET'} ${url.replace('/api', '')}` === REORG
    ? { ok: true, status: 200, json: async () => rascunho([{ id: 'a', nome: 'Com conteúdo', inicio: 1, fim: 4, temConteudo: true }, { id: null, nome: 'Livre', inicio: 5, fim: 10, temConteudo: false }])[1] } : antes(url, init))))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Reorganizar com IA')
  await screen.findByText(/Proposta da IA carregada/)
  expect(screen.getByText(/só o nome pode mudar/)).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Unir com o próximo (tópico 1)' }) as HTMLButtonElement).disabled).toBe(true)
})

const barraDeUso = () => screen.getByRole('progressbar', { name: 'Uso de IA' }) as HTMLProgressElement

test('barra de uso de IA aparece no cabeçalho de todas as telas internas, mostra a porcentagem da cota semanal e abre os detalhes', async () => {
  for (const caminho of ['/', '/perfil', '/novo-estudo', `/materiais/${ID}`]) {
    irPara(caminho)
    servidor({ ...base, 'GET /perfil': [200, { nome: 'Ana', email: 'a@x.com' }], 'GET /perfil/sessoes': [200, []], [`GET /materiais/${ID}`]: material('pronto'), [`GET /materiais/${ID}/topicos`]: [200, topicosIniciais] })
    const { unmount } = render(<App />)
    await waitFor(() => expect(barraDeUso().value).toBe(13), { timeout: 3000 }) // 39 de 300 créditos = 13% da cota da semana
    unmount()
  }
  irPara('/')
  servidor({ ...base })
  render(<App />)
  await waitFor(() => expect(barraDeUso().value).toBe(13))
  expect(barraDeUso().className).toContain('ok')
  expect(screen.queryByText(/39 de 300 créditos/)).toBeNull() // sem detalhes até o usuário abrir
  fireEvent.click(screen.getByRole('button', { name: /Uso de IA/ }))
  expect(screen.getByText('13% usada · 39 de 300 créditos')).toBeTruthy()
  expect(screen.getAllByText(/Renova .*19\/10/).length).toBeGreaterThan(0)   // quando a cota volta
  expect(document.body.textContent).not.toMatch(/US\$|dólar/)               // nada de dinheiro na tela
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(screen.queryByText('13% usada · 39 de 300 créditos')).toBeNull()
})

test('barra de uso muda de cor perto do limite e avisa quando o limite é atingido', async () => {
  servidor({ ...base, 'GET /uso': [200, { ...usoPadrao, percentual: 83, creditosUsados: 249 }] })
  const a = render(<App />)
  await waitFor(() => expect(barraDeUso().value).toBe(83))
  expect(barraDeUso().className).toContain('atencao')
  fireEvent.click(screen.getByRole('button', { name: /Uso de IA/ }))
  expect(screen.getByText(/Sua cota está acabando/)).toBeTruthy()
  a.unmount()
  servidor({ ...base, 'GET /uso': [200, { ...usoPadrao, percentual: 100, creditosUsados: 300 }] })   // cota esgotada
  render(<App />)
  await waitFor(() => expect(barraDeUso().value).toBe(100))
  expect(barraDeUso().className).toContain('esgotado')
  fireEvent.click(screen.getByRole('button', { name: /Uso de IA/ }))
  expect(screen.getByText(/Cota da semana esgotada/)).toBeTruthy()
})

test('barra de uso não aparece nas telas de entrada nem quando o uso não carrega', async () => {
  localStorage.clear()
  irPara('/entrar')
  servidor({ ...base })
  const a = render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(screen.queryByRole('progressbar', { name: 'Uso de IA' })).toBeNull()
  a.unmount()
  localStorage.setItem('nexo-sessao', '1')
  irPara('/')
  servidor({ ...base, 'GET /uso': [500, { mensagem: 'x' }] })
  render(<App />)
  expect(await screen.findByText(/Comece pelo seu primeiro estudo/)).toBeTruthy()
  expect(screen.queryByRole('progressbar', { name: 'Uso de IA' })).toBeNull()
})

test('a barra de uso é atualizada quando uma operação com IA acontece', async () => {
  irPara('/novo-estudo')
  let n = 0
  servidor({ ...base, 'GET /uso': () => { n++; return [200, { ...usoPadrao, percentual: n >= 2 ? 20 : 13 }] }, 'POST /materiais': [429, { mensagem: 'limite', codigo: 'limite_operacoes', esperaSegundos: 60 }] })
  render(<App />)
  await waitFor(() => expect(barraDeUso().value).toBe(13))
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('a.pdf')] } })
  clicar('Enviar material')
  await waitFor(() => expect(barraDeUso().value).toBe(20)) // 6 de 30, consultado de novo depois do envio recusado
})

test('limite de operações ou de gasto no envio mostra a mensagem do servidor', async () => {
  irPara('/novo-estudo')
  servidor({ ...base, 'POST /materiais': [429, { mensagem: 'Você atingiu o limite de 30 operações com IA por hora. Tente novamente em 12 minutos.', codigo: 'limite_operacoes', esperaSegundos: 720 }] })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Arquivo'), { target: { files: [arquivo('a.pdf')] } })
  clicar('Enviar material')
  expect((await screen.findByRole('alert')).textContent).toContain('limite de 30 operações com IA por hora')
  expect((screen.getByRole('button', { name: 'Enviar material' }) as HTMLButtonElement).disabled).toBe(false) // pode tentar de novo depois
})

test('IA: limite de gasto na reorganização mostra a mensagem e mantém a divisão', async () => {
  telaDeRevisao()
  const antes = (globalThis.fetch as any).getMockImplementation()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => (`${init.method ?? 'GET'} ${url.replace('/api', '')}` === REORG
    ? { ok: false, status: 429, json: async () => ({ mensagem: 'Você atingiu o limite de gasto com IA (US$ 10,00 em 30 dias). Novas operações com IA ficam bloqueadas até 12/11/2026 às 10:00.', codigo: 'limite_gasto', esperaSegundos: 99999 }) } : antes(url, init))))
  render(<App />)
  await screen.findByLabelText('Nome do tópico 1')
  clicar('Reorganizar com IA')
  expect((await screen.findByRole('alert')).textContent).toContain('bloqueadas até 12/11/2026')
  expect(screen.queryByText(/Alterações não salvas/)).toBeNull()
})

test('início lista os materiais com o status e abre um deles', async () => {
  servidor({
    ...base,
    'GET /materiais': [200, [
      { id: ID, nomeArquivo: 'apostila.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' },
      { id: '22222222-2222-2222-2222-222222222222', nomeArquivo: 'velho.pdf', status: 'falhou', motivoFalha: 'sem_texto', paginas: 3, criadoEm: '2026-10-14T10:00:00Z' },
    ]],
    [`GET /materiais/${ID}`]: material('pronto'),
    [`GET /materiais/${ID}/topicos`]: [200, topicosIniciais],
  })
  render(<App />)
  expect((await screen.findByText(/não tem texto/)).textContent).toContain('PDF escaneado')
  expect(screen.getByText('Falhou')).toBeTruthy()
  clicar('apostila.pdf')
  await waitFor(() => expect(window.location.pathname).toBe(`/materiais/${ID}`))
  expect(await screen.findByRole('heading', { name: 'Tópicos do material' })).toBeTruthy()
})

test('sem sessão, abrir um material leva ao login', async () => {
  localStorage.clear()
  irPara(`/materiais/${ID}`)
  servidor({})
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeTruthy()
})

test('revisão: o campo do último trecho aceita digitar com calma (apagar, valor incompleto ou fora do limite) e só aplica valores válidos', async () => {
  telaDeRevisao()
  render(<App />)
  const campo = (await screen.findByLabelText('Último trecho do tópico 1')) as HTMLInputElement
  fireEvent.focus(campo)
  fireEvent.change(campo, { target: { value: '' } })
  expect(campo.value).toBe('')                        // apagou tudo: continua vazio, não volta sozinho
  fireEvent.change(campo, { target: { value: '10' } })  // fora do limite (o seguinte vai até 10): rascunho com aviso, nada muda
  expect(campo.value).toBe('10')
  expect(screen.getByText(/O último trecho deste tópico vai de 1 a 9/)).toBeTruthy()  // o tópico seguinte (5 a 10) precisa manter ao menos um trecho
  expect(campo.getAttribute('aria-invalid')).toBe('true')
  expect(screen.getAllByText('Trechos 5 a 10').length).toBeGreaterThan(0)  // nada mudou
  fireEvent.change(campo, { target: { value: '8' } })    // valor válido: aplica na hora e o aviso some
  expect(screen.getAllByText('Trechos 9 a 10').length).toBeGreaterThan(0)
  expect(screen.queryByText(/O último trecho deste tópico vai de/)).toBeNull()
  fireEvent.change(campo, { target: { value: 'a7x' } })  // letras são ignoradas
  expect(campo.value).toBe('7')
  fireEvent.change(campo, { target: { value: '99' } })
  fireEvent.blur(campo)                                // ao sair, o campo volta ao valor real
  expect(campo.value).toBe('7')
})

test('revisão: o campo de dividir deixa apagar e digitar, e só habilita o botão com valor dentro da faixa', async () => {
  telaDeRevisao()
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Dividir (tópico 1)' }))
  const campo = screen.getByLabelText('Dividir depois do trecho') as HTMLInputElement
  const botao = screen.getByRole('button', { name: 'Dividir aqui' }) as HTMLButtonElement
  fireEvent.change(campo, { target: { value: '' } })
  expect(campo.value).toBe('')
  expect(botao.disabled).toBe(true)
  fireEvent.change(campo, { target: { value: '4' } })   // o tópico vai de 1 a 4: dividir depois do último não faz sentido
  expect(botao.disabled).toBe(true)
  fireEvent.change(campo, { target: { value: '3' } })
  expect(botao.disabled).toBe(false)
})

test('tópico livre: a orientação digitada é enviada junto do nome', async () => {
  irPara('/novo-estudo')
  const chamadas = servidor({ ...base, 'POST /topicos': [201, { id: '22222222-2222-2222-2222-222222222222', nome: 'Guerra', ordem: 1 }] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Informar tópico' })
  fireEvent.change(screen.getByLabelText('Tópico'), { target: { value: 'Segunda Guerra' } })
  fireEvent.change(screen.getByLabelText('Orientações (opcional)'), { target: { value: ' Espanha e Portugal; estilo FCC ' } })
  clicar('Registrar tópico')
  await waitFor(() => expect(chamadas.some((c) => c.chave === 'POST /topicos')).toBe(true))
  expect(chamadas.find((c) => c.chave === 'POST /topicos')!.corpo).toEqual({ nome: 'Segunda Guerra', assuntoId: null, orientacao: 'Espanha e Portugal; estilo FCC' })
})
