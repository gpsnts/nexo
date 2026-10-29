// Tipos e regras puras do fluxo de estudo. Sem React: testadas em estudo.test.ts.
/** Pasta (assunto) do usuário. */
export type Assunto = { id: string; nome: string; padrao: boolean }
/** Material enviado, como a API devolve. `titulo` é o nome dado ao estudo; `assuntoId` é a pasta (nulo até haver tópicos); `gerando` marca geração em curso. */
export type Material = { id: string; nomeArquivo: string; status: 'pendente' | 'processando' | 'pronto' | 'falhou'; motivoFalha: string | null; paginas: number | null; criadoEm: string; gerando?: boolean; titulo?: string | null; assuntoId?: string | null }
/** Andamento da indexação: status, etapa corrente e percentual (0 a 100). */
export type Andamento = { status: Material['status']; etapa: string; percentual: number; motivoFalha: string | null }
/** Tópico de um material, com a faixa de trechos, a prévia e se está congelado (`temConteudo`). */
export type Topico = { id: string; assuntoId: string; nome: string; ordem: number; inicio: number; fim: number; previa: string | null; temConteudo: boolean }
/** Rascunho de divisão proposto pela IA ("Reorganizar com IA"); só vale depois de confirmado. */
export type Rascunho = { topicos: { id: string | null; nome: string; inicio: number; fim: number; temConteudo: boolean }[]; mudou: boolean; modelo: string; tokensEntrada: number; tokensSaida: number }
/** Cota semanal de IA: a tela mostra a porcentagem e quando renova; `operacoes` é só a proteção por hora, que não aparece. */
export type Uso = { creditosUsados: number; creditosLimite: number; percentual: number; renovaEm: string; operacoes: number; limiteOperacoes: number; janelaOperacoesMinutos: number }
/** Estado de uma geração de resumo ou questões, consultado por polling. */
export type Geracao = {
  id: string; operacao: 'resumo' | 'questoes' | 'verificacao_questao'; status: 'pendente' | 'em_andamento' | 'concluida' | 'falhou'; progresso: number
  aprovadas: number; rejeitadas: number; repetidas: number; gravadas: number; taxaRejeicao: number | null; mensagem: string | null; verificacaoId: string | null; motivo: string | null
}
/** Detalhe de um tópico: fontes, resumo mais recente, contagens e a geração em curso ou a última. */
export type TopicoDetalhe = {
  id: string; assuntoId: string; nome: string; livre: boolean; fontes: { materialId: string; nomeArquivo: string; titulo?: string | null; inicio: number; fim: number }[]
  resumo: { conteudo: string; criadoEm: string } | null; totalQuestoes: number; maximoQuestoes: number; maximoResumos: number; geracaoAberta: string | null; ultimaGeracao: Geracao | null
  orientacao: string | null
}
/** Tópico livre (sem material), como aparece na lista inicial. */
export type TopicoLivre = { id: string; nome: string; criadoEm: string; totalQuestoes: number; temResumo: boolean; gerando?: boolean; assuntoId?: string | null }
export const LIMITE_LISTA = 100  // a API devolve no máximo isto por lista (os mais recentes)
/** Trecho do material que sustenta uma questão. */
export type Apoio = { nomeArquivo: string; ordem: number; numeroPagina: number | null }
/** Resumo de um tópico. */
export type Resumo = { conteudo: string; criadoEm: string }
/** Nível de dificuldade das questões. */
export type Dificuldade = 'facil' | 'media' | 'dificil'
export const NIVEIS: { valor: Dificuldade; rotulo: string; dica: string }[] = [
  { valor: 'facil', rotulo: 'Fácil', dica: 'Definições, termos e fatos que o conteúdo diz de forma direta.' },
  { valor: 'media', rotulo: 'Média', dica: 'Compreender e aplicar: explicar, reconhecer exemplos, relacionar pontos.' },
  { valor: 'dificil', rotulo: 'Difícil', dica: 'Analisar e comparar, com alternativas bem parecidas entre si.' },
]
/**
 * Nome de um nível de dificuldade.
 * @param n valor do nível (facil, media ou dificil).
 * @returns o rótulo em português; "Média" se for desconhecido ou ausente.
 */
