import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { EVENTO_USO, formatarDolar, nivelDoUso, percentualUso, type Uso } from './estudo'

/**
 * Barra de uso de IA no cabeçalho de todas as telas internas (ADR 0033): mostra o que está mais perto do limite (operações por hora ou gasto
 * na janela). Clicar abre os detalhes. Atualiza ao abrir cada tela e quando algo gasta IA (envio, reorganização, fim da indexação).
 * Sem o dado (falha ao consultar), a barra simplesmente não aparece: ela só informa, quem decide é o servidor.
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
    const fora = (e: MouseEvent) => { if (!raiz.current?.contains(e.target as Node)) setAberto(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setAberto(false) }
    document.addEventListener('mousedown', fora)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', fora); document.removeEventListener('keydown', esc) }
  }, [aberto])

  if (!uso) return null
  const pct = percentualUso(uso)
  const nivel = nivelDoUso(uso)
  const pctOps = Math.min(100, Math.round((uso.operacoes / uso.limiteOperacoes) * 100))
  const pctGasto = Math.min(100, Math.round((uso.gastoUsd / uso.limiteGastoUsd) * 100))
  const resumoOps = `${uso.operacoes} de ${uso.limiteOperacoes} operações ${uso.janelaOperacoesMinutos === 60 ? 'na última hora' : `nos últimos ${uso.janelaOperacoesMinutos} minutos`}`
  const resumoGasto = `${formatarDolar(uso.gastoUsd)} de ${formatarDolar(uso.limiteGastoUsd)} em ${uso.janelaGastoDias} dias`

  return (
    <div className="uso-barra" ref={raiz}>
      <button type="button" className="uso-botao" aria-expanded={aberto} aria-controls="painel-uso" onClick={() => setAberto(!aberto)}>
        <span className="uso-rotulo">Uso de IA</span>
        <progress className={`barra uso ${nivel}`} max={100} value={pct} aria-label="Uso de IA" aria-valuetext={`${pct}% do limite. ${resumoOps}; ${resumoGasto}`} />
      </button>
      {aberto && (
        <div id="painel-uso" className="uso-painel" role="region" aria-label="Detalhes do uso de IA">
          <p className="uso-linha"><span>Operações</span><progress className={`barra uso ${nivelDoUso({ ...uso, gastoUsd: 0 })}`} max={100} value={pctOps} aria-label="Operações com IA" /><small>{resumoOps}</small></p>
          <p className="uso-linha"><span>Gasto</span><progress className={`barra uso ${nivelDoUso({ ...uso, operacoes: 0 })}`} max={100} value={pctGasto} aria-label="Gasto com IA" /><small>{resumoGasto}</small></p>
          {nivel === 'esgotado' && <p className="erro">Limite atingido: novas operações com IA ficam bloqueadas até o uso antigo sair da janela.</p>}
        </div>
      )}
    </div>
  )
}
