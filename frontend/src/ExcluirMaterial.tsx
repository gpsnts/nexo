import { useState } from 'react'
import { api, ErroApi } from './api'

/**
 * Exclusão definitiva de um material, com confirmação. Apaga o arquivo e os trechos; tópicos, resumos, questões, provas e respostas ficam, e um tópico que só usava este material passa a ser livre.
 * @param props materialId: material; nome: nome do estudo (para o rótulo acessível); aoExcluir: chamado depois de excluir.
 * @returns a seção de exclusão.
 */
export default function ExcluirMaterial({ materialId, nome, aoExcluir }: { materialId: string; nome: string; aoExcluir: () => void }) {
  const [confirmando, setConfirmando] = useState(false)
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState('')
  /**
   * Exclui o material.
   * @returns promessa resolvida quando termina.
   */
  async function excluir() {
    setOcupado(true); setErro('')
    try { await api(`/materiais/${materialId}`, { metodo: 'DELETE' }); aoExcluir() } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível excluir. Tente de novo.'); setOcupado(false) }
  }
  return (
    <section className="secao perigo" aria-labelledby="titulo-excluir-material">
      <h2 id="titulo-excluir-material">Excluir material</h2>
      {!confirmando ? (
        <>
          <p className="sub">Remove o arquivo e os trechos. Seus tópicos, resumos, questões, provas e respostas continuam; um tópico que só usava este material vira tópico livre.</p>
          <button type="button" className="secundario" onClick={() => setConfirmando(true)} aria-label={`Excluir o material ${nome}`}>Excluir material</button>
        </>
      ) : (
        <div role="alert" className="aviso-ia">
          <p>Excluir "{nome}" de vez? Isso não pode ser desfeito. O texto que já foi enviado ao serviço externo de IA não pode ser apagado por nós.</p>
          <div className="acoes-linha">
            <button type="button" className="perigo" onClick={excluir} disabled={ocupado}>{ocupado ? 'Aguarde…' : 'Excluir definitivamente'}</button>
            <button type="button" className="secundario" onClick={() => setConfirmando(false)} disabled={ocupado}>Cancelar</button>
          </div>
        </div>
      )}
      {erro && <p className="erro" role="alert">{erro}</p>}
    </section>
  )
}