export const rotuloDoNivel = (n?: string | null) => NIVEIS.find((x) => x.valor === n)?.rotulo ?? 'Média'
/** Questão de um tópico; a resposta correta só vem depois de respondida. */
export type Questao = { id: string; dificuldade: Dificuldade; enunciado: string; alternativas: string[]; respostaCorreta: number | null; dica: string | null; respondida: boolean; ultimaCorreta: boolean | null; proximaRevisao: string | null; criadoEm: string; apoio: Apoio[] }

/**
 * Junta números em texto: `1, 2 e 3`.
 * @param n números ou textos.
 * @returns a lista em português.
 */
const lista = (n: (string | number)[]) => (n.length > 1 ? `${n.slice(0, -1).join(', ')} e ${n[n.length - 1]}` : `${n[0]}`)
/**
 * "apostila.pdf, págs. 3 e 4" / "notas.txt, trecho 12": de onde o material tirou a resposta (só trechos citados na verificação).
 * @param apoio trechos que sustentam a questão.
 * @returns um texto por arquivo, separados por "; ".
 */
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
/**
 * Formata uma taxa como porcentagem.
 * @param taxa valor de 0 a 1.
 * @returns texto como "25%".
 */
export const percentualDe = (taxa: number) => `${Math.round(taxa * 100)}%`
/** Prévia de um trecho do material. */
export type Trecho = { ordem: number; numeroPagina: number | null; previa: string }

export const TAMANHO_MAXIMO_MB = 20

/** Avisos de privacidade (LGPD): o texto sai do sistema e vai a um provedor de IA externo; o aviso aparece onde isso acontece. */
export const AVISO_IA = {
  cadastro: 'Ao usar o Nexo, o conteúdo que você enviar é processado por um serviço externo de inteligência artificial para gerar resumos, questões e divisões em tópicos. Envie apenas material que você pode compartilhar.',
  material: 'O texto do material é enviado a um serviço externo de inteligência artificial para gerar resumos, questões e a divisão em tópicos. Envie apenas material que você pode compartilhar.',
  topicoLivre: 'O nome do tópico é enviado a um serviço externo de inteligência artificial para gerar o conteúdo.',
  reorganizar: 'O início de cada trecho é enviado a um serviço externo de inteligência artificial.',
  topico: 'Os trechos usados são enviados a um serviço externo de inteligência artificial para gerar o conteúdo.',
  nome: 'Os nomes dos tópicos e o início do material são enviados a um serviço externo de inteligência artificial para sugerir o nome.',
  chat: 'Suas perguntas e os trechos do tópico são enviados a um serviço externo de inteligência artificial. A conversa fica salva neste tópico até você limpá-la.',
  dica: 'A questão e os trechos que a sustentam são enviados a um serviço externo de inteligência artificial para gerar a dica.',
  topicoLivreGeracao: 'O nome do tópico é enviado a um serviço externo de inteligência artificial.',
}
export const FORMATOS = ['pdf', 'docx', 'txt']
export const NOME_MAXIMO = 200
export const ORIENTACAO_MAXIMA = 500  // igual ao limite do backend

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

/**
 * Mensagem para um código de falha do processamento.
 * @param codigo código do motivo, ou nulo.
 * @returns a mensagem clara; uma genérica se o código for desconhecido.
 */
export const motivoDe = (codigo: string | null) => (codigo && MOTIVOS[codigo]) || 'Não foi possível processar o arquivo.'

/**
 * Mesmas regras do backend para falhar cedo; o servidor continua sendo a autoridade (a extensão não prova o conteúdo).
 * @param a arquivo escolhido, ou nulo.
 * @returns a mensagem de erro, ou texto vazio se o arquivo servir.
 */
