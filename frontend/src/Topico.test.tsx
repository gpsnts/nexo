import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { config } from './estudo'

type Rota = (corpo: any) => [number, unknown?]
const T = '22222222-2222-2222-2222-222222222222'
const REG = '33333333-3333-3333-3333-333333333333'

function servidor(rotas: Record<string, Rota | [number, unknown?]>) {
  const chamadas: { chave: string; corpo: any; url: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '').split('?')[0]}`
    const corpo = typeof init.body === 'string' ? JSON.parse(init.body) : init.body
    chamadas.push({ chave, corpo, url })
    const r = rotas[chave]
    const [status, dados] = typeof r === 'function' ? r(corpo) : (r ?? [404, { mensagem: 'sem rota' }])
    return { ok: status < 400, status, json: async () => dados }
  }))
  return chamadas
}

const uso = { operacoes: 4, limiteOperacoes: 30, janelaOperacoesMinutos: 60, gastoUsd: 0.5, limiteGastoUsd: 10, janelaGastoDias: 30 }
const detalhe = (extra = {}) => ({
  id: T, assuntoId: 'g', nome: 'Células', livre: false, fontes: [{ materialId: 'm', nomeArquivo: 'bio.pdf', inicio: 1, fim: 5 }],
  resumo: null, totalQuestoes: 0, maximoQuestoes: 50, maximoResumos: 10, geracaoAberta: null, ultimaGeracao: null, ...extra,
})
const geracao = (extra = {}) => ({ id: REG, operacao: 'questoes', status: 'em_andamento', progresso: 40, aprovadas: 0, rejeitadas: 0, repetidas: 0, gravadas: 0, taxaRejeicao: null, mensagem: null, verificacaoId: null, motivo: null, ...extra })
const base = { 'GET /uso': [200, uso] as [number, unknown], 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] as [number, unknown] }
const questao = { id: 'q1', enunciado: 'O que produz ATP?', alternativas: ['Núcleo', 'Mitocôndria', 'Ribossomo', 'Lisossomo'], respostaCorreta: 1, criadoEm: '2026-10-15T10:00:00Z', apoio: [{ nomeArquivo: 'bio.pdf', ordem: 2, numeroPagina: 3 }, { nomeArquivo: 'bio.pdf', ordem: 3, numeroPagina: 4 }] }

beforeEach(() => { config.intervaloAndamentoMs = 5; config.avisoParadaMs = 180_000; config.tentativasAndamento = 3; config.avisoDemoraMs = 5000; localStorage.setItem('nexo-sessao', '1'); window.history.replaceState(null, '', `/topicos/${T}`) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })

const clicar = (nome: string | RegExp) => fireEvent.click(screen.getByRole('button', { name: nome }))

test('gera questões com barra de andamento, mostra o resultado e lista as questões com a correta indicada', async () => {
  let n = 0
  let gravado = false
  const chamadas = servidor({
    ...base,
    [`GET /topicos/${T}`]: () => [200, detalhe({ totalQuestoes: gravado ? 1 : 0 })],
    [`GET /topicos/${T}/questoes`]: () => [200, gravado ? [questao] : []],
    [`POST /topicos/${T}/questoes`]: [202, { registroId: REG, verificacaoId: 'v' }],
    [`GET /geracoes/${REG}`]: () => {
      n++
      if (n < 3) return [200, geracao({ progresso: 20 * n })]
      gravado = true
      return [200, geracao({ status: 'concluida', progresso: 100, aprovadas: 2, rejeitadas: 1, gravadas: 1, repetidas: 1, taxaRejeicao: 1 / 3 })]
    },
  })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Células' })).toBeTruthy()
  expect(screen.getByText(/limites deste tópico ficam fixos/)).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Quantas questões gerar'), { target: { value: '3' } })
  clicar('Gerar questões')
  expect(await screen.findByRole('progressbar', { name: 'Andamento da geração' })).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/questoes`)!.corpo).toEqual({ quantidade: 3 })
  const resultado = await screen.findByText(/1 questão gravada de 3 pedidas/)
  expect(resultado.closest('[role=status]')!.textContent).toContain('taxa de rejeição 33%')
  expect(resultado.closest('[role=status]')!.textContent).toContain('1 repetida descartada')
  expect(await screen.findByText('O que produz ATP?')).toBeTruthy()
  expect(screen.getByText(/B\) Mitocôndria/).closest('li')!.textContent).toContain('(correta)')
  expect(screen.getByText('Fonte: bio.pdf, págs. 3 e 4')).toBeTruthy()
  expect(screen.getByText(/Modo revisão/)).toBeTruthy()
})

