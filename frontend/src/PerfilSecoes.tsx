import { useState, type FormEvent } from 'react'
import { api, definirToken, ErroApi, type Sessao } from './api'
import Campo, { CampoUsuario } from './Campo'
import { erroEmail, erroNome, erroObrigatorio, erroSenhaNova, focarPrimeiroInvalido, temErro, type Erros } from './validacao'

type Dados = { nome: string; email: string }

/** Uma seção do perfil: formulário próprio, validação por campo, mensagem de erro ou sucesso e botão travado durante o envio. */
function Secao({ titulo, aoEnviar, validar, rotuloBotao, perigo, desabilitado, children }: {
  titulo: string; aoEnviar: () => Promise<string | void>; validar?: () => boolean; rotuloBotao: string; perigo?: boolean; desabilitado?: boolean; children: React.ReactNode
}) {
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  const [enviando, setEnviando] = useState(false)
  async function enviar(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setErro(''); setOk('')
    if (validar && !validar()) { focarPrimeiroInvalido(e.currentTarget); return }
    setEnviando(true)
    try {
      setOk((await aoEnviar()) ?? '')
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
    } finally {
      setEnviando(false)
    }
  }
  return (
    <section className={perigo ? 'secao larga perigo' : 'secao'}>
      <h2>{titulo}</h2>
      <form onSubmit={enviar} noValidate>
        {children}
        {erro && <p role="alert" className="erro">{erro}</p>}
        {ok && <p role="status" className="ok">{ok}</p>}
        <button type="submit" className={perigo ? 'perigo' : undefined} disabled={enviando || desabilitado}>{enviando ? 'Aguarde…' : rotuloBotao}</button>
      </form>
    </section>
  )
}

export function DadosPessoais({ nomeInicial, aoSalvar }: { nomeInicial: string; aoSalvar: (d: Dados) => void }) {
  const [nome, setNome] = useState(nomeInicial)
  const [erros, setErros] = useState<Erros>({})
  return (
    <Secao titulo="Dados pessoais" rotuloBotao="Salvar nome"
      validar={() => { const e = { nome: erroNome(nome) }; setErros(e); return !temErro(e) }}
      aoEnviar={async () => { aoSalvar(await api<Dados>('/perfil', { metodo: 'PATCH', corpo: { nome: nome.trim() } })); return 'Nome atualizado.' }}>
      <Campo rotulo="Nome" valor={nome} mudar={(v) => { setNome(v); setErros({}) }} auto="name" erro={erros.nome} />
    </Secao>
  )
}

export function TrocaEmail({ email }: { email: string }) {
  const [novoEmail, setNovoEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erros, setErros] = useState<Erros>({})
  return (
    <Secao titulo="E-mail" rotuloBotao="Enviar confirmação"
      validar={() => { const e = { novoEmail: erroEmail(novoEmail), senha: erroObrigatorio(senha, 'Informe a senha atual.') }; setErros(e); return !temErro(e) }}
      aoEnviar={async () => {
        await api('/perfil/email', { corpo: { senhaAtual: senha, novoEmail: novoEmail.trim() } })
        setSenha('')
        return 'Enviamos um link de confirmação para o novo endereço. O e-mail de acesso só muda depois de confirmar.'
      }}>
      <CampoUsuario email={email} />
      <Campo rotulo="Novo e-mail" valor={novoEmail} mudar={(v) => { setNovoEmail(v); setErros((x) => ({ ...x, novoEmail: '' })) }} tipo="email" auto="email" erro={erros.novoEmail} />
      <Campo rotulo="Senha atual" valor={senha} mudar={(v) => { setSenha(v); setErros((x) => ({ ...x, senha: '' })) }} tipo="password" auto="current-password" erro={erros.senha} />
    </Secao>
  )
}

export function TrocaSenha({ email }: { email: string }) {
  const [atual, setAtual] = useState('')
  const [nova, setNova] = useState('')
  const [erros, setErros] = useState<Erros>({})
  return (
    <Secao titulo="Senha" rotuloBotao="Alterar senha"
      validar={() => { const e = { atual: erroObrigatorio(atual, 'Informe a senha atual.'), nova: erroSenhaNova(nova) }; setErros(e); return !temErro(e) }}
      aoEnviar={async () => {
        const s = await api<Sessao>('/perfil/senha', { metodo: 'PUT', corpo: { senhaAtual: atual, novaSenha: nova } })
        definirToken(s.token)
        setAtual(''); setNova('')
        return 'Senha alterada. Os outros dispositivos foram desconectados e enviamos um aviso ao seu e-mail.'
      }}>
      <CampoUsuario email={email} />
      <Campo rotulo="Senha atual" valor={atual} mudar={(v) => { setAtual(v); setErros((x) => ({ ...x, atual: '' })) }} tipo="password" auto="current-password" erro={erros.atual} />
      <Campo rotulo="Nova senha" valor={nova} mudar={(v) => { setNova(v); setErros((x) => ({ ...x, nova: '' })) }} tipo="password" auto="new-password" dica="Mínimo de 8 caracteres." erro={erros.nova} />
    </Secao>
  )
}

export function ExcluirConta({ email, aoExcluir }: { email: string; aoExcluir: () => void }) {
  const [senha, setSenha] = useState('')
  const [confirmacao, setConfirmacao] = useState('')
  return (
    <Secao titulo="Excluir conta" perigo rotuloBotao="Excluir minha conta" desabilitado={!senha || confirmacao !== 'EXCLUIR'}
      aoEnviar={async () => { await api('/perfil', { metodo: 'DELETE', corpo: { senha, confirmacao } }); aoExcluir() }}>
      <p className="sub">Isto apaga a conta e todo o seu conteúdo (materiais, tópicos, questões, provas e histórico). Não há como desfazer.</p>
      <CampoUsuario email={email} />
      <Campo rotulo="Senha atual" valor={senha} mudar={setSenha} tipo="password" auto="current-password" />
      <Campo rotulo="Digite EXCLUIR para confirmar" valor={confirmacao} mudar={setConfirmacao} />
    </Secao>
  )
}
