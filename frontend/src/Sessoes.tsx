import Carregando from './Esqueleto'
import { useCallback, useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import { dataCompleta, tempoRelativo } from './tempo'

/** Sessão ativa devolvida por `GET /perfil/sessoes`. */
type Item = { id: string; dispositivo: string; ip: string; inicio: string; ultimaAtividade: string; atual: boolean }

/**
 * Dispositivos conectados à conta. Cada item é um login; encerrar derruba a renovação daquele dispositivo.
 * @returns a seção de sessões ativas.
 */
export default function Sessoes() {
  const [itens, setItens] = useState<Item[] | null>(null)
  const [falhou, setFalhou] = useState(false)
  const [erro, setErro] = useState('')
  const [ocupado, setOcupado] = useState(false)

  const carregar = useCallback(() => api<Item[]>('/perfil/sessoes').then(setItens).catch(() => setFalhou(true)), [])
  /** Repete a consulta depois de uma falha. */
  const tentarDeNovo = () => { setFalhou(false); carregar() }
  useEffect(() => { carregar() }, [carregar])

  /**
   * Encerra uma ou todas as outras sessões e recarrega a lista.
   * @param caminho rota `DELETE` (uma sessão ou todas as outras).
   * @returns promessa resolvida quando termina.
   */
  async function executar(caminho: string) {
    setErro(''); setOcupado(true)
    try {
      await api(caminho, { metodo: 'DELETE' })
      await carregar()
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
    } finally {
      setOcupado(false)
    }
  }

  return (
    <section id="secao-sessoes" className="secao larga">
      <h2>Sessões ativas</h2>
      <p className="sub">Dispositivos em que a sua conta está conectada. Se algum não for seu, encerre-o e troque a senha.</p>
      {itens === null && !falhou && <Carregando blocos={2} />}
      {falhou && (
        <p role="alert" className="erro">Não foi possível carregar as sessões. <button type="button" className="link" onClick={tentarDeNovo}>Tentar de novo</button></p>
      )}
      {itens && (
        <ul className="sessoes">
          {itens.map((s) => (
            <li key={s.id}>
              <div>
                <strong>{s.dispositivo}</strong> {s.atual && <span className="selo">Este dispositivo</span>}
                <small>
                  IP {s.ip || 'desconhecido'} · conectado desde <time dateTime={s.inicio} title={dataCompleta(s.inicio)}>{dataCompleta(s.inicio)}</time> ·
                  {' '}última atividade <time dateTime={s.ultimaAtividade} title={dataCompleta(s.ultimaAtividade)}>{tempoRelativo(s.ultimaAtividade)}</time>
                </small>
              </div>
              {!s.atual && <button type="button" className="secundario" disabled={ocupado} onClick={() => executar(`/perfil/sessoes/${s.id}`)}>Encerrar</button>}
            </li>
          ))}
        </ul>
      )}
      {erro && <p role="alert" className="erro">{erro}</p>}
      {itens && itens.length > 1 && (
        <button type="button" className="secundario" disabled={ocupado} onClick={() => executar('/perfil/sessoes')}>Sair de todos os outros dispositivos</button>
      )}
    </section>
  )
}
