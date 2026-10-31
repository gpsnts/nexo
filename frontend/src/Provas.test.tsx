import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { agruparProvas, mover, relogio, type ProvaAberta, type ProvaQuestao, type ProvaResumo } from './estudo'

type Rota = (corpo: any) => [number, unknown?]
const P = '44444444-4444-4444-4444-444444444444'
const NOVA = '55555555-5555-5555-5555-555555555555'

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
const uso = { creditosUsados: 39, creditosLimite: 300, percentual: 13, renovaEm: '2026-10-19T03:00:00Z', operacoes: 4, limiteOperacoes: 30, janelaOperacoesMinutos: 60 }
const base = { 'GET /uso': [200, uso] as [number, unknown], 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] as [number, unknown] }
const pastas = [{ id: 'g', nome: 'Geral', padrao: true }, { id: 'h', nome: 'História', padrao: false }, { id: 'f', nome: 'Física', padrao: false }]
const topicos = (nomes: string[]) => nomes.map((nome, i) => ({ id: `t${i + 1}`, nome, ordem: i + 1, livre: true, temConteudo: true, limiteDeProvas: 0, totalQuestoes: 4, materiais: [] }))

const prova = (extra: Partial<ProvaResumo> = {}): ProvaResumo => ({
  id: P, grupoId: null, status: 'gerada', quantidade: 2, respondidas: 0, acertos: 0, limiteMinutos: null, iniciadaEm: null, prazo: null, concluidaEm: null,
  criadaEm: '2026-03-02T12:00:00Z', assuntos: [{ id: 'h', nome: 'História', topicoLimiteId: 't2', topicoLimite: 'Segunda Guerra' }], ...extra,
})
const q = (n: number, extra: Partial<ProvaQuestao> = {}): ProvaQuestao => ({
  ordem: n, id: `q${n}`, topicoId: 't1', topico: 'Segunda Guerra', assunto: 'História', enunciado: `Pergunta ${n}?`, alternativas: ['a', 'b'], dica: null, dificuldade: 'media',
  respondida: false, alternativaEscolhida: null, correta: null, respostaCorreta: null, ...extra,
})
const aberta = (p: ProvaResumo, questoes: ProvaQuestao[]): ProvaAberta => ({ prova: p, agora: new Date().toISOString(), questoes })

beforeEach(() => { localStorage.setItem('nexo-sessao', '1') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })
const ir = (caminho: string) => window.history.replaceState(null, '', caminho)
const clicar = (nome: string | RegExp) => fireEvent.click(screen.getByRole('button', { name: nome }))

