import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { DadosPessoais, ExcluirConta, TrocaEmail, TrocaSenha } from './PerfilSecoes'
import Sessoes from './Sessoes'
import { useTitulo } from './titulo'

type Dados = { nome: string; email: string }

export default function Perfil({ nome: nomeAtual, aoIrInicio, aoSair, aoExcluir, aoAtualizarNome }: {
  nome: string; aoIrInicio: () => void; aoSair: () => void; aoExcluir: () => void; aoAtualizarNome: (n: string) => void
}) {
  const [dados, setDados] = useState<Dados | null>(null)
  useTitulo('Meu perfil', true)
  useEffect(() => { api<Dados>('/perfil').then(setDados).catch(() => {}) }, [])

  return (
    <div className="pagina">
      <Cabecalho atual="perfil" aoIrInicio={aoIrInicio} aoIrPerfil={() => {}} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Meu perfil</h1>
        {dados && <p className="sub">{dados.email}</p>}
        <div className="grade">
          <DadosPessoais nomeInicial={dados?.nome ?? nomeAtual} key={dados?.nome ?? nomeAtual} aoSalvar={(d) => { setDados(d); aoAtualizarNome(d.nome) }} />
          <TrocaEmail email={dados?.email ?? ''} />
          <TrocaSenha email={dados?.email ?? ''} />
          <Sessoes />
          <ExcluirConta email={dados?.email ?? ''} aoExcluir={aoExcluir} />
        </div>
      </main>
    </div>
  )
}
