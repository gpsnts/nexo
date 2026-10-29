import { useState } from 'react'
import { api, ErroApi } from './api'
import { ORIENTACAO_MAXIMA } from './estudo'

/**
 * Campo de orientação do estudante para o conteúdo gerado do tópico (foco, pontos a cobrir, banca ou vestibular de referência).
 * O texto digitado vale para a próxima geração; "Salvar como padrão" o grava no tópico para as seguintes (resumo, questões e chat).
 * @param props topicoId: tópico; livre: se é tópico livre (muda o texto de ajuda); valor: texto no campo; salvo: orientação gravada; aoMudar: recebe o texto digitado; aoSalvar: recebe o texto gravado.
 * @returns a seção de orientações.
 */
export default function Orientacao({ topicoId, livre, valor, salvo, aoMudar, aoSalvar }: {
  topicoId: string; livre: boolean; valor: string; salvo: string; aoMudar: (v: string) => void; aoSalvar: (v: string) => void
}) {
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  const diferente = valor.trim() !== salvo
  /**
   * Grava a orientação como padrão do tópico.
   * @returns promessa resolvida quando termina.
   */
  const salvar = async () => {
    setSalvando(true); setErro(''); setOk('')
    try {
      const r = await api<{ orientacao: string | null }>(`/topicos/${topicoId}/orientacao`, { metodo: 'PUT', corpo: { orientacao: valor } })
      aoSalvar(r.orientacao ?? ''); setOk(r.orientacao ? 'Orientação salva como padrão deste tópico.' : 'Orientação removida.')
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível salvar. Tente novamente.')
    } finally { setSalvando(false) }
  }
  return (
    <section className="secao" aria-labelledby="titulo-orientacao">
      <h2 id="titulo-orientacao">Orientações para a IA <span className="sub">(opcional)</span></h2>
      <div className="campo">
        <label htmlFor="orientacao" className="rotulo">O que você quer destacar?</label>
        <textarea id="orientacao" rows={3} maxLength={ORIENTACAO_MAXIMA} value={valor} onChange={(e) => { aoMudar(e.target.value); setOk('') }}
          placeholder={livre ? 'Ex.: inclua a situação da Espanha e de Portugal; questões no estilo da banca FCC' : 'Ex.: aprofunde os conceitos de aquisição; questões no estilo do vestibular da UFRGS'} />
        <small>
          {livre ? 'Vale para o resumo, as questões e o chat. A IA segue o foco e o estilo pedidos sem inventar fatos.' : 'Vale para o resumo, as questões e o chat. O conteúdo continua baseado no seu material: a orientação só define a ênfase e o estilo.'}
          {' '}{valor.length}/{ORIENTACAO_MAXIMA}
        </small>
      </div>
      <div className="acoes-linha">
        <button type="button" className="secundario" onClick={salvar} disabled={!diferente || salvando}>{salvando ? 'Aguarde…' : 'Salvar como padrão do tópico'}</button>
      </div>
      <p className="sub dica">{diferente ? 'O texto acima vale só para as próximas gerações desta página até você salvá-lo como padrão.' : salvo ? 'Esta orientação é o padrão do tópico.' : 'Sem orientação.'}</p>
      {erro && <p role="alert" className="erro">{erro}</p>}
      {ok && <p role="status" className="ok">{ok}</p>}
    </section>
  )
}
