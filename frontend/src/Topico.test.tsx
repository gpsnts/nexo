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

const uso = { creditosUsados: 39, creditosLimite: 300, percentual: 13, renovaEm: '2026-10-19T03:00:00Z', operacoes: 4, limiteOperacoes: 30, janelaOperacoesMinutos: 60 }
const detalhe = (extra = {}) => ({
  id: T, assuntoId: 'g', nome: 'Células', livre: false, fontes: [{ materialId: 'm', nomeArquivo: 'bio.pdf', inicio: 1, fim: 5 }],
  resumo: null, totalQuestoes: 0, maximoQuestoes: 50, maximoResumos: 10, geracaoAberta: null, ultimaGeracao: null, orientacao: null, ...extra,
})
const geracao = (extra = {}) => ({ id: REG, operacao: 'questoes', status: 'em_andamento', progresso: 40, aprovadas: 0, rejeitadas: 0, repetidas: 0, gravadas: 0, taxaRejeicao: null, mensagem: null, verificacaoId: null, motivo: null, ...extra })
const base = { 'GET /uso': [200, uso] as [number, unknown], [`GET /topicos/${T}/chat`]: [200, []] as [number, unknown], 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] as [number, unknown] }
const questao = { id: 'q1', enunciado: 'O que produz ATP?', alternativas: ['Núcleo', 'Mitocôndria', 'Ribossomo', 'Lisossomo'], dificuldade: 'media', respostaCorreta: null, respondida: false, ultimaCorreta: null, proximaRevisao: null, criadoEm: '2026-10-15T10:00:00Z', apoio: [{ nomeArquivo: 'bio.pdf', ordem: 2, numeroPagina: 3 }, { nomeArquivo: 'bio.pdf', ordem: 3, numeroPagina: 4 }] }

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
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/questoes`)!.corpo).toEqual({ quantidade: 3, dificuldade: 'media' })
  const resultado = await screen.findByText(/1 questão gravada de 3 pedidas/)
  expect(resultado.closest('[role=status]')!.textContent).toContain('taxa de rejeição 33%')
  expect(resultado.closest('[role=status]')!.textContent).toContain('1 repetida descartada')
  expect(await screen.findByText('O que produz ATP?')).toBeTruthy()
  expect(screen.getByText(/B\) Mitocôndria/).closest('li')!.textContent).not.toContain('(correta)')
  expect(screen.getByText('Fonte: bio.pdf, págs. 3 e 4')).toBeTruthy()
  expect(screen.getByText(/gabarito de cada questão aparece depois/)).toBeTruthy()
})

test('responde uma questão: gabarito só depois de confirmar, e o resultado volta ao tópico', async () => {
  let respondida = false
  const chamadas = servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })],
    [`GET /topicos/${T}/questoes`]: () => [200, [respondida ? { ...questao, respostaCorreta: 1, respondida: true, ultimaCorreta: false, proximaRevisao: '2099-03-10' } : questao]],
    'POST /questoes/q1/respostas': () => { respondida = true; return [200, { correta: false, respostaCorreta: 1, proximaRevisao: '2099-03-10' }] },
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  expect(await screen.findByRole('heading', { name: /O que produz ATP\?/ })).toBeTruthy()
  expect(screen.queryByText(/\(correta\)/)).toBeNull()
  expect(screen.getByRole('button', { name: 'Confirmar resposta' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: /Núcleo/ }))  // seleciona; só grava ao confirmar
  expect(chamadas.some((c) => c.chave === 'POST /questoes/q1/respostas')).toBe(false)
  clicar('Confirmar resposta')
  expect(await screen.findByText(/Não foi dessa vez\. A resposta certa é a alternativa B/)).toBeTruthy()
  expect(chamadas.find((c) => c.chave === 'POST /questoes/q1/respostas')!.corpo).toEqual({ alternativa: 0 })
  expect(screen.getByText(/volta para revisão em 10\/03\/2099/)).toBeTruthy()
  expect(screen.getByRole('button', { name: /Núcleo.*sua resposta, incorreta/ })).toBeTruthy()
  expect(screen.getByRole('button', { name: /Mitocôndria.*correta/ })).toBeTruthy()
  clicar('Ver resultado')
  expect(await screen.findByText(/Você acertou 0 de 1\./)).toBeTruthy()
  clicar('Voltar ao tópico')
  expect(await screen.findByText(/Última resposta: incorreta · próxima revisão em 10\/03\/2099/)).toBeTruthy()
  expect(screen.getByText(/B\) Mitocôndria/).closest('li')!.textContent).toContain('(correta)')
})

test('erro ao registrar a resposta mantém a questão e deixa tentar de novo', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [questao]], 'POST /questoes/q1/respostas': [500, { mensagem: 'Falha' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  fireEvent.click(await screen.findByRole('button', { name: /Mitocôndria/ }))
  clicar('Confirmar resposta')
  expect((await screen.findByRole('alert')).textContent).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Confirmar resposta' }).hasAttribute('disabled')).toBe(false) // a escolha continua e dá para tentar de novo
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
  expect(await screen.findByText('com resumo')).toBeTruthy(); expect(screen.getByText('2 questões')).toBeTruthy()
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

test('avisa que a ação usa créditos da cota semanal e explica que levar tempo não prende o usuário', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ geracaoAberta: REG })], [`GET /topicos/${T}/questoes`]: [200, []], [`GET /geracoes/${REG}`]: [200, geracao({ progresso: 10 })] })
  render(<App />)
  expect((await screen.findAllByText(/Usa créditos da sua cota semanal de IA/)).length).toBe(2)   // resumo e questões
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

const pastas = [{ id: 'g', nome: 'Geral', padrao: true }, { id: 'f', nome: 'Física', padrao: false }, { id: 'h', nome: 'História', padrao: false }]
const inicio = (extra: Record<string, [number, unknown?] | ((c: any) => [number, unknown?])> = {}) => {
  window.history.replaceState(null, '', '/')
  return servidor({
    ...base, 'GET /assuntos': [200, pastas],
    'GET /materiais': [200, [{ id: 'm1', nomeArquivo: 'ww2.pdf', titulo: 'Segunda Guerra', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-16T10:00:00Z', assuntoId: 'h' },
      { id: 'm2', nomeArquivo: 'termo.pdf', titulo: null, status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z', assuntoId: 'f' }]],
    'GET /topicos/livres': [200, [{ id: 't1', nome: 'Óptica', criadoEm: '2026-10-14T10:00:00Z', totalQuestoes: 0, temResumo: false, assuntoId: 'g' }]], ...extra,
  })
}

test('pastas na tela inicial: filtro por pasta, nome do estudo no lugar do arquivo e "Geral" explicada', async () => {
  inicio()
  render(<App />)
  const filtros = await screen.findByRole('list', { name: 'Filtrar por pasta' })
  expect(filtros.textContent).toContain('Todos'); expect(filtros.textContent).toContain('Física'); expect(filtros.textContent).toContain('História')
  expect(screen.getByText(/"Geral" guarda os estudos que não estão em nenhuma pasta/)).toBeTruthy()
  expect(await screen.findByRole('button', { name: 'Segunda Guerra' })).toBeTruthy()   // o nome do estudo, não o do arquivo
  expect(screen.getByText(/ww2\.pdf/)).toBeTruthy()                                      // o arquivo continua visível
  expect(screen.getByRole('button', { name: 'termo.pdf' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'História' }))
  expect(screen.getByRole('button', { name: 'Segunda Guerra' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'termo.pdf' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Óptica' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Geral' }))
  expect(screen.getByRole('button', { name: 'Óptica' })).toBeTruthy(); expect(screen.queryByRole('button', { name: 'Segunda Guerra' })).toBeNull()
})

test('pastas: criar, renomear e excluir com confirmação', async () => {
  const chamadas = inicio({
    'POST /assuntos': [201, { id: 'n', nome: 'Química', padrao: false }],
    'PUT /assuntos/f': [200, { id: 'f', nome: 'Física Moderna', padrao: false }],
    'DELETE /assuntos/f': [204],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '+ Nova pasta' }))
  fireEvent.change(screen.getByLabelText('Nome da nova pasta'), { target: { value: ' Química ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Criar pasta' }))
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'POST /assuntos')!.corpo).toEqual({ nome: 'Química' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Física' }))
  expect(screen.queryByRole('button', { name: /Renomear a pasta Geral/ })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Renomear a pasta Física' }))
  fireEvent.change(screen.getByLabelText('Novo nome da pasta'), { target: { value: 'Física Moderna' } })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'PUT /assuntos/f')!.corpo).toEqual({ nome: 'Física Moderna' }))
  fireEvent.click(await screen.findByRole('button', { name: /Excluir a pasta/ }))
  expect(screen.getByText(/voltam para "Geral"/)).toBeTruthy()
  expect(chamadas.some((c) => c.chave === 'DELETE /assuntos/f')).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Excluir pasta' }))
  await waitFor(() => expect(chamadas.some((c) => c.chave === 'DELETE /assuntos/f')).toBe(true))
})

test('pasta: erro do servidor aparece e nome vazio é recusado antes de enviar', async () => {
  const chamadas = inicio({ 'POST /assuntos': [409, { mensagem: 'Já existe uma pasta com esse nome. Use a que já existe.', codigo: 'assunto_duplicado' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '+ Nova pasta' }))
  fireEvent.click(screen.getByRole('button', { name: 'Criar pasta' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Informe o nome da pasta')
  expect(chamadas.some((c) => c.chave === 'POST /assuntos')).toBe(false)
  fireEvent.change(screen.getByLabelText('Nome da nova pasta'), { target: { value: 'Física' } })
  fireEvent.click(screen.getByRole('button', { name: 'Criar pasta' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Já existe uma pasta')
})

test('tópico livre troca de pasta pela própria página', async () => {
  const chamadas = servidor({ ...base, 'GET /assuntos': [200, pastas], [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [], assuntoId: 'g' })], [`GET /topicos/${T}/questoes`]: [200, []], [`PUT /topicos/${T}/assunto`]: [200, { id: 'f', nome: 'Física', padrao: false }] })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Pasta'), { target: { value: 'f' } })
  await waitFor(() => expect(chamadas.find((c) => c.chave === `PUT /topicos/${T}/assunto`)!.corpo).toEqual({ assuntoId: 'f' }))
})

const MID = '44444444-4444-4444-4444-444444444444'
const estudoPronto = (extra = {}) => ({ id: MID, nomeArquivo: 'ww2.pdf', titulo: null, status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-16T10:00:00Z', assuntoId: 'g', ...extra })

test('estudo: renomear à mão, sugerir nome com a IA e voltar ao nome do arquivo', async () => {
  window.history.replaceState(null, '', `/materiais/${MID}`)
  let atual: any = estudoPronto()
  const chamadas = servidor({
    ...base, 'GET /assuntos': [200, pastas], [`GET /materiais/${MID}`]: () => [200, atual], [`GET /materiais/${MID}/topicos`]: [200, []], [`GET /materiais/${MID}/paginas`]: [200, []],
    [`POST /materiais/${MID}/sugerir-nome`]: [200, { nome: 'Segunda Guerra Mundial' }],
    [`PUT /materiais/${MID}`]: (c) => { atual = estudoPronto({ titulo: c.titulo || null }); return [200, atual] },
  })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'ww2.pdf' })).toBeTruthy()
  fireEvent.click(await screen.findByRole('button', { name: 'Renomear estudo' }))
  expect((screen.getByLabelText('Nome do estudo') as HTMLInputElement).value).toBe('ww2.pdf')
  fireEvent.click(screen.getByRole('button', { name: 'Sugerir nome com IA' }))
  await waitFor(() => expect((screen.getByLabelText('Nome do estudo') as HTMLInputElement).value).toBe('Segunda Guerra Mundial'))
  expect(chamadas.some((c) => c.chave === `PUT /materiais/${MID}`)).toBe(false) // a sugestão só vale se o estudante salvar
  fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
  expect(await screen.findByRole('heading', { name: 'Segunda Guerra Mundial' })).toBeTruthy()
  expect(screen.getByText('Arquivo: ww2.pdf')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Renomear estudo' }))
  fireEvent.change(screen.getByLabelText('Nome do estudo'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
  expect(await screen.findByRole('heading', { name: 'ww2.pdf' })).toBeTruthy()
})

test('estudo: falha ao sugerir mostra o erro e mantém o texto; mover de pasta avisa', async () => {
  window.history.replaceState(null, '', `/materiais/${MID}`)
  const chamadas = servidor({
    ...base, 'GET /assuntos': [200, pastas], [`GET /materiais/${MID}`]: [200, estudoPronto()], [`GET /materiais/${MID}/topicos`]: [200, []], [`GET /materiais/${MID}/paginas`]: [200, []],
    [`POST /materiais/${MID}/sugerir-nome`]: [503, { mensagem: 'O modelo de linguagem não respondeu.', codigo: 'ia_indisponivel' }], [`PUT /materiais/${MID}/assunto`]: [200, pastas[2]],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Renomear estudo' }))
  fireEvent.change(screen.getByLabelText('Nome do estudo'), { target: { value: 'Meu nome' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sugerir nome com IA' }))
  expect((await screen.findByRole('alert')).textContent).toContain('não respondeu')
  expect((screen.getByLabelText('Nome do estudo') as HTMLInputElement).value).toBe('Meu nome')
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
  fireEvent.change(await screen.findByLabelText('Pasta'), { target: { value: 'h' } })
  expect(await screen.findByText('Estudo movido.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `PUT /materiais/${MID}/assunto`)!.corpo).toEqual({ assuntoId: 'h' })
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
  expect(screen.queryByText(/Comece pelo seu primeiro estudo/)).toBeNull()
  unmount(); cleanup()
  servidor({ ...rotas, 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []] })
  render(<App />)
  expect(await screen.findByText(/Comece pelo seu primeiro estudo/)).toBeTruthy()
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
  await screen.findByText(/Comece pelo seu primeiro estudo/)
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
    const rotas: Record<string, unknown> = { 'GET /uso': uso, 'POST /auth/refresh': { token: 't', nome: 'Ana' }, [`GET /topicos/${T}`]: detalhe(), [`GET /topicos/${T}/questoes`]: [], [`GET /topicos/${T}/chat`]: [] }
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

test('questão respondida com revisão vencida avisa que está pendente', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [{ ...questao, respostaCorreta: 1, respondida: true, ultimaCorreta: true, proximaRevisao: '2026-03-10' }]] })
  render(<App />)
  expect(await screen.findByText(/revisão pendente desde 10\/03\/2026/)).toBeTruthy()
})

const dica = { ...questao, respostaCorreta: null }

test('dica: recolhida por padrão, só gera ao clicar, mostra o texto e esconde de novo', async () => {
  const chamadas = servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [dica]], 'POST /questoes/q1/dica': [200, { dica: 'Pense na usina de energia da célula.' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  const botao = await screen.findByRole('button', { name: 'Ver dica' })
  expect(botao.getAttribute('aria-expanded')).toBe('false')
  expect(chamadas.some((c) => c.chave === 'POST /questoes/q1/dica')).toBe(false)
  fireEvent.click(botao)
  expect(await screen.findByText('Pense na usina de energia da célula.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Esconder dica' }).getAttribute('aria-expanded')).toBe('true')
  fireEvent.click(screen.getByRole('button', { name: 'Esconder dica' }))
  expect(screen.queryByText('Pense na usina de energia da célula.')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Ver dica' }))  // reabrir não chama de novo
  expect(await screen.findByText('Pense na usina de energia da célula.')).toBeTruthy()
  expect(chamadas.filter((c) => c.chave === 'POST /questoes/q1/dica').length).toBe(1)
})

test('dica já guardada aparece sem chamar o servidor; falha mostra erro e permite tentar de novo', async () => {
  let n = 0
  const chamadas = servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [{ ...dica, dica: 'Dica pronta.' }]] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Ver dica' }))
  expect(await screen.findByText('Dica pronta.')).toBeTruthy()
  expect(chamadas.some((c) => c.chave.includes('/dica'))).toBe(false)
  cleanup(); vi.unstubAllGlobals()
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [dica]], 'POST /questoes/q1/dica': () => (++n === 1 ? [503, { mensagem: 'O modelo de linguagem não respondeu.' }] : [200, { dica: 'Agora foi.' }]) })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Ver dica' }))
  expect((await screen.findByRole('alert')).textContent).toContain('não respondeu')
  fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
  expect(await screen.findByText('Agora foi.')).toBeTruthy()
})

test('a dica some depois de confirmar a resposta', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [dica]], 'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 1, proximaRevisao: '2099-01-01' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  fireEvent.click(await screen.findByRole('button', { name: /Mitocôndria/ })); clicar('Confirmar resposta')
  expect(await screen.findByText('Correto!')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Ver dica' })).toBeNull()
})

const msg = (id: string, papel: string, conteudo: string) => ({ id, papel, conteudo, criadoEm: '2026-10-16T10:00:00Z' })

test('chat: mostra o histórico salvo, envia a pergunta e acrescenta a resposta', async () => {
  const chamadas = servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /topicos/${T}/chat`]: [200, [msg('1', 'usuario', 'O que é célula?'), msg('2', 'assistente', 'A unidade básica da vida.')]],
    [`POST /topicos/${T}/chat`]: [200, [msg('3', 'usuario', 'E o núcleo?'), msg('4', 'assistente', 'Guarda o DNA.')]],
  })
  render(<App />)
  expect(await screen.findByText('A unidade básica da vida.')).toBeTruthy()
  const caixa = screen.getByLabelText('Sua pergunta') as HTMLTextAreaElement
  expect(screen.getByRole('button', { name: 'Enviar' }).hasAttribute('disabled')).toBe(true)
  fireEvent.change(caixa, { target: { value: '  E o núcleo?  ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
  expect(await screen.findByText('Guarda o DNA.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/chat`)!.corpo).toEqual({ mensagem: 'E o núcleo?' })
  expect(screen.getByText('E o núcleo?')).toBeTruthy()
  expect(caixa.value).toBe('')
})

test('chat: Enter envia, Shift+Enter não; falha devolve o texto e mostra o erro', async () => {
  const chamadas = servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []], [`POST /topicos/${T}/chat`]: [429, { mensagem: 'Você atingiu o limite de 30 operações com IA por hora.', codigo: 'limite_operacoes' }] })
  render(<App />)
  const caixa = (await screen.findByLabelText('Sua pergunta')) as HTMLTextAreaElement
  fireEvent.change(caixa, { target: { value: 'Por quê?' } })
  fireEvent.keyDown(caixa, { key: 'Enter', shiftKey: true })
  expect(chamadas.some((c) => c.chave === `POST /topicos/${T}/chat`)).toBe(false)
  fireEvent.keyDown(caixa, { key: 'Enter' })
  expect((await screen.findByRole('alert')).textContent).toContain('limite de 30 operações')
  expect(caixa.value).toBe('Por quê?')
})

