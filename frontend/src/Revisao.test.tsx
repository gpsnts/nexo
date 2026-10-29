import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useState } from 'react'
import App from './App'
import { Resultado } from './Responder'

type Rota = [number, unknown?]
function servidor(rotas: Record<string, Rota | ((corpo: any) => Rota)>) {
  const chamadas: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url.replace('/api', '').split('?')[0]}`
    chamadas.push(chave)
    const r = rotas[chave]
    const [status, dados] = typeof r === 'function' ? r(typeof init.body === 'string' ? JSON.parse(init.body) : undefined) : (r ?? [404, { mensagem: 'sem rota' }])
    return { ok: status < 400, status, json: async () => dados }
  }))
  return chamadas
}
const uso = { creditosUsados: 39, creditosLimite: 300, percentual: 13, renovaEm: '2026-10-19T03:00:00Z', operacoes: 4, limiteOperacoes: 30, janelaOperacoesMinutos: 60 }
const base = { 'GET /uso': [200, uso] as Rota, 'POST /auth/refresh': [200, { token: 't', nome: 'Ana' }] as Rota }

beforeEach(() => localStorage.setItem('nexo-sessao', '1'))
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })
const ir = (caminho: string) => window.history.replaceState(null, '', caminho)

test('revisão sem questões vencidas informa e sugere estudar', async () => {
  ir('/revisar')
  servidor({ ...base, 'GET /revisao': [200, { topicos: [], questoes: [] }] })
  render(<App />)
  expect(await screen.findByText('Nenhuma questão para revisar agora.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Ir ao início' })).toBeTruthy()
})

test('revisão lista os tópicos, responde as questões e mostra o resumo da sessão', async () => {
  ir('/revisar')
  const chamadas = servidor({
    ...base,
    'GET /revisao': [200, { topicos: [{ id: 't1', nome: 'Células', vencidas: 2, taxaErro: 0.5 }], questoes: [
      { id: 'q1', topicoId: 't1', topico: 'Células', enunciado: 'Primeira?', alternativas: ['a', 'b'] },
      { id: 'q2', topicoId: 't1', topico: 'Células', enunciado: 'Segunda?', alternativas: ['a', 'b'] },
    ] }],
    'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
    'POST /questoes/q2/respostas': [200, { correta: false, respostaCorreta: 1, proximaRevisao: '2026-03-10' }],
  })
  render(<App />)
  expect(await screen.findByText(/2 questões nesta sessão/)).toBeTruthy()
  expect(screen.getByText(/2 vencidas · 50% de erros/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Começar revisão' }))
  expect(await screen.findByRole('heading', { name: /Primeira\?/ })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: /^A\s*a$/ })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar resposta' }))
  expect(await screen.findByText('Correto!')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Próxima questão' }))
  fireEvent.click(await screen.findByRole('button', { name: /^A\s*a$/ })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar resposta' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Ver resultado' }))
  expect(await screen.findByText(/Você acertou 1 de 2\./)).toBeTruthy()
  expect(screen.getByText('Próxima revisão agendada: 10/03/2026.')).toBeTruthy()
  expect(screen.getByText('1/2')).toBeTruthy()
  const anel = document.querySelector('.anel-valor')!  // o anel se preenche até a nota: metade da circunferência
  const circ = 2 * Math.PI * 52
  await waitFor(() => expect(Number(anel.getAttribute('stroke-dashoffset'))).toBeCloseTo(circ / 2, 1))
  expect(chamadas.filter((c) => c.startsWith('POST /questoes')).length).toBe(2)
  fireEvent.click(screen.getByRole('button', { name: 'Nova revisão' }))
  expect(await screen.findByRole('button', { name: 'Começar revisão' })).toBeTruthy()
})

test('revisão com falha oferece tentar de novo', async () => {
  ir('/revisar')
  let n = 0
  servidor({ ...base, 'GET /revisao': () => (++n === 1 ? [500, {}] : [200, { topicos: [], questoes: [] }]) })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Tentar de novo' }))
  expect(await screen.findByText('Nenhuma questão para revisar agora.')).toBeTruthy()
})

test('painel sem respostas mostra o estado vazio', async () => {
  ir('/desempenho')
  servidor({ ...base, 'GET /desempenho': [200, { vazio: true, semanas: [], assuntos: [], fracos: [] }] })
  render(<App />)
  expect(await screen.findByText('Ainda não há histórico para exibir.')).toBeTruthy()
})

test('painel mostra a evolução por assunto, a tabela equivalente e os tópicos mais fracos', async () => {
  ir('/desempenho')
  const ponto = (semana: string, total: number, acertos: number) => ({ semana, total, acertos })
  servidor({
    ...base,
    'GET /desempenho': [200, {
      vazio: false, semanas: ['2026-03-02', '2026-03-09'],
      assuntos: [{ id: 'a1', nome: 'Biologia', total: 6, acertos: 3, pontos: [ponto('2026-03-02', 4, 1), ponto('2026-03-09', 2, 2)], topicos: [
        { id: 't1', nome: 'Células', total: 6, acertos: 3, pontos: [ponto('2026-03-02', 4, 1), ponto('2026-03-09', 2, 2)] },
      ] }],
      fracos: [{ id: 't1', nome: 'Células', assunto: 'Biologia', total: 6, acertos: 3, percentual: 50 }],
    }],
  })
  render(<App />)
  expect(await screen.findByRole('heading', { name: /Biologia: 50% de acertos/ })).toBeTruthy()
  expect(screen.getByRole('img', { name: /Evolução semanal de acertos em Biologia/ })).toBeTruthy()
  expect(screen.getAllByText('25% (1/4)').length).toBe(2)   // tabela: a linha do assunto e a do tópico
  expect(screen.getAllByText('100% (2/2)').length).toBe(2)
  expect(screen.getAllByText(/50%/, { selector: 'strong' }).length).toBeGreaterThan(0)  // no painel de números e na lista dos mais fracos
  expect(screen.getByRole('button', { name: 'Revisar agora' })).toBeTruthy()
})

test('o cabeçalho leva a Revisar e Desempenho', async () => {
  ir('/perfil')
  servidor({ ...base, 'GET /revisao': [200, { topicos: [], questoes: [] }], 'GET /perfil': [200, { nome: 'Ana', email: 'a@x.com' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Revisar' }))
  expect(await screen.findByRole('heading', { name: 'Revisar' })).toBeTruthy()
  expect(window.location.pathname).toBe('/revisar')
})

test('revisão sempre oferece gerar novas questões: na lista, no vazio, no resultado e abre o tópico já no gerador', async () => {
  ir('/revisar')
  servidor({
    ...base,
    'GET /revisao': [200, { recentes: [{ id: 't1', nome: 'Células' }], topicos: [{ id: 't1', nome: 'Células', vencidas: 1, taxaErro: 0 }, { id: 't2', nome: 'Genética', vencidas: 1, taxaErro: 0 }], questoes: [
      { id: 'q1', topicoId: 't1', topico: 'Células', enunciado: 'Primeira?', alternativas: ['a', 'b'] },
      { id: 'q2', topicoId: 't2', topico: 'Genética', enunciado: 'Segunda?', alternativas: ['a', 'b'] },
    ] }],
    'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
    'POST /questoes/q2/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Gerar novas questões de Genética' }))  // na lista de tópicos
  await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/topicos/t2?gerar=1'))
  cleanup(); ir('/revisar')
  servidor({
    ...base,
    'GET /revisao': [200, { recentes: [], topicos: [{ id: 't1', nome: 'Células', vencidas: 1, taxaErro: 0 }, { id: 't2', nome: 'Genética', vencidas: 1, taxaErro: 0 }], questoes: [
      { id: 'q1', topicoId: 't1', topico: 'Células', enunciado: 'Primeira?', alternativas: ['a', 'b'] },
      { id: 'q2', topicoId: 't2', topico: 'Genética', enunciado: 'Segunda?', alternativas: ['a', 'b'] },
    ] }],
    'POST /questoes/q1/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
    'POST /questoes/q2/respostas': [200, { correta: true, respostaCorreta: 0, proximaRevisao: '2026-03-12' }],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Começar revisão' }))
  for (let n = 0; n < 2; n++) {
    fireEvent.click(await screen.findByRole('button', { name: /^A\s*a$/ })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar resposta' }))
    fireEvent.click(await screen.findByRole('button', { name: /Próxima questão|Ver resultado/ }))
  }
  expect(await screen.findByRole('button', { name: 'Gerar novas questões de Células' })).toBeTruthy()  // no resultado, um atalho por tópico da sessão
  fireEvent.click(screen.getByRole('button', { name: 'Gerar novas questões de Células' }))
  await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/topicos/t1?gerar=1'))
})

test('sem nada vencido a revisão oferece gerar questões novas dos tópicos estudados há pouco', async () => {
  ir('/revisar')
  servidor({ ...base, 'GET /revisao': [200, { topicos: [], questoes: [], recentes: [{ id: 't9', nome: 'Óptica' }] }] })
  render(<App />)
  expect(await screen.findByText('Nenhuma questão para revisar agora.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Gerar novas questões de Óptica' }).textContent).toBe('Gerar novas questões')
  expect(screen.getByRole('button', { name: 'Ir ao início' })).toBeTruthy()
})

test('início: o painel Hoje oferece gerar novas questões do tópico mais urgente', async () => {
  window.history.replaceState(null, '', '/')
  servidor({ ...base, 'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true }]], 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []], 'GET /revisao': [200, { recentes: [], topicos: [{ id: 't5', nome: 'Cinemática', vencidas: 3, taxaErro: 0.5 }], questoes: [] }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Gerar novas questões' }))
  await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/topicos/t5?gerar=1'))
})

test('revisão: dá para escolher os tópicos; com todos marcados usa as questões já carregadas, com escolha pede só as dos marcados', async () => {
  ir('/revisar')
  let n = 0  // a 1ª chamada é a lista; a 2ª é o pedido filtrado (o servidor de teste ignora a query)
  const chamadas = servidor({
    ...base,
    'GET /revisao': () => (++n === 1
      ? [200, { topicos: [{ id: 't1', nome: 'Células', vencidas: 2, taxaErro: 0.5 }, { id: 't2', nome: 'Átomos', vencidas: 1, taxaErro: 0 }], questoes: [{ id: 'q1', topicoId: 't1', topico: 'Células', enunciado: 'Primeira?', alternativas: ['a', 'b'] }] }]
      : [200, { topicos: [], questoes: [{ id: 'q9', topicoId: 't2', topico: 'Átomos', enunciado: 'Do átomo?', alternativas: ['a', 'b'] }] }]),
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('checkbox', { name: 'Células' }))
  expect(screen.getByText(/1 questão nesta sessão/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Começar revisão' }))
  expect(await screen.findByRole('heading', { name: /Do átomo\?/ })).toBeTruthy()
  expect(chamadas.filter((c) => c === 'GET /revisao').length).toBe(2)
})

test('revisão: sem nenhum tópico marcado não dá para começar', async () => {
  ir('/revisar')
  servidor({ ...base, 'GET /revisao': [200, { topicos: [{ id: 't1', nome: 'Células', vencidas: 1, taxaErro: 0 }], questoes: [{ id: 'q1', topicoId: 't1', topico: 'Células', enunciado: 'P?', alternativas: ['a', 'b'] }] }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('checkbox', { name: 'Células' }))
  expect((screen.getByRole('button', { name: 'Começar revisão' }) as HTMLButtonElement).disabled).toBe(true)
})

test('o resultado leva o foco ao título uma vez e não o toma de volta ao re-renderizar', async () => {
  const Tela = () => { const [n, setN] = useState(0); return <Resultado acertos={1} total={2}><button type="button" onClick={() => setN(n + 1)}>Atualizar {n}</button></Resultado> }
  render(<Tela />)
  const titulo = screen.getByRole('heading', { name: 'Resultado', hidden: true })
  await waitFor(() => expect(document.activeElement).toBe(titulo))
  const botao = screen.getByRole('button', { name: /Atualizar/ })
  botao.focus(); fireEvent.click(botao)
  expect(await screen.findByRole('button', { name: 'Atualizar 1' })).toBeTruthy()
  expect(document.activeElement).toBe(botao)  // com o callback inline o foco voltava ao título a cada renderização
})
