// Tipos e regras puras do fluxo de estudo (UC02 a UC05). Sem React: testadas em estudo.test.ts.
export type Assunto = { id: string; nome: string; padrao: boolean }
export type Material = { id: string; nomeArquivo: string; status: 'pendente' | 'processando' | 'pronto' | 'falhou'; motivoFalha: string | null; paginas: number | null; criadoEm: string; gerando?: boolean }
export type Andamento = { status: Material['status']; etapa: string; percentual: number; motivoFalha: string | null }
export type Topico = { id: string; assuntoId: string; nome: string; ordem: number; inicio: number; fim: number; previa: string | null; temConteudo: boolean }
export type Rascunho = { topicos: { id: string | null; nome: string; inicio: number; fim: number; temConteudo: boolean }[]; mudou: boolean; modelo: string; tokensEntrada: number; tokensSaida: number }
export type Uso = { operacoes: number; limiteOperacoes: number; janelaOperacoesMinutos: number; gastoUsd: number; limiteGastoUsd: number; janelaGastoDias: number }
export type Geracao = {
  id: string; operacao: 'resumo' | 'questoes' | 'verificacao_questao'; status: 'pendente' | 'em_andamento' | 'concluida' | 'falhou'; progresso: number
  aprovadas: number; rejeitadas: number; repetidas: number; gravadas: number; taxaRejeicao: number | null; mensagem: string | null; verificacaoId: string | null; motivo: string | null
}
export type TopicoDetalhe = {
  id: string; assuntoId: string; nome: string; livre: boolean; fontes: { materialId: string; nomeArquivo: string; inicio: number; fim: number }[]
  resumo: { conteudo: string; criadoEm: string } | null; totalQuestoes: number; maximoQuestoes: number; maximoResumos: number; geracaoAberta: string | null; ultimaGeracao: Geracao | null
}
export type TopicoLivre = { id: string; nome: string; criadoEm: string; totalQuestoes: number; temResumo: boolean; gerando?: boolean }
export const LIMITE_LISTA = 100  // a API devolve no máximo isto por lista (os mais recentes)
export type Apoio = { nomeArquivo: string; ordem: number; numeroPagina: number | null }
export type Resumo = { conteudo: string; criadoEm: string }
export type Questao = { id: string; enunciado: string; alternativas: string[]; respostaCorreta: number; criadoEm: string; apoio: Apoio[] }

const lista = (n: (string | number)[]) => (n.length > 1 ? `${n.slice(0, -1).join(', ')} e ${n[n.length - 1]}` : `${n[0]}`)
/** "apostila.pdf, págs. 3 e 4" / "notas.txt, trecho 12": de onde o material tirou a resposta (só trechos citados na verificação). */
export function rotuloApoio(apoio: Apoio[]): string {
  const porArquivo = new Map<string, Apoio[]>()
  apoio.forEach((a) => porArquivo.set(a.nomeArquivo, [...(porArquivo.get(a.nomeArquivo) ?? []), a]))
  return [...porArquivo].map(([nome, itens]) => {
    const paginas = [...new Set(itens.map((i) => i.numeroPagina).filter((p): p is number => p !== null))]
    if (paginas.length) return `${nome}, ${paginas.length > 1 ? 'págs.' : 'pág.'} ${lista(paginas)}`
    const ordens = itens.map((i) => i.ordem)
    return `${nome}, ${ordens.length > 1 ? 'trechos' : 'trecho'} ${lista(ordens)}`
  }).join('; ')
}
export const QUESTOES_POR_PEDIDO = 10
export const percentualDe = (taxa: number) => `${Math.round(taxa * 100)}%`
export type Trecho = { ordem: number; numeroPagina: number | null; previa: string }

export const TAMANHO_MAXIMO_MB = 20

/** Avisos de privacidade (LGPD, ADR 0040): o texto sai do sistema e vai a um provedor de IA externo; o aviso aparece onde isso acontece. */
export const AVISO_IA = {
  cadastro: 'Ao usar o Nexo, o conteúdo que você enviar é processado por um serviço externo de inteligência artificial para gerar resumos, questões e divisões em tópicos. Envie apenas material que você pode compartilhar.',
  material: 'O texto do material é enviado a um serviço externo de inteligência artificial para gerar resumos, questões e a divisão em tópicos. Envie apenas material que você pode compartilhar.',
  topicoLivre: 'O nome do tópico é enviado a um serviço externo de inteligência artificial para gerar o conteúdo.',
  reorganizar: 'O início de cada trecho é enviado a um serviço externo de inteligência artificial.',
  topico: 'Os trechos usados são enviados a um serviço externo de inteligência artificial para gerar o conteúdo.',
  topicoLivreGeracao: 'O nome do tópico é enviado a um serviço externo de inteligência artificial.',
}
export const FORMATOS = ['pdf', 'docx', 'txt']
export const NOME_MAXIMO = 200