test('chat: limpar pede confirmação e apaga a conversa', async () => {
  const chamadas = servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []],
    [`GET /topicos/${T}/chat`]: [200, [msg('1', 'usuario', 'oi'), msg('2', 'assistente', 'olá')]], [`DELETE /topicos/${T}/chat`]: [204],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Limpar conversa' }))
  expect(chamadas.some((c) => c.chave === `DELETE /topicos/${T}/chat`)).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Apagar conversa' }))
  expect(await screen.findByText(/Tire dúvidas sobre o conteúdo/)).toBeTruthy()
  expect(screen.queryByText('olá')).toBeNull()
})

test('chat: falha ao carregar o histórico oferece tentar de novo', async () => {
  let n = 0
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []], [`GET /topicos/${T}/chat`]: () => (++n === 1 ? [500, {}] : [200, [msg('1', 'usuario', 'oi')]]) })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Tentar de novo' }))
  expect(await screen.findByText('oi')).toBeTruthy()
})

test('"Baseado em" e "Voltar ao material" usam o nome do estudo, não o do arquivo', async () => {
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ fontes: [{ materialId: 'm', nomeArquivo: 'apostila_final.docx', titulo: 'Catalogação 1', inicio: 4, fim: 5 }] })], [`GET /topicos/${T}/questoes`]: [200, []] })
  render(<App />)
  expect(await screen.findByRole('button', { name: /Voltar ao material "Catalogação 1"/ })).toBeTruthy()
  expect(screen.getByText(/Baseado em Catalogação 1 \(trechos 4 a 5\)/)).toBeTruthy()
  expect(screen.queryByText(/apostila_final\.docx/)).toBeNull()
})

