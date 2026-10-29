import Carregando from './Esqueleto'
import { useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import { AVISO_IA, avisarUso, type Mensagem } from './estudo'
import { tempoRelativo } from './tempo'

const MAXIMO = 2000

/**
 * Chat com o tutor sobre o tópico: respostas fundamentadas nos trechos do tópico; o histórico fica salvo até ser limpo.
 * @param props topicoId: tópico da conversa.
 * @returns a seção do chat.
 */
export default function Chat({ topicoId }: { topicoId: string }) {
  const [mensagens, setMensagens] = useState<Mensagem[] | null>(null)
  const [falhaHistorico, setFalhaHistorico] = useState(false)
  const [texto, setTexto] = useState('')
  const [pendente, setPendente] = useState<string | null>(null) // pergunta enviada, ainda sem resposta
  const [erro, setErro] = useState('')
  const [limpando, setLimpando] = useState(false)
  const fim = useRef<HTMLDivElement>(null)
  /**
   * Carrega o histórico da conversa.
   * @returns promessa resolvida quando termina.
   */
  const carregar = () => api<Mensagem[]>(`/topicos/${topicoId}/chat`).then((m) => { setMensagens(m); setFalhaHistorico(false) }).catch(() => setFalhaHistorico(true))
  useEffect(() => { carregar() }, [topicoId]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { fim.current?.scrollIntoView?.({ block: 'nearest' }) }, [mensagens, pendente])

  /**
   * Envia a pergunta; se falhar, devolve o texto ao campo para tentar de novo.
   * @param e evento de envio do formulário, se houver.
   * @returns promessa resolvida quando termina.
   */
  async function enviar(e?: React.FormEvent) {
    e?.preventDefault()
    const pergunta = texto.trim()
    if (!pergunta || pendente !== null) return
    setErro(''); setPendente(pergunta); setTexto('')
    try {
      const novas = await api<Mensagem[]>(`/topicos/${topicoId}/chat`, { metodo: 'POST', corpo: { mensagem: pergunta } })
      setMensagens((m) => [...(m ?? []), ...novas])
    } catch (x) {
      setTexto(pergunta) // nada foi gravado: devolve o texto para tentar de novo
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível enviar. Tente de novo.')
    } finally { setPendente(null); avisarUso() }
  }

  /**
   * Apaga a conversa do tópico.
   * @returns promessa resolvida quando termina.
   */
  async function limpar() {
    try { await api(`/topicos/${topicoId}/chat`, { metodo: 'DELETE' }); setMensagens([]); setLimpando(false) } catch { setErro('Não foi possível limpar a conversa.') }
  }

  return (
    <section className="secao chat" aria-labelledby="titulo-chat">
      <div className="titulo-linha">
        <h2 id="titulo-chat">Converse sobre este tópico</h2>
        {mensagens && mensagens.length > 0 && !limpando && <button type="button" className="link" onClick={() => setLimpando(true)}>Limpar conversa</button>}
      </div>
      {limpando && (
        <div role="alert" className="aviso-ia">
          <p>Apagar toda a conversa deste tópico? Isso não pode ser desfeito.</p>
          <div className="acoes-linha"><button type="button" className="perigo" onClick={limpar}>Apagar conversa</button><button type="button" className="secundario" onClick={() => setLimpando(false)}>Cancelar</button></div>
        </div>
      )}
      {falhaHistorico ? (
        <div role="alert"><p className="erro">Não foi possível carregar a conversa.</p><button type="button" onClick={carregar}>Tentar de novo</button></div>
      ) : mensagens === null ? <Carregando blocos={2} /> : (
        <div className="conversa" role="log" aria-label="Conversa com o tutor" aria-live="polite">
          {mensagens.length === 0 && pendente === null && <p className="sub">Tire dúvidas sobre o conteúdo. Por exemplo: "explique de outro jeito" ou "por que isso acontece?".</p>}
          {mensagens.map((m) => (
            <div key={m.id} className={`balao ${m.papel}`}>
              <span className="oculto">{m.papel === 'usuario' ? 'Você: ' : 'Tutor: '}</span>
              <p>{m.conteudo}</p>
              <small className="sub">{tempoRelativo(m.criadoEm)}</small>
            </div>
          ))}
          {pendente !== null && (
            <>
              <div className="balao usuario"><span className="oculto">Você: </span><p>{pendente}</p></div>
              <div className="balao assistente" role="status"><p className="sub">Pensando…</p></div>
            </>
          )}
          <div ref={fim} />
        </div>
      )}
      <form onSubmit={enviar} noValidate className="chat-form">
        <label htmlFor="pergunta-chat" className="rotulo">Sua pergunta</label>
        <div className="chat-linha">
          <textarea id="pergunta-chat" rows={2} maxLength={MAXIMO} value={texto} onChange={(e) => setTexto(e.target.value)} disabled={pendente !== null}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); enviar() } }} />
          <button type="submit" disabled={!texto.trim() || pendente !== null}>{pendente !== null ? 'Aguarde…' : 'Enviar'}</button>
        </div>
        {erro && <p className="erro" role="alert">{erro}</p>}
        <p className="sub dica">Enter envia e Shift+Enter quebra a linha. Cada pergunta usa créditos da sua cota semanal de IA. {AVISO_IA.chat}</p>
      </form>
    </section>
  )
}
