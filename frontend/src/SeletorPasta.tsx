import { useState } from 'react'
import { api, ErroApi } from './api'
import type { Assunto } from './estudo'

/**
 * Seletor para mudar um estudo de pasta, usado no cartão da lista inicial e nas telas de estudo.
 * @param props id: id único do campo; pastas: pastas do usuário; atual: id da pasta atual (nulo = "Geral"); nome: nome do estudo, para o rótulo acessível; caminho: rota `PUT` que move o estudo; desabilitado: trava o campo; aoMudar: avisa o id da nova pasta depois de movido.
 * @returns o seletor com a mensagem de erro, se houver.
 */
export default function SeletorPasta({ id, pastas, atual, nome, caminho, desabilitado = false, aoMudar }: {
  id: string; pastas: Assunto[]; atual?: string | null; nome: string; caminho: string; desabilitado?: boolean; aoMudar: (pasta: string) => void
}) {
  const geral = pastas.find((p) => p.padrao)?.id ?? ''
  const [erro, setErro] = useState('')
  const [movendo, setMovendo] = useState(false)
  /**
   * Move o estudo para a pasta escolhida.
   * @param pasta id da pasta de destino.
   * @returns promessa resolvida quando termina.
   */
  const mover = async (pasta: string) => {
    setErro(''); setMovendo(true)
    try { await api(caminho, { metodo: 'PUT', corpo: { assuntoId: pasta } }); aoMudar(pasta) } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível mudar de pasta.') } finally { setMovendo(false) }
  }
  return (
    <div className="campo campo-pasta">
      <label htmlFor={id}>Pasta</label>
      <select id={id} aria-label={`Pasta de ${nome}`} value={atual ?? geral} disabled={desabilitado || movendo || pastas.length === 0} onChange={(e) => mover(e.target.value)}>
        {pastas.map((p) => <option key={p.id} value={p.id}>{p.padrao ? 'Geral (sem pasta)' : p.nome}</option>)}
      </select>
      {erro && <small role="alert" className="erro">{erro}</small>}
    </div>
  )
}