test('"Gerar novas questões": do cartão do questionário, do resultado e da URL ?gerar=1 levam o cursor ao campo de quantidade', async () => {
  window.history.replaceState(null, '', `/topicos/${T}?gerar=1`)
  servidor({ ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [questao]], 'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 1, proximaRevisao: '2099-01-01' }] })
  render(<App />)
  const campo = await screen.findByLabelText('Quantas questões gerar')
  await waitFor(() => expect(document.activeElement).toBe(campo))   // veio da URL
  expect(window.location.search).toBe('')                              // o aviso vale uma vez
  ;(document.activeElement as HTMLElement).blur()
  fireEvent.click(screen.getByRole('button', { name: 'Gerar novas questões' }))  // do cartão do questionário
  await waitFor(() => expect(document.activeElement).toBe(campo))
  ;(document.activeElement as HTMLElement).blur()
  fireEvent.click(screen.getByRole('button', { name: 'Começar questionário' }))
  fireEvent.click(await screen.findByRole('button', { name: /Mitocôndria/ })); clicar('Confirmar resposta')
  fireEvent.click(await screen.findByRole('button', { name: 'Ver resultado' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Gerar novas questões' }))                          // do resultado do questionário
  await waitFor(() => expect(document.activeElement?.id).toBe('quantidade'))
})

test('dificuldade: média por padrão, a escolhida vai no pedido e o nível aparece na questão e no questionário', async () => {
  const chamadas = servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })],
    [`GET /topicos/${T}/questoes`]: [200, [{ ...questao, dificuldade: 'dificil' }]],
    [`POST /topicos/${T}/questoes`]: [202, { registroId: REG, verificacaoId: 'v' }], [`GET /geracoes/${REG}`]: [200, geracao({ status: 'concluida', progresso: 100, gravadas: 3 })],
  })
  render(<App />)
  expect((await screen.findByLabelText('Média') as HTMLInputElement).checked).toBe(true)
  expect(screen.getByText(/Compreender e aplicar/)).toBeTruthy()
  fireEvent.click(screen.getByLabelText('Difícil'))
  expect(screen.getByText(/alternativas bem parecidas/)).toBeTruthy()
  fireEvent.click(screen.getByLabelText('Fácil'))
  fireEvent.click(screen.getByLabelText('Difícil'))
  fireEvent.change(screen.getByLabelText('Quantas questões gerar'), { target: { value: '3' } })
  clicar('Gerar questões')
  await waitFor(() => expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/questoes`)!.corpo).toEqual({ quantidade: 3, dificuldade: 'dificil' }))
  expect(screen.getAllByText('Difícil', { selector: '.nivel' }).length).toBeGreaterThan(0)   // na lista do banco de questões
  fireEvent.click(screen.getByRole('button', { name: 'Começar questionário' }))
  expect((await screen.findAllByText('Difícil', { selector: '.nivel' })).length).toBeGreaterThan(0)  // no cabeçalho do questionário
})

test('estudo em "Geral": o seletor de pasta mostra Geral mesmo com outra pasta antes na lista', async () => {
  const MID2 = '55555555-5555-5555-5555-555555555555'
  window.history.replaceState(null, '', `/materiais/${MID2}`)
  servidor({
    ...base, 'GET /assuntos': [200, [{ id: 'f', nome: 'Física', padrao: false }, { id: 'g', nome: 'Geral', padrao: true }]],   // a ordem da API não é garantida
    [`GET /materiais/${MID2}`]: [200, { id: MID2, nomeArquivo: 'a.pdf', titulo: null, status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-16T10:00:00Z', assuntoId: 'g' }],
    [`GET /materiais/${MID2}/topicos`]: [200, []], [`GET /materiais/${MID2}/paginas`]: [200, []],
  })
  render(<App />)
  const seletor = (await screen.findByLabelText('Pasta')) as HTMLSelectElement
  await waitFor(() => expect(seletor.options.length).toBe(2))
  expect(seletor.value).toBe('g')
  expect(seletor.selectedOptions[0].textContent).toBe('Geral (sem pasta)')
})

test('questionário: Enter só confirma quando o foco não está em um controle (dica, outra alternativa) e confirma na escolhida ou fora de controles', async () => {
  const chamadas = servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [{ ...questao, dificuldade: 'media' }]],
    'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 1, proximaRevisao: '2099-01-01' }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  const respostas = () => chamadas.filter((c) => c.chave === 'POST /questoes/q1/respostas').length
  const nucleo = await screen.findByRole('button', { name: /Núcleo/ }); const mito = screen.getByRole('button', { name: /Mitocôndria/ })
  fireEvent.click(nucleo)                                   // escolhe A
  expect(fireEvent.keyDown(screen.getByRole('button', { name: 'Ver dica' }), { key: 'Enter' })).toBe(true)  // Enter na dica: não é cancelado (abre a dica)
  expect(fireEvent.keyDown(mito, { key: 'Enter' })).toBe(true)                                              // Enter em outra alternativa: deixa escolher aquela
  expect(respostas()).toBe(0)
  fireEvent.click(mito); fireEvent.keyDown(document.body, { key: 'Enter' })                                 // fora de controles: confirma a escolhida (B)
  await waitFor(() => expect(respostas()).toBe(1))
  expect(chamadas.find((c) => c.chave === 'POST /questoes/q1/respostas')!.corpo).toEqual({ alternativa: 1 })
})

test('questionário: Enter na própria alternativa escolhida confirma', async () => {
  const chamadas = servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })], [`GET /topicos/${T}/questoes`]: [200, [questao]],
    'POST /questoes/q1/respostas': [200, { correta: false, respostaCorreta: 1, proximaRevisao: '2099-01-01' }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))
  const nucleo = await screen.findByRole('button', { name: /Núcleo/ })
  fireEvent.click(nucleo); fireEvent.keyDown(nucleo, { key: 'Enter' })
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'POST /questoes/q1/respostas')!.corpo).toEqual({ alternativa: 0 }))
})

test('questionário: uma geração que termina no meio da sessão não troca as questões de lugar', async () => {
  let consultas = 0
  const nova = { ...questao, id: 'q2', enunciado: 'Pergunta nova gerada agora?', respondida: false }
  servidor({
    ...base, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 1 })],
    [`GET /topicos/${T}/questoes`]: () => [200, consultas >= 3 ? [nova, questao] : [questao]],   // depois que a geração termina a lista tem 2 questões
    [`POST /topicos/${T}/questoes`]: [202, { registroId: REG, verificacaoId: 'v' }],
    [`GET /geracoes/${REG}`]: () => { consultas++; return consultas >= 3 ? [200, geracao({ status: 'concluida', progresso: 100, gravadas: 1 })] : [200, geracao({ progresso: 30 })] },
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Gerar questões' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Começar questionário' }))   // começa com a geração ainda em andamento
  expect(await screen.findByRole('heading', { name: /O que produz ATP\?/ })).toBeTruthy()
  await waitFor(() => expect(consultas).toBeGreaterThanOrEqual(3))
  await new Promise((r) => setTimeout(r, 60))                                             // dá tempo de o polling terminar e recarregar a lista
  expect(screen.getByRole('heading', { name: /O que produz ATP\?/ })).toBeTruthy()       // continua a mesma questão
  expect(screen.queryByText(/Pergunta nova gerada agora/)).toBeNull()
  expect(screen.getByText('1 / 1')).toBeTruthy()
})

test('orientação: vem preenchida com a padrão, só vai ao pedido quando muda e pode ser salva como padrão', async () => {
  const chamadas = servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [], orientacao: 'foque na Espanha' })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`POST /topicos/${T}/questoes`]: [202, { registroId: REG, verificacaoId: 'v' }],
    [`GET /geracoes/${REG}`]: [200, geracao()],
    [`PUT /topicos/${T}/orientacao`]: (c) => [200, { orientacao: c.orientacao || null }],
  })
  render(<App />)
  const campo = (await screen.findByLabelText('O que você quer destacar?')) as HTMLTextAreaElement
  expect(campo.value).toBe('foque na Espanha')
  clicar('Gerar questões')
  await waitFor(() => expect(chamadas.some((c) => c.chave === `POST /topicos/${T}/questoes`)).toBe(true))
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/questoes`)!.corpo).toEqual({ quantidade: 5, dificuldade: 'media' })
  fireEvent.change(campo, { target: { value: 'estilo FCC' } })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar como padrão do tópico' }))
  expect(await screen.findByText('Orientação salva como padrão deste tópico.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `PUT /topicos/${T}/orientacao`)!.corpo).toEqual({ orientacao: 'estilo FCC' })
  expect((screen.getByRole('button', { name: 'Salvar como padrão do tópico' }) as HTMLButtonElement).disabled).toBe(true)
})

