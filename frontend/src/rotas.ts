// Rotas do frontend (ADR 0018). Sem biblioteca: são poucas e o servidor já devolve index.html para qualquer caminho (nginx).
export type NomeRota = 'inicio' | 'perfil' | 'novo' | 'material' | 'entrar' | 'criar' | 'recuperar' | 'redefinir' | 'confirmar' | 'verificar' | 'desconhecida'

const CAMINHOS: Record<Exclude<NomeRota, 'desconhecida' | 'material'>, string> = {
  inicio: '/',
  perfil: '/perfil',
  novo: '/novo-estudo',
  entrar: '/entrar',
  criar: '/criar-conta',
  recuperar: '/recuperar-senha',
  redefinir: '/redefinir-senha', // abertas pelos links de e-mail, com ?token=
  confirmar: '/confirmar-email',
  verificar: '/verificar-email',
}

export const caminhoDe = (n: Exclude<NomeRota, 'desconhecida' | 'material'>) => CAMINHOS[n]

/** /materiais/<id>: a única rota com parâmetro. */
const MATERIAL = /^\/materiais\/([0-9a-f-]{36})$/i
export const idDoMaterial = (caminho: string = window.location.pathname) => MATERIAL.exec(caminho.replace(/\/+$/, ''))?.[1] ?? null
export const urlMaterial = (id: string) => `/materiais/${id}`
export const ehLink = (n: NomeRota) => n === 'redefinir' || n === 'confirmar' || n === 'verificar'
export const ehEntrada = (n: NomeRota) => n === 'entrar' || n === 'criar' || n === 'recuperar'
export const ehInterna = (n: NomeRota) => n === 'inicio' || n === 'perfil' || n === 'novo' || n === 'material'

export function rotaDaUrl(caminho: string = window.location.pathname): NomeRota {
  const limpo = caminho.length > 1 ? caminho.replace(/\/+$/, '') : caminho
  if (idDoMaterial(limpo)) return 'material'
  return (Object.entries(CAMINHOS).find(([, c]) => c === limpo)?.[0] as NomeRota | undefined) ?? 'desconhecida'
}

/** Para onde voltar depois do login (?proximo=/perfil). Só aceita telas internas conhecidas: nada de endereço de fora (open redirect). */
export function proximoDaUrl(): 'inicio' | 'perfil' {
  const valor = new URLSearchParams(window.location.search).get('proximo')
  return valor && rotaDaUrl(valor) === 'perfil' ? 'perfil' : 'inicio'
}

export function urlDe(nome: Exclude<NomeRota, 'desconhecida' | 'material'>, proximo?: 'inicio' | 'perfil'): string {
  return CAMINHOS[nome] + (proximo && proximo !== 'inicio' ? `?proximo=${encodeURIComponent(CAMINHOS[proximo])}` : '')
}
