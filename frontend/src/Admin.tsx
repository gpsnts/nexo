import { useCallback, useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { dataCompleta, tempoRelativo } from './tempo'
import { useTitulo } from './titulo'

/** Conta como o painel a recebe de `GET /admin/usuarios`: só cadastro e consumo, nunca o conteúdo dos estudos. */
type Conta = {
  id: string; nome: string; email: string; status: 'ativo' | 'suspenso'; admin: boolean; emailVerificado: boolean
  criadoEm: string | null; ultimoAcessoEm: string | null; cotaPersonalizada: number | null; creditosLimite: number; creditosUsados: number; percentual: number
}

/** Ação aberta em uma conta: ajustar a cota ou confirmar a exclusão. */
type Aberta = { id: string; tipo: 'cota' | 'excluir' } | null

/**
 * Texto do selo de estado da conta.
 * @param c conta.
 * @returns o rótulo mostrado ao administrador.
 */
const rotuloEstado = (c: Conta) => (c.admin ? 'Administrador' : c.status === 'suspenso' ? 'Suspensa' : 'Ativa')

/**
 * Painel de administração: suspender e reativar, ajustar a cota de IA de cada conta e excluir contas. Mostra só
 * dados de cadastro e consumo (nunca materiais, respostas ou resumos). O servidor responde 404 a quem não é administrador.
 * @param props aoIrInicio: volta ao início; aoIrPerfil: vai ao perfil; aoSair: encerra a sessão.
 * @returns a tela.
 */
export default function Admin({ aoIrInicio, aoIrPerfil, aoSair }: { aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void }) {
  const [contas, setContas] = useState<Conta[] | null>(null)
  const [falhou, setFalhou] = useState(false)
  const [erro, setErro] = useState('')
  const [ocupado, setOcupado] = useState(false)
  const [aberta, setAberta] = useState<Aberta>(null)
  const [valor, setValor] = useState('')
  useTitulo('Administração', true)

  const carregar = useCallback(() => api<Conta[]>('/admin/usuarios').then(setContas).catch(() => setFalhou(true)), [])
  useEffect(() => { carregar() }, [carregar])

  /**
   * Executa uma ação sobre uma conta e recarrega a lista.
   * @param caminho rota da ação.
   * @param opcoes método e corpo da chamada.
   * @returns promessa resolvida quando termina.
   */
  async function executar(caminho: string, opcoes: { metodo?: string; corpo?: unknown } = { metodo: 'POST' }) {
    setErro(''); setOcupado(true)
    try {
      await api(caminho, opcoes)
      setAberta(null); setValor('')
      await carregar()
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
    } finally {
      setOcupado(false)
    }
  }

  /**
   * Abre ou fecha o formulário de uma ação em uma conta.
   * @param c conta.
   * @param tipo ação a abrir.
   * @returns nada.
   */
  const alternar = (c: Conta, tipo: 'cota' | 'excluir') => {
    setErro(''); setValor(tipo === 'cota' ? String(c.creditosLimite) : '')
    setAberta(aberta?.id === c.id && aberta.tipo === tipo ? null : { id: c.id, tipo })
  }

  return (
    <div className="pagina">
      <Cabecalho atual="admin" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} atalho={false} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Administração</h1>
        <p className="sub">Corte o acesso de quem abusar, ajuste a cota de IA de cada conta ou exclua contas. Você vê só cadastro e consumo, nunca o conteúdo dos estudos.</p>
        {contas === null && !falhou && <Carregando blocos={3} />}
        {falhou && <p role="alert" className="erro">Não foi possível carregar as contas. <button type="button" className="link" onClick={() => { setFalhou(false); carregar() }}>Tentar de novo</button></p>}
        {erro && <p role="alert" className="erro">{erro}</p>}
        {contas && contas.length === 0 && <p className="sub">Nenhuma conta ainda.</p>}
        {contas && (
          <ul className="usuarios-admin" aria-label="Contas">
            {contas.map((c) => (
              <li key={c.id} className={`usuario-admin ${c.status}`}>
                <header>
                  <div>
                    <strong>{c.nome}</strong>
                    <small>{c.email}{!c.emailVerificado && ' · e-mail ainda não confirmado'}</small>
                  </div>
                  <span className={`selo-status ${c.status === 'suspenso' ? 'falhou' : 'pronto'}`}>{rotuloEstado(c)}</span>
                </header>
                <small>
                  Cadastro {c.criadoEm ? dataCompleta(c.criadoEm) : '—'} · último acesso {c.ultimoAcessoEm ? tempoRelativo(c.ultimoAcessoEm) : 'nunca'}
                </small>
                <div className="uso">
                  <progress className="barra" max={100} value={c.percentual} aria-label={`Cota usada por ${c.nome}`} />
                  <span>{c.percentual}% da cota semanal ({c.creditosUsados} de {c.creditosLimite} créditos){c.cotaPersonalizada !== null && ' · cota personalizada'}</span>
                </div>
                {!c.admin && (
                  <div className="acoes">
                    {c.status === 'ativo' && <button type="button" className="secundario" disabled={ocupado} onClick={() => executar(`/admin/usuarios/${c.id}/suspender`)}>Suspender</button>}
                    {c.status === 'suspenso' && <button type="button" disabled={ocupado} onClick={() => executar(`/admin/usuarios/${c.id}/reativar`)}>Reativar</button>}
                    <button type="button" className="secundario" aria-expanded={aberta?.id === c.id && aberta.tipo === 'cota'} onClick={() => alternar(c, 'cota')}>Cota</button>
                    <button type="button" className="secundario" aria-expanded={aberta?.id === c.id && aberta.tipo === 'excluir'} onClick={() => alternar(c, 'excluir')}>Excluir</button>
                  </div>
                )}
                {aberta?.id === c.id && aberta.tipo === 'cota' && (
                  <form className="acoes" onSubmit={(e) => { e.preventDefault(); executar(`/admin/usuarios/${c.id}/cota`, { metodo: 'PUT', corpo: { creditos: Number(valor) } }) }}>
                    <label>Créditos por semana (0 = sem IA)
                      <input type="number" min={0} max={100000} step={1} required value={valor} onChange={(e) => setValor(e.target.value)} />
                    </label>
                    <button type="submit" disabled={ocupado || valor === ''}>Salvar cota</button>
                    <button type="button" className="secundario" disabled={ocupado} onClick={() => executar(`/admin/usuarios/${c.id}/cota`, { metodo: 'PUT', corpo: { creditos: null } })}>Usar a cota padrão</button>
                  </form>
                )}
                {aberta?.id === c.id && aberta.tipo === 'excluir' && (
                  <form className="acoes" onSubmit={(e) => { e.preventDefault(); executar(`/admin/usuarios/${c.id}`, { metodo: 'DELETE', corpo: { confirmacao: valor } }) }}>
                    <label>Digite EXCLUIR para apagar a conta e todos os dados dela
                      <input value={valor} onChange={(e) => setValor(e.target.value)} autoComplete="off" />
                    </label>
                    <button type="submit" className="perigo" disabled={ocupado || valor !== 'EXCLUIR'}>Excluir conta</button>
                  </form>
                )}
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}