export const config = { intervaloAndamentoMs: 2000, avisoParadaMs: 180_000, tentativasAndamento: 3, avisoDemoraMs: 5000 }

export const ETAPAS: Record<string, string> = {
  na_fila: 'Na fila para processar…',
  extracao: 'Lendo o arquivo e dividindo em trechos…',
  embeddings: 'Indexando o conteúdo…',
  topicos: 'Sugerindo a divisão em tópicos…',
  concluido: 'Pronto.',
  falhou: 'O processamento falhou.',
}

export const MOTIVOS: Record<string, string> = {
  sem_texto: 'O arquivo não tem texto que possamos ler (PDF escaneado, só com imagens?). Envie uma versão com texto selecionável.',
  formato_invalido: 'O arquivo está corrompido ou não é um PDF, DOCX ou TXT válido.',
  paginas_excedidas: 'O material passa do limite de páginas.',
  interrompido: 'O processamento foi interrompido. Envie o arquivo novamente.',
  falha_ia: 'O serviço de processamento falhou. Envie o arquivo novamente em instantes.',
  configuracao_ia: 'O serviço de IA não está configurado ou o provedor recusou a conta (chave, modelo, preço ou crédito). Reenviar o arquivo não resolve: confira a configuração (README) e os logs do servidor.',
  erro_interno: 'Ocorreu um erro inesperado. Envie o arquivo novamente.',
}

export const motivoDe = (codigo: string | null) => (codigo && MOTIVOS[codigo]) || 'Não foi possível processar o arquivo.'

/** Mesmas regras do backend para falhar cedo; o servidor continua sendo a autoridade (a extensão não prova o conteúdo). */
export function erroArquivo(a: File | null): string {
  if (!a) return 'Escolha um arquivo.'
  const ext = a.name.split('.').pop()?.toLowerCase() ?? ''
  if (!FORMATOS.includes(ext)) return 'Formato não aceito. Envie PDF, DOCX ou TXT.'
  if (a.size === 0) return 'O arquivo está vazio.'
  if (a.size > TAMANHO_MAXIMO_MB * 1024 * 1024) return `O arquivo passa de ${TAMANHO_MAXIMO_MB} MB.`
  return ''
}

// ---- revisão da proposta de tópicos: tudo local até "Confirmar" (um PUT atômico, regra 8)
// bloqueado: o tópico já tem resumo ou questões; só o nome pode mudar (o servidor também recusa, ADR 0028)
export type Faixa = { chave: string; id: string | null; nome: string; inicio: number; fim: number; bloqueado: boolean }

let contador = 0
export const novaChave = () => `n${++contador}`

export const faixasDe = (t: Topico[]): Faixa[] => t.map((x) => ({ chave: x.id, id: x.id, nome: x.nome, inicio: x.inicio, fim: x.fim, bloqueado: x.temConteudo }))

const colados = (f: Faixa[], i: number) => f[i].fim + 1 === f[i + 1].inicio

/** Une o tópico i com o seguinte (só se não há trechos de fora entre eles): fica o nome e o id do primeiro. */
export function unir(f: Faixa[], i: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || f[i + 1].bloqueado || !colados(f, i)) return f
  return [...f.slice(0, i), { ...f[i], fim: f[i + 1].fim }, ...f.slice(i + 2)]
}

/** Divide o tópico i depois do trecho `apos`; o novo tópico não tem id (é criado no servidor). */
export function dividir(f: Faixa[], i: number, apos: number): Faixa[] {
  const t = f[i]
  if (!t || t.bloqueado || apos < t.inicio || apos >= t.fim) return f
  return [...f.slice(0, i), { ...t, fim: apos }, { chave: novaChave(), id: null, nome: `${t.nome} (continuação)`.slice(0, NOME_MAXIMO), inicio: apos + 1, fim: t.fim, bloqueado: false }, ...f.slice(i + 1)]
}

/**
 * Muda onde o tópico i termina. Colado no seguinte, move a fronteira dos dois (o seguinte começa logo depois);
 * com trechos de fora entre eles, só o tópico i muda e `fim` vai até o último trecho antes do seguinte.
 */
