import { useDevolverFoco } from './foco'
import { AVISO_IA } from './estudo'
import { useEffect, useState, type FormEvent } from 'react'
import { api, ErroApi, type Sessao } from './api'
import Campo from './Campo'
import Marca from './Marca'
import TemaToggle from './TemaToggle'
import { useTitulo } from './titulo'
import { erroEmail, erroNome, erroObrigatorio, erroSenhaNova, focarPrimeiroInvalido, temErro, type Erros } from './validacao'

export type Modo = 'entrar' | 'criar' | 'esqueci'

const titulos: Record<Modo, string> = { entrar: 'Entrar', criar: 'Criar conta', esqueci: 'Recuperar senha' }

/** O modo vem da rota (/entrar, /criar-conta, /recuperar-senha): o botão Voltar do navegador troca de modo e cada tela tem link próprio. */
export default function Entrar({ modo, emailInicial, aoMudarEmail, aoMudarModo, aoEntrar, aviso: avisoInicial = '' }: {
  modo: Modo; emailInicial: string; aoMudarEmail: (e: string) => void; aoMudarModo: (m: Modo) => void; aoEntrar: (s: Sessao) => void; aviso?: string
}) {
  const [nome, setNome] = useState('')
  const [email, setEmailLocal] = useState(emailInicial)
  const setEmail = (v: string) => { setEmailLocal(v); aoMudarEmail(v) } // o e-mail digitado acompanha a troca de modo
  const [senha, setSenha] = useState('')
  const [erros, setErros] = useState<Erros>({})
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState(avisoInicial)
  const [enviando, setEnviando] = useState(false)
  const botao = useDevolverFoco(enviando)
  const [naoVerificado, setNaoVerificado] = useState(false) // login recusado: e-mail ainda não confirmado
  const [aguardando, setAguardando] = useState(false) // cadastro feito: falta confirmar o e-mail
  const [codigoErro, setCodigoErro] = useState('')
  const [espera, setEspera] = useState(0) // contagem regressiva do limite de tentativas

  useEffect(() => {
    if (espera <= 0) return
    const t = setTimeout(() => setEspera((e) => e - 1), 1000)
    return () => clearTimeout(t)
  }, [espera])

  useTitulo(aguardando ? 'Confira seu e-mail' : titulos[modo])

  const mudar = aoMudarModo // a senha digitada não acompanha: o App remonta este componente a cada modo

  // Digitar limpa o erro daquele campo; o resto continua até o próximo envio.
  const campo = (nomeDoCampo: string, definir: (v: string) => void) => (v: string) => { definir(v); setErros((e) => ({ ...e, [nomeDoCampo]: '' })) }

  async function reenviar() {
    setErro(''); setAviso('')
    try {
      await api('/auth/reenviar-verificacao', { corpo: { email: email.trim() } })
      setAviso('Se a conta existir e ainda não estiver confirmada, enviamos um novo link.')
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
    }
  }

  async function enviar(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const formulario = e.currentTarget
    setErro(''); setAviso(''); setNaoVerificado(false); setCodigoErro('') // mensagem velha do servidor não fica ao lado dos erros novos
    const novos: Erros = {
      ...(modo === 'criar' ? { nome: erroNome(nome) } : {}),
      email: erroEmail(email),
      ...(modo === 'criar' ? { senha: erroSenhaNova(senha) } : modo === 'entrar' ? { senha: erroObrigatorio(senha, 'Informe a senha.') } : {}),
    }
    setErros(novos)
    if (temErro(novos)) { focarPrimeiroInvalido(formulario); return }
    setEnviando(true)
    try {
      const mail = email.trim()
      if (modo === 'esqueci') {
        await api('/auth/esqueci-senha', { corpo: { email: mail } })
        setAviso('Se este e-mail tiver conta, enviamos um link para criar uma nova senha. Ele vale por 30 minutos.')
      } else if (modo === 'criar') {
        await api('/auth/cadastro', { corpo: { nome: nome.trim(), email: mail, senha } })
        setSenha(''); setAguardando(true) // a conta só entra depois de confirmar o e-mail (ADR 0014)
      } else {
        aoEntrar(await api<Sessao>('/auth/login', { corpo: { email: mail, senha } }))
      }
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
      setNaoVerificado(err instanceof ErroApi && err.codigo === 'email_nao_verificado')
      setCodigoErro(err instanceof ErroApi ? (err.codigo ?? '') : '')
      if (err instanceof ErroApi && err.espera) setEspera(err.espera)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="centro">
      <main className="cartao">
        <div className="topo"><Marca /><TemaToggle /></div>
        {aguardando ? (
          <>
            <h1 id="conteudo">Confira seu e-mail</h1>
            <p className="sub">Enviamos um link de confirmação para <strong>{email.trim()}</strong>. Abra o link para ativar a conta e depois entre.</p>
            {erro && <p role="alert" className="erro">{erro}</p>}
            {aviso && <p role="status" className="ok">{aviso}</p>}
            <button type="button" onClick={reenviar}>Reenviar e-mail</button>
            <div className="acoes"><button type="button" className="link" onClick={() => mudar('entrar')}>Voltar para entrar</button></div>
          </>
        ) : (
          <>
            <h1>{titulos[modo]}</h1>
            <p className="sub">Seu tutor de estudo guiado: resumos, questões e revisões a partir do seu material.</p>
            {modo === 'criar' && <p className="sub privacidade">{AVISO_IA.cadastro}</p>}
            <form onSubmit={enviar} key={modo} noValidate>
              {modo === 'criar' && <Campo rotulo="Nome" valor={nome} mudar={campo('nome', setNome)} auto="name" erro={erros.nome} focar />}
              <Campo rotulo="E-mail" valor={email} mudar={campo('email', setEmail)} tipo="email" auto="email" erro={erros.email} focar={modo !== 'criar'} />
              {modo !== 'esqueci' && (
                <Campo rotulo="Senha" valor={senha} mudar={campo('senha', setSenha)} tipo="password" auto={modo === 'criar' ? 'new-password' : 'current-password'}
                  dica={modo === 'criar' ? 'Mínimo de 8 caracteres.' : undefined} erro={erros.senha} />
              )}
              {erro && <p role="alert" className="erro">{erro}</p>}
              {aviso && <p role="status" className="ok">{aviso}</p>}
              {naoVerificado && <button type="button" className="link" onClick={reenviar}>Reenviar e-mail de confirmação</button>}
              {codigoErro === 'email_em_uso' && (
                <div className="acoes">
                  <button type="button" className="link" onClick={() => mudar('entrar')}>Entrar com este e-mail</button>
                  <button type="button" className="link" onClick={() => mudar('esqueci')}>Esqueci minha senha</button>
                </div>
              )}
              <button ref={botao} type="submit" disabled={enviando || espera > 0}>
                {espera > 0 ? `Tente de novo em ${espera} s` : enviando ? 'Aguarde…' : modo === 'esqueci' ? 'Enviar link' : titulos[modo]}
              </button>
            </form>
            <div className="acoes">
              {modo === 'entrar' && <button type="button" className="link" onClick={() => mudar('esqueci')}>Esqueci minha senha</button>}
              <button type="button" className="link" onClick={() => mudar(modo === 'entrar' ? 'criar' : 'entrar')}>
                {modo === 'entrar' ? 'Criar conta' : modo === 'criar' ? 'Já tenho conta' : 'Voltar para entrar'}
              </button>
            </div>
          </>
        )}
      </main>
    </div>
  )
}
