import { useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ErroApi } from './api'
import { IconeLampada } from './Icones'
import { avisarUso, rotuloDoNivel, type QuestaoParaResponder, type RespostaDada } from './estudo'
import { dataIso } from './tempo'

const LETRAS = 'ABCDE'

/**
 * Questionário no estilo do NotebookLM: uma questão por tela; tocar numa alternativa a seleciona (dá para trocar) e "Confirmar resposta" grava e mostra o gabarito; "Próxima" avança. Teclado: A a E (ou 1 a 5) selecionam, Enter confirma e depois avança, seta para a direita também avança. O resultado final é de quem usa (`fim`).
 * @param props questoes: questões a responder; fim: recebe acertos, total e a próxima revisão e devolve a tela final.
 * @returns a questão corrente, ou a tela final.
 */
export default function Responder({ questoes, fim }: { questoes: QuestaoParaResponder[]; fim: (r: { acertos: number; total: number; proxima: string | null }) => ReactNode }) {
  const [i, setI] = useState(0)
  const [escolha, setEscolha] = useState<number | null>(null)
  const [feedback, setFeedback] = useState<RespostaDada | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [erro, setErro] = useState('')
  const [acertos, setAcertos] = useState(0)
  const [resultados, setResultados] = useState<boolean[]>([]) // acertou ou errou cada questão já respondida (alimenta a trilha de progresso)
  const [proxima, setProxima] = useState<string | null>(null) // a data de revisão mais próxima entre as respondidas
  const seguinte = useRef<HTMLButtonElement>(null)
  const titulo = useRef<HTMLHeadingElement>(null)
  const q = questoes[i]

  /**
   * Seleciona uma alternativa (antes de confirmar).
   * @param k índice da alternativa.
   */
  const escolher = (k: number) => { if (!feedback && !enviando) { setEscolha(k); setErro('') } }
  /**
   * Grava a resposta escolhida e mostra o gabarito.
   * @param e evento de envio do formulário, se houver.
   * @returns promessa resolvida quando termina.
   */
  async function confirmar(e?: React.FormEvent) {
    e?.preventDefault()
    if (feedback || enviando || escolha === null || !q) return
    setEnviando(true); setErro('')
    try {
      const r = await api<RespostaDada>(`/questoes/${q.id}/respostas`, { metodo: 'POST', corpo: { alternativa: escolha } })
      setFeedback(r)
      if (r.correta) setAcertos((n) => n + 1)
      setResultados((v) => [...v, r.correta])
      setProxima((p) => (p === null || r.proximaRevisao < p ? r.proximaRevisao : p))
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível registrar a resposta. Tente de novo.')
    } finally { setEnviando(false) }
  }
  /** Avança para a próxima questão. */
  const avancar = () => { setI((n) => n + 1); setEscolha(null); setFeedback(null); setErro('') }

  useEffect(() => { if (feedback) seguinte.current?.focus() }, [feedback])
  useEffect(() => { titulo.current?.focus() }, [i])
  useEffect(() => {
    /**
     * Atalhos de teclado do questionário.
     * @param e evento de teclado.
   * @returns nada.
     */
    const tecla = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || !q) return
      if (feedback) { if (e.key === 'ArrowRight') { e.preventDefault(); avancar() } return } // Enter já aciona o botão "Próxima", que tem o foco
      if (e.key === 'Enter' && escolha !== null) {
        const alvo = e.target instanceof HTMLElement ? e.target : null
        const opcao = alvo?.closest<HTMLElement>('.opcao')
        // Enter numa alternativa: na que já está escolhida confirma; em outra, deixa o Enter fazer o que ele faz (escolher aquela).
        if (opcao) { if (Number(opcao.dataset.indice) === escolha) { e.preventDefault(); confirmar() } return }
        // Em qualquer outro controle (dica, links, campos, o próprio botão de confirmar) o Enter é do controle.
        if (alvo?.closest('button, a, input, textarea, select, summary, [tabindex]:not([tabindex="-1"])')) return
        e.preventDefault(); confirmar(); return
      }
      const k = /^[1-5]$/.test(e.key) ? Number(e.key) - 1 : LETRAS.toLowerCase().indexOf(e.key.toLowerCase())
      if (e.key.length === 1 && k >= 0 && k < q.alternativas.length) { e.preventDefault(); escolher(k) }
    }
    window.addEventListener('keydown', tecla)
    return () => window.removeEventListener('keydown', tecla)
  })

  if (i >= questoes.length) return <>{fim({ acertos, total: questoes.length, proxima })}</>
  const ultima = i + 1 === questoes.length
  return (
    <section className="quiz" aria-labelledby="titulo-resposta">
      <div className="quiz-topo">
        <ol className="trilha" aria-label="Progresso do questionário">
          {questoes.map((_, k) => {
            const estado = k < resultados.length ? (resultados[k] ? 'certa' : 'errada') : k === i ? 'atual' : ''
            return <li key={k} className={estado}><span className="oculto">Questão {k + 1}: {estado === 'certa' ? 'certa' : estado === 'errada' ? 'errada' : estado === 'atual' ? 'em andamento' : 'pendente'}</span></li>
          })}
        </ol>
        <span className="quiz-contagem">{i + 1} / {questoes.length}</span>
      </div>
      <p className="sub quiz-topico">{q.topico}{q.dificuldade && <span className={`nivel nivel-${q.dificuldade}`}>{rotuloDoNivel(q.dificuldade)}</span>}</p>
      <h2 id="titulo-resposta" className="quiz-enunciado" tabIndex={-1} ref={titulo}><span className="oculto">Questão {i + 1} de {questoes.length}: </span>{q.enunciado}</h2>
      {!feedback && <Dica key={q.id} questaoId={q.id} inicial={q.dica ?? null} />}
      <form onSubmit={confirmar} noValidate>
      <div className="opcoes" role="group" aria-labelledby="titulo-resposta">
        {q.alternativas.map((a, k) => {
          const estado = feedback ? (k === feedback.respostaCorreta ? 'correta' : k === escolha ? 'errada' : 'neutra') : escolha === k ? 'escolhida' : ''
          return (
            <button key={k} type="button" data-indice={k} className={`opcao ${estado}`} aria-pressed={escolha === k} aria-disabled={feedback !== null || enviando} onClick={() => escolher(k)}>
              <span className="letra">{LETRAS[k]}</span>
              <span className="texto-opcao">{a}{estado === 'correta' && <strong className="oculto"> (correta)</strong>}{estado === 'errada' && <strong className="oculto"> (sua resposta, incorreta)</strong>}</span>
              {estado === 'correta' && <span className="marca-resp" aria-hidden="true">✓</span>}
              {estado === 'errada' && <span className="marca-resp" aria-hidden="true">✕</span>}
            </button>
          )
        })}
      </div>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {!feedback && (
        <div className="quiz-acoes">
          <p className="sub dica">No teclado, <kbd className="tecla">A</kbd> a <kbd className="tecla">{LETRAS[q.alternativas.length - 1]}</kbd> escolhem e <kbd className="tecla">Enter</kbd> confirma.</p>
          <button type="submit" disabled={escolha === null || enviando}>{enviando ? 'Aguarde…' : 'Confirmar resposta'}</button>
        </div>
      )}
      </form>
      {feedback && (
        <div role="status" className={`feedback ${feedback.correta ? 'acerto' : 'erro-resp'}`}>
          <p className="feedback-titulo">{feedback.correta ? 'Correto!' : `Não foi dessa vez. A resposta certa é a alternativa ${LETRAS[feedback.respostaCorreta]}.`}</p>
          <p className="sub dica">Esta questão volta para revisão em {dataIso(feedback.proximaRevisao)}.</p>
          <button type="button" ref={seguinte} onClick={avancar}>{ultima ? 'Ver resultado' : 'Próxima questão'}</button>
        </div>
      )}
    </section>
  )
}