export function erroArquivo(a: File | null): string {
  if (!a) return 'Escolha um arquivo.'
  const ext = a.name.split('.').pop()?.toLowerCase() ?? ''
  if (!FORMATOS.includes(ext)) return 'Formato não aceito. Envie PDF, DOCX ou TXT.'
  if (a.size === 0) return 'O arquivo está vazio.'
  if (a.size > TAMANHO_MAXIMO_MB * 1024 * 1024) return `O arquivo passa de ${TAMANHO_MAXIMO_MB} MB.`
  return ''
}

// ---- revisão da proposta de tópicos: tudo local até "Confirmar" (um PUT atômico)
// bloqueado: o tópico já tem resumo ou questões; só o nome pode mudar (o servidor também recusa)
/** Faixa de um tópico na tela de revisão. `chave` identifica o item na lista; `bloqueado` marca tópico com resumo ou questões (só o nome muda). */
export type Faixa = { chave: string; id: string | null; nome: string; inicio: number; fim: number; bloqueado: boolean }

let contador = 0
/**
 * Gera uma chave única para um tópico novo na tela.
 * @returns a chave, como `n1`.
 */
export const novaChave = () => `n${++contador}`

/**
 * Converte os tópicos da API em faixas editáveis.
 * @param t tópicos do material.
 * @returns as faixas.
 */
export const faixasDe = (t: Topico[]): Faixa[] => t.map((x) => ({ chave: x.id, id: x.id, nome: x.nome, inicio: x.inicio, fim: x.fim, bloqueado: x.temConteudo }))

/**
 * Diz se o tópico i termina logo antes do início do seguinte (sem trechos de fora entre eles).
 * @param f faixas.
 * @param i índice do tópico.
 * @returns verdadeiro se estão coladas.
 */
const colados = (f: Faixa[], i: number) => f[i].fim + 1 === f[i + 1].inicio

/**
 * Une o tópico i com o seguinte (só se não há trechos de fora entre eles): fica o nome e o id do primeiro.
 * @param f faixas.
 * @param i índice do primeiro tópico.
 * @returns as novas faixas (as mesmas se a união não for permitida).
 */
export function unir(f: Faixa[], i: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || f[i + 1].bloqueado || !colados(f, i)) return f
  return [...f.slice(0, i), { ...f[i], fim: f[i + 1].fim }, ...f.slice(i + 2)]
}

/**
 * Divide o tópico i depois do trecho `apos`; o novo tópico não tem id (é criado no servidor).
 * @param f faixas.
 * @param i índice do tópico.
 * @param apos último trecho do primeiro pedaço.
 * @returns as novas faixas (as mesmas se a divisão não for permitida).
 */
export function dividir(f: Faixa[], i: number, apos: number): Faixa[] {
  const t = f[i]
  if (!t || t.bloqueado || apos < t.inicio || apos >= t.fim) return f
  return [...f.slice(0, i), { ...t, fim: apos }, { chave: novaChave(), id: null, nome: `${t.nome} (continuação)`.slice(0, NOME_MAXIMO), inicio: apos + 1, fim: t.fim, bloqueado: false }, ...f.slice(i + 1)]
}

/**
 * Muda onde o tópico i termina. Colado no seguinte, move a fronteira dos dois (o seguinte começa logo depois); com trechos de fora entre eles, só o tópico i muda e `fim` vai até o último trecho antes do seguinte.
 * @param f faixas.
 * @param i índice do tópico.
 * @param fim novo último trecho.
 * @returns as novas faixas (as mesmas se o ajuste não for permitido).
 */
export function ajustar(f: Faixa[], i: number, fim: number): Faixa[] {
  if (i < 0 || i >= f.length - 1 || f[i].bloqueado || fim < f[i].inicio) return f
  if (!colados(f, i)) return fim < f[i + 1].inicio ? f.map((t, k) => (k === i ? { ...t, fim } : t)) : f
  if (f[i + 1].bloqueado || fim >= f[i + 1].fim) return f
  return f.map((t, k) => (k === i ? { ...t, fim } : k === i + 1 ? { ...t, inicio: fim + 1 } : t))
}

