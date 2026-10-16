import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { api, definirToken, quandoPerderSessao, renovarSessao, type Sessao } from './api'
import Entrar, { type Modo } from './Entrar'
import Inicio from './Inicio'
import LinkDeEmail from './LinkDeEmail'
import Material from './Material'
import NaoEncontrada from './NaoEncontrada'
import NovoEstudo from './NovoEstudo'
import Perfil from './Perfil'
import Topico from './Topico'
import { ehEntrada, ehInterna, ehLink, idDoMaterial, idDoTopico, proximoDaUrl, rotaDaUrl, urlDe, urlMaterial, urlTopico, type NomeRota } from './rotas'
import { chaveSessao, marcarSessao, talvezTenhaSessao } from './sessaoLocal'

type Destino = Exclude<NomeRota, 'desconhecida' | 'material' | 'topico'>
const modoDaRota = (r: NomeRota): Modo => (r === 'criar' ? 'criar' : r === 'recuperar' ? 'esqueci' : 'entrar')
const rotaDoModo = (m: Modo): Destino => (m === 'criar' ? 'criar' : m === 'esqueci' ? 'recuperar' : 'entrar')

// A URL é a fonte da verdade da tela: o React a lê como um armazenamento externo (Voltar/Avançar e navegação própria avisam).
const EVENTO_ROTA = 'nexo:rota'
const assinarRota = (aviso: () => void) => {
  window.addEventListener('popstate', aviso)
  window.addEventListener(EVENTO_ROTA, aviso)
  return () => { window.removeEventListener('popstate', aviso); window.removeEventListener(EVENTO_ROTA, aviso) }
}

export default function App() {
  const rota = useSyncExternalStore(assinarRota, () => rotaDaUrl(), () => 'inicio' as NomeRota)
  // O token do link do e-mail é lido uma vez e sai da URL (histórico, favoritos e capturas de tela não o guardam).
  const [tokenDoLink] = useState(() => (ehLink(rotaDaUrl()) ? new URLSearchParams(window.location.search).get('token') : null))
  const [carregando, setCarregando] = useState(talvezTenhaSessao)
  const [sessao, setSessao] = useState<{ nome: string } | null>(null)
  const [aviso, setAviso] = useState('')
  const [emailRascunho, setEmailRascunho] = useState('')
  const logado = useRef(false)
  useEffect(() => { logado.current = sessao !== null }, [sessao]) // o listener de storage lê o valor mais recente por aqui

  // Navegação com histórico (ADR 0018): cada tela tem endereço; Voltar/Avançar funcionam.
  const navegar = useCallback((nome: Destino, opcoes: { substituir?: boolean; proximo?: 'inicio' | 'perfil' } = {}) => {
    window.history[opcoes.substituir ? 'replaceState' : 'pushState'](null, '', urlDe(nome, opcoes.proximo))
    window.dispatchEvent(new Event(EVENTO_ROTA))
  }, [])
  const abrirMaterial = useCallback((id: string) => {
    window.history.pushState(null, '', urlMaterial(id))
    window.dispatchEvent(new Event(EVENTO_ROTA))
  }, [])

  const abrirTopico = useCallback((id: string) => {
    window.history.pushState(null, '', urlTopico(id))
    window.dispatchEvent(new Event(EVENTO_ROTA))
  }, [])

  const entrar = useCallback((s: Sessao) => { definirToken(s.token); marcarSessao(true); setAviso(''); setSessao({ nome: s.nome }) }, [])
  const sair = useCallback(() => { definirToken(null); marcarSessao(false); setSessao(null) }, [])

  useEffect(() => {
    if (ehLink(rotaDaUrl()) && window.location.search) window.history.replaceState(null, '', window.location.pathname)
  }, [])

  useEffect(() => {
    // Sessão perdida sem o usuário pedir (renovação recusada): volta ao login explicando o motivo.
    quandoPerderSessao(() => { sair(); setAviso('Sua sessão expirou. Entre novamente.') })
    // Recarregar a página: o token em memória some, mas o cookie de refresh devolve a sessão (só sondamos quem já entrou antes).
    if (talvezTenhaSessao()) renovarSessao().then(entrar).catch(() => marcarSessao(false)).finally(() => setCarregando(false))
  }, [entrar, sair])

  // Sem sessão, telas internas levam ao login e voltam para o destino depois; com sessão, o login leva ao destino.
  useEffect(() => {
    if (carregando) return
    if (!sessao && ehInterna(rota)) navegar('entrar', { substituir: true, proximo: rota === 'perfil' ? 'perfil' : 'inicio' })
    else if (sessao && ehEntrada(rota)) navegar(proximoDaUrl(), { substituir: true })
  }, [carregando, sessao, rota, navegar])

  // Várias abas: sair numa derruba as outras; entrar numa abre a sessão nas que estão no login.
  useEffect(() => {
    const aoMudarArmazenamento = (e: StorageEvent) => {
      if (e.key !== chaveSessao) return
      if (e.newValue === null && logado.current) { definirToken(null); setSessao(null); setAviso('Você saiu em outra aba.') }
      else if (e.newValue === '1' && !logado.current) renovarSessao().then(entrar).catch(() => {})
    }
    window.addEventListener('storage', aoMudarArmazenamento)
    return () => window.removeEventListener('storage', aoMudarArmazenamento)
  }, [entrar])

  const encerrar = () => { api('/auth/logout', { metodo: 'POST' }).catch(() => {}); sair() }
  const mudarModo = (m: Modo) => navegar(rotaDoModo(m), { proximo: proximoDaUrl() })

  if (rota === 'desconhecida') return <NaoEncontrada logado={sessao !== null} aoVoltar={() => navegar(sessao ? 'inicio' : 'entrar')} />
  if (ehLink(rota))
    return <LinkDeEmail tipo={rota as 'redefinir' | 'confirmar' | 'verificar'} token={tokenDoLink} aoConcluir={() => navegar('entrar', { substituir: true })} aoPedirNovo={() => navegar('recuperar')} />
  if (carregando) return null
  if (!sessao)
    return <Entrar key={modoDaRota(rota)} modo={modoDaRota(rota)} emailInicial={emailRascunho} aoMudarEmail={setEmailRascunho} aoMudarModo={mudarModo} aoEntrar={entrar} aviso={aviso} />
  const comum = { aoIrInicio: () => navegar('inicio'), aoIrPerfil: () => navegar('perfil'), aoSair: encerrar }
  if (rota === 'perfil')
    return <Perfil nome={sessao.nome} aoIrInicio={comum.aoIrInicio} aoSair={encerrar} aoExcluir={sair} aoAtualizarNome={(nome) => setSessao({ nome })} />
  if (rota === 'novo') return <NovoEstudo {...comum} aoAbrirMaterial={abrirMaterial} aoAbrirTopico={abrirTopico} />
  if (rota === 'material')
    return <Material key={idDoMaterial()} id={idDoMaterial()!} {...comum} aoNovoEnvio={() => navegar('novo')} aoAbrirTopico={abrirTopico} />
  if (rota === 'topico') return <Topico key={idDoTopico()} id={idDoTopico()!} {...comum} aoAbrirMaterial={abrirMaterial} />
  return <Inicio nome={sessao.nome} aoSair={encerrar} aoAbrirPerfil={() => navegar('perfil')} aoNovoEstudo={() => navegar('novo')} aoAbrirMaterial={abrirMaterial} aoAbrirTopico={abrirTopico} />
}
