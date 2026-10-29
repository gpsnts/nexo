import Carregando from './Esqueleto'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import { AVISO_IA, type Assunto, type Dificuldade, NIVEIS, rotuloDoNivel, avisarUso, capaDe, nomeDoEstudo, config, percentualDe, QUESTOES_POR_PEDIDO, rotuloApoio, type Geracao, type Questao, type Resumo, type TopicoDetalhe } from './estudo'
import Chat from './Chat'
import Orientacao from './Orientacao'
import SeletorPasta from './SeletorPasta'
import { IconeFaisca, IconeVoltar } from './Icones'
import Responder, { Resultado } from './Responder'
import { dataCompleta, dataIso } from './tempo'
import { useTitulo } from './titulo'

const LETRAS = 'ABCDE'
/**
 * Data de hoje no fuso do navegador.
 * @returns a data no formato AAAA-MM-DD.
 */
const hojeIso = () => new Date().toLocaleDateString('sv') // AAAA-MM-DD no fuso do navegador
/** Pedido de geração feito pelo usuário (resumo ou questões), guardado para repetir após falha. */
type Pedido = { tipo: 'resumo' } | { tipo: 'questoes'; quantidade: number; dificuldade: Dificuldade }

/**
 * Um tópico: gera resumo e questões com barra de andamento, e lista o que já foi gravado.
 * @param props id: tópico; aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoAbrirMaterial: abre o material de origem.
 * @returns a tela.
 */
