import Carregando from './Esqueleto'
import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { Ilustracao } from './Icones'
import { percentual, type Desempenho as Dados, type Ponto, type Serie } from './estudo'
import { irPara } from './rotas'
import { dataIso } from './tempo'
import { useTitulo } from './titulo'

const L = 320, A = 140, ME = 34, MD = 8, MT = 8, MB = 22 // moldura do gráfico (unidades do viewBox)

/**
 * Linha do percentual de acertos por semana. Semana sem resposta fica sem ponto e a linha não a atravessa.
 * @param props semanas: segundas-feiras do período; pontos: respostas por semana; nome: assunto ou tópico, para o texto alternativo.
 * @returns o gráfico SVG.
 */
function Grafico({ semanas, pontos, nome }: { semanas: string[]; pontos: Ponto[]; nome: string }) {
  /**
   * Posição horizontal de uma semana no gráfico.
   * @param i índice da semana.
   * @returns coordenada x no viewBox.
   */
  const x = (i: number) => ME + (semanas.length === 1 ? (L - ME - MD) / 2 : (i * (L - ME - MD)) / (semanas.length - 1))
  /**
   * Posição vertical de um percentual no gráfico.
   * @param p percentual de 0 a 100.
   * @returns coordenada y no viewBox.
   */
  const y = (p: number) => MT + ((100 - p) * (A - MT - MB)) / 100
  const por = new Map(pontos.map((p) => [p.semana, p]))
  const trechos: string[][] = [[]]
  semanas.forEach((s, i) => {
    const p = por.get(s)
    if (p) trechos[trechos.length - 1].push(`${x(i)},${y(percentual(p.acertos, p.total))}`)
    else if (trechos[trechos.length - 1].length) trechos.push([])
  })
  return (
    <svg className="grafico" viewBox={`0 0 ${L} ${A}`} role="img" aria-label={`Evolução semanal de acertos em ${nome}. Os mesmos dados estão na tabela abaixo.`}>
      {[0, 50, 100].map((v) => <g key={v}><line x1={ME} x2={L - MD} y1={y(v)} y2={y(v)} className="grade-y" /><text x={ME - 4} y={y(v) + 3} textAnchor="end">{v}%</text></g>)}
      {semanas.map((s, i) => (semanas.length <= 4 || (semanas.length - 1 - i) % 2 === 0) && <text key={s} x={x(i)} y={A - 6} textAnchor={i === 0 ? 'start' : i === semanas.length - 1 ? 'end' : 'middle'}>{dataIso(s).slice(0, 5)}</text>)}
      {trechos.filter((t) => t.length > 1).map((t, k) => <polygon key={`a${k}`} points={`${t[0].split(',')[0]},${A - MB} ${t.join(' ')} ${t[t.length - 1].split(',')[0]},${A - MB}`} className="area" />)}
      {trechos.filter((t) => t.length > 1).map((t, k) => <polyline key={k} points={t.join(' ')} className="linha-g" />)}
      {semanas.map((s, i) => { const p = por.get(s); return p && <circle key={s} cx={x(i)} cy={y(percentual(p.acertos, p.total))} r={3.5} className="ponto"><title>{`Semana de ${dataIso(s).slice(0, 5)}: ${percentual(p.acertos, p.total)}% (${p.acertos} de ${p.total})`}</title></circle> })}
    </svg>
  )
}

/**
 * Semanas em que o assunto teve resposta: colunas vazias só ocupam a tela do celular.
 * @param a série do assunto ou tópico.
 * @returns as semanas com resposta.
 */
const semanasComDados = (a: Serie) => a.pontos.map((p) => p.semana)
/**
 * Texto da célula da tabela de uma semana.
 * @param s série.
 * @param semana segunda-feira da semana.
 * @returns "50% (1/2)", ou "–" sem respostas.
 */
const celula = (s: Serie, semana: string) => { const p = s.pontos.find((q) => q.semana === semana); return p ? `${percentual(p.acertos, p.total)}% (${p.acertos}/${p.total})` : '–' }

/**
 * Painel de desempenho: evolução semanal (segunda a domingo) por assunto e por tópico, e os tópicos mais fracos.
 * @param props aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoAbrirTopico: abre um tópico.
 * @returns a tela.
 */