const RAIO = 52
const CIRCUNFERENCIA = 2 * Math.PI * RAIO

/**
 * Anel que se preenche até a nota (a animação só roda se o sistema não pediu menos movimento; ver estilo.css). Os números também estão em texto.
 * @param props acertos: respostas certas; total: questões respondidas.
 * @returns o anel com a nota.
 */
function Anel({ acertos, total }: { acertos: number; total: number }) {
  const razao = total === 0 ? 0 : acertos / total
  const [cheio, setCheio] = useState(false)
  useEffect(() => { const id = requestAnimationFrame(() => setCheio(true)); return () => cancelAnimationFrame(id) }, [])
  return (
    <div className="anel" aria-hidden="true">
      <svg viewBox="0 0 120 120" width="160" height="160">
        <circle className="anel-fundo" cx="60" cy="60" r={RAIO} />
        <circle className={`anel-valor ${razao >= 0.7 ? 'bom' : razao >= 0.4 ? 'medio' : 'baixo'}`} cx="60" cy="60" r={RAIO} transform="rotate(-90 60 60)"
          strokeDasharray={CIRCUNFERENCIA} strokeDashoffset={cheio ? CIRCUNFERENCIA * (1 - razao) : CIRCUNFERENCIA} />
      </svg>
      <div className="anel-texto"><span className="anel-nota">{acertos}/{total}</span><span className="anel-pct">{Math.round(razao * 100)}%</span></div>
    </div>
  )
}

