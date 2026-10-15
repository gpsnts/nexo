// Tipos e regras puras do fluxo de estudo (UC02 a UC05). Sem React: testadas em estudo.test.ts.
export type Assunto = { id: string; nome: string; padrao: boolean }
export type Material = { id: string; nomeArquivo: string; status: 'pendente' | 'processando' | 'pronto' | 'falhou'; motivoFalha: string | null; paginas: number | null; criadoEm: string }
export type Andamento = { status: Material['status']; etapa: string; percentual: number; motivoFalha: string | null }
export type Topico = { id: string; assuntoId: string; nome: string; ordem: number; inicio: number; fim: number; previa: string | null; temConteudo: boolean }
export type Rascunho = { topicos: { id: string | null; nome: string; inicio: number; fim: number; temConteudo: boolean }[]; mudou: boolean; modelo: string; tokensEntrada: number; tokensSaida: number }
export type Uso = { operacoes: number; limiteOperacoes: number; janelaOperacoesMinutos: number; gastoUsd: number; limiteGastoUsd: number; janelaGastoDias: number }
export type Trecho = { ordem: number; numeroPagina: number | null; previa: string }

export const TAMANHO_MAXIMO_MB = 20
export const FORMATOS = ['pdf', 'docx', 'txt']
export const NOME_MAXIMO = 200

export const config = { intervaloAndamentoMs: 2000 }

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

/** Une o tópico i com o seguinte: fica o nome e o id do primeiro. */
export function unir(f: Faixa[], i: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || f[i + 1].bloqueado) return f
  return [...f.slice(0, i), { ...f[i], fim: f[i + 1].fim }, ...f.slice(i + 2)]
}

/** Divide o tópico i depois do trecho `apos`; o novo tópico não tem id (é criado no servidor). */
export function dividir(f: Faixa[], i: number, apos: number): Faixa[] {
  const t = f[i]
  if (!t || t.bloqueado || apos < t.inicio || apos >= t.fim) return f
  return [...f.slice(0, i), { ...t, fim: apos }, { chave: novaChave(), id: null, nome: `${t.nome} (continuação)`.slice(0, NOME_MAXIMO), inicio: apos + 1, fim: t.fim, bloqueado: false }, ...f.slice(i + 1)]
}

/** Move a fronteira entre o tópico i e o seguinte: o tópico i termina em `fim` e o seguinte começa logo depois. */
export function ajustar(f: Faixa[], i: number, fim: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || f[i + 1].bloqueado || fim < f[i].inicio || fim >= f[i + 1].fim) return f
  return f.map((t, k) => (k === i ? { ...t, fim } : k === i + 1 ? { ...t, inicio: fim + 1 } : t))
}

/** O material inteiro como um tópico só (UC02 A3). */
export function umSo(f: Faixa[], nome: string): Faixa[] {
  if (f.some((t) => t.bloqueado)) return f
  return [{ ...f[0], nome: (f[0].nome || nome).slice(0, NOME_MAXIMO), fim: f[f.length - 1].fim }]
}

/** Erro por tópico (índice -> mensagem). A cobertura sem lacunas fica garantida pelas operações acima. */
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