test('regras puras: agrupar provas do mesmo pedido, relógio e mover', () => {
  const a = prova({ id: 'a', grupoId: 'g1' }), b = prova({ id: 'b' }), c = prova({ id: 'c', grupoId: 'g1' })
  expect(agruparProvas([a, b, c]).map((g) => g.map((p) => p.id))).toEqual([['a', 'c'], ['b']])
  expect(relogio(65)).toBe('01:05'); expect(relogio(3725)).toBe('1:02:05'); expect(relogio(-3)).toBe('00:00')
  expect(mover(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a']); expect(mover(['a', 'b'], 1, 1)).toEqual(['a', 'b']); expect(mover(['a'], 0, 4)).toEqual(['a'])
})

test('lista as provas do mesmo pedido juntas e abre uma', async () => {
  ir('/provas')
  servidor({ ...base, 'GET /provas': [200, [
    prova({ id: 'a', grupoId: 'g1', assuntos: [{ id: 'h', nome: 'História', topicoLimiteId: null, topicoLimite: null }], status: 'concluida', acertos: 1 }),
    prova({ id: 'b', grupoId: 'g1', assuntos: [{ id: 'f', nome: 'Física', topicoLimiteId: null, topicoLimite: null }] }),
    prova({ id: 'c', assuntos: [{ id: 'g', nome: 'Geral', topicoLimiteId: null, topicoLimite: null }], status: 'em_andamento', respondidas: 1 }),
  ]] })
  render(<App />)
  expect(await screen.findByText(/2 provas, uma por assunto/)).toBeTruthy()
  const itens = within(screen.getByRole('list', { name: 'Provas' })).getAllByRole('listitem')
  expect(itens).toHaveLength(2)
  expect(within(itens[0]).getByText('Física')).toBeTruthy(); expect(within(itens[0]).getByText('1 de 2 certas')).toBeTruthy()
  expect(within(itens[1]).getByText('1 de 2 respondidas')).toBeTruthy()
  clicar('Ver resultado: prova de História')
  expect(window.location.pathname).toBe('/provas/a')
})

test('sem provas, orienta a montar a primeira', async () => {
  ir('/provas')
  servidor({ ...base, 'GET /provas': [200, []] })
  render(<App />)
  expect(await screen.findByText('Nenhuma prova ainda')).toBeTruthy()
})

test('monta uma prova com tópico-limite, quantidade e tempo e abre a prova criada', async () => {
  ir('/provas')
  const chamadas = servidor({
    ...base, 'GET /provas': [200, []], 'GET /assuntos': [200, pastas], 'GET /assuntos/h/topicos': [200, topicos(['Primeira Guerra', 'Segunda Guerra', 'Guerra Fria'])],
    'POST /provas': [201, { grupoId: null, provas: [prova()] }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Montar prova' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: 'História' }))
  const limite = await screen.findByLabelText('Já estudei até o tópico')
  expect(screen.getByText(/12 questões disponíveis\./)).toBeTruthy()
  expect(within(limite).getByRole('option', { name: '2. Segunda Guerra (4)' })).toBeTruthy()
  fireEvent.change(limite, { target: { value: 't2' } })
  expect(screen.getByText(/8 questões disponíveis até aí/)).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Quantidade de questões'), { target: { value: '12' } })
  fireEvent.click(screen.getByRole('checkbox', { name: 'Limitar o tempo' }))
  fireEvent.change(screen.getByLabelText('Minutos para fazer a prova'), { target: { value: '45' } })
  clicar('Gerar prova')
  await waitFor(() => expect(window.location.pathname).toBe(`/provas/${P}`))
  expect(chamadas.find((c) => c.chave === 'POST /provas')!.corpo).toEqual({ assuntos: [{ assuntoId: 'h', topicoLimiteId: 't2' }], quantidade: 12, separadas: false, limiteMinutos: 45 })
})

test('com dois assuntos oferece misturar ou separar e avisa quando são várias provas', async () => {
  ir('/provas')
  const chamadas = servidor({
    ...base, 'GET /provas': [200, []], 'GET /assuntos': [200, pastas], 'GET /assuntos/h/topicos': [200, topicos(['A'])], 'GET /assuntos/f/topicos': [200, topicos(['B'])],
    'POST /provas': [201, { grupoId: 'g1', provas: [prova({ id: 'a', grupoId: 'g1' }), prova({ id: 'b', grupoId: 'g1' })] }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Montar prova' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: 'História' })); fireEvent.click(screen.getByRole('checkbox', { name: 'Física' }))
  fireEvent.click(await screen.findByRole('radio', { name: /Uma prova por assunto/ }))
  clicar('Gerar prova')
  expect(await screen.findByText(/2 provas criadas, uma por assunto/)).toBeTruthy()
  expect(chamadas.find((c) => c.chave === 'POST /provas')!.corpo).toMatchObject({ separadas: true, assuntos: [{ assuntoId: 'h' }, { assuntoId: 'f' }] })
})

test('valida antes de enviar e, com questões insuficientes, oferece reduzir a quantidade', async () => {
  ir('/provas')
  let chamado = 0
  servidor({
    ...base, 'GET /provas': [200, []], 'GET /assuntos': [200, pastas], 'GET /assuntos/h/topicos': [200, topicos(['A'])],
    'POST /provas': () => { chamado++; return [409, { mensagem: 'Há só 4 questões no assunto e você pediu 10.', codigo: 'questoes_insuficientes', disponiveis: 4, pedidas: 10 }] },
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Montar prova' }))
  clicar('Gerar prova')
  expect((await screen.findByRole('alert')).textContent).toContain('Escolha ao menos um assunto.')
  fireEvent.click(await screen.findByRole('checkbox', { name: 'História' }))
  fireEvent.change(screen.getByLabelText('Quantidade de questões'), { target: { value: '99' } })
  clicar('Gerar prova')
  expect((await screen.findByRole('alert')).textContent).toContain('de 1 a 50 questões')
  expect(chamado).toBe(0)
  fireEvent.change(screen.getByLabelText('Quantidade de questões'), { target: { value: '10' } })
  clicar('Gerar prova')
  const reduzir = await screen.findByRole('button', { name: 'Reduzir para 4 questões' })
  expect(screen.getByRole('button', { name: 'Abrir os tópicos para gerar mais questões' })).toBeTruthy()
  fireEvent.click(reduzir)
  expect((screen.getByLabelText('Quantidade de questões') as HTMLInputElement).value).toBe('4')
})

test('prova não iniciada mostra o escopo e o aviso do relógio; começar e responder mandam a prova', async () => {
  ir(`/provas/${P}`)
  let iniciada = false
  const chamadas = servidor({
    ...base,
    [`GET /provas/${P}`]: () => [200, iniciada ? aberta(prova({ status: 'em_andamento', limiteMinutos: 30, iniciadaEm: new Date().toISOString(), prazo: new Date(Date.now() + 1800_000).toISOString() }), [q(1), q(2)])
      : aberta(prova({ limiteMinutos: 30 }), [q(1), q(2)])],
    [`POST /provas/${P}/iniciar`]: () => { iniciada = true; return [200, aberta(prova({ status: 'em_andamento', limiteMinutos: 30, iniciadaEm: new Date().toISOString(), prazo: new Date(Date.now() + 1800_000).toISOString() }), [q(1), q(2)])] },
    'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
  })
  render(<App />)
  expect(await screen.findByText(/até o tópico "Segunda Guerra"/)).toBeTruthy()
  expect(screen.getByText(/limite de 30 minutos/)).toBeTruthy()
  clicar('Começar prova')
  expect(await screen.findByRole('heading', { name: /Pergunta 1\?/ })).toBeTruthy()
  expect(screen.getByRole('timer').textContent).toMatch(/^(29|30):\d\d$/)
  fireEvent.click(screen.getByRole('button', { name: /^A\s*a$/ })); clicar('Confirmar resposta')
  expect(await screen.findByText('Correto!')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === 'POST /questoes/q1/respostas')!.corpo).toEqual({ alternativa: 0, provaId: P })
})

test('retomar uma prova em andamento mostra só as questões que faltam', async () => {
  ir(`/provas/${P}`)
  servidor({ ...base, [`GET /provas/${P}`]: [200, aberta(prova({ status: 'em_andamento', respondidas: 1, iniciadaEm: '2026-03-02T12:00:00Z' }), [q(1, { respondida: true, alternativaEscolhida: 0, correta: true, respostaCorreta: 0 }), q(2)])] })
  render(<App />)
  expect(await screen.findByRole('heading', { name: /Pergunta 2\?/ })).toBeTruthy()
  expect(screen.getByText('1 de 2 respondidas.')).toBeTruthy()
  expect(screen.queryByRole('timer')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Ver dica' })).toBeNull()  // prova não oferece dica (e não gasta IA)
})

test('tempo esgotado: o relógio pede o estado final e a tela passa ao resultado', async () => {
  ir(`/provas/${P}`)
  let consultas = 0
  const emAndamento = aberta(prova({ status: 'em_andamento', limiteMinutos: 5, iniciadaEm: '2026-03-02T12:00:00Z', prazo: new Date(Date.now() - 1000).toISOString() }), [q(1), q(2)])
  servidor({
    ...base,
    [`GET /provas/${P}`]: () => { consultas++; return [200, consultas === 1 ? emAndamento : aberta(prova({ status: 'concluida', respondidas: 0, concluidaEm: '2026-03-02T12:05:00Z', limiteMinutos: 5 }), [q(1), q(2)])] },
  })
  render(<App />)
  expect(await screen.findByText('Resultado da prova')).toBeTruthy()
  expect(screen.getByText(/2 questões ficaram sem resposta e contaram como erro/)).toBeTruthy()
  expect(screen.getAllByText('Sem resposta.')).toHaveLength(2)
})

test('o servidor recusar a resposta por tempo esgotado leva ao resultado', async () => {
  ir(`/provas/${P}`)
  let encerrada = false
  servidor({
    ...base,
    [`GET /provas/${P}`]: () => [200, encerrada ? aberta(prova({ status: 'concluida', concluidaEm: '2026-03-02T12:05:00Z' }), [q(1), q(2)]) : aberta(prova({ status: 'em_andamento', iniciadaEm: '2026-03-02T12:00:00Z' }), [q(1), q(2)])],
    'POST /questoes/q1/respostas': () => { encerrada = true; return [409, { mensagem: 'Esta prova já terminou.', codigo: 'prova_encerrada' }] },
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: /^A\s*a$/ })); clicar('Confirmar resposta')
  expect(await screen.findByText('Resultado da prova')).toBeTruthy()
})

test('resultado mostra a nota, o desempenho por assunto e a revisão; refazer abre a nova prova', async () => {
  ir(`/provas/${P}`)
  const chamadas = servidor({
    ...base,
    [`GET /provas/${P}`]: [200, aberta(prova({ status: 'concluida', respondidas: 2, acertos: 1, concluidaEm: '2026-03-02T12:05:00Z' }), [
      q(1, { respondida: true, alternativaEscolhida: 0, correta: true, respostaCorreta: 0 }),
      q(2, { assunto: 'Física', respondida: true, alternativaEscolhida: 0, correta: false, respostaCorreta: 1 }),
    ])],
    [`POST /provas/${P}/refazer`]: [201, prova({ id: NOVA })],
    [`GET /provas/${NOVA}`]: [200, aberta(prova({ id: NOVA }), [q(1), q(2)])],
  })
  render(<App />)
  expect(await screen.findByText(/Você acertou 1 de 2\./)).toBeTruthy()
  const porAssunto = screen.getByRole('heading', { name: 'Por assunto' }).closest('section')!
  expect(within(porAssunto).getByText(/História/).parentElement!.textContent).toContain('1 de 1')
  expect(within(porAssunto).getByText(/Física/).parentElement!.textContent).toContain('0 de 1')
  expect(screen.getByText(/Você marcou A\. a\. Correta: B\. b/)).toBeTruthy()
  clicar('Refazer prova')
  expect(await screen.findByRole('heading', { name: 'Pronta para começar' })).toBeTruthy()  // a tela troca de prova, não só a URL
  expect(window.location.pathname).toBe(`/provas/${NOVA}`)
  expect(chamadas.some((c) => c.chave === `POST /provas/${P}/refazer`)).toBe(true)
})

test('prova de outra conta ou inexistente explica e oferece voltar', async () => {
  ir(`/provas/${P}`)
  servidor({ ...base, [`GET /provas/${P}`]: [404, { mensagem: 'Prova não encontrada.', codigo: 'nao_encontrado' }] })
  render(<App />)
  expect(await screen.findByText('Esta prova não existe ou não é da sua conta.')).toBeTruthy()
})

test('excluir prova pede confirmação e volta à lista', async () => {
  ir(`/provas/${P}`)
  const chamadas = servidor({ ...base, [`GET /provas/${P}`]: [200, aberta(prova(), [q(1), q(2)])], [`DELETE /provas/${P}`]: [204], 'GET /provas': [200, []] })
  render(<App />)
  clicar((await screen.findByRole('button', { name: 'Excluir esta prova' })).textContent!)
  expect(screen.getByRole('alert').textContent).toContain('continuam no seu histórico')
  expect(chamadas.some((c) => c.chave.startsWith('DELETE'))).toBe(false)
  clicar('Cancelar')
  clicar('Excluir esta prova')
  clicar('Excluir definitivamente')
  await waitFor(() => expect(chamadas.some((c) => c.chave === `DELETE /provas/${P}`)).toBe(true))
  await waitFor(() => expect(window.location.pathname).toBe('/provas'))
})
