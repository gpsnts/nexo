import { useState } from 'react'
import { api, ErroApi } from './api'
import type { Assunto } from './estudo'
import { irPara, urlPasta } from './rotas'

export const TODAS = 'todas'

/**
 * Pastas dos estudos (os "assuntos" do projeto): filtro por pasta, criar, renomear e excluir. "Geral" é a pasta padrão, onde caem os estudos sem pasta; não se renomeia nem se exclui. Excluir uma pasta não apaga estudos: eles voltam para "Geral".
 * @param props pastas: pastas do usuário; atual: filtro escolhido; aoEscolher: muda o filtro; aoMudar: avisa que a lista de pastas ou de estudos mudou.
 * @returns a seção de pastas.
 */
export default function Pastas({ pastas, atual, aoEscolher, aoMudar }: { pastas: Assunto[]; atual: string; aoEscolher: (id: string) => void; aoMudar: () => void }) {
  const [modo, setModo] = useState<'criar' | 'renomear' | 'excluir' | null>(null)
  const [nome, setNome] = useState('')
  const [erro, setErro] = useState('')
  const [ocupado, setOcupado] = useState(false)
  const selecionada = pastas.find((p) => p.id === atual)
  /**
   * Abre o formulário de criar, renomear ou confirmação de excluir.
   * @param m modo do formulário.
   */
  const abrir = (m: 'criar' | 'renomear' | 'excluir') => { setModo(m); setErro(''); setNome(m === 'renomear' ? selecionada?.nome ?? '' : '') }

  /**
   * Roda uma ação da API com bloqueio de cliques repetidos e mensagem de erro.
   * @param acao chamada à API.
   * @returns promessa resolvida quando termina.
   */
  async function executar(acao: () => Promise<void>) {
    setOcupado(true); setErro('')
    try { await acao(); setModo(null); aoMudar() } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível concluir. Tente novamente.') } finally { setOcupado(false) }
  }
  /**
   * Valida o nome e cria ou renomeia a pasta.
   * @param e evento de envio do formulário.
   * @returns nada, ou a mensagem de erro definida quando o nome está vazio.
   */
  const salvar = (e: React.FormEvent) => {
    e.preventDefault()
    if (!nome.trim()) return setErro('Informe o nome da pasta.')
    if (modo === 'criar') executar(async () => { const p = await api<Assunto>('/assuntos', { corpo: { nome: nome.trim() } }); aoEscolher(p.id) })
    else if (selecionada) executar(async () => { await api(`/assuntos/${selecionada.id}`, { metodo: 'PUT', corpo: { nome: nome.trim() } }) })
  }
  /**
   * Exclui a pasta selecionada e volta ao filtro "Todos".
   * @returns promessa resolvida quando termina, ou nada se não houver pasta selecionada.
   */
  const excluir = () => selecionada && executar(async () => { await api(`/assuntos/${selecionada.id}`, { metodo: 'DELETE' }); aoEscolher(TODAS) })

  return (
    <section className="pastas" aria-labelledby="titulo-pastas">
      <div className="titulo-linha">
        <h2 id="titulo-pastas">Pastas</h2>
        <button type="button" className="link" onClick={() => abrir('criar')} disabled={modo === 'criar'}>+ Nova pasta</button>
      </div>
      <p className="sub dica">Agrupe seus estudos por tema, como "História" ou "Física". "Geral" guarda os estudos que não estão em nenhuma pasta.</p>
      <ul className="filtros" aria-label="Filtrar por pasta">
        <li><button type="button" className="chip" aria-pressed={atual === TODAS} onClick={() => aoEscolher(TODAS)}>Todos</button></li>
        {pastas.map((p) => <li key={p.id}><button type="button" className="chip" aria-pressed={atual === p.id} onClick={() => aoEscolher(p.id)}>{p.nome}</button></li>)}
      </ul>
      {selecionada && selecionada.topicos !== undefined && (
        <p className="sub" role="status">{selecionada.topicos} {selecionada.topicos === 1 ? 'tópico' : 'tópicos'}{selecionada.materiais ? ` de ${selecionada.materiais} ${selecionada.materiais === 1 ? 'material' : 'materiais'}` : ''} nesta pasta.</p>
      )}
      {selecionada && modo === null && (
        <div className="acoes-linha">
          <button type="button" className="secundario" onClick={() => irPara(urlPasta(selecionada.id))} aria-label={`Organizar os tópicos da pasta ${selecionada.padrao ? 'Geral' : selecionada.nome}`}>Organizar tópicos</button>
          {!selecionada.padrao && <button type="button" className="secundario" onClick={() => abrir('renomear')} aria-label={`Renomear a pasta ${selecionada.nome}`}>Renomear pasta</button>}
          {!selecionada.padrao && <button type="button" className="secundario" onClick={() => abrir('excluir')} aria-label={`Excluir a pasta ${selecionada.nome}`}>Excluir pasta</button>}
        </div>
      )}
      {(modo === 'criar' || modo === 'renomear') && (
        <form onSubmit={salvar} noValidate className="form-pasta">
          <div className="campo">
            <label htmlFor="nome-pasta">{modo === 'criar' ? 'Nome da nova pasta' : 'Novo nome da pasta'}</label>
            <input id="nome-pasta" value={nome} maxLength={60} autoFocus onChange={(e) => setNome(e.target.value)} />
          </div>
          <div className="acoes-linha">
            <button type="submit" disabled={ocupado}>{ocupado ? 'Aguarde…' : modo === 'criar' ? 'Criar pasta' : 'Salvar nome'}</button>
            <button type="button" className="secundario" onClick={() => setModo(null)}>Cancelar</button>
          </div>
        </form>
      )}
      {modo === 'excluir' && selecionada && (
        <div className="aviso-ia" role="alert">
          <p>Excluir a pasta "{selecionada.nome}"? Os estudos dela não são apagados: voltam para "Geral".</p>
          <div className="acoes-linha">
            <button type="button" className="perigo" onClick={excluir} disabled={ocupado}>{ocupado ? 'Aguarde…' : 'Excluir pasta'}</button>
            <button type="button" className="secundario" onClick={() => setModo(null)}>Cancelar</button>
          </div>
        </div>
      )}
      {erro && <p className="erro" role="alert">{erro}</p>}
    </section>
  )
}
