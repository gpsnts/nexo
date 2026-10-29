import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { EVENTO_USO, nivelDoUso, percentualUso, quandoRenova, type Uso } from './estudo'

/**
 * Barra de uso de IA no cabeçalho de todas as telas internas: mostra a porcentagem da cota semanal e, ao clicar, os detalhes e quando renova. Atualiza ao abrir cada tela e quando algo gasta IA (envio, reorganização, fim da indexação). Sem o dado (falha ao consultar), a barra simplesmente não aparece: ela só informa, quem decide é o servidor.
 * @returns a barra, ou nada se o uso não estiver disponível.
 */
export default function BarraUso() {
  const [uso, setUso] = useState<Uso | null>(null)
  const [aberto, setAberto] = useState(false)
  const raiz = useRef<HTMLDivElement>(null)

  const carregar = useCallback(() => { api<Uso>('/uso').then(setUso).catch(() => {}) }, [])
  useEffect(() => {
    carregar()
    window.addEventListener(EVENTO_USO, carregar)
    return () => window.removeEventListener(EVENTO_USO, carregar)
  }, [carregar])

  useEffect(() => {
    if (!aberto) return
    /**
     * Fecha o painel ao clicar fora.
     * @param e evento de mouse.
     */
    const fora = (e: MouseEvent) => { if (!raiz.current?.contains(e.target as Node)) setAberto(false) }
    /**
     * Fecha o painel com a tecla Esc.
     * @param e evento de teclado.
     */
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setAberto(false) }
    document.addEventListener('mousedown', fora)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', fora); document.removeEventListener('keydown', esc) }
  }, [aberto])

  if (!uso) return null
  const pct = percentualUso(uso)
  const nivel = nivelDoUso(uso)
  const renova = quandoRenova(uso.renovaEm)

  return (
    <div className="uso-barra" ref={raiz}>
      <button type="button" className="uso-botao" aria-expanded={aberto} aria-controls="painel-uso" onClick={() => setAberto(!aberto)}>
        <span className="uso-rotulo">Uso de IA <strong>{pct}%</strong></span>
        <progress className={`barra uso ${nivel}`} max={100} value={pct} aria-label="Uso de IA" aria-valuetext={`${pct}% da cota da semana usada. Renova ${renova}.`} />
      </button>
      {aberto && (
        <div id="painel-uso" className="uso-painel" role="region" aria-label="Detalhes do uso de IA">
          <p className="uso-linha"><span>Cota da semana</span><progress className={`barra uso ${nivel}`} max={100} value={pct} aria-label="Cota da semana" /><small>{pct}% usada · {uso.creditosUsados} de {uso.creditosLimite} créditos</small></p>
          <p className="sub dica">Renova {renova}.</p>
          {nivel === 'atencao' && <p className="aviso-uso">Sua cota está acabando. Ela renova {renova}.</p>}
          {nivel === 'esgotado' && <p className="erro">Cota da semana esgotada: novas operações com IA ficam bloqueadas até {renova}.</p>}
        </div>
      )}
    </div>
  )
}