export default function Topico({ id, aoIrInicio, aoIrPerfil, aoSair, aoAbrirMaterial }: { id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirMaterial: (id: string) => void }) {
  const [topico, setTopico] = useState<TopicoDetalhe | null>(null)
  const [questoes, setQuestoes] = useState<Questao[]>([])
  const [respondendo, setRespondendo] = useState(false)
  const [listaQuiz, setListaQuiz] = useState<Questao[]>([])   // congelada ao começar: uma geração que termina no meio não pode trocar as questões de lugar
  const [pedidoFoco, setPedidoFoco] = useState(() => (new URLSearchParams(window.location.search).has('gerar') ? 1 : 0)) // contador de pedidos de "Gerar novas questões": leva o cursor ao gerador
  const focado = useRef(0)
  /** Congela a lista de questões (as ainda não respondidas primeiro) e abre o questionário. */
  const comecarQuestionario = () => { setListaQuiz([...questoes].sort((a, b) => Number(a.respondida) - Number(b.respondida))); setRespondendo(true) }
  const pendentes = questoes.filter((q) => q.respondida && q.proximaRevisao && q.proximaRevisao <= hojeIso()).length
  const [falha, setFalha] = useState(false)
  const [naoEncontrado, setNaoEncontrado] = useState(false)
  const [quantidade, setQuantidade] = useState(5)
  const [orientacao, setOrientacao] = useState('') // texto no campo; só vale como padrão depois de salvo
  const [dificuldade, setDificuldade] = useState<Dificuldade>('media')
  const [geracao, setGeracao] = useState<Geracao | null>(null) // operação em andamento ou recém-terminada
  const [ultimo, setUltimo] = useState<Pedido | null>(null)
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [cancelando, setCancelando] = useState(false)
  const [demorando, setDemorando] = useState(false) // o pedido ainda não foi aceito e o servidor está demorando (serviço de IA lento ou fora do ar)
  const [resumos, setResumos] = useState<Resumo[]>([])
  const [perdido, setPerdido] = useState<string | null>(null) // registro cujo andamento deixou de responder (a geração segue no servidor)
  const [parada, setParada] = useState(false) // sem progresso há muito tempo
  const acompanhando = useRef<string | null>(null)
  const aviso = useRef<HTMLParagraphElement>(null)
  const focarAviso = useRef(false)
  useTitulo(topico?.nome ?? 'Tópico', true)
  useEffect(() => {
    if (window.location.search.includes('gerar')) window.history.replaceState(null, '', window.location.pathname) // o aviso vale uma vez
  }, [])
  useEffect(() => {
    if (pedidoFoco === focado.current || !topico || respondendo) return
    focado.current = pedidoFoco
    const campo = document.getElementById('quantidade')
    campo?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    campo?.focus({ preventScroll: true })
  }, [pedidoFoco, topico, respondendo])

  const carregar = useCallback(
    () => Promise.all([api<TopicoDetalhe>(`/topicos/${id}`), api<Questao[]>(`/topicos/${id}/questoes`), api<Resumo[]>(`/topicos/${id}/resumos`).catch(() => [] as Resumo[])]) // o histórico só complementa
      .then(([t, q, r]) => { setTopico(t); setQuestoes(q); setResumos(r); setFalha(false); setOrientacao((atual) => (atual === '' || atual === (t.orientacao ?? '') ? (t.orientacao ?? '') : atual)); return t })
      .catch((e) => { if (e instanceof ErroApi && e.status === 404) setNaoEncontrado(true); else setFalha(true); return null }),
    [id],
  )
  useEffect(() => { carregar().then((t) => { if (t?.geracaoAberta) acompanhar(t.geracaoAberta) }) }, [carregar]) // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Consulta o registro a cada poucos segundos até concluir ou falhar; ao fim recarrega o tópico (e o uso de IA no cabeçalho). Falha de rede passageira (ex.: o servidor reiniciando) não encerra o acompanhamento: tenta de novo algumas vezes e só então oferece "Tentar de novo". Sem mudança de progresso por muito tempo, avisa que a geração parece parada (dá para cancelar).
   * @param registroId registro da geração a acompanhar.
   * @returns nada.
   */
  function acompanhar(registroId: string) {
    acompanhando.current = registroId
    setPerdido(null); setParada(false)
    let falhasSeguidas = 0
    let ultimoPct = -1
    let desde = Date.now()
    /**
     * Uma consulta ao registro; agenda a próxima enquanto a geração estiver aberta.
     * @returns promessa resolvida quando termina.
     */
    const consultar = async () => {
      if (acompanhando.current !== registroId) return
      try {
        const g = await api<Geracao>(`/geracoes/${registroId}`)
        if (acompanhando.current !== registroId) return
        falhasSeguidas = 0
        setGeracao(g)
        if (g.status === 'concluida' || g.status === 'falhou') { acompanhando.current = null; setParada(false); avisarUso(); carregar(); return }
        if (g.progresso !== ultimoPct) { ultimoPct = g.progresso; desde = Date.now(); setParada(false) }
        else if (Date.now() - desde > config.avisoParadaMs) setParada(true)
      } catch {
        if (acompanhando.current !== registroId) return
        if (++falhasSeguidas >= config.tentativasAndamento) { acompanhando.current = null; setPerdido(registroId); return }
      }
      setTimeout(consultar, config.intervaloAndamentoMs * (falhasSeguidas + 1))
    }
    consultar()
  }
  useEffect(() => () => { acompanhando.current = null }, [])

  /**
   * Pede um resumo ou questões e passa a acompanhar o registro criado.
   * @param p pedido feito.
   * @returns promessa resolvida quando termina.
   */
  async function pedir(p: Pedido) {
    setErro(''); setGeracao(null); setUltimo(p); setEnviando(true); setDemorando(false)
    const aviso = setTimeout(() => setDemorando(true), config.avisoDemoraMs)
    try {
      const r = await api<{ registroId: string }>(p.tipo === 'resumo' ? `/topicos/${id}/resumo` : `/topicos/${id}/questoes`, { metodo: 'POST', corpo: { ...(p.tipo === 'resumo' ? {} : { quantidade: p.quantidade, dificuldade: p.dificuldade }), ...(orientacao.trim() !== (topico?.orientacao ?? '') ? { orientacao: orientacao.trim() } : {}) } })
      avisarUso()
      acompanhar(r.registroId)
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível concluir. Tente novamente.')
      if (x instanceof ErroApi) avisarUso()
    } finally {
      clearTimeout(aviso)
      setDemorando(false)
      setEnviando(false)
    }
  }

  /**
   * Cancela a geração em andamento.
   * @returns promessa resolvida quando termina.
   */
  async function cancelar() {
    const registro = geracao?.id ?? acompanhando.current
    if (!registro) return
    setCancelando(true); setErro('')
    try {
      const g = await api<Geracao>(`/geracoes/${registro}/cancelar`, { metodo: 'POST' })
      acompanhando.current = null
      setPerdido(null); setParada(false); focarAviso.current = true
      setGeracao(g); avisarUso(); carregar()
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível cancelar. Tente novamente.')
    } finally {
      setCancelando(false)
    }
  }

  useEffect(() => { if (focarAviso.current && geracao?.motivo === 'cancelada') { focarAviso.current = false; aviso.current?.focus() } }, [geracao]) // o botão some ao cancelar: leva o foco para o aviso

  const gerando = perdido !== null || (geracao ? geracao.status === 'pendente' || geracao.status === 'em_andamento' : !!acompanhando.current)
  const ocupado = enviando || gerando
  const restantes = topico ? topico.maximoQuestoes - topico.totalQuestoes : 0

  /** Se a operação corrente (ou a última pedida) é um resumo. */
  const doResumo = (geracao?.operacao ?? ultimo?.tipo) === 'resumo'
  /** Bloco de andamento: erro, aviso de conexão perdida, barra de progresso, botão de cancelar e resultado. */
  const andamento = (
    <>
      {erro && <p role="alert" className="erro">{erro}</p>}
      {perdido && (
        <div role="alert">
          <p className="erro">Perdemos a conexão com o andamento. A geração continua no servidor: tente de novo para voltar a acompanhar.</p>
          <button type="button" onClick={() => acompanhar(perdido)}>Tentar de novo</button>
        </div>
      )}
      {!perdido && ocupado && !geracao?.status?.startsWith('f') && (
        <div>
          <progress className="barra" max={100} value={geracao?.progresso ?? 0} aria-label="Andamento da geração" aria-valuetext={`${geracao?.progresso ?? 0}%`} />
          <p className="sub" role="status">{geracao?.progresso ?? 0}% · {doResumo ? 'Gerando o resumo…' : 'Gerando e verificando as questões…'}</p>
          <p className="sub">Pode levar alguns minutos. Você pode sair desta página: o resultado aparece aqui quando terminar. Enquanto isso, não é possível gerar outro conteúdo neste tópico.</p>
          {demorando && <p className="sub aviso-parada" role="note">O serviço de IA está demorando para responder. Aguarde mais um pouco; se não der certo, aparece uma mensagem e você pode tentar de novo.</p>}
          {parada && <p className="sub aviso-parada" role="note">Sem novidades há alguns minutos. Se não avançar, cancele e tente de novo.</p>}
          {!enviando && <button type="button" className="secundario cancelar" onClick={cancelar} disabled={cancelando}>{cancelando ? 'Cancelando…' : <><span aria-hidden="true">✕ </span>Cancelar geração</>}</button>}
        </div>
      )}
      {geracao?.status === 'falhou' && geracao.motivo === 'cancelada' && <p className="sub" role="status" tabIndex={-1} ref={aviso}>{geracao.mensagem}</p>}
      {geracao?.status === 'falhou' && geracao.motivo !== 'cancelada' && (
        <div role="alert">
          <p className="erro">{geracao.mensagem}</p>
          {ultimo && <button type="button" onClick={() => pedir(ultimo)} disabled={ocupado}>Tentar de novo</button>}
        </div>
      )}
      {geracao?.status === 'concluida' && geracao.operacao === 'questoes' && <ResultadoQuestoes g={geracao} pedidas={ultimo?.tipo === 'questoes' ? ultimo.quantidade : 0} />}
      {!geracao && !ocupado && !doResumo && topico?.ultimaGeracao?.status === 'concluida' && <ResultadoQuestoes g={topico.ultimaGeracao} pedidas={0} titulo="Última geração" />}
    </>
  )

  return (
    <div className="pagina">
      <Cabecalho atual="estudo" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <div className={`cabeca-estudo${topico ? ` capa-${capaDe(topico.nome)}` : ''}`}>
          <IconeFaisca tam={30} />
          <h1 tabIndex={-1}>{topico?.nome ?? 'Tópico'}</h1>
        </div>
        {naoEncontrado ? (
          <div role="alert">
            <p className="erro">Este tópico não existe ou não é da sua conta.</p>
            <button type="button" onClick={aoIrInicio}>Voltar ao início</button>
          </div>
        ) : falha ? (
          <div role="alert">
            <p className="erro">Não foi possível abrir o tópico.</p>
            <button type="button" onClick={() => carregar()}>Tentar de novo</button>
          </div>
        ) : !topico ? (
          <Carregando />
        ) : respondendo ? (
          <>
            <button type="button" className="link" onClick={() => { setRespondendo(false); carregar() }}>← Sair do questionário</button>
            <Responder questoes={listaQuiz} fim={({ acertos, total, proxima }) => (
                <Resultado acertos={acertos} total={total} proxima={proxima}>
                  <button type="button" onClick={() => { setRespondendo(false); carregar() }}>Voltar ao tópico</button>
                  <button type="button" className="secundario" onClick={() => { setRespondendo(false); setPedidoFoco((n) => n + 1); carregar() }}>Gerar novas questões</button>
                </Resultado>
              )} />
          </>
        ) : (
          <>
            <p className="sub">
              {topico.livre ? `Tópico livre: o conteúdo vem do conhecimento geral do modelo, sem material de apoio. Confira antes de estudar. ${AVISO_IA.topicoLivreGeracao}`
                : `Baseado em ${topico.fontes.map((f) => `${nomeDoEstudo(f)} (trechos ${f.inicio} a ${f.fim})`).join(', ')}. ${AVISO_IA.topico}`}
            </p>
            {topico.livre
              ? <button type="button" className="link voltar" onClick={aoIrInicio}><IconeVoltar tam={16} />Voltar ao início</button>
              : <button type="button" className="link voltar" onClick={() => aoAbrirMaterial(topico.fontes[0].materialId)}><IconeVoltar tam={16} />Voltar ao material "{nomeDoEstudo(topico.fontes[0])}"</button>}
            {(topico.livre || topico.fontes.length === 1) && <PastaDoTopico topicoId={id} materialId={topico.livre ? null : topico.fontes[0].materialId} atual={topico.assuntoId} nome={topico.nome} />}
            {!topico.livre && !topico.resumo && topico.totalQuestoes === 0 && (
              <p className="aviso-ia" role="note">Ao gerar o primeiro conteúdo, os limites deste tópico ficam fixos: só o nome poderá mudar.</p>
            )}

            <div className="topico-pagina">
            <div className="coluna-principal">
            {questoes.length > 0 && !respondendo && (
              <section className="quiz-chamada" aria-labelledby="titulo-quiz">
                <div>
                  <h2 id="titulo-quiz">Questionário</h2>
                  <p className="sub">{questoes.length} {questoes.length === 1 ? 'questão' : 'questões'} · {questoes.filter((q) => q.respondida).length} já respondidas{pendentes > 0 ? ` · ${pendentes} para revisar hoje` : ''}</p>
                </div>
                <div className="acoes-linha">
                  <button type="button" onClick={comecarQuestionario}>Começar questionário</button>
                  <button type="button" className="sobre-cor" onClick={() => setPedidoFoco((n) => n + 1)}>Gerar novas questões</button>
                </div>
              </section>
            )}

            <Orientacao topicoId={id} livre={topico.livre} valor={orientacao} salvo={topico.orientacao ?? ''} aoMudar={setOrientacao} aoSalvar={(v) => { setOrientacao(v); setTopico({ ...topico, orientacao: v || null }) }} />

            <section className="secao" aria-labelledby="titulo-resumo">
              <h2 id="titulo-resumo">Resumo</h2>
              {topico.resumo ? <><p className="texto-longo">{topico.resumo.conteudo}</p><p className="sub dica">Gerado em {dataCompleta(topico.resumo.criadoEm)}</p></> : <p className="sub">Ainda não há resumo.</p>}
              {resumos.length > 1 && (
                <details className="anteriores">
                  <summary>Resumos anteriores ({resumos.length - 1}) · guardamos os {topico.maximoResumos} mais recentes</summary>
                  {resumos.slice(1).map((r) => <div key={r.criadoEm}><p className="sub dica">Gerado em {dataCompleta(r.criadoEm)}</p><p className="texto-longo">{r.conteudo}</p></div>)}
                </details>
              )}
              <button type="button" onClick={() => pedir({ tipo: 'resumo' })} disabled={ocupado}>{topico.resumo ? 'Gerar novo resumo' : 'Gerar resumo'}</button>
              <p className="sub dica">Usa créditos da sua cota semanal de IA.</p>
              {doResumo && andamento}
            </section>

            <section className="secao" aria-labelledby="titulo-questoes">
              <h2 id="titulo-questoes">Questões ({topico.totalQuestoes})</h2>
              <fieldset className="niveis">
                <legend className="rotulo">Dificuldade das novas questões</legend>
                <div className="niveis-opcoes">
                  {NIVEIS.map((n) => (
                    <label key={n.valor} className="nivel-opcao">
                      <input type="radio" name="dificuldade" value={n.valor} checked={dificuldade === n.valor} onChange={() => setDificuldade(n.valor)} />
                      <span>{n.rotulo}</span>
                    </label>
                  ))}
                </div>
                <p className="sub dica">{NIVEIS.find((n) => n.valor === dificuldade)!.dica}</p>
              </fieldset>
              <label htmlFor="quantidade" className="rotulo">Quantas questões gerar</label>
              <div className="linha-gerar">
                <input id="quantidade" type="number" inputMode="numeric" min={1} max={Math.min(QUESTOES_POR_PEDIDO, Math.max(restantes, 1))} value={quantidade}
                  onChange={(e) => setQuantidade(Math.max(1, Math.min(restantes > 0 ? restantes : 1, QUESTOES_POR_PEDIDO, Number(e.target.value) || 1)))} />
                <button type="button" onClick={() => pedir({ tipo: 'questoes', quantidade, dificuldade })} disabled={ocupado || restantes <= 0}>Gerar questões</button>
              </div>
              <p className="sub dica">Usa créditos da sua cota semanal de IA (gerar e verificar).</p>
              {restantes <= 0 && <p className="sub">Este tópico chegou ao máximo de {topico.maximoQuestoes} questões.</p>}
              {!doResumo && andamento}

            {questoes.length > 0 && !respondendo && (
              <details className="banco">
                <summary>Banco de questões ({questoes.length})</summary>
                <p className="sub dica acao-estudo">O gabarito de cada questão aparece depois que você responde no questionário.</p>
                <ol className="questoes" role="list" aria-label="Questões do tópico">
                  {questoes.map((q) => (
                    <li key={q.id} className="questao">
                      <p>{q.enunciado}</p>
                      <span className={`nivel nivel-${q.dificuldade}`}>{rotuloDoNivel(q.dificuldade)}</span>
                      <ul>
                        {q.alternativas.map((a, i) => (
                          <li key={i} className={i === q.respostaCorreta ? 'correta' : undefined}>
                            {LETRAS[i]}) {a}{i === q.respostaCorreta && <strong> (correta)</strong>}
                          </li>
                        ))}
                      </ul>
                      {q.respondida && q.proximaRevisao && <small className="sub">Última resposta: {q.ultimaCorreta ? 'correta' : 'incorreta'} · {q.proximaRevisao <= hojeIso() ? `revisão pendente desde ${dataIso(q.proximaRevisao)}` : `próxima revisão em ${dataIso(q.proximaRevisao)}`}</small>}
                      {q.apoio.length > 0 && <small className="sub">{q.respondida && ' · '}Fonte: {rotuloApoio(q.apoio)}</small>}
                    </li>
                  ))}
                </ol>
              </details>
            )}
            </section>
            </div>
            <aside className="coluna-chat" aria-label="Conversa com o tutor"><Chat topicoId={id} /></aside>
            </div>
          </>
        )}
      </main>
    </div>
  )
}