export default function Desempenho({ aoIrInicio, aoIrPerfil, aoSair, aoAbrirTopico }: { aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirTopico: (id: string) => void }) {
  const [dados, setDados] = useState<Dados | null>(null)
  const [falha, setFalha] = useState(false)
  useTitulo('Desempenho', true)
  const respostasTotais = dados?.assuntos.reduce((n, a) => n + a.total, 0) ?? 0
  const acertosTotais = dados?.assuntos.reduce((n, a) => n + a.acertos, 0) ?? 0
  /**
   * Busca o painel na API.
   * @returns promessa resolvida quando termina.
   */
  const buscar = () => api<Dados>('/desempenho').then(setDados).catch(() => setFalha(true))
  /** Busca de novo depois de uma falha. */
  const carregar = () => { setFalha(false); buscar() }
  useEffect(() => { buscar() }, [])
  return (
    <div className="pagina">
      <Cabecalho atual="desempenho" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1>Desempenho</h1>
        {falha && <div role="alert"><p className="erro">Não foi possível carregar o desempenho.</p><button type="button" onClick={carregar}>Tentar de novo</button></div>}
        {!falha && dados === null && <Carregando />}
        {dados?.vazio && (
          <div className="vazio" role="status">
            <Ilustracao tipo="desempenho" />
            <p><strong>Ainda não há histórico para exibir.</strong></p>
            <p className="sub">Responda a algumas questões e a evolução semanal aparece aqui.</p>
            <button type="button" onClick={aoIrInicio}>Ir ao início</button>
          </div>
        )}
        {dados && !dados.vazio && (
          <>
            <div className="estatisticas">
              <div className="estatistica destaque"><strong>{percentual(acertosTotais, respostasTotais)}%</strong><span>de acertos nas últimas {dados.semanas.length} semanas</span></div>
              <div className="estatistica"><strong>{respostasTotais}</strong><span>{respostasTotais === 1 ? 'resposta registrada' : 'respostas registradas'}</span></div>
              <div className="estatistica"><strong>{dados.assuntos.length}</strong><span>{dados.assuntos.length === 1 ? 'pasta com atividade' : 'pastas com atividade'}</span></div>
            </div>
            <p className="sub">Percentual de acertos por semana (de segunda a domingo).</p>
            {dados.fracos.length > 0 && (
              <section className="secao" aria-labelledby="titulo-fracos">
                <h2 id="titulo-fracos">Tópicos com menor percentual de acertos</h2>
                <ul className="lista">
                  {dados.fracos.map((f) => (
                    <li key={f.id}>
                      <button type="button" className="link" onClick={() => aoAbrirTopico(f.id)}>{f.nome}</button> <small className="sub">({f.assunto})</small><br />
                      <strong>{Math.round(f.percentual)}%</strong> <small className="sub">de acertos · {f.acertos} de {f.total} respostas</small>
                    </li>
                  ))}
                </ul>
                <button type="button" onClick={() => irPara('/revisar')}>Revisar agora</button>
              </section>
            )}
            {dados.assuntos.map((a) => (
              <section className="secao" key={a.id} aria-labelledby={`assunto-${a.id}`}>
                <h2 id={`assunto-${a.id}`}>{a.nome}: {percentual(a.acertos, a.total)}% de acertos <small className="sub">({a.acertos} de {a.total})</small></h2>
                <Grafico semanas={dados.semanas} pontos={a.pontos} nome={a.nome} />
                <details>
                  <summary>Ver em tabela, com os tópicos (semanas sem resposta ficam de fora)</summary>
                  <div className="rolagem">
                    <table>
                      <thead><tr><th scope="col">Item</th>{semanasComDados(a).map((s) => <th scope="col" key={s}>Semana de {dataIso(s).slice(0, 5)}</th>)}</tr></thead>
                      <tbody>
                        {[a, ...a.topicos].map((s, k) => <tr key={s.id}><th scope="row">{k === 0 ? `Assunto: ${s.nome}` : s.nome}</th>{semanasComDados(a).map((w) => <td key={w}>{celula(s, w)}</td>)}</tr>)}
                      </tbody>
                    </table>
                  </div>
                </details>
              </section>
            ))}
          </>
        )}
      </main>
    </div>
  )
}
