import Carregando from './Esqueleto'
import { useCallback, useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import Andamento from './Andamento'
import Cabecalho from './Cabecalho'
import ExcluirMaterial from './ExcluirMaterial'
import { IconeDocumento } from './Icones'
import { AVISO_IA, avisarUso, capaDe, nomeDoEstudo, type Assunto, type Material as Dados } from './estudo'
import RevisaoTopicos from './RevisaoTopicos'
import { useTitulo } from './titulo'

/**
 * Um material: enquanto processa mostra a barra; falhou permite reenviar; pronto abre a revisão dos tópicos.
 * @param props id: material; aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoNovoEnvio: reenvio após falha; aoAbrirTopico: abre um tópico.
 * @returns a tela.
 */
export default function Material({ id, aoIrInicio, aoIrPerfil, aoSair, aoNovoEnvio, aoAbrirTopico }: {
  id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoNovoEnvio: () => void; aoAbrirTopico: (id: string) => void
}) {
  const [material, setMaterial] = useState<Dados | null>(null)
  const [falha, setFalha] = useState(false)
  const [naoEncontrado, setNaoEncontrado] = useState(false)
  useTitulo(material ? nomeDoEstudo(material) : 'Material', true)
  const carregar = useCallback(() => api<Dados>(`/materiais/${id}`).then(setMaterial).catch((e) => (e instanceof ErroApi && e.status === 404 ? setNaoEncontrado(true) : setFalha(true))), [id])
  useEffect(() => { carregar() }, [carregar])

  return (
    <div className="pagina">
      <Cabecalho atual="estudo" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <div className={`cabeca-estudo${material ? ` capa-${capaDe(nomeDoEstudo(material))}` : ''}`}>
          <IconeDocumento tam={30} />
          <h1 tabIndex={-1}>{material ? nomeDoEstudo(material) : 'Material'}</h1>
        </div>
        {material && material.status === 'pronto' && <Identidade material={material} aoMudar={setMaterial} />}
        {naoEncontrado ? (
          <div role="alert">
            <p className="erro">Este material não existe ou não é da sua conta.</p>
            <button type="button" onClick={aoIrInicio}>Voltar ao início</button>
          </div>
        ) : falha ? (
          <div role="alert">
            <p className="erro">Não foi possível abrir o material.</p>
            <button type="button" onClick={() => { setFalha(false); carregar() }}>Tentar de novo</button>
          </div>
        ) : !material ? (
          <Carregando />
        ) : material.status === 'pronto' ? (
          <>
            <RevisaoTopicos materialId={id} nomeArquivo={material.nomeArquivo} aoAbrirTopico={aoAbrirTopico} />
            <ExcluirMaterial materialId={id} nome={nomeDoEstudo(material)} aoExcluir={aoIrInicio} />
          </>
        ) : (
          <>
            <Andamento materialId={id} aoTerminar={carregar} aoReenviar={aoNovoEnvio} />
            {material.status === 'falhou' && <ExcluirMaterial materialId={id} nome={nomeDoEstudo(material)} aoExcluir={aoIrInicio} />}
          </>
        )}
      </main>
    </div>
  )
}

/**
 * Nome do estudo (à mão ou sugerido pela IA) e pasta. O nome do arquivo continua visível quando o estudo tem outro nome.
 * @param props material: material pronto; aoMudar: recebe o material atualizado.
 * @returns o bloco de identidade do estudo.
 */
function Identidade({ material, aoMudar }: { material: Dados; aoMudar: (m: Dados) => void }) {
  const [pastas, setPastas] = useState<Assunto[]>([])
  const [editando, setEditando] = useState(false)
  const [nome, setNome] = useState('')
  const [sugerindo, setSugerindo] = useState(false)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState('')
  useEffect(() => { api<Assunto[]>('/assuntos').then(setPastas).catch(() => {}) }, [])
  const geral = pastas.find((p) => p.padrao)?.id
  /**
   * Mensagem de erro para o usuário.
   * @param x erro capturado.
   * @param padrao texto usado se não for erro da API.
   * @returns a mensagem a mostrar.
   */
  const msg = (x: unknown, padrao: string) => (x instanceof ErroApi ? x.message : padrao)

  /**
   * Grava o nome do estudo.
   * @param e evento de envio do formulário.
   * @returns promessa resolvida quando termina.
   */
  async function salvar(e: React.FormEvent) {
    e.preventDefault(); setSalvando(true); setErro('')
    try { aoMudar(await api<Dados>(`/materiais/${material.id}`, { metodo: 'PUT', corpo: { titulo: nome } })); setEditando(false) } catch (x) { setErro(msg(x, 'Não foi possível salvar o nome.')) } finally { setSalvando(false) }
  }
  /**
   * Pede à IA uma sugestão de nome e a põe no campo (não grava).
   * @returns promessa resolvida quando termina.
   */
  async function sugerir() {
    setSugerindo(true); setErro('')
    try { setNome((await api<{ nome: string }>(`/materiais/${material.id}/sugerir-nome`, { metodo: 'POST' })).nome) } catch (x) { setErro(msg(x, 'Não foi possível sugerir um nome.')) } finally { setSugerindo(false); avisarUso() }
  }
  /**
   * Move o estudo para outra pasta.
   * @param id pasta de destino.
   * @returns promessa resolvida quando termina.
   */
  async function mover(id: string) {
    setErro(''); setAviso('')
    try { await api(`/materiais/${material.id}/assunto`, { metodo: 'PUT', corpo: { assuntoId: id } }); aoMudar({ ...material, assuntoId: id }); setAviso('Estudo movido.') } catch (x) { setErro(msg(x, 'Não foi possível mover o estudo.')) }
  }

  return (
    <div className="identidade">
      {material.titulo && <p className="sub">Arquivo: {material.nomeArquivo}</p>}
      {!editando ? (
        <div className="acoes-linha">
          <button type="button" className="secundario" onClick={() => { setNome(nomeDoEstudo(material)); setEditando(true); setErro('') }}>Renomear estudo</button>
          <div className="campo campo-pasta">
            <label htmlFor="pasta-estudo">Pasta</label>
            <select id="pasta-estudo" value={material.assuntoId ?? geral ?? ''} onChange={(e) => mover(e.target.value)}>
              {pastas.map((p) => <option key={p.id} value={p.id}>{p.padrao ? 'Geral (sem pasta)' : p.nome}</option>)}
            </select>
          </div>
        </div>
      ) : (
        <form onSubmit={salvar} noValidate className="form-pasta">
          <div className="campo">
            <label htmlFor="nome-estudo">Nome do estudo</label>
            <input id="nome-estudo" value={nome} maxLength={120} autoFocus onChange={(e) => setNome(e.target.value)} disabled={sugerindo} />
            <small>Em branco, volta a valer o nome do arquivo. {AVISO_IA.nome}</small>
          </div>
          <div className="acoes-linha">
            <button type="submit" disabled={salvando || sugerindo}>{salvando ? 'Aguarde…' : 'Salvar nome'}</button>
            <button type="button" className="secundario" onClick={sugerir} disabled={sugerindo || salvando}>{sugerindo ? 'Pensando…' : 'Sugerir nome com IA'}</button>
            <button type="button" className="link" onClick={() => setEditando(false)}>Cancelar</button>
          </div>
        </form>
      )}
      {aviso && <p role="status" className="ok">{aviso}</p>}
      {erro && <p className="erro" role="alert">{erro}</p>}
    </div>
  )
}