test('gera o resumo e mostra o texto', async () => {
  let pronto = false
  servidor({
    ...base,
    [`GET /topicos/${T}`]: () => [200, detalhe({ resumo: pronto ? { conteudo: 'Resumo fiel ao material.', criadoEm: '2026-10-15T10:00:00Z' } : null })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`POST /topicos/${T}/resumo`]: [202, { registroId: REG, verificacaoId: null }],
    [`GET /geracoes/${REG}`]: () => { pronto = true; return [200, geracao({ operacao: 'resumo', status: 'concluida', progresso: 100 })] },
  })
  render(<App />)
  await screen.findByRole('heading', { name: 'Células' })
  clicar('Gerar resumo')
  expect(await screen.findByText('Resumo fiel ao material.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Gerar novo resumo' })).toBeTruthy()
})

test('falha do modelo mostra a mensagem e "Tentar de novo" repete o mesmo pedido', async () => {
  let posts = 0
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe()],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`POST /topicos/${T}/questoes`]: () => { posts++; return [202, { registroId: REG, verificacaoId: null }] },
    [`GET /geracoes/${REG}`]: () => [200, geracao({ status: posts < 2 ? 'falhou' : 'concluida', progresso: 100, mensagem: 'A geração não foi concluída. Tente novamente.' })],
  })
  render(<App />)
  await screen.findByRole('heading', { name: 'Células' })
  clicar('Gerar questões')
  expect((await screen.findByRole('alert')).textContent).toContain('Tente novamente')
  clicar('Tentar de novo')
  await waitFor(() => expect(posts).toBe(2))
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
})

test('limite de IA vem do servidor como mensagem clara e não abre andamento', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [] })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`POST /topicos/${T}/resumo`]: [429, { mensagem: 'Limite de operações com IA atingido. Tente novamente em 12 minutos.', codigo: 'limite_operacoes' }],
  })
  render(<App />)
  expect(await screen.findByText(/Tópico livre/)).toBeTruthy()
  clicar('Gerar resumo')
  expect((await screen.findByRole('alert')).textContent).toContain('12 minutos')
  expect(screen.queryByRole('progressbar', { name: 'Andamento da geração' })).toBeNull()
})

test('retoma o acompanhamento de uma geração que já estava em andamento', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: [200, geracao({ progresso: 55 })],
  })
  render(<App />)
  expect((await screen.findByRole('progressbar', { name: 'Andamento da geração' })).getAttribute('aria-valuetext')).toBe('55%')
  expect((screen.getByRole('button', { name: 'Gerar questões' }) as HTMLButtonElement).disabled).toBe(true)
})

test('no teto de questões do tópico o botão fica desabilitado com explicação', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 50 })], [`GET /topicos/${T}/questoes`]: [200, []] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Células' })
  expect((screen.getByRole('button', { name: 'Gerar questões' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.getByText(/máximo de 50 questões/)).toBeTruthy()
})

test('na revisão do material, "Estudar" abre o tópico salvo', async () => {
  const MAT = '11111111-1111-1111-1111-111111111111'
  window.history.replaceState(null, '', `/materiais/${MAT}`)
  servidor({
    ...base,
    [`GET /materiais/${MAT}`]: [200, { id: MAT, nomeArquivo: 'bio.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' }],
    [`GET /materiais/${MAT}/topicos`]: [200, [{ id: T, assuntoId: 'g', nome: 'Células', ordem: 1, inicio: 1, fim: 5, previa: 'x', temConteudo: false }]],
    [`GET /materiais/${MAT}/paginas`]: [200, [1, 1, 2, 2, 3]],
    [`GET /topicos/${T}`]: [200, detalhe()],
    [`GET /topicos/${T}/questoes`]: [200, []],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: /Estudar o tópico 1/ }))
  await waitFor(() => expect(window.location.pathname).toBe(`/topicos/${T}`))
  expect(await screen.findByRole('heading', { name: 'Células' })).toBeTruthy()
})