/**
 * Tela final do questionário: anel com a nota, frase de incentivo e as ações de quem usa.
 * @param props acertos, total: nota; proxima: data da próxima revisão; titulo: título oculto da seção; children: ações.
 * @returns a tela de resultado.
 */
export function Resultado({ acertos, total, proxima, titulo = 'Resultado', children }: { acertos: number; total: number; proxima?: string | null; titulo?: string; children: ReactNode }) {
  const razao = total === 0 ? 0 : acertos / total
  const tituloFim = useRef<HTMLHeadingElement>(null)
  useEffect(() => { tituloFim.current?.focus() }, [])  // só ao abrir: um callback inline devolveria o foco a cada renderização
  const frase = razao === 1 ? 'Perfeito! Você acertou tudo.' : razao >= 0.7 ? 'Muito bem! Continue assim.' : razao >= 0.4 ? 'Bom começo. As questões erradas voltam logo para revisão.' : 'Sem problema: errar faz parte. As questões erradas voltam amanhã.'
  return (
    <section className="resultado" aria-labelledby="titulo-fim">
      <h2 id="titulo-fim" className="oculto" tabIndex={-1} ref={tituloFim}>{titulo}</h2>
      <Anel acertos={acertos} total={total} />
      <p role="status">Você acertou {acertos} de {total}. {frase}</p>
      {proxima && <p className="sub">Próxima revisão agendada: {dataIso(proxima)}.</p>}
      <div className="acoes">{children}</div>
    </section>
  )
}

/**
 * Quadro de dica opcional (como no NotebookLM): recolhido por padrão; só gera (e gasta uma operação de IA) se o estudante clicar. A dica fica guardada na questão.
 * @param props questaoId: questão; inicial: dica já gravada, se houver.
 * @returns o botão e o quadro da dica.
 */
function Dica({ questaoId, inicial }: { questaoId: string; inicial: string | null }) {
  const [aberta, setAberta] = useState(false)
  const [texto, setTexto] = useState(inicial)
  const [carregando, setCarregando] = useState(false)
  const [erro, setErro] = useState('')
  /**
   * Abre ou fecha o quadro; gera a dica na primeira abertura.
   * @returns nada.
   */
  function alternar() {
    if (aberta) return setAberta(false)
    setAberta(true)
    if (!texto && !carregando) gerar()
  }
  /**
   * Pede a dica ao backend.
   * @returns promessa resolvida quando termina.
   */
  async function gerar() {
    setCarregando(true); setErro('')
    try {
      setTexto((await api<{ dica: string }>(`/questoes/${questaoId}/dica`, { metodo: 'POST' })).dica)
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível gerar a dica. Tente de novo.')
    } finally { setCarregando(false); avisarUso() }
  }
  return (
    <div className="dica-bloco">
      <button type="button" className="dica-botao" aria-expanded={aberta} aria-controls={`dica-${questaoId}`} onClick={alternar}>
        <IconeLampada tam={18} />{aberta ? 'Esconder dica' : 'Ver dica'}
      </button>
      {aberta && (
        <div id={`dica-${questaoId}`} className="dica-quadro" role="region" aria-label="Dica">
          {carregando && <p role="status" className="sub">Pensando numa dica…</p>}
          {texto && <p>{texto}</p>}
          {erro && <p className="erro" role="alert">{erro} <button type="button" className="link" onClick={gerar}>Tentar de novo</button></p>}
          {texto && !inicial && <p className="sub dica">Gerada por IA a partir do material; confira se tiver dúvida.</p>}
        </div>
      )}
    </div>
  )
}