export function ajustar(f: Faixa[], i: number, fim: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || fim < f[i].inicio) return f
  if (!colados(f, i)) return fim < f[i + 1].inicio ? f.map((t, k) => (k === i ? { ...t, fim } : t)) : f
  if (f[i + 1].bloqueado || fim >= f[i + 1].fim) return f
  return f.map((t, k) => (k === i ? { ...t, fim } : k === i + 1 ? { ...t, inicio: fim + 1 } : t))
}

/** O material inteiro (de 1 a `total`) como um tópico só (UC02 A3). */
export function umSo(f: Faixa[], nome: string, total: number): Faixa[] {
  if (f.some((t) => t.bloqueado)) return f
  return [{ ...f[0], nome: (f[0].nome || nome).slice(0, NOME_MAXIMO), inicio: 1, fim: total }]
}

/** Tira o tópico i da divisão: os trechos dele ficam de fora (continuam no material, sem resumo nem questões). Sempre sobra ao menos um tópico. */
export function excluir(f: Faixa[], i: number): Faixa[] {
  if (!f[i] || f[i].bloqueado || f.length < 2) return f
  return f.filter((_, k) => k !== i)
}

/** Trechos que nenhum tópico usa, em faixas [de, ate]. */
export function lacunas(f: Faixa[], total: number): [number, number][] {
  const r: [number, number][] = []
  let esperado = 1
  for (const t of f) {
    if (t.inicio > esperado) r.push([esperado, t.inicio - 1])
    esperado = t.fim + 1
  }
  if (esperado <= total) r.push([esperado, total])
  return r
}

/** Cria um tópico novo com os trechos de fora de/ate (volta a usá-los). */
export function criarNaLacuna(f: Faixa[], de: number, ate: number): Faixa[] {
  const novo: Faixa = { chave: novaChave(), id: null, nome: `Trechos ${de} a ${ate}`, inicio: de, fim: ate, bloqueado: false }
  const i = f.findIndex((t) => t.inicio > ate)
  return i < 0 ? [...f, novo] : [...f.slice(0, i), novo, ...f.slice(i)]
}

/** Erro por tópico (índice -> mensagem). Ordem e sobreposição ficam garantidas pelas operações acima; lacunas são permitidas (ADR 0042). */
export function errosDeNome(f: Faixa[]): Record<number, string> {
  const e: Record<number, string> = {}
  f.forEach((t, i) => {
    if (!t.nome.trim()) e[i] = 'Informe o nome do tópico.'
    else if (t.nome.trim().length > NOME_MAXIMO) e[i] = `O nome pode ter até ${NOME_MAXIMO} caracteres.`
  })
  return e
}

export const igual = (a: Faixa[], b: Faixa[]) => a.length === b.length && a.every((x, i) => x.id === b[i].id && x.nome === b[i].nome && x.inicio === b[i].inicio && x.fim === b[i].fim)

/** "págs. 3 a 9" a partir da página de cada trecho (índice = ordem - 1); vazio em DOCX e TXT, que não têm páginas. */
export function rotuloPaginas(paginas: (number | null)[], inicio: number, fim: number): string {
  const a = paginas[inicio - 1]
  const b = paginas[fim - 1]
  if (a == null || b == null) return ''
  return a === b ? `pág. ${a}` : `págs. ${a} a ${b}`
}

export function formatarTamanho(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`
}

const dolar = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'USD' })
export const formatarDolar = (v: number) => dolar.format(v).replace(/\s/g, ' ')

/** Quanto do limite mais apertado já foi usado (0 a 100): vale o pior entre operações e gasto. */
export const percentualUso = (u: Uso) => Math.min(100, Math.round(Math.max(u.operacoes / u.limiteOperacoes, u.gastoUsd / u.limiteGastoUsd) * 100))

/** ok até 79%, atencao a partir de 80%, esgotado no limite (o servidor passa a recusar as operações com IA). */
export const nivelDoUso = (u: Uso): 'ok' | 'atencao' | 'esgotado' => {
  const p = Math.max(u.operacoes / u.limiteOperacoes, u.gastoUsd / u.limiteGastoUsd)
  return p >= 1 ? 'esgotado' : p >= 0.8 ? 'atencao' : 'ok'
}

/** Quem gasta IA (envio, reorganização, fim da indexação) avisa; a barra do cabeçalho consulta o uso de novo. */
export const EVENTO_USO = 'nexo:uso'
export const avisarUso = () => window.dispatchEvent(new Event(EVENTO_USO))