/**
 * Resultado de uma geração de questões: gravadas, reprovadas na verificação e repetidas.
 * @param props g: estado da geração; pedidas: quantas questões foram pedidas; titulo: prefixo opcional.
 * @returns o bloco de resultado.
 */
function ResultadoQuestoes({ g, pedidas, titulo }: { g: Geracao; pedidas: number; titulo?: string }) {
  return (
    <div role="status" className="ok">
      <p>
        {titulo ? `${titulo}: ` : ''}{g.gravadas} {g.gravadas === 1 ? 'questão gravada' : 'questões gravadas'}
        {pedidas > g.gravadas ? ` de ${pedidas} pedidas` : ''}.
      </p>
      {g.rejeitadas > 0 && (
        <p className="sub">
          {g.rejeitadas} reprovada{g.rejeitadas === 1 ? '' : 's'} na verificação de fidelidade e descartada{g.rejeitadas === 1 ? '' : 's'}
          {g.taxaRejeicao !== null && ` (taxa de rejeição ${percentualDe(g.taxaRejeicao)})`}.
        </p>
      )}
      {g.repetidas > 0 && <p className="sub">{g.repetidas} repetida{g.repetidas === 1 ? '' : 's'} descartada{g.repetidas === 1 ? '' : 's'}.</p>}
      {pedidas > g.gravadas && g.gravadas >= 0 && <p className="sub">Nem todas passaram na verificação. Você pode gerar mais.</p>}
    </div>
  )
}