/**
 * O material inteiro (de 1 a `total`) como um tópico só.
 * @param f faixas atuais.
 * @param nome nome sugerido, usado se a primeira faixa não tiver.
 * @param total quantidade de trechos.
 * @returns uma única faixa (as mesmas faixas se houver tópico bloqueado).
 */
export function umSo(f: Faixa[], nome: string, total: number): Faixa[] {
  if (f.some((t) => t.bloqueado)) return f
  return [{ ...f[0], nome: (f[0].nome || nome).slice(0, NOME_MAXIMO), inicio: 1, fim: total }]
}

/**
 * Tira o tópico i da divisão: os trechos dele ficam de fora (continuam no material, sem resumo nem questões). Sempre sobra ao menos um tópico.
 * @param f faixas.
 * @param i índice do tópico.
 * @returns as novas faixas (as mesmas se não for permitido).
 */
export function excluir(f: Faixa[], i: number): Faixa[] {
  if (!f[i] || f[i].bloqueado || f.length < 2) return f
  return f.filter((_, k) => k !== i)
}

/**
 * Trechos que nenhum tópico usa.
 * @param f faixas.
 * @param total quantidade de trechos.
 * @returns faixas [de, ate] dos trechos de fora.
 */
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

/**
 * Cria um tópico novo com os trechos de fora de/ate (volta a usá-los).
 * @param f faixas.
 * @param de primeiro trecho.
 * @param ate último trecho.
 * @returns as faixas com o novo tópico na posição certa.
 */
export function criarNaLacuna(f: Faixa[], de: number, ate: number): Faixa[] {
  const novo: Faixa = { chave: novaChave(), id: null, nome: `Trechos ${de} a ${ate}`, inicio: de, fim: ate, bloqueado: false }
  const i = f.findIndex((t) => t.inicio > ate)
  return i < 0 ? [...f, novo] : [...f.slice(0, i), novo, ...f.slice(i)]
}

/**
 * Erro por tópico. Ordem e sobreposição ficam garantidas pelas operações acima; lacunas são permitidas.
 * @param f faixas.
 * @returns mapa de índice para mensagem, só dos tópicos com nome inválido.
 */
export function errosDeNome(f: Faixa[]): Record<number, string> {
  const e: Record<number, string> = {}
  f.forEach((t, i) => {
    if (!t.nome.trim()) e[i] = 'Informe o nome do tópico.'
    else if (t.nome.trim().length > NOME_MAXIMO) e[i] = `O nome pode ter até ${NOME_MAXIMO} caracteres.`
  })
  return e
}

/**
 * Compara duas listas de faixas por id, nome e limites.
 * @param a primeira lista.
 * @param b segunda lista.
 * @returns verdadeiro se são iguais.
 */
export const igual = (a: Faixa[], b: Faixa[]) => a.length === b.length && a.every((x, i) => x.id === b[i].id && x.nome === b[i].nome && x.inicio === b[i].inicio && x.fim === b[i].fim)

/**
 * "págs. 3 a 9" a partir da página de cada trecho (índice = ordem - 1); vazio em DOCX e TXT, que não têm páginas.
 * @param paginas página de cada trecho.
 * @param inicio primeiro trecho do tópico.
 * @param fim último trecho do tópico.
 * @returns o rótulo, ou texto vazio se não houver páginas.
 */
export function rotuloPaginas(paginas: (number | null)[], inicio: number, fim: number): string {
  const a = paginas[inicio - 1]
  const b = paginas[fim - 1]
  if (a == null || b == null) return ''
  return a === b ? `pág. ${a}` : `págs. ${a} a ${b}`
}

/**
 * Formata um tamanho de arquivo.
 * @param bytes tamanho em bytes.
 * @returns texto como "120 KB" ou "1,5 MB".
 */
