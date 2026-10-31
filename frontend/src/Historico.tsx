import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { LIMITE_LISTA, motivoDe, nomeDoEstudo, type Assunto, type Material, type TopicoHistorico } from './estudo'
import { irPara } from './rotas'
import { dataCompleta } from './tempo'
import { useTitulo } from './titulo'

const LIMITE_TOPICOS = 200  // igual ao padrão do servidor

/**
 * Histórico: todos os materiais e todos os tópicos (de material ou livres), do mais novo ao mais antigo, com filtro por pasta. Daqui se abre um item para ver resumo e questões, gerar mais ou usá-lo numa prova.
 * @param props aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoAbrirMaterial, aoAbrirTopico: abrem um item.
 * @returns a tela.
 */
export default function Historico({ aoIrInicio, aoIrPerfil, aoSair, aoAbrirMaterial, aoAbrirTopico }: {
  aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirMaterial: (id: string) => void; aoAbrirTopico: (id: string) => void
}) {
  const [pastas, setPastas] = useState<Assunto[]>([])
  const [materiais, setMateriais] = useState<Material[] | null>(null)
  const [topicos, setTopicos] = useState<TopicoHistorico[] | null>(null)
  const [falha, setFalha] = useState(false)
  const [filtro, setFiltro] = useState('')
  useTitulo('Histórico', true)
  /** Busca materiais e tópicos; qualquer falha mostra uma só mensagem com "Tentar de novo". */
  const buscar = () => {
    Promise.all([api<Material[]>(`/materiais?limite=${LIMITE_LISTA}`), api<TopicoHistorico[]>(`/topicos?limite=${LIMITE_TOPICOS}`)]).then(([m, t]) => { setMateriais(m); setTopicos(t) }).catch(() => setFalha(true))
  }
  useEffect(() => { buscar() }, [])
  useEffect(() => { api<Assunto[]>('/assuntos').then(setPastas).catch(() => {}) }, [])
  /**
   * Nome de uma pasta.
   * @param id pasta.
   * @returns o nome ("Geral" para a padrão), ou vazio se desconhecida.
   */
  const nomeDaPasta = (id?: string | null) => { const p = pastas.find((x) => x.id === id); return p ? (p.padrao ? 'Geral' : p.nome) : '' }

  const mats = materiais?.filter((m) => !filtro || (m.assuntoIds?.length ? m.assuntoIds.includes(filtro) : m.assuntoId === filtro))
  const tops = topicos?.filter((t) => !filtro || t.assuntoId === filtro)
  const vazio = materiais !== null && topicos !== null && materiais.length === 0 && topicos.length === 0

  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} atalho={false} />
      <main id="conteudo" tabIndex={-1}>
        <button type="button" className="link voltar" onClick={aoIrInicio}>← Voltar ao início</button>
        <h1 tabIndex={-1}>Histórico</h1>
        <p className="sub">Tudo o que você já estudou, do mais novo ao mais antigo. Abra um item para rever o resumo e as questões, gerar mais ou usá-lo numa prova.</p>
        {falha ? (
          <div role="alert"><p className="erro">Não foi possível carregar o histórico.</p><button type="button" onClick={() => { setFalha(false); buscar() }}>Tentar de novo</button></div>
        ) : mats === undefined || tops === undefined ? <Carregando /> : vazio ? (
          <section className="vazio">
            <h2>Seu histórico está vazio</h2>
            <p className="sub">Comece enviando um material ou registrando um tópico.</p>
            <button type="button" onClick={() => irPara('/novo-estudo')}>Criar meu primeiro estudo</button>
          </section>
        ) : (
          <>
            {pastas.length > 1 && (
              <div className="campo campo-pasta">
                <label htmlFor="filtro-historico">Pasta</label>
                <select id="filtro-historico" value={filtro} onChange={(e) => setFiltro(e.target.value)}>
                  <option value="">Todas as pastas</option>
                  {pastas.map((p) => <option key={p.id} value={p.id}>{p.padrao ? 'Geral' : p.nome}</option>)}
                </select>
              </div>
            )}
            <section className="secao" aria-labelledby="titulo-hist-materiais">
              <h2 id="titulo-hist-materiais">Materiais ({mats.length})</h2>
              {mats.length === 0 ? <p className="sub">Nenhum material nesta pasta.</p> : (
                <ul className="lista lista-historico" aria-label="Materiais">
                  {mats.map((m) => (
                    <li key={m.id}>
                      <button type="button" className="link nome" onClick={() => aoAbrirMaterial(m.id)}>{nomeDoEstudo(m)}</button>
                      <div className="meta"><span>{dataCompleta(m.criadoEm)}</span>{m.paginas ? <span>{m.paginas} páginas</span> : null}{m.status === 'falhou' ? <span>{motivoDe(m.motivoFalha)}</span> : m.status !== 'pronto' ? <span>em processamento</span> : null}</div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section className="secao" aria-labelledby="titulo-hist-topicos">
              <h2 id="titulo-hist-topicos">Tópicos ({tops.length})</h2>
              {tops.length === 0 ? <p className="sub">Nenhum tópico nesta pasta.</p> : (
                <ul className="lista lista-historico" aria-label="Tópicos">
                  {tops.map((t) => (
                    <li key={t.id}>
                      <button type="button" className="link nome" onClick={() => aoAbrirTopico(t.id)}>{t.nome}</button>
                      <div className="meta">
                        <span>{dataCompleta(t.criadoEm)}</span>
                        <span>{t.livre ? 'Tópico livre' : t.materiais.map((m) => m.nome).join(' + ')}</span>
                        <span>{t.totalQuestoes} {t.totalQuestoes === 1 ? 'questão' : 'questões'}</span>
                        <span>{t.temResumo ? 'com resumo' : 'sem resumo'}</span>
                        {nomeDaPasta(t.assuntoId) && <span>Pasta {nomeDaPasta(t.assuntoId)}</span>}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              {topicos!.length >= LIMITE_TOPICOS && <p className="sub">Mostrando os {LIMITE_TOPICOS} tópicos mais recentes.</p>}
              <button type="button" className="secundario" onClick={() => irPara('/provas')}>Montar uma prova com estes tópicos</button>
            </section>
          </>
        )}
      </main>
    </div>
  )
}
