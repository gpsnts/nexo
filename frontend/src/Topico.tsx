import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import { AVISO_IA, avisarUso, config, percentualDe, QUESTOES_POR_PEDIDO, rotuloApoio, type Geracao, type Questao, type Resumo, type TopicoDetalhe } from './estudo'
import { dataCompleta } from './tempo'
import { useTitulo } from './titulo'

const LETRAS = 'ABCDE'
type Pedido = { tipo: 'resumo' } | { tipo: 'questoes'; quantidade: number }

/** Um tópico: gera resumo e questões (UC06 a UC09) com barra de andamento, e lista o que já foi gravado. */
export default function Topico({ id, aoIrInicio, aoIrPerfil, aoSair, aoAbrirMaterial }: { id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirMaterial: (id: string) => void }) {
  const [topico, setTopico] = useState<TopicoDetalhe | null>(null)
  const [questoes, setQuestoes] = useState<Questao[]>([])
  const [falha, setFalha] = useState(false)
  const [naoEncontrado, setNaoEncontrado] = useState(false)
  const [quantidade, setQuantidade] = useState(5)
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

  const carregar = useCallback(
    () => Promise.all([api<TopicoDetalhe>(`/topicos/${id}`), api<Questao[]>(`/topicos/${id}/questoes`), api<Resumo[]>(`/topicos/${id}/resumos`).catch(() => [] as Resumo[])]) // o histórico só complementa
      .then(([t, q, r]) => { setTopico(t); setQuestoes(q); setResumos(r); setFalha(false); return t })
      .catch((e) => { if (e instanceof ErroApi && e.status === 404) setNaoEncontrado(true); else setFalha(true); return null }),
    [id],
  )
  useEffect(() => { carregar().then((t) => { if (t?.geracaoAberta) acompanhar(t.geracaoAberta) }) }, [carregar]) // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Consulta o registro a cada poucos segundos até concluir ou falhar; ao fim recarrega o tópico (e o uso de IA no cabeçalho).
   * Falha de rede passageira (ex.: o servidor reiniciando) não encerra o acompanhamento: tenta de novo algumas vezes e só então oferece "Tentar de novo".
   * Sem mudança de progresso por muito tempo, avisa que a geração parece parada (dá para cancelar).
   */
  function acompanhar(registroId: string) {
    acompanhando.current = registroId
    setPerdido(null); setParada(false)
    let falhasSeguidas = 0
    let ultimoPct = -1
    let desde = Date.now()
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

  async function pedir(p: Pedido) {
    setErro(''); setGeracao(null); setUltimo(p); setEnviando(true); setDemorando(false)
    const aviso = setTimeout(() => setDemorando(true), config.avisoDemoraMs)
    try {
      const r = await api<{ registroId: string }>(p.tipo === 'resumo' ? `/topicos/${id}/resumo` : `/topicos/${id}/questoes`, { metodo: 'POST', corpo: p.tipo === 'resumo' ? {} : { quantidade: p.quantidade } })
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

  const doResumo = (geracao?.operacao ?? ultimo?.tipo) === 'resumo'
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
        <h1 tabIndex={-1}>{topico?.nome ?? 'Tópico'}</h1>
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
          <p className="sub" role="status">Carregando…</p>
        ) : (
          <>
            <p className="sub">
              {topico.livre ? `Tópico livre: o conteúdo vem do conhecimento geral do modelo, sem material de apoio. Confira antes de estudar. ${AVISO_IA.topicoLivreGeracao}`
                : `Baseado em ${topico.fontes.map((f) => `${f.nomeArquivo} (trechos ${f.inicio} a ${f.fim})`).join(', ')}. ${AVISO_IA.topico}`}
            </p>
            {topico.livre
              ? <button type="button" className="link" onClick={aoIrInicio}>← Voltar ao início</button>
              : <button type="button" className="link" onClick={() => aoAbrirMaterial(topico.fontes[0].materialId)}>← Voltar ao material "{topico.fontes[0].nomeArquivo}"</button>}
            {!topico.livre && !topico.resumo && topico.totalQuestoes === 0 && (
              <p className="aviso-ia" role="note">Ao gerar o primeiro conteúdo, os limites deste tópico ficam fixos: só o nome poderá mudar.</p>
            )}

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
              <p className="sub dica">Usa 1 operação do seu limite de IA por hora.</p>
              {doResumo && andamento}
            </section>

            <section className="secao" aria-labelledby="titulo-questoes">
              <h2 id="titulo-questoes">Questões ({topico.totalQuestoes})</h2>
              <label htmlFor="quantidade" className="rotulo">Quantas questões gerar</label>
              <div className="linha-gerar">
                <input id="quantidade" type="number" inputMode="numeric" min={1} max={Math.min(QUESTOES_POR_PEDIDO, Math.max(restantes, 1))} value={quantidade}
                  onChange={(e) => setQuantidade(Math.max(1, Math.min(restantes > 0 ? restantes : 1, QUESTOES_POR_PEDIDO, Number(e.target.value) || 1)))} />
                <button type="button" onClick={() => pedir({ tipo: 'questoes', quantidade })} disabled={ocupado || restantes <= 0}>Gerar questões</button>
              </div>
              <p className="sub dica">Usa 2 operações do seu limite de IA por hora (gerar e verificar).</p>
              {restantes <= 0 && <p className="sub">Este tópico chegou ao máximo de {topico.maximoQuestoes} questões.</p>}
              {!doResumo && andamento}

            {questoes.length > 0 && <p className="sub dica">Modo revisão: as respostas corretas aparecem destacadas.</p>}
            {questoes.length > 0 && (
              <ol className="questoes" role="list" aria-label="Questões do tópico">
                {questoes.map((q) => (
                  <li key={q.id} className="questao">
                    <p>{q.enunciado}</p>
                    <ul>
                      {q.alternativas.map((a, i) => (
                        <li key={i} className={i === q.respostaCorreta ? 'correta' : undefined}>
                          {LETRAS[i]}) {a}{i === q.respostaCorreta && <strong> (correta)</strong>}
                        </li>
                      ))}
                    </ul>
                    {q.apoio.length > 0 && <small className="sub">Fonte: {rotuloApoio(q.apoio)}</small>}
                  </li>
                ))}
              </ol>
            )}
            </section>
          </>
        )}
      </main>
    </div>
  )
}

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
