// Indicador NÃO sensível de "já houve login neste navegador". Evita sondar /auth/refresh (e levar 401 no console) em quem nunca entrou.
const CHAVE = 'nexo-sessao'
export const chaveSessao = CHAVE // as outras abas escutam o evento storage desta chave

export function talvezTenhaSessao(): boolean {
  try {
    return localStorage.getItem(CHAVE) === '1'
  } catch {
    return true // sem armazenamento: melhor sondar do que deixar a pessoa deslogada
  }
}

export function marcarSessao(ativa: boolean) {
  try {
    if (ativa) localStorage.setItem(CHAVE, '1')
    else localStorage.removeItem(CHAVE)
  } catch { /* ignorado */ }
}