test('"Voltar" leva ao material do tópico, e o tópico livre volta ao início', async () => {
  const MAT = 'm1111111-1111-1111-1111-111111111111'.replace('m', '1')
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ fontes: [{ materialId: MAT, nomeArquivo: 'bio.pdf', inicio: 1, fim: 5 }] })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /materiais/${MAT}`]: [200, { id: MAT, nomeArquivo: 'bio.pdf', status: 'processando', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' }],
    [`GET /materiais/${MAT}/andamento`]: [200, { status: 'processando', etapa: 'extracao', percentual: 20, motivoFalha: null }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: /Voltar ao material "bio.pdf"/ }))
  await waitFor(() => expect(window.location.pathname).toBe(`/materiais/${MAT}`))
})

test('tópico livre: aparece na lista única de estudos e "Voltar ao início" volta para ela', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [] })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }]],
    'GET /materiais': [200, []],
    'GET /topicos/livres': [200, [{ id: T, nome: 'Movimento Retilíneo', criadoEm: '2026-10-15T10:00:00Z', totalQuestoes: 2, temResumo: true }]],
  })
  window.history.replaceState(null, '', '/')
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Seus estudos' })).toBeTruthy()
  expect(await screen.findByText(/com resumo · 2 questões/)).toBeTruthy()
  expect(screen.getByText('Tópico livre')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Movimento Retilíneo' }))
  await waitFor(() => expect(window.location.pathname).toBe(`/topicos/${T}`))
  fireEvent.click(await screen.findByRole('button', { name: /Voltar ao início/ }))
  await waitFor(() => expect(window.location.pathname).toBe('/'))
})

test('o campo de quantidade nunca passa do que ainda cabe no tópico', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 47 })], [`GET /topicos/${T}/questoes`]: [200, []] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Células' })
  fireEvent.change(screen.getByLabelText('Quantas questões gerar'), { target: { value: '9' } })
  expect((screen.getByLabelText('Quantas questões gerar') as HTMLInputElement).value).toBe('3')
})

test('mostra o custo da ação em operações e a explicação de que levar tempo não prende o usuário', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })], [`GET /topicos/${T}/questoes`]: [200, []], [`GET /geracoes/${REG}`]: [200, geracao({ progresso: 10 })] })
  render(<App />)
  expect(await screen.findByText(/Usa 1 operação do seu limite/)).toBeTruthy()
  expect(screen.getByText(/Usa 2 operações do seu limite/)).toBeTruthy()
  expect(await screen.findByText(/Você pode sair desta página/)).toBeTruthy()
})

test('depois de recarregar, o resultado da última geração de questões continua visível', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 4, ultimaGeracao: geracao({ status: 'concluida', progresso: 100, aprovadas: 5, rejeitadas: 2, gravadas: 4, repetidas: 1, taxaRejeicao: 2 / 7 }) })],
    [`GET /topicos/${T}/questoes`]: [200, []],
  })
  render(<App />)
  const r = await screen.findByText(/Última geração: 4 questões gravadas/)
  expect(r.closest('[role=status]')!.textContent).toContain('taxa de rejeição 29%')
})

test('rótulo da fonte: páginas quando há, trechos quando não há (DOCX/TXT)', async () => {
  const { rotuloApoio } = await import('./estudo')
  expect(rotuloApoio([{ nomeArquivo: 'a.pdf', ordem: 1, numeroPagina: 5 }])).toBe('a.pdf, pág. 5')
  expect(rotuloApoio([{ nomeArquivo: 'a.pdf', ordem: 1, numeroPagina: 5 }, { nomeArquivo: 'a.pdf', ordem: 2, numeroPagina: 5 }, { nomeArquivo: 'a.pdf', ordem: 3, numeroPagina: 7 }])).toBe('a.pdf, págs. 5 e 7')
  expect(rotuloApoio([{ nomeArquivo: 'n.txt', ordem: 12, numeroPagina: null }, { nomeArquivo: 'n.txt', ordem: 13, numeroPagina: null }])).toBe('n.txt, trechos 12 e 13')
  expect(rotuloApoio([{ nomeArquivo: 'a.pdf', ordem: 1, numeroPagina: 2 }, { nomeArquivo: 'n.txt', ordem: 4, numeroPagina: null }])).toBe('a.pdf, pág. 2; n.txt, trecho 4')
  expect(rotuloApoio([])).toBe('')
})

test('"Estudar" fica desabilitado enquanto há alterações não salvas, com a explicação', async () => {
  const MAT = '11111111-1111-1111-1111-111111111111'
  window.history.replaceState(null, '', `/materiais/${MAT}`)
  servidor({
    ...base,
    [`GET /materiais/${MAT}`]: [200, { id: MAT, nomeArquivo: 'bio.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' }],
    [`GET /materiais/${MAT}/topicos`]: [200, [{ id: T, assuntoId: 'g', nome: 'Células', ordem: 1, inicio: 1, fim: 5, previa: 'x', temConteudo: false }]],
    [`GET /materiais/${MAT}/paginas`]: [200, [1, 1, 2, 2, 3]],
  })
  render(<App />)
  const estudar = await screen.findByRole('button', { name: /Estudar o tópico 1/ }) as HTMLButtonElement
  expect(estudar.disabled).toBe(false)
  fireEvent.change(screen.getByLabelText('Nome do tópico 1'), { target: { value: 'Células e organelas' } })
  expect(estudar.disabled).toBe(true)
  expect(screen.getByText(/antes de usar a IA ou de estudar um tópico/)).toBeTruthy()
  expect(screen.getByText('Alterações não salvas.')).toBeTruthy() // a barra fixa fica curta (em celular a mensagem longa ocupava um quarto da tela)
})

test('cancelar uma geração em andamento: chama a API, para o andamento e mostra o aviso sem "Tentar de novo"', async () => {
  let cancelado = false
  const chamadas = servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: () => [200, cancelado ? geracao({ status: 'falhou', motivo: 'cancelada', mensagem: 'Geração cancelada.' }) : geracao({ progresso: 30 })],
    [`POST /geracoes/${REG}/cancelar`]: () => { cancelado = true; return [200, geracao({ status: 'falhou', motivo: 'cancelada', mensagem: 'Geração cancelada. As questões já gravadas foram mantidas.' })] },
  })
  render(<App />)
  await screen.findByRole('progressbar', { name: 'Andamento da geração' })
  clicar('Cancelar geração')
  expect(await screen.findByText('Geração cancelada. As questões já gravadas foram mantidas.')).toBeTruthy()
  expect(chamadas.some((c) => c.chave === `POST /geracoes/${REG}/cancelar`)).toBe(true)
  expect(screen.queryByRole('progressbar', { name: 'Andamento da geração' })).toBeNull()
  expect(screen.queryByRole('alert')).toBeNull() // cancelar não é erro: sem alerta vermelho nem "Tentar de novo"
  expect(screen.queryByRole('button', { name: 'Tentar de novo' })).toBeNull()
  expect((screen.getByRole('button', { name: 'Gerar questões' }) as HTMLButtonElement).disabled).toBe(false)
})

test('erro ao cancelar mostra a mensagem e mantém o andamento', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: [200, geracao({ progresso: 30 })],
    [`POST /geracoes/${REG}/cancelar`]: [500, { mensagem: 'Falhou.' }],
  })
  render(<App />)
  await screen.findByRole('progressbar', { name: 'Andamento da geração' })
  clicar('Cancelar geração')
  expect((await screen.findByRole('alert')).textContent).toContain('Falhou.')
  expect(screen.getByRole('progressbar', { name: 'Andamento da geração' })).toBeTruthy()
})

test('assuntos na tela inicial aparecem como etiquetas, com o padrão indicado', async () => {
  window.history.replaceState(null, '', '/')
  servidor({ ...base, 'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }, { id: 'f', nome: 'Física', padrao: false }]], 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []] })
  render(<App />)
  const lista = await screen.findByRole('list', { name: 'Assuntos' })
  expect(lista.textContent).toContain('Geral · padrão'); expect(lista.textContent).toContain('Física')
  expect(screen.getByText(/O assunto padrão guarda os estudos/)).toBeTruthy() // explicação visível, não só ao passar o mouse
})

test('falhas passageiras de rede no andamento são absorvidas: o acompanhamento continua sem alerta', async () => {
  let n = 0
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: () => { n++; return n <= 2 ? [502, {}] : [200, geracao({ progresso: 70 })] },
  })
  render(<App />)
  await screen.findByRole('progressbar', { name: 'Andamento da geração' })
  await waitFor(() => expect(screen.getByRole('progressbar', { name: 'Andamento da geração' }).getAttribute('aria-valuetext')).toBe('70%'))
  expect(screen.queryByRole('alert')).toBeNull()
})

test('depois de falhas seguidas oferece "Tentar de novo" sem barra congelada nem cancelar, e volta a acompanhar', async () => {
  let queda = true
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: () => (queda ? [502, {}] : [200, geracao({ progresso: 80 })]),
  })
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Perdemos a conexão com o andamento')
  expect(screen.queryByRole('progressbar', { name: 'Andamento da geração' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Cancelar geração' })).toBeNull()
  expect((screen.getByRole('button', { name: 'Gerar questões' }) as HTMLButtonElement).disabled).toBe(true) // a geração segue no servidor
  queda = false
  clicar('Tentar de novo')
  await screen.findByRole('progressbar', { name: 'Andamento da geração' })
  await waitFor(() => expect(screen.getByRole('progressbar', { name: 'Andamento da geração' }).getAttribute('aria-valuetext')).toBe('80%'))
  expect(screen.queryByRole('alert')).toBeNull()
})

test('sem mudança de progresso por muito tempo avisa que parece parada', async () => {
  config.avisoParadaMs = 30
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })], [`GET /topicos/${T}/questoes`]: [200, []], [`GET /geracoes/${REG}`]: [200, geracao({ progresso: 30 })] })
  render(<App />)
  expect((await screen.findByText(/Sem novidades há alguns minutos/)).getAttribute('role')).toBe('note')
  expect(screen.getByText(/não é possível gerar outro conteúdo neste tópico/)).toBeTruthy()
})

test('depois de cancelar o foco vai para o aviso (o botão sumiu)', async () => {
  let cancelado = false
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /geracoes/${REG}`]: () => [200, cancelado ? geracao({ status: 'falhou', motivo: 'cancelada', mensagem: 'Geração cancelada.' }) : geracao({ progresso: 30 })],
    [`POST /geracoes/${REG}/cancelar`]: () => { cancelado = true; return [200, geracao({ status: 'falhou', motivo: 'cancelada', mensagem: 'Geração cancelada.' })] },
  })
  render(<App />)
  await screen.findByRole('progressbar', { name: 'Andamento da geração' })
  clicar('Cancelar geração')
  const aviso = await screen.findByText('Geração cancelada.')
  await waitFor(() => expect(document.activeElement).toBe(aviso))
})

