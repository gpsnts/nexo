/**
 * Blocos que pulsam no lugar do conteúdo enquanto carrega. O texto "Carregando…" fica para leitores de tela.
 * @param props blocos: quantidade de blocos (padrão 3).
 * @returns o indicador de carregamento.
 */
export default function Carregando({ blocos = 3 }: { blocos?: number }) {
  return (
    <div className="esqueleto" role="status">
      <span className="oculto">Carregando…</span>
      {Array.from({ length: blocos }, (_, i) => <i key={i} />)}
    </div>
  )
}
