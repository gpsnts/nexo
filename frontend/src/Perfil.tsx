import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { DadosPessoais, ExcluirConta, TrocaEmail, TrocaSenha } from './PerfilSecoes'
import Sessoes from './Sessoes'
import { useTitulo } from './titulo'

/** Dados do perfil devolvidos por `GET /perfil`. */
type Dados = { nome: string; email: string }

/**
 * Tela do perfil: dados pessoais, troca de e-mail e de senha, sessões ativas e exclusão da conta.
 * @param props nome: nome atual; aoIrInicio: volta ao início; aoSair: encerra a sessão; aoExcluir: chamado após excluir a conta; aoAtualizarNome: avisa o novo nome.
 * @returns a tela.
 */
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
        <nav aria-label="Seções do perfil" className="indice">
          <a href="#secao-dados">Dados pessoais</a><a href="#secao-email">E-mail</a><a href="#secao-senha">Senha</a><a href="#secao-sessoes">Sessões</a><a href="#secao-excluir">Excluir conta</a>
        </nav>
        <div className="grade grade-perfil">
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
