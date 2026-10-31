import { useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import { nomeDoEstudo, type Material, type TopicoDetalhe } from './estudo'

/**
 * Materiais que alimentam um tópico: lista as fontes e, enquanto o tópico não tem resumo nem questões, deixa acrescentar uma faixa de trechos de qualquer material pronto (inclusive de outro) ou retirar uma fonte. Sem nenhuma fonte o tópico vira livre.
 * @param props topicoId: tópico; fontes: fontes atuais; congelado: se o tópico já tem resumo ou questões (as fontes não mudam mais); aoMudar: recarrega o tópico depois de uma mudança.
 * @returns a seção de fontes.
 */
export default function FontesDoTopico({ topicoId, fontes, congelado, aoMudar }: { topicoId: string; fontes: TopicoDetalhe['fontes']; congelado: boolean; aoMudar: () => void }) {
  const [aberto, setAberto] = useState(false)
  const [materiais, setMateriais] = useState<Material[]>([])
  const [material, setMaterial] = useState('')
  const [inicio, setInicio] = useState('')
  const [fim, setFim] = useState('')
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState('')
  const [ocupado, setOcupado] = useState(false)
  useEffect(() => {
    if (aberto && materiais.length === 0) api<Material[]>('/materiais?limite=100').then((m) => setMateriais(m.filter((x) => x.status === 'pronto'))).catch(() => setErro('Não foi possível carregar seus materiais.'))
  }, [aberto, materiais.length])
  // um tópico usa no máximo uma faixa por material
  const disponiveis = materiais.filter((m) => !fontes.some((f) => f.materialId === m.id))

  /**
   * Roda uma ação da API com bloqueio de cliques repetidos e mensagem de erro.
   * @param acao chamada à API.
   * @param ok mensagem de sucesso.
   * @returns promessa resolvida quando termina.
   */
  async function executar(acao: () => Promise<unknown>, ok: string) {
    setOcupado(true); setErro(''); setAviso('')
    try { await acao(); setAviso(ok); aoMudar() } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível concluir. Tente de novo.') } finally { setOcupado(false) }
  }
  /**
   * Valida a faixa e acrescenta a fonte.
   * @param e evento de envio do formulário.
   * @returns nada.
   */
  const adicionar = (e: React.FormEvent) => {
    e.preventDefault()
    const a = Number(inicio), b = Number(fim)
    if (!material) return setErro('Escolha o material.')
    if (!Number.isInteger(a) || !Number.isInteger(b) || a < 1 || b < a) return setErro('Informe a faixa de trechos: o primeiro, de 1 em diante, e o último, que não pode vir antes.')
    executar(async () => { await api(`/topicos/${topicoId}/fontes`, { corpo: { materialId: material, inicio: a, fim: b } }); setAberto(false); setMaterial(''); setInicio(''); setFim('') }, 'Fonte acrescentada.')
  }

  return (
    <section className="secao" aria-labelledby="titulo-fontes">
      <h2 id="titulo-fontes">Fontes</h2>
      {fontes.length === 0 ? <p className="sub">Este tópico não tem material: o conteúdo vem do conhecimento geral do modelo.</p> : (
        <ul className="escopo">
          {fontes.map((f) => (
            <li key={f.materialId}>
              {nomeDoEstudo(f)}, trechos {f.inicio} a {f.fim}
              {!congelado && <button type="button" className="link" disabled={ocupado} aria-label={`Retirar a fonte ${nomeDoEstudo(f)}`} onClick={() => executar(() => api(`/topicos/${topicoId}/fontes/${f.materialId}`, { metodo: 'DELETE' }), 'Fonte retirada.')}>Retirar</button>}
            </li>
          ))}
        </ul>
      )}
      {congelado ? <p className="sub dica">Este tópico já tem resumo ou questões, então as fontes não mudam mais. Para usar outro material, crie um tópico novo.</p> : !aberto ? (
        <button type="button" className="secundario" onClick={() => { setAberto(true); setErro(''); setAviso('') }}>Acrescentar material</button>
      ) : (
        <form onSubmit={adicionar} noValidate className="form-pasta">
          <div className="campo">
            <label htmlFor="fonte-material">Material</label>
            <select id="fonte-material" value={material} onChange={(e) => setMaterial(e.target.value)}>
              <option value="">Escolha…</option>
              {disponiveis.map((m) => <option key={m.id} value={m.id}>{nomeDoEstudo(m)}</option>)}
            </select>
          </div>
          <div className="faixa-campos">
            <div className="campo">
              <label htmlFor="fonte-inicio">Primeiro trecho</label>
              <input id="fonte-inicio" type="number" inputMode="numeric" min={1} value={inicio} onChange={(e) => setInicio(e.target.value)} />
            </div>
            <div className="campo">
              <label htmlFor="fonte-fim">Último trecho</label>
              <input id="fonte-fim" type="number" inputMode="numeric" min={1} value={fim} onChange={(e) => setFim(e.target.value)} />
            </div>
          </div>
          <small>Use a numeração dos trechos da tela do material. A faixa não pode incluir trechos que outro tópico já usa.</small>
          <div className="acoes-linha">
            <button type="submit" disabled={ocupado}>{ocupado ? 'Aguarde…' : 'Acrescentar'}</button>
            <button type="button" className="link" onClick={() => setAberto(false)}>Cancelar</button>
          </div>
        </form>
      )}
      {aviso && <p role="status" className="ok">{aviso}</p>}
      {erro && <p className="erro" role="alert">{erro}</p>}
    </section>
  )
}