/**
 * Pasta do tópico: o livre muda sozinho; o de um material muda junto com o material (o estudo inteiro vai para a nova pasta).
 * @param props topicoId: tópico; materialId: material único de origem, ou nulo se é tópico livre; atual: pasta atual; nome: nome do tópico.
 * @returns o seletor de pasta, ou uma dica para criar pastas quando só existe a "Geral".
 */
function PastaDoTopico({ topicoId, materialId, atual, nome }: { topicoId: string; materialId: string | null; atual: string; nome: string }) {
  const [pastas, setPastas] = useState<Assunto[]>([])
  const [valor, setValor] = useState(atual)
  useEffect(() => { api<Assunto[]>('/assuntos').then(setPastas).catch(() => {}) }, [])
  if (pastas.length === 0) return null
  if (pastas.length < 2) return <p className="sub dica">Para organizar seus estudos, crie pastas na tela inicial.</p>
  return (
    <>
      <SeletorPasta id="pasta-topico" pastas={pastas} atual={valor} nome={nome} caminho={materialId ? `/materiais/${materialId}/assunto` : `/topicos/${topicoId}/assunto`} aoMudar={setValor} />
      {materialId && <p className="sub dica">O estudo inteiro (todos os tópicos do material) muda de pasta.</p>}
    </>
  )
}