test('resumo mostra a data e os resumos anteriores ficam acessíveis', async () => {
  servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ resumo: { conteudo: 'Resumo novo.', criadoEm: '2026-10-15T15:00:00Z' } })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /topicos/${T}/resumos`]: [200, [{ conteudo: 'Resumo novo.', criadoEm: '2026-10-15T15:00:00Z' }, { conteudo: 'Resumo antigo.', criadoEm: '2026-10-14T15:00:00Z' }]],
  })
  render(<App />)
  expect(await screen.findByText('Resumo novo.')).toBeTruthy()
  expect(screen.getAllByText(/Gerado em 1[45]\/10\/2026/).length).toBeGreaterThan(0)
  const anteriores = screen.getByText(/Resumos anteriores \(1\) · guardamos os 10 mais recentes/)
  expect(anteriores.closest('details')!.textContent).toContain('Resumo antigo.')
})

test('sem histórico de resumos (falha do endpoint) a tela continua funcionando', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ resumo: { conteudo: 'Só este.', criadoEm: '2026-10-15T15:00:00Z' } })], [`GET /topicos/${T}/questoes`]: [200, []] })
  render(<App />)
  expect(await screen.findByText('Só este.')).toBeTruthy()
  expect(screen.queryByText(/Resumos anteriores/)).toBeNull()
})

test('início: materiais e tópicos livres numa só lista, do mais novo ao mais antigo, com o tipo de cada um', async () => {
  window.history.replaceState(null, '', '/')
  const rotas = { ...base, 'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }]] as [number, unknown] }
  servidor({
    ...rotas,
    'GET /materiais': [200, [{ id: 'm1', nomeArquivo: 'antigo.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-10T10:00:00Z' }]],
    'GET /topicos/livres': [200, [{ id: T, nome: 'Livre novo', criadoEm: '2026-10-15T10:00:00Z', totalQuestoes: 0, temResumo: false }]],
  })
  const { unmount } = render(<App />)
  const lista = await screen.findByRole('list', { name: 'Estudos' })
  const itens = Array.from(lista.querySelectorAll(':scope > li')).map((li) => li.textContent ?? '')
  expect(itens).toHaveLength(2)
  expect(itens[0]).toContain('Livre novo'); expect(itens[0]).toContain('Tópico livre')
  expect(itens[1]).toContain('antigo.pdf'); expect(itens[1]).toContain('Material')
  expect(screen.queryByText(/ainda não tem nenhum estudo/)).toBeNull()
  unmount(); cleanup()
  servidor({ ...rotas, 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []] })
  render(<App />)
  expect(await screen.findByText(/ainda não tem nenhum estudo/)).toBeTruthy()
})

test('id inexistente ou de outra conta: mensagem de não encontrado com caminho de volta, sem "Tentar de novo"', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [404, { mensagem: 'Tópico não encontrado.', codigo: 'nao_encontrado' }], [`GET /topicos/${T}/questoes`]: [404, { mensagem: 'x' }] })
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Este tópico não existe ou não é da sua conta')
  expect(screen.queryByRole('button', { name: 'Tentar de novo' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Voltar ao início' }))
  await waitFor(() => expect(window.location.pathname).toBe('/'))
})

test('material inexistente: não encontrado com volta ao início; erro de servidor continua oferecendo "Tentar de novo"', async () => {
  const MAT = '11111111-1111-1111-1111-111111111111'
  window.history.replaceState(null, '', `/materiais/${MAT}`)
  servidor({ ...base, [`GET /materiais/${MAT}`]: [404, { mensagem: 'Material não encontrado.' }] })
  const { unmount } = render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Este material não existe ou não é da sua conta')
  expect(screen.queryByRole('button', { name: 'Tentar de novo' })).toBeNull()
  unmount(); cleanup()
  servidor({ ...base, [`GET /materiais/${MAT}`]: [500, { mensagem: 'x' }] })
  render(<App />)
  expect((await screen.findByRole('alert')).textContent).toContain('Não foi possível abrir o material')
  expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeTruthy()
})

test('início: "Gerando…" nos estudos com geração em andamento, "Enviar novamente" no material que falhou e aviso de lista cortada', async () => {
  window.history.replaceState(null, '', '/')
  const muitos = Array.from({ length: 100 }, (_, i) => ({ id: `t${i}`, nome: `Livre ${i}`, criadoEm: '2026-10-15T10:00:00Z', totalQuestoes: 0, temResumo: false, gerando: i === 0 }))
  servidor({
    ...base,
    'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }]],
    'GET /materiais': [200, [
      { id: 'm1', nomeArquivo: 'ruim.pdf', status: 'falhou', motivoFalha: 'sem_texto', paginas: 2, criadoEm: '2026-10-14T10:00:00Z', gerando: false },
      { id: 'm2', nomeArquivo: 'bom.pdf', status: 'pronto', motivoFalha: null, paginas: 5, criadoEm: '2026-10-13T10:00:00Z', gerando: true },
    ]],
    'GET /topicos/livres': [200, muitos],
  })
  render(<App />)
  await screen.findByRole('list', { name: 'Estudos' })
  expect(screen.getAllByText('Gerando…')).toHaveLength(2) // um tópico livre e um material
  expect(screen.getByText('Mostrando os 100 tópicos livres mais recentes.')).toBeTruthy()
  expect(screen.queryByText(/materiais mais recentes/)).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Enviar novamente: ruim.pdf' }))
  await waitFor(() => expect(window.location.pathname).toBe('/novo-estudo'))
})

test('envio de arquivo: botão próprio em português e nome do arquivo escolhido', async () => {
  window.history.replaceState(null, '', '/novo-estudo')
  servidor({ ...base, 'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }]] })
  render(<App />)
  expect(await screen.findByText('Escolher arquivo')).toBeTruthy()
  expect(screen.getByText('Nenhum arquivo escolhido')).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Arquivo'), { target: { files: [new File([new Uint8Array(5)], 'apostila.pdf')] } })
  expect(screen.getByText(/apostila\.pdf · /)).toBeTruthy()
  expect(screen.queryByText('Nenhum arquivo escolhido')).toBeNull()
})

test('as listas do início pedem o mesmo limite que a tela usa no aviso de lista cortada', async () => {
  window.history.replaceState(null, '', '/')
  const chamadas = servidor({ ...base, 'GET /assuntos': [200, []], 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []] })
  render(<App />)
  await screen.findByText(/ainda não tem nenhum estudo/)
  expect(chamadas.find((c) => c.chave === 'GET /materiais')!.url).toContain('limite=100')
  expect(chamadas.find((c) => c.chave === 'GET /topicos/livres')!.url).toContain('limite=100')
})

test('login recusado: o foco volta ao botão (o navegador o joga no body ao desabilitar) e o alerta continua', async () => {
  localStorage.clear()
  window.history.replaceState(null, '', '/entrar')
  servidor({ 'POST /auth/login': [401, { mensagem: 'E-mail ou senha incorretos.', codigo: 'credenciais_invalidas' }] })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('E-mail'), { target: { value: 'ana@exemplo.com' } })
  fireEvent.change(screen.getByLabelText('Senha', { exact: true }), { target: { value: 'senha-forte-1' } })
  const botao = screen.getByRole('button', { name: 'Entrar' }) as HTMLButtonElement
  botao.focus()
  fireEvent.click(botao)
  botao.blur() // simula o navegador: um botão desabilitado perde o foco
  expect((await screen.findByRole('alert')).textContent).toContain('E-mail ou senha incorretos')
  await waitFor(() => expect(document.activeElement).toBe(botao))
})

test('perfil: índice de seções leva a cada seção do perfil', async () => {
  window.history.replaceState(null, '', '/perfil')
  servidor({ ...base, 'GET /perfil': [200, { nome: 'Ana', email: 'ana@exemplo.com' }], 'GET /perfil/sessoes': [200, []] })
  render(<App />)
  const indice = await screen.findByRole('navigation', { name: 'Seções do perfil' })
  const links = Array.from(indice.querySelectorAll('a'))
  expect(links.map((a) => a.textContent)).toEqual(['Dados pessoais', 'E-mail', 'Senha', 'Sessões', 'Excluir conta'])
  await screen.findByRole('heading', { name: 'Sessões ativas' })
  for (const a of links) expect(document.querySelector(a.getAttribute('href')!), a.textContent ?? '').toBeTruthy() // cada âncora existe
})

test('pedido que demora para ser aceito avisa que o serviço de IA está lento, e o aviso some quando responde', async () => {
  config.avisoDemoraMs = 20
  let liberar: () => void = () => {}
  const segura = new Promise<void>((r) => { liberar = r })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '').split('?')[0]}`
    if (chave === `POST /topicos/${T}/resumo`) { await segura; return { ok: false, status: 503, json: async () => ({ mensagem: 'O modelo de linguagem não respondeu. Tente novamente em instantes.', codigo: 'ia_indisponivel' }) } }
    const rotas: Record<string, unknown> = { 'GET /uso': uso, 'POST /auth/refresh': { token: 't', nome: 'Ana' }, [`GET /topicos/${T}`]: detalhe(), [`GET /topicos/${T}/questoes`]: [] }
    return { ok: chave in rotas, status: chave in rotas ? 200 : 404, json: async () => rotas[chave] }
  }))
  render(<App />)
  await screen.findByRole('heading', { name: 'Células' })
  clicar('Gerar resumo')
  expect((await screen.findByText(/O serviço de IA está demorando para responder/)).getAttribute('role')).toBe('note')
  expect(screen.queryByRole('button', { name: /Cancelar geração/ })).toBeNull() // ainda não há operação para cancelar
  liberar()
  expect((await screen.findByRole('alert')).textContent).toContain('O modelo de linguagem não respondeu')
  expect(screen.queryByText(/O serviço de IA está demorando/)).toBeNull()
})

