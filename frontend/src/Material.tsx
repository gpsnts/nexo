import { useCallback, useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import Andamento from './Andamento'
import Cabecalho from './Cabecalho'
import type { Material as Dados } from './estudo'
import RevisaoTopicos from './RevisaoTopicos'
import { useTitulo } from './titulo'

/** Um material: enquanto processa mostra a barra; falhou permite reenviar; pronto abre a revisão dos tópicos. */
export default function Material({ id, aoIrInicio, aoIrPerfil, aoSair, aoNovoEnvio, aoAbrirTopico }: {
  id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoNovoEnvio: () => void; aoAbrirTopico: (id: string) => void
}) {
  const [material, setMaterial] = useState<Dados | null>(null)
  const [falha, setFalha] = useState(false)
  const [naoEncontrado, setNaoEncontrado] = useState(false)
  useTitulo(material?.nomeArquivo ?? 'Material', true)
  const carregar = useCallback(() => api<Dados>(`/materiais/${id}`).then(setMaterial).catch((e) => (e instanceof ErroApi && e.status === 404 ? setNaoEncontrado(true) : setFalha(true))), [id])
  useEffect(() => { carregar() }, [carregar])

  return (
    <div className="pagina">
      <Cabecalho atual="estudo" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>{material?.nomeArquivo ?? 'Material'}</h1>
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
          <p className="sub" role="status">Carregando…</p>
        ) : material.status === 'pronto' ? (
          <RevisaoTopicos materialId={id} nomeArquivo={material.nomeArquivo} aoAbrirTopico={aoAbrirTopico} />
        ) : (
          <Andamento materialId={id} aoTerminar={carregar} aoReenviar={aoNovoEnvio} />
        )}
      </main>
    </div>
  )
}
