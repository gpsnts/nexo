import type { ReactNode } from 'react'

/** Ícones de traço (24x24), decorativos: o rótulo de texto sempre acompanha. */
const base = { viewBox: '0 0 24 24', width: 20, height: 20, fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, focusable: false } as const
/**
 * Moldura SVG comum dos ícones de traço.
 * @param props children: desenho interno; tam: tamanho em pixels.
 * @returns o elemento SVG.
 */
function Traco({ children, tam = 20 }: { children: ReactNode; tam?: number }) {
  return <svg {...base} width={tam} height={tam}>{children}</svg>
}

/**
 * Ícone de início, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeInicio({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5a1 1 0 0 0 1 1H10v-5.5h4v5.5h3.5a1 1 0 0 0 1-1V10" /></></Traco>
}
/**
 * Ícone de revisão, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeRevisar({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M20 12a8 8 0 1 1-2.6-5.9" /><path d="M20 4v4.5h-4.5" /><path d="m9 12 2.2 2.2L15.5 10" /></></Traco>
}
/**
 * Ícone de prova (folha com marcações), decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeProva({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M7 3.5h8l3.5 3.5v13a.5.5 0 0 1-.5.5H7a.5.5 0 0 1-.5-.5V4a.5.5 0 0 1 .5-.5Z" /><path d="M14.5 3.5V7H18" /><path d="m9 12 1.2 1.2L12.5 11" /><path d="M9 17h6" /></></Traco>
}
/**
 * Ícone de desempenho (barras), decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeDesempenho({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></></Traco>
}
/**
 * Ícone de perfil, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconePerfil({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><circle cx="12" cy="8" r="4" /><path d="M4 21c.8-4 4-6 8-6s7.2 2 8 6" /></></Traco>
}
/**
 * Ícone de sair, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeSair({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M9 4H5.5A1.5 1.5 0 0 0 4 5.5v13A1.5 1.5 0 0 0 5.5 20H9" /><path d="m16 8 4 4-4 4M20 12H9" /></></Traco>
}
/**
 * Ícone de adicionar, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeMais({ tam }: { tam?: number }) {
  return <Traco tam={tam}><path d="M12 5v14M5 12h14" /></Traco>
}
/**
 * Ícone de documento, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeDocumento({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M14 3H7.5A1.5 1.5 0 0 0 6 4.5v15A1.5 1.5 0 0 0 7.5 21h9a1.5 1.5 0 0 0 1.5-1.5V7z" /><path d="M14 3v4h4M9 12h6M9 16h6" /></></Traco>
}
/**
 * Ícone de faísca (tópico livre e IA), decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeFaisca({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" /><path d="M19 15l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z" /></></Traco>
}
/**
 * Ícone de envio de arquivo, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeUpload({ tam }: { tam?: number }) {
  return <Traco tam={tam}><><path d="M12 16V4M7 9l5-5 5 5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" /></></Traco>
}
/**
 * Ícone de lâmpada (dica), decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeLampada({ tam }: { tam?: number }) {
  return <Traco tam={tam}><path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z" /></Traco>
}
/**
 * Ícone de confirmação, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeCheck({ tam }: { tam?: number }) {
  return <Traco tam={tam}><path d="m5 12.5 4.5 4.5L19 7.5" /></Traco>
}
/**
 * Ícone de voltar, decorativo (o rótulo de texto acompanha).
 * @param props tam: tamanho em pixels (padrão 20).
 * @returns o SVG do ícone.
 */
export function IconeVoltar({ tam }: { tam?: number }) {
  return <Traco tam={tam}><path d="M19 12H5m6-6-6 6 6 6" /></Traco>
}

/**
 * Teia de nós (o "nexo" do logotipo), usada como textura discreta nos painéis em cor.
 * @param props className: classe CSS do SVG (padrão `rede`).
 * @returns o SVG decorativo.
 */
export function Rede({ className = 'rede' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 320 320" aria-hidden="true" focusable="false" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round">
      <path d="M40 270 120 120 220 230 290 60M120 120 60 60M220 230 280 290M120 120 230 120" />
      <circle cx="40" cy="270" r="14" fill="#fff" /><circle cx="120" cy="120" r="14" fill="#fff" /><circle cx="220" cy="230" r="14" fill="#fff" />
      <circle cx="290" cy="60" r="20" fill="#fbbf24" stroke="none" /><circle cx="60" cy="60" r="9" fill="#fff" /><circle cx="280" cy="290" r="9" fill="#fff" /><circle cx="230" cy="120" r="9" fill="#fff" />
    </svg>
  )
}

/**
 * Cenas simples para os estados vazios (usam a cor primária do tema).
 * @param props tipo: cena (estudos, revisao ou desempenho).
 * @returns o SVG da cena.
 */
export function Ilustracao({ tipo }: { tipo: 'estudos' | 'revisao' | 'desempenho' }) {
  const comum = { className: 'ilustracao', viewBox: '0 0 160 120', fill: 'none', 'aria-hidden': true, focusable: false } as const
  if (tipo === 'revisao')
    return (
      <svg {...comum}><circle cx="80" cy="62" r="40" fill="currentColor" opacity=".12" /><circle cx="80" cy="62" r="28" stroke="currentColor" strokeWidth="5" /><path d="m66 63 10 10 19-21" stroke="currentColor" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="132" cy="28" r="6" fill="#fbbf24" /><path d="M26 30l3 8 8 3-8 3-3 8-3-8-8-3 8-3z" fill="currentColor" opacity=".5" /></svg>
    )
  if (tipo === 'desempenho')
    return (
      <svg {...comum}><rect x="22" y="70" width="20" height="34" rx="6" fill="currentColor" opacity=".35" /><rect x="52" y="48" width="20" height="56" rx="6" fill="currentColor" opacity=".55" /><rect x="82" y="58" width="20" height="46" rx="6" fill="currentColor" opacity=".35" />
        <rect x="112" y="26" width="20" height="78" rx="6" fill="currentColor" /><circle cx="122" cy="16" r="6" fill="#fbbf24" /><path d="M16 108h132" stroke="currentColor" strokeWidth="3" strokeLinecap="round" opacity=".4" /></svg>
    )
  return (
    <svg {...comum}><rect x="30" y="26" width="76" height="82" rx="12" fill="currentColor" opacity=".18" transform="rotate(-8 68 67)" /><rect x="44" y="18" width="76" height="86" rx="12" fill="currentColor" opacity=".35" transform="rotate(4 82 61)" />
      <rect x="52" y="22" width="76" height="86" rx="12" fill="#fff" stroke="currentColor" strokeWidth="4" /><path d="M66 48h46M66 62h46M66 76h28" stroke="currentColor" strokeWidth="5" strokeLinecap="round" /><circle cx="130" cy="24" r="8" fill="#fbbf24" /></svg>
  )
}