test('material que falhou por configuração do serviço de IA avisa que reenviar não resolve', async () => {
  window.history.replaceState(null, '', '/')
  servidor({
    ...base,
    'GET /assuntos': [200, []],
    'GET /materiais': [200, [{ id: 'm1', nomeArquivo: 'a.pdf', status: 'falhou', motivoFalha: 'configuracao_ia', paginas: 2, criadoEm: '2026-10-15T10:00:00Z', gerando: false }]],
    'GET /topicos/livres': [200, []],
  })
  render(<App />)
  expect(await screen.findByText(/Reenviar o arquivo não resolve/)).toBeTruthy()
})

test('avisos de que o conteúdo vai a um serviço externo de IA: cadastro, envio, tópico livre, revisão e tópico', async () => {
  const externo = /serviço externo de inteligência artificial/
  // cadastro
  localStorage.clear()
  window.history.replaceState(null, '', '/criar-conta')
  servidor({})
  const a = render(<App />)
  expect((await screen.findByText(/Ao usar o Nexo, o conteúdo que você enviar é processado por um serviço externo/)).textContent).toMatch(/Envie apenas material que você pode compartilhar/)
  a.unmount(); cleanup()
  // login não precisa do aviso (nada é enviado ainda)
  window.history.replaceState(null, '', '/entrar')
  servidor({})
  const b = render(<App />)
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(screen.queryByText(externo)).toBeNull()
  b.unmount(); cleanup()
  // envio de material e tópico livre
  localStorage.setItem('nexo-sessao', '1')
  window.history.replaceState(null, '', '/novo-estudo')
  servidor({ ...base, 'GET /assuntos': [200, []] })
  const c = render(<App />)
  expect(await screen.findByText(/O texto do material é enviado a um serviço externo/)).toBeTruthy()
  expect(screen.getByText(/O nome do tópico é enviado a um serviço externo/)).toBeTruthy()
  c.unmount(); cleanup()
  // tópico livre e tópico de material
  window.history.replaceState(null, '', `/topicos/${T}`)
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [] })], [`GET /topicos/${T}/questoes`]: [200, []] })
  const d = render(<App />)
  expect(await screen.findByText(/O nome do tópico é enviado a um serviço externo/)).toBeTruthy()
  d.unmount(); cleanup()
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []] })
  render(<App />)
  expect(await screen.findByText(/Os trechos usados são enviados a um serviço externo/)).toBeTruthy()
})

test('revisão de tópicos avisa que o início de cada trecho vai ao serviço externo ao usar a IA', async () => {
  const MAT = '11111111-1111-1111-1111-111111111111'
  window.history.replaceState(null, '', `/materiais/${MAT}`)
  servidor({
    ...base,
    [`GET /materiais/${MAT}`]: [200, { id: MAT, nomeArquivo: 'bio.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' }],
    [`GET /materiais/${MAT}/topicos`]: [200, [{ id: T, assuntoId: 'g', nome: 'Células', ordem: 1, inicio: 1, fim: 5, previa: 'x', temConteudo: false }]],
    [`GET /materiais/${MAT}/paginas`]: [200, [1, 1, 2, 2, 3]],
  })
  render(<App />)
  expect(await screen.findByText(/O início de cada trecho é enviado a um serviço externo de inteligência artificial/)).toBeTruthy()
})
