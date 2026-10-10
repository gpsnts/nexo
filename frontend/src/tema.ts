export type Tema = 'sistema' | 'claro' | 'escuro'
const CHAVE = 'nexo-tema'
const COR_CLARA = '#f6f7fb'
const COR_ESCURA = '#0f1020'

// localStorage pode lançar (navegação privada, dados bloqueados): o tema é conveniência, nunca pode quebrar a página.
export function lerTema(): Tema {
  try {
    const v = localStorage.getItem(CHAVE)
    return v === 'claro' || v === 'escuro' ? v : 'sistema'
  } catch {
    return 'sistema'
  }
}

/** Cor da barra do navegador no celular: acompanha o tema escolhido (as duas metas ficam iguais quando o tema é forçado). */
function ajustarBarra(t: Tema) {
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((m) => {
    const escura = (m.getAttribute('media') ?? '').includes('dark')
    m.content = t === 'claro' ? COR_CLARA : t === 'escuro' ? COR_ESCURA : escura ? COR_ESCURA : COR_CLARA
  })
}

/** "sistema" remove o atributo e deixa valer prefers-color-scheme; "claro" e "escuro" o forçam. */
export function aplicarTema(t: Tema) {
  const raiz = document.documentElement
  if (t === 'sistema') raiz.removeAttribute('data-theme')
  else raiz.setAttribute('data-theme', t === 'claro' ? 'light' : 'dark')
  ajustarBarra(t)
  try {
    if (t === 'sistema') localStorage.removeItem(CHAVE)
    else localStorage.setItem(CHAVE, t)
  } catch { /* sem armazenamento: vale só nesta visita */ }
}