export function formatarTamanho(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`
}

/**
 * Quanto da cota da semana já foi usado.
 * @param u uso corrente.
 * @returns percentual de 0 a 100.
 */
export const percentualUso = (u: Uso) => Math.min(100, Math.max(0, u.percentual))

/**
 * ok até 79%, atencao a partir de 80%, esgotado em 100% (o servidor passa a recusar as operações com IA até a cota renovar).
 * @param u uso corrente.
 * @returns o nível do uso.
 */
export const nivelDoUso = (u: Uso): 'ok' | 'atencao' | 'esgotado' => (u.percentual >= 100 ? 'esgotado' : u.percentual >= 80 ? 'atencao' : 'ok')

/**
 * "segunda-feira, 19/10, 00:00": quando a cota volta.
 * @param iso instante da renovação (ISO 8601).
 * @returns o texto em português.
 */
export const quandoRenova = (iso: string) => new Date(iso).toLocaleString('pt-BR', { weekday: 'long', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

/** Quem gasta IA (envio, reorganização, fim da indexação) avisa; a barra do cabeçalho consulta o uso de novo. */
export const EVENTO_USO = 'nexo:uso'
/**
 * Quem gasta IA (envio, reorganização, fim da indexação) avisa; a barra do cabeçalho consulta o uso de novo.
 * @returns nada; dispara o evento `nexo:uso` na janela.
 */
export const avisarUso = () => window.dispatchEvent(new Event(EVENTO_USO))

// Estudo, revisão e desempenho
/** Resultado de uma resposta: se acertou, qual era a correta e a próxima revisão. */
export type RespostaDada = { correta: boolean; respostaCorreta: number; proximaRevisao: string }
/** Questão apresentada para responder (sem a resposta correta). */
export type QuestaoParaResponder = { id: string; dificuldade?: Dificuldade; enunciado: string; alternativas: string[]; topico?: string; dica?: string | null }
/** Sessão de revisão do dia: tópicos com questões vencidas, questões da sessão e tópicos recentes. */
export type Revisao = { recentes?: { id: string; nome: string }[]; topicos: { id: string; nome: string; vencidas: number; taxaErro: number }[]; questoes: (QuestaoParaResponder & { topicoId: string })[] }
/** Respostas de uma semana (total e acertos). */
export type Ponto = { semana: string; total: number; acertos: number }
/** Série semanal de um assunto ou tópico. */
export type Serie = { id: string; nome: string; total: number; acertos: number; pontos: Ponto[] }
/** Painel de desempenho: semanas do período, assuntos com a série de cada tópico e os tópicos mais fracos. */
export type Desempenho = {
  vazio: boolean; semanas: string[]
  assuntos: (Serie & { topicos: Serie[] })[]
  fracos: { id: string; nome: string; assunto: string; total: number; acertos: number; percentual: number }[]
}
/**
 * Percentual de acertos.
 * @param acertos respostas certas.
 * @param total respostas.
 * @returns percentual inteiro; 0 se não houver respostas.
 */
export const percentual = (acertos: number, total: number) => (total === 0 ? 0 : Math.round((acertos * 100) / total))

/** Mensagem do chat de um tópico. */
export type Mensagem = { id: string; papel: 'usuario' | 'assistente'; conteudo: string; criadoEm: string }

/**
 * O nome do estudo é o que o estudante escolheu; sem escolha, o nome do arquivo.
 * @param m material com `titulo` e `nomeArquivo`.
 * @returns o nome a mostrar.
 */
export const nomeDoEstudo = (m: { titulo?: string | null; nomeArquivo: string }) => m.titulo || m.nomeArquivo

/**
 * Cada estudo ganha uma das 8 capas, sempre a mesma para o mesmo nome (a CSP proíbe estilo inline, então a cor vem de uma classe).
 * @param texto nome do estudo.
 * @returns número da capa, de 0 a 7.
 */
export const capaDe = (texto: string) => [...texto].reduce((n, c) => n + c.charCodeAt(0), 0) % 8
