import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { mover, type Assunto, type TopicoDaPasta } from './estudo'
import { irPara } from './rotas'
import { useTitulo } from './titulo'

/**
 * Tópicos de uma pasta: a ordem (arrastando ou pelos botões Subir e Descer, que servem de escolha pelo teclado), a mudança de pasta de cada tópico e o acesso às fontes. A ordem vale como "posição" no tópico-limite das provas.
 * @param props id: pasta; aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoAbrirTopico: abre um tópico.
 * @returns a tela.
 */
export default function TopicosDaPasta({ id, aoIrInicio, aoIrPerfil, aoSair, aoAbrirTopico }: { id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirTopico: (id: string) => void }) {
  const [pastas, setPastas] = useState<Assunto[]>([])
  const [topicos, setTopicos] = useState<TopicoDaPasta[] | null>(null)
  const [falha, setFalha] = useState(false)
  const [naoEncontrada, setNaoEncontrada] = useState(false)
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState('')
  const [salvando, setSalvando] = useState(false)
  const [arrastando, setArrastando] = useState<number | null>(null)
  const [sobre, setSobre] = useState<number | null>(null)
  const ultimoFoco = useRef<string | null>(null)
  const pasta = pastas.find((p) => p.id === id)
  useTitulo(pasta ? (pasta.padrao ? 'Geral' : pasta.nome) : 'Pasta', true)

  /**
   * Busca os tópicos da pasta.
   * @returns promessa resolvida quando termina.
   */
  const carregar = useCallback(() => api<TopicoDaPasta[]>(`/assuntos/${id}/topicos`).then(setTopicos).catch((e) => (e instanceof ErroApi && e.status === 404 ? setNaoEncontrada(true) : setFalha(true))), [id])
  useEffect(() => { carregar() }, [carregar])
  useEffect(() => { api<Assunto[]>('/assuntos').then(setPastas).catch(() => {}) }, [])
  // depois de subir ou descer, o foco volta ao botão do mesmo tópico (a lista é redesenhada)
  useEffect(() => {
    if (ultimoFoco.current) { document.getElementById(ultimoFoco.current)?.focus(); ultimoFoco.current = null }
  }, [topicos])

  /**
   * Move um tópico para outra posição: mostra já e grava; se o servidor recusar, volta como estava.
   * @param de posição atual.
   * @param para posição de destino.
   * @param foco id do botão que deve receber o foco depois.
   * @returns promessa resolvida quando termina.
   */
  async function reordenar(de: number, para: number, foco?: string) {
    if (!topicos || salvando) return
    const novos = mover(topicos, de, para)
    if (novos === topicos) return
    setTopicos(novos.map((t, i) => ({ ...t, ordem: i + 1 }))); setErro(''); setAviso(''); setSalvando(true)
    if (foco) ultimoFoco.current = foco
    try {
      await api(`/assuntos/${id}/topicos/ordem`, { metodo: 'PUT', corpo: { ids: novos.map((t) => t.id) } })
      setAviso(`"${topicos[de].nome}" agora é o tópico ${para + 1}.`)
    } catch (x) {
      setTopicos(topicos); setErro(x instanceof ErroApi ? x.message : 'Não foi possível salvar a ordem. Tente de novo.')
    } finally { setSalvando(false) }
  }
  /**
   * Muda um tópico de pasta e tira o tópico desta lista.
   * @param t tópico.
   * @param destino pasta de destino.
   * @returns promessa resolvida quando termina.
   */
  async function trocarDePasta(t: TopicoDaPasta, destino: string) {
    setErro(''); setAviso('')
    try {
      await api(`/topicos/${t.id}/assunto`, { metodo: 'PUT', corpo: { assuntoId: destino } })
      setAviso(`"${t.nome}" foi para a pasta ${nomeDe(destino)}.`); carregar()
    } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível mudar de pasta. Tente de novo.') }
  }
  /**
   * Nome de uma pasta.
   * @param pastaId pasta.
   * @returns o nome, com "Geral" para a padrão.
   */
  const nomeDe = (pastaId: string) => { const p = pastas.find((x) => x.id === pastaId); return p ? (p.padrao ? 'Geral' : p.nome) : '' }

  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} atalho={false} />
      <main id="conteudo" tabIndex={-1}>
        <button type="button" className="link voltar" onClick={aoIrInicio}>← Voltar ao início</button>
        <h1 tabIndex={-1}>{pasta ? `Tópicos de ${pasta.padrao ? 'Geral' : pasta.nome}` : 'Tópicos da pasta'}</h1>
        <p className="sub">A ordem daqui decide quais tópicos entram numa prova quando você diz "já estudei até este tópico". Arraste um tópico, ou use Subir e Descer.</p>
        {naoEncontrada ? (
          <div role="alert"><p className="erro">Esta pasta não existe ou não é da sua conta.</p><button type="button" onClick={aoIrInicio}>Voltar ao início</button></div>
        ) : falha ? (
          <div role="alert"><p className="erro">Não foi possível carregar os tópicos.</p><button type="button" onClick={() => { setFalha(false); carregar() }}>Tentar de novo</button></div>
        ) : topicos === null ? <Carregando /> : topicos.length === 0 ? (
          <p className="sub" role="status">Esta pasta ainda não tem tópicos.</p>
        ) : (
          <ol className="lista lista-topicos" aria-label="Tópicos da pasta">
            {topicos.map((t, i) => (
              <li
                key={t.id} className={`${arrastando === i ? 'arrastando' : ''}${sobre === i && arrastando !== i ? ' sobre' : ''}`}
                onDragOver={(e) => { if (arrastando !== null) { e.preventDefault(); setSobre(i) } }}
                onDrop={(e) => { e.preventDefault(); if (arrastando !== null) reordenar(arrastando, i); setArrastando(null); setSobre(null) }}
              >
                {/* só a alça arrasta: um item inteiro arrastável atrapalha clicar e selecionar nos controles dentro dele (principalmente no Firefox) */}
                <span
                  className="alca" aria-hidden="true" draggable={!salvando}
                  onDragStart={(e) => { setArrastando(i); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', t.id); const item = e.currentTarget.closest('li'); if (item && e.dataTransfer.setDragImage) e.dataTransfer.setDragImage(item, 16, 16) }}
                  onDragEnd={() => { setArrastando(null); setSobre(null) }}
                >⠿</span>
                <div className="topico-info">
                  <button type="button" className="link nome" onClick={() => aoAbrirTopico(t.id)}>{t.ordem}. {t.nome}</button>
                  <div className="meta">
                    <span>{t.livre ? 'Tópico livre' : t.materiais.map((m) => m.nome).join(' + ')}</span>
                    <span>{t.totalQuestoes} {t.totalQuestoes === 1 ? 'questão' : 'questões'}</span>
                    {t.limiteDeProvas > 0 && <span>limite de {t.limiteDeProvas} {t.limiteDeProvas === 1 ? 'prova' : 'provas'}</span>}
                  </div>
                </div>
                <div className="acoes-topico">
                  <button type="button" className="secundario pequeno" id={`sobe-${t.id}`} disabled={i === 0 || salvando} aria-label={`Subir ${t.nome}`} onClick={() => reordenar(i, i - 1, `sobe-${t.id}`)}>Subir</button>
                  <button type="button" className="secundario pequeno" id={`desce-${t.id}`} disabled={i === topicos.length - 1 || salvando} aria-label={`Descer ${t.nome}`} onClick={() => reordenar(i, i + 1, `desce-${t.id}`)}>Descer</button>
                  {pastas.length > 1 && (
                    <div className="campo campo-pasta">
                      <label htmlFor={`mover-${t.id}`} className="oculto">Mover {t.nome} para a pasta</label>
                      <select id={`mover-${t.id}`} value={id} disabled={t.limiteDeProvas > 0} title={t.limiteDeProvas > 0 ? 'Este tópico é o limite de uma prova e não muda de pasta.' : undefined} onChange={(e) => trocarDePasta(t, e.target.value)}>
                        {pastas.map((p) => <option key={p.id} value={p.id}>{p.id === id ? 'Nesta pasta' : `Mover para ${p.padrao ? 'Geral' : p.nome}`}</option>)}
                      </select>
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
        {aviso && <p role="status" className="ok">{aviso}</p>}
        {erro && <p className="erro" role="alert">{erro}</p>}
        {topicos && topicos.length > 0 && <button type="button" className="secundario" onClick={() => irPara('/provas')}>Montar prova com esta pasta</button>}
      </main>
    </div>
  )
}
