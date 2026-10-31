import { cleanup, createEvent, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'

type Rota = (corpo: any) => [number, unknown?]
const H = '66666666-6666-6666-6666-666666666666'
const T = '22222222-2222-2222-2222-222222222222'
const MAT = '77777777-7777-7777-7777-777777777777'

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
const pastas = [{ id: 'g', nome: 'Geral', padrao: true }, { id: H, nome: 'História', padrao: false }]
const topico = (id: string, nome: string, ordem: number, extra = {}) => ({ id, nome, ordem, livre: false, temConteudo: false, limiteDeProvas: 0, totalQuestoes: 3, materiais: [{ id: MAT, nome: 'apostila.pdf' }], ...extra })
const tres = [topico('a', 'Causas', 1), topico('b', 'Batalhas', 2), topico('c', 'Fim', 3)]

beforeEach(() => { localStorage.setItem('nexo-sessao', '1') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); localStorage.clear() })
const ir = (caminho: string) => window.history.replaceState(null, '', caminho)
const ordem = () => within(screen.getByRole('list', { name: 'Tópicos da pasta' })).getAllByRole('listitem').map((li) => li.querySelector('.nome')!.textContent)

test('lista os tópicos da pasta e Subir e Descer gravam a ordem', async () => {
  ir(`/pastas/${H}`)
  const chamadas = servidor({ ...base, 'GET /assuntos': [200, pastas], [`GET /assuntos/${H}/topicos`]: [200, tres], [`PUT /assuntos/${H}/topicos/ordem`]: [200, []] })
  render(<App />)
  expect(await screen.findByRole('heading', { name: 'Tópicos de História' })).toBeTruthy()
  expect(ordem()).toEqual(['1. Causas', '2. Batalhas', '3. Fim'])
  expect(screen.getByRole('button', { name: 'Subir Causas' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Descer Causas' }))
  await waitFor(() => expect(ordem()).toEqual(['1. Batalhas', '2. Causas', '3. Fim']))
  await waitFor(() => expect(chamadas.find((c) => c.chave === `PUT /assuntos/${H}/topicos/ordem`)!.corpo).toEqual({ ids: ['b', 'a', 'c'] }))
  expect(await screen.findByText('"Causas" agora é o tópico 2.')).toBeTruthy()
})

test('arrastar um tópico para outra posição grava a nova ordem', async () => {
  ir(`/pastas/${H}`)
  const chamadas = servidor({ ...base, 'GET /assuntos': [200, pastas], [`GET /assuntos/${H}/topicos`]: [200, tres], [`PUT /assuntos/${H}/topicos/ordem`]: [200, []] })
  render(<App />)
  await screen.findByRole('heading', { name: 'Tópicos de História' })
  const itens = within(screen.getByRole('list', { name: 'Tópicos da pasta' })).getAllByRole('listitem')
  const dataTransfer = { effectAllowed: '', setData: () => {}, setDragImage: () => {} }
  const alca = itens[2].querySelector('.alca')!  // só a alça arrasta
  expect(itens[2].getAttribute('draggable')).toBeNull(); expect(alca.getAttribute('draggable')).toBe('true')
  fireEvent(alca, Object.assign(createEvent.dragStart(alca), { dataTransfer }))
  fireEvent.dragOver(itens[0]); fireEvent.drop(itens[0])
  await waitFor(() => expect(chamadas.find((c) => c.chave === `PUT /assuntos/${H}/topicos/ordem`)!.corpo).toEqual({ ids: ['c', 'a', 'b'] }))
  expect(ordem()).toEqual(['1. Fim', '2. Causas', '3. Batalhas'])
})

test('se o servidor recusar a ordem, a lista volta ao que era e explica', async () => {
  ir(`/pastas/${H}`)
  servidor({ ...base, 'GET /assuntos': [200, pastas], [`GET /assuntos/${H}/topicos`]: [200, tres], [`PUT /assuntos/${H}/topicos/ordem`]: [422, { mensagem: 'A lista precisa ter todos os tópicos da pasta.', codigo: 'ordem_invalida' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Descer Causas' }))
  expect((await screen.findByRole('alert')).textContent).toContain('precisa ter todos os tópicos')
  expect(ordem()).toEqual(['1. Causas', '2. Batalhas', '3. Fim'])
})

test('mudar um tópico de pasta o tira da lista; o limite de prova não muda', async () => {
  ir(`/pastas/${H}`)
  let movido = false
  const chamadas = servidor({
    ...base, 'GET /assuntos': [200, pastas],
    [`GET /assuntos/${H}/topicos`]: () => [200, movido ? [tres[1], tres[2]] : [tres[0], tres[1], topico('c', 'Fim', 3, { limiteDeProvas: 2 })]],
    'PUT /topicos/a/assunto': () => { movido = true; return [200, pastas[0]] },
  })
  render(<App />)
  await screen.findByRole('heading', { name: 'Tópicos de História' })
  expect(screen.getByText('limite de 2 provas')).toBeTruthy()
  expect((screen.getByLabelText('Mover Fim para a pasta') as HTMLSelectElement).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Mover Causas para a pasta'), { target: { value: 'g' } })
  expect(await screen.findByText('"Causas" foi para a pasta Geral.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === 'PUT /topicos/a/assunto')!.corpo).toEqual({ assuntoId: 'g' })
  await waitFor(() => expect(ordem()).toEqual(['2. Batalhas', '3. Fim']))
})

test('pasta inexistente ou de outra conta explica e volta ao início', async () => {
  ir(`/pastas/${H}`)
  servidor({ ...base, 'GET /assuntos': [200, pastas], [`GET /assuntos/${H}/topicos`]: [404, { mensagem: 'Pasta não encontrada.', codigo: 'assunto_nao_encontrado' }] })
  render(<App />)
  expect(await screen.findByText('Esta pasta não existe ou não é da sua conta.')).toBeTruthy()
})

// ---- fontes do tópico
const detalhe = (extra = {}) => ({
  id: T, assuntoId: 'g', nome: 'Células', livre: false, fontes: [{ materialId: MAT, nomeArquivo: 'bio.pdf', inicio: 1, fim: 5 }],
  resumo: null, totalQuestoes: 0, maximoQuestoes: 50, maximoResumos: 10, geracaoAberta: null, ultimaGeracao: null, orientacao: null, ...extra,
})
const comTopico = { ...base, 'GET /assuntos': [200, pastas] as [number, unknown], [`GET /topicos/${T}`]: [200, detalhe()] as [number, unknown], [`GET /topicos/${T}/questoes`]: [200, []] as [number, unknown], [`GET /topicos/${T}/chat`]: [200, []] as [number, unknown] }

test('acrescenta a fonte de outro material a um tópico sem conteúdo', async () => {
  ir(`/topicos/${T}`)
  const chamadas = servidor({
    ...comTopico,
    'GET /materiais': [200, [{ id: MAT, nomeArquivo: 'bio.pdf', status: 'pronto' }, { id: 'm2', nomeArquivo: 'quimica.pdf', status: 'pronto' }, { id: 'm3', nomeArquivo: 'x.pdf', status: 'processando' }]],
    [`POST /topicos/${T}/fontes`]: [201, []],
  })
  render(<App />)
  expect(await screen.findByText(/bio\.pdf, trechos 1 a 5/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Acrescentar material' }))
  const escolha = await screen.findByLabelText('Material')
  await waitFor(() => expect(within(escolha).getAllByRole('option').map((o) => o.textContent)).toEqual(['Escolha…', 'quimica.pdf']))
  fireEvent.click(screen.getByRole('button', { name: 'Acrescentar' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Escolha o material.')
  fireEvent.change(escolha, { target: { value: 'm2' } })
  fireEvent.change(screen.getByLabelText('Primeiro trecho'), { target: { value: '8' } }); fireEvent.change(screen.getByLabelText('Último trecho'), { target: { value: '9' } })
  fireEvent.click(screen.getByRole('button', { name: 'Acrescentar' }))
  expect(await screen.findByText('Fonte acrescentada.')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === `POST /topicos/${T}/fontes`)!.corpo).toEqual({ materialId: 'm2', inicio: 8, fim: 9 })
})

test('faixa recusada pelo servidor mostra o motivo', async () => {
  ir(`/topicos/${T}`)
  servidor({ ...comTopico, 'GET /materiais': [200, [{ id: 'm2', nomeArquivo: 'quimica.pdf', status: 'pronto' }]], [`POST /topicos/${T}/fontes`]: [409, { mensagem: 'Parte dessa faixa já pertence ao tópico "Ácidos".', codigo: 'faixa_sobreposta' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Acrescentar material' }))
  fireEvent.change(await screen.findByLabelText('Material'), { target: { value: 'm2' } })
  await waitFor(() => expect((screen.getByLabelText('Material') as HTMLSelectElement).value).toBe('m2'))
  fireEvent.change(screen.getByLabelText('Primeiro trecho'), { target: { value: '1' } }); fireEvent.change(screen.getByLabelText('Último trecho'), { target: { value: '3' } })
  fireEvent.click(screen.getByRole('button', { name: 'Acrescentar' }))
  expect((await screen.findByRole('alert')).textContent).toContain('já pertence ao tópico "Ácidos"')
})

test('retirar uma fonte chama a API; tópico com conteúdo não deixa mudar as fontes', async () => {
  ir(`/topicos/${T}`)
  const chamadas = servidor({ ...comTopico, [`DELETE /topicos/${T}/fontes/${MAT}`]: [204] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Retirar a fonte bio.pdf' }))
  expect(await screen.findByText('Fonte retirada.')).toBeTruthy()
  expect(chamadas.some((c) => c.chave === `DELETE /topicos/${T}/fontes/${MAT}`)).toBe(true)
  cleanup()
  servidor({ ...comTopico, [`GET /topicos/${T}`]: [200, detalhe({ totalQuestoes: 3 })] })
  render(<App />)
  expect(await screen.findByText(/não mudam mais/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Acrescentar material' })).toBeNull()
  expect(screen.queryByRole('button', { name: /Retirar a fonte/ })).toBeNull()
})

// ---- excluir material
const material = { id: MAT, nomeArquivo: 'bio.pdf', status: 'pronto', motivoFalha: null, paginas: 3, criadoEm: '2026-10-15T10:00:00Z' }
const paraMaterial = { ...base, 'GET /assuntos': [200, pastas] as [number, unknown], [`GET /materiais/${MAT}`]: [200, material] as [number, unknown], [`GET /materiais/${MAT}/topicos`]: [200, [{ id: T, assuntoId: 'g', nome: 'Células', ordem: 1, inicio: 1, fim: 5, previa: 'x', temConteudo: false }]] as [number, unknown], [`GET /materiais/${MAT}/paginas`]: [200, [1, 1, 2, 2, 3]] as [number, unknown] }

test('excluir material pede confirmação, avisa o que fica e volta ao início', async () => {
  ir(`/materiais/${MAT}`)
  const chamadas = servidor({ ...paraMaterial, [`DELETE /materiais/${MAT}`]: [204], 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []], 'GET /revisao': [200, { topicos: [], questoes: [] }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Excluir o material bio.pdf' }))
  expect(screen.getByRole('alert').textContent).toContain('não pode ser desfeito')
  expect(chamadas.some((c) => c.chave.startsWith('DELETE'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
  expect(screen.queryByRole('button', { name: 'Excluir definitivamente' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Excluir o material bio.pdf' }))
  fireEvent.click(screen.getByRole('button', { name: 'Excluir definitivamente' }))
  await waitFor(() => expect(chamadas.some((c) => c.chave === `DELETE /materiais/${MAT}`)).toBe(true))
  await waitFor(() => expect(window.location.pathname).toBe('/'))
})

test('excluir material que está gerando conteúdo mostra o motivo', async () => {
  ir(`/materiais/${MAT}`)
  servidor({ ...paraMaterial, [`DELETE /materiais/${MAT}`]: [409, { mensagem: 'Há um resumo ou questões sendo gerados a partir deste material.', codigo: 'material_gerando' }] })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Excluir o material bio.pdf' }))
  fireEvent.click(screen.getByRole('button', { name: 'Excluir definitivamente' }))
  expect(await screen.findByText(/sendo gerados a partir deste material/)).toBeTruthy()
  expect(window.location.pathname).toBe(`/materiais/${MAT}`)
})

// ---- trechos de fora usados em outro tópico, histórico e contagem por pasta
test('trechos de fora podem ser usados em um tópico que já existe, sem mudar a divisão', async () => {
  ir(`/materiais/${MAT}`)
  const chamadas = servidor({
    ...paraMaterial,
    [`GET /materiais/${MAT}/topicos`]: [200, [{ id: T, assuntoId: 'g', nome: 'Células', ordem: 1, inicio: 1, fim: 2, previa: 'x', temConteudo: false }]],
    [`GET /materiais/${MAT}/topicos-candidatos`]: [200, [{ id: 'reforco', nome: 'Reforço' }]],
    'POST /topicos/reforco/fontes': [201, []],
  })
  render(<App />)
  expect((await screen.findAllByText(/Fora dos tópicos/))[0]).toBeTruthy()
  const alvo = await screen.findByLabelText(/Ou use estes trechos em um tópico que já existe/)
  const botao = screen.getByRole('button', { name: 'Usar os trechos 3 a 5 nesse tópico' })
  expect((botao as HTMLButtonElement).disabled).toBe(true)  // sem escolher o tópico, não envia
  fireEvent.change(alvo, { target: { value: 'reforco' } })
  fireEvent.click(botao)
  expect(await screen.findByText('Trechos 3 a 5 acrescentados ao tópico "Reforço".')).toBeTruthy()
  expect(chamadas.find((c) => c.chave === 'POST /topicos/reforco/fontes')!.corpo).toEqual({ materialId: MAT, inicio: 3, fim: 5 })
})

test('histórico lista materiais e tópicos, filtra por pasta e abre um item', async () => {
  ir('/historico')
  servidor({
    ...base, 'GET /assuntos': [200, pastas],
    'GET /materiais': [200, [{ ...material, assuntoId: H, assuntoIds: [H], titulo: 'Segunda Guerra' }]],
    'GET /topicos': [200, [
      { id: 'a', nome: 'Causas', assuntoId: H, livre: false, materiais: [{ id: MAT, nome: 'apostila.pdf' }], totalQuestoes: 4, temResumo: true, criadoEm: '2026-10-16T12:00:00Z' },
      { id: 'b', nome: 'Óptica', assuntoId: 'g', livre: true, materiais: [], totalQuestoes: 1, temResumo: false, criadoEm: '2026-10-15T12:00:00Z' },
    ]],
  })
  render(<App />)
  expect(within(await screen.findByRole('list', { name: 'Tópicos' })).getAllByRole('listitem')).toHaveLength(2)
  expect(screen.getByText('4 questões')).toBeTruthy(); expect(screen.getByText('com resumo')).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Pasta'), { target: { value: 'g' } })
  expect(within(screen.getByRole('list', { name: 'Tópicos' })).getAllByRole('listitem')).toHaveLength(1)
  expect(screen.getByText('Nenhum material nesta pasta.')).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Pasta'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Causas' }))
  expect(window.location.pathname).toBe('/topicos/a')
})

test('histórico vazio orienta a começar um estudo', async () => {
  ir('/historico')
  servidor({ ...base, 'GET /assuntos': [200, pastas], 'GET /materiais': [200, []], 'GET /topicos': [200, []] })
  render(<App />)
  expect(await screen.findByText('Seu histórico está vazio')).toBeTruthy()
})

test('a pasta escolhida mostra quantos tópicos e materiais tem', async () => {
  ir('/')
  servidor({
    ...base, 'GET /materiais': [200, []], 'GET /topicos/livres': [200, []], 'GET /revisao': [200, { topicos: [], questoes: [] }],
    'GET /assuntos': [200, [{ id: 'g', nome: 'Geral', padrao: true, topicos: 0, materiais: 0 }, { id: H, nome: 'História', padrao: false, topicos: 3, materiais: 1 }]],
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'História' }))
  expect(await screen.findByText('3 tópicos de 1 material nesta pasta.')).toBeTruthy()
})
