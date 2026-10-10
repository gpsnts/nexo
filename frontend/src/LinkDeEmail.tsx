import { useState, type FormEvent } from 'react'
import { api, ErroApi } from './api'
import Campo from './Campo'
import Marca from './Marca'
import TemaToggle from './TemaToggle'
import { useTitulo } from './titulo'
import { erroSenhaNova, focarPrimeiroInvalido } from './validacao'

/** Telas abertas pelos links enviados por e-mail: /redefinir-senha?token=… e /confirmar-email?token=… */
export default function LinkDeEmail({ tipo, token, aoConcluir, aoPedirNovo }: {
  tipo: 'redefinir' | 'confirmar' | 'verificar'; token: string | null; aoConcluir: () => void; aoPedirNovo: () => void
}) {
  const [novaSenha, setNovaSenha] = useState('')
  const [erro, setErro] = useState('')
  const [pronto, setPronto] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [erroSenha, setErroSenha] = useState('')
  const [invalido, setInvalido] = useState(token === null) // sem token (ex.: página recarregada: o token sai da URL por segurança)

  useTitulo(tipo === 'redefinir' ? 'Nova senha' : tipo === 'verificar' ? 'Ativar conta' : 'Confirmar e-mail')

  async function enviar(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (tipo === 'redefinir') {
      const msg = erroSenhaNova(novaSenha)
      setErroSenha(msg)
      if (msg) { focarPrimeiroInvalido(e.currentTarget); return }
    }
    setErro('')
    setEnviando(true)
    try {
      if (tipo === 'redefinir') await api('/auth/redefinir-senha', { corpo: { token, novaSenha } })
      else await api(tipo === 'verificar' ? '/auth/verificar-email' : '/auth/confirmar-email', { corpo: { token } })
      setPronto(true)
    } catch (err) {
      setErro(err instanceof ErroApi ? err.message : 'Sem conexão com o servidor.')
      if (err instanceof ErroApi && err.codigo === 'link_invalido') setInvalido(true)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="centro">
      <main className="cartao" id="conteudo">
        <div className="topo"><Marca /><TemaToggle /></div>
        <h1>{tipo === 'redefinir' ? 'Nova senha' : tipo === 'verificar' ? 'Ativar conta' : 'Confirmar e-mail'}</h1>
        {invalido ? (
          <>
            <p role="alert" className="erro">{erro || 'Link incompleto. Abra de novo o link que enviamos por e-mail.'}</p>
            <button type="button" onClick={tipo === 'redefinir' ? aoPedirNovo : aoConcluir}>{tipo === 'redefinir' ? 'Pedir um novo link' : 'Ir para o login'}</button>
          </>
        ) : pronto ? (
          <>
            <p role="status" className="ok">{tipo === 'redefinir' ? 'Senha alterada. Entre com a nova senha.' : tipo === 'verificar' ? 'E-mail confirmado. Sua conta está ativa: entre para começar.' : 'E-mail confirmado. Entre com o novo e-mail.'}</p>
            <button type="button" onClick={aoConcluir}>Ir para o login</button>
          </>
        ) : (
          <form onSubmit={enviar} noValidate>
            {tipo === 'confirmar' && <p className="sub">Confirme que este é o seu novo e-mail de acesso.</p>}
            {tipo === 'verificar' && <p className="sub">Confirme que este e-mail é seu para ativar a conta.</p>}
            {tipo === 'redefinir' && <Campo rotulo="Nova senha" valor={novaSenha} mudar={(v) => { setNovaSenha(v); setErroSenha('') }} tipo="password" auto="new-password" dica="Mínimo de 8 caracteres." erro={erroSenha} focar />}
            {erro && <p role="alert" className="erro">{erro}</p>}
            <button type="submit" disabled={enviando}>{enviando ? 'Aguarde…' : tipo === 'redefinir' ? 'Salvar nova senha' : tipo === 'verificar' ? 'Ativar conta' : 'Confirmar novo e-mail'}</button>
          </form>
        )}
      </main>
    </div>
  )
}