test('orientação alterada e não salva vai só no pedido de geração, inclusive vazia (gerar sem orientação desta vez)', async () => {
  const chamadas = servidor({
    ...base,
    [`GET /topicos/${T}`]: [200, detalhe({ livre: true, fontes: [], orientacao: 'foque na Espanha' })],
    [`GET /topicos/${T}/questoes`]: [200, []],
    [`POST /topicos/${T}/resumo`]: [202, { registroId: REG, verificacaoId: null }],
    [`GET /geracoes/${REG}`]: [200, geracao({ operacao: 'resumo' })],
  })
  render(<App />)
  fireEvent.change(await screen.findByLabelText('O que você quer destacar?'), { target: { value: '' } })
  clicar('Gerar resumo')
  await waitFor(() => expect(chamadas.some((c) => c.chave === `POST /topicos/${T}/resumo`)).toBe(true))
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/resumo`)!.corpo).toEqual({ orientacao: '' })
})

test('mudar de pasta direto no cartão da lista inicial, para material e para tópico livre', async () => {
  const chamadas = inicio({ 'PUT /materiais/m1/assunto': [200, { id: 'f', nome: 'Física', padrao: false }], 'PUT /topicos/t1/assunto': [200, { id: 'h', nome: 'História', padrao: false }] })
  render(<App />)
  fireEvent.change(await screen.findByRole('combobox', { name: 'Pasta de Segunda Guerra' }), { target: { value: 'f' } })
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'PUT /materiais/m1/assunto')!.corpo).toEqual({ assuntoId: 'f' }))
  fireEvent.change(screen.getByRole('combobox', { name: 'Pasta de Óptica' }), { target: { value: 'h' } })
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'PUT /topicos/t1/assunto')!.corpo).toEqual({ assuntoId: 'h' }))
  expect(chamadas.filter((c) => c.chave === 'GET /materiais').length).toBeGreaterThan(1)  // a lista é recarregada depois de mover
})

test('no tópico de material o seletor de pasta move o material inteiro', async () => {
  const chamadas = servidor({
    ...base, 'GET /assuntos': [200, pastas],
    [`GET /topicos/${T}`]: [200, detalhe()], [`GET /topicos/${T}/questoes`]: [200, []],
    'PUT /materiais/m/assunto': [200, { id: 'f', nome: 'Física', padrao: false }],
  })
  render(<App />)
  fireEvent.change(await screen.findByRole('combobox', { name: 'Pasta de Células' }), { target: { value: 'f' } })
  await waitFor(() => expect(chamadas.find((c) => c.chave === 'PUT /materiais/m/assunto')!.corpo).toEqual({ assuntoId: 'f' }))
  expect(screen.getByText(/O estudo inteiro/)).toBeTruthy()
})
