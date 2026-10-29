// Indicador NÃO sensível de "já houve login neste navegador". Evita sondar /auth/refresh (e levar 401 no console) em quem nunca entrou.
const CHAVE = 'nexo-sessao'
export const chaveSessao = CHAVE // as outras abas escutam o evento storage desta chave

/**
 * Indica se já houve login neste navegador.
 * @returns verdadeiro se marcado (ou se não há armazenamento: melhor sondar do que deixar a pessoa deslogada).
 */
export function talvezTenhaSessao(): boolean {
  try {
    return localStorage.getItem(CHAVE) === '1'
  } catch {
    return true // sem armazenamento: melhor sondar do que deixar a pessoa deslogada
  }
}

/**
 * Marca ou desmarca que há sessão neste navegador.
 * @param ativa verdadeiro após login; falso ao sair.
 */
export function marcarSessao(ativa: boolean) {
  try {
    if (ativa) localStorage.setItem(CHAVE, '1')
    else localStorage.removeItem(CHAVE)
  } catch { /* ignorado */ }
}
