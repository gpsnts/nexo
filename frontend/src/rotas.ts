// Rotas do frontend. Sem biblioteca: são poucas e o servidor já devolve index.html para qualquer caminho (nginx).
export const EVENTO_ROTA = 'nexo:rota'
/**
 * Navega para um caminho interno (pushState + aviso ao App).
 * @param caminho caminho interno, como `/perfil`.
 */
export function irPara(caminho: string) {
  window.history.pushState(null, '', caminho)
  window.dispatchEvent(new Event(EVENTO_ROTA))
}
/** Nome de uma tela do frontend, derivado da URL. */
export type NomeRota = 'inicio' | 'perfil' | 'novo' | 'material' | 'topico' | 'pasta' | 'prova' | 'provas' | 'revisar' | 'historico' | 'desempenho' | 'entrar' | 'criar' | 'recuperar' | 'redefinir' | 'confirmar' | 'verificar' | 'desconhecida'

const CAMINHOS: Record<Exclude<NomeRota, 'desconhecida' | 'material' | 'topico' | 'pasta' | 'prova'>, string> = {
  inicio: '/',
  perfil: '/perfil',
  novo: '/novo-estudo',
  revisar: '/revisar',
  provas: '/provas',
  historico: '/historico',
  desempenho: '/desempenho',
  entrar: '/entrar',
  criar: '/criar-conta',
  recuperar: '/recuperar-senha',
  redefinir: '/redefinir-senha', // abertas pelos links de e-mail, com ?token=
  confirmar: '/confirmar-email',
  verificar: '/verificar-email',
}


/** /materiais/<id>: a única rota com parâmetro. */
const MATERIAL = /^\/materiais\/([0-9a-f-]{36})$/i
/**
 * Id do material na URL (`/materiais/<id>`).
 * @param caminho caminho a examinar; por padrão, o atual.
 * @returns o id, ou nulo se o caminho não for de material.
 */
export const idDoMaterial = (caminho: string = window.location.pathname) => MATERIAL.exec(caminho.replace(/\/+$/, ''))?.[1] ?? null
/**
 * Monta a URL de um material.
 * @param id material.
 * @returns `/materiais/<id>`.
 */
export const urlMaterial = (id: string) => `/materiais/${id}`
/** /topicos/<id>: resumo e questões de um tópico. */
const TOPICO = /^\/topicos\/([0-9a-f-]{36})$/i
/**
 * Id do tópico na URL (`/topicos/<id>`).
 * @param caminho caminho a examinar; por padrão, o atual.
 * @returns o id, ou nulo se o caminho não for de tópico.
 */
export const idDoTopico = (caminho: string = window.location.pathname) => TOPICO.exec(caminho.replace(/\/+$/, ''))?.[1] ?? null
/**
 * Monta a URL de um tópico.
 * @param id tópico.
 * @returns `/topicos/<id>`.
 */
export const urlTopico = (id: string) => `/topicos/${id}`
/**
 * Abre o tópico já com o cursor em "Quantas questões gerar" (atalho "Gerar novas questões" da revisão e do início).
 * @param id tópico.
 * @returns a URL do tópico com `?gerar=1`.
 */
export const urlTopicoGerar = (id: string) => `${urlTopico(id)}?gerar=1`
/** /pastas/<id>: tópicos de uma pasta (ordem, mudança de pasta, fontes). */
const PASTA = /^\/pastas\/([0-9a-f-]{36})$/i
/**
 * Id da pasta na URL (`/pastas/<id>`).
 * @param caminho caminho a examinar; por padrão, o atual.
 * @returns o id, ou nulo se o caminho não for de pasta.
 */
export const idDaPasta = (caminho: string = window.location.pathname) => PASTA.exec(caminho.replace(/\/+$/, ''))?.[1] ?? null
/**
 * Monta a URL de uma pasta.
 * @param id pasta.
 * @returns `/pastas/<id>`.
 */
export const urlPasta = (id: string) => `/pastas/${id}`
/** /provas/<id>: uma prova (responder ou ver o resultado). */
const PROVA = /^\/provas\/([0-9a-f-]{36})$/i
/**
 * Id da prova na URL (`/provas/<id>`).
 * @param caminho caminho a examinar; por padrão, o atual.
 * @returns o id, ou nulo se o caminho não for de prova.
 */
export const idDaProva = (caminho: string = window.location.pathname) => PROVA.exec(caminho.replace(/\/+$/, ''))?.[1] ?? null
/**
 * Monta a URL de uma prova.
 * @param id prova.
 * @returns `/provas/<id>`.
 */
export const urlProva = (id: string) => `/provas/${id}`
/**
 * Diz se a rota é aberta por link de e-mail.
 * @param n nome da rota.
 * @returns verdadeiro para redefinir, confirmar e verificar.
 */
export const ehLink = (n: NomeRota) => n === 'redefinir' || n === 'confirmar' || n === 'verificar'
/**
 * Diz se a rota é de entrada (sem login).
 * @param n nome da rota.
 * @returns verdadeiro para entrar, criar e recuperar.
 */
export const ehEntrada = (n: NomeRota) => n === 'entrar' || n === 'criar' || n === 'recuperar'
/**
 * Diz se a rota exige login.
 * @param n nome da rota.
 * @returns verdadeiro para as telas internas.
 */
export const ehInterna = (n: NomeRota) => n === 'inicio' || n === 'perfil' || n === 'novo' || n === 'revisar' || n === 'historico' || n === 'desempenho' || n === 'material' || n === 'topico' || n === 'pasta' || n === 'prova' || n === 'provas'

/**
 * Descobre a tela de um caminho.
 * @param caminho caminho a examinar; por padrão, o atual.
 * @returns o nome da rota, ou `desconhecida`.
 */
export function rotaDaUrl(caminho: string = window.location.pathname): NomeRota {
  const limpo = caminho.length > 1 ? caminho.replace(/\/+$/, '') : caminho
  if (idDoMaterial(limpo)) return 'material'
  if (idDoTopico(limpo)) return 'topico'
  if (idDaPasta(limpo)) return 'pasta'
  if (idDaProva(limpo)) return 'prova'
  return (Object.entries(CAMINHOS).find(([, c]) => c === limpo)?.[0] as NomeRota | undefined) ?? 'desconhecida'
}

/**
 * Para onde voltar depois do login (?proximo=/perfil). Só aceita telas internas conhecidas: nada de endereço de fora (open redirect).
 * @returns `perfil` se pedido, senão `inicio`.
 */
export function proximoDaUrl(): 'inicio' | 'perfil' {
  const valor = new URLSearchParams(window.location.search).get('proximo')
  return valor && rotaDaUrl(valor) === 'perfil' ? 'perfil' : 'inicio'
}

/**
 * Monta a URL de uma tela fixa.
 * @param nome nome da rota.
 * @param proximo tela para onde voltar depois do login, opcional.
 * @returns o caminho, com `?proximo=` quando houver.
 */
export function urlDe(nome: Exclude<NomeRota, 'desconhecida' | 'material' | 'topico' | 'pasta' | 'prova'>, proximo?: 'inicio' | 'perfil'): string {
  return CAMINHOS[nome] + (proximo && proximo !== 'inicio' ? `?proximo=${encodeURIComponent(CAMINHOS[proximo])}` : '')
}
