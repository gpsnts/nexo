// Rotas do frontend (ADR 0018). Sem biblioteca: são poucas e o servidor já devolve index.html para qualquer caminho (nginx).
export type NomeRota = 'inicio' | 'perfil' | 'entrar' | 'criar' | 'recuperar' | 'redefinir' | 'confirmar' | 'verificar' | 'desconhecida'

const CAMINHOS: Record<Exclude<NomeRota, 'desconhecida'>, string> = {
  inicio: '/',
  perfil: '/perfil',
  entrar: '/entrar',
  criar: '/criar-conta',
  recuperar: '/recuperar-senha',
  redefinir: '/redefinir-senha', // abertas pelos links de e-mail, com ?token=
  confirmar: '/confirmar-email',
  verificar: '/verificar-email',
}

export const caminhoDe = (n: Exclude<NomeRota, 'desconhecida'>) => CAMINHOS[n]
export const ehLink = (n: NomeRota) => n === 'redefinir' || n === 'confirmar' || n === 'verificar'
export const ehEntrada = (n: NomeRota) => n === 'entrar' || n === 'criar' || n === 'recuperar'
export const ehInterna = (n: NomeRota) => n === 'inicio' || n === 'perfil'

export function rotaDaUrl(caminho: string = window.location.pathname): NomeRota {
  const limpo = caminho.length > 1 ? caminho.replace(/\/+$/, '') : caminho
  return (Object.entries(CAMINHOS).find(([, c]) => c === limpo)?.[0] as NomeRota | undefined) ?? 'desconhecida'
}

/** Para onde voltar depois do login (?proximo=/perfil). Só aceita telas internas conhecidas: nada de endereço de fora (open redirect). */
export function proximoDaUrl(): 'inicio' | 'perfil' {
  const valor = new URLSearchParams(window.location.search).get('proximo')
  return valor && rotaDaUrl(valor) === 'perfil' ? 'perfil' : 'inicio'
}

export function urlDe(nome: Exclude<NomeRota, 'desconhecida'>, proximo?: 'inicio' | 'perfil'): string {
  return CAMINHOS[nome] + (proximo && proximo !== 'inicio' ? `?proximo=${encodeURIComponent(CAMINHOS[proximo])}` : '')
}
