import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { relogio, type ProvaAberta, type ProvaResumo } from './estudo'
import Responder, { Resultado } from './Responder'
import { irPara, urlProva } from './rotas'
import { dataCompleta } from './tempo'
import { useTitulo } from './titulo'

const LETRAS = 'ABCDE'

/**
 * Uma prova: antes de começar mostra o escopo; em andamento, o questionário com o relógio (se houver limite); depois, o resultado com a revisão das questões.
 * @param props id: prova; aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho.
 * @returns a tela.
 */
export default function Prova({ id, aoIrInicio, aoIrPerfil, aoSair }: { id: string; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void }) {
  const [aberta, setAberta] = useState<ProvaAberta | null>(null)
  const [falha, setFalha] = useState(false)
  const [naoEncontrada, setNaoEncontrada] = useState(false)
  const [iniciando, setIniciando] = useState(false)
  const [erro, setErro] = useState('')
  useTitulo('Prova', true)
  /**
   * Busca a prova (e, no servidor, conclui a que passou do tempo).
   * @returns promessa resolvida quando termina.
   */
  const carregar = useCallback(() => api<ProvaAberta>(`/provas/${id}`).then(setAberta).catch((e) => (e instanceof ErroApi && e.status === 404 ? setNaoEncontrada(true) : setFalha(true))), [id])
  useEffect(() => { carregar() }, [carregar])

  /**
   * Começa a prova (inicia o relógio, se houver limite).
   * @returns promessa resolvida quando termina.
   */
  async function comecar() {
    setIniciando(true); setErro('')
    try { setAberta(await api<ProvaAberta>(`/provas/${id}/iniciar`, { metodo: 'POST' })) } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível começar a prova. Tente de novo.') } finally { setIniciando(false) }
  }
  const p = aberta?.prova

  return (
    <div className="pagina">
      <Cabecalho atual="provas" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <button type="button" className="link voltar" onClick={() => irPara('/provas')}>← Todas as provas</button>
        <h1 tabIndex={-1}>{p ? `Prova de ${p.assuntos.map((a) => a.nome).join(', ')}` : 'Prova'}</h1>
        {naoEncontrada ? (
          <div role="alert"><p className="erro">Esta prova não existe ou não é da sua conta.</p><button type="button" onClick={() => irPara('/provas')}>Ver minhas provas</button></div>
        ) : falha ? (
          <div role="alert"><p className="erro">Não foi possível abrir a prova.</p><button type="button" onClick={() => { setFalha(false); carregar() }}>Tentar de novo</button></div>
        ) : !aberta || !p ? <Carregando /> : p.status === 'gerada' ? (
          <section className="secao" aria-labelledby="titulo-intro">
            <h2 id="titulo-intro">Pronta para começar</h2>
            <Escopo prova={p} />
            {p.limiteMinutos && <p className="aviso-ia" role="note">Esta prova tem limite de {p.limiteMinutos} minutos. O relógio começa agora e não para; ao acabar, o que ficar sem resposta conta como errado.</p>}
            {erro && <p className="erro" role="alert">{erro}</p>}
            <button type="button" onClick={comecar} disabled={iniciando}>{iniciando ? 'Aguarde…' : 'Começar prova'}</button>
          </section>
        ) : p.status === 'em_andamento' ? (
          <>
            {p.prazo && <Relogio prazo={p.prazo} agora={aberta.agora} aoAcabar={carregar} />}
            <p className="sub">{p.respondidas} de {p.quantidade} respondidas.</p>
            <Responder
              key={p.id} provaId={p.id} aoEncerrar={carregar}
              questoes={aberta.questoes.filter((q) => !q.respondida)}
              fim={() => <Encerrando aoMontar={carregar} />}
            />
          </>
        ) : (
          <Fim aberta={aberta} aoRefeita={(nova) => { setAberta(null); irPara(urlProva(nova.id)) }} />
        )}
        {aberta && <ExcluirProva id={id} />}
      </main>
    </div>
  )
}

/**
 * Escopo da prova: assuntos, tópico-limite, quantidade e tempo.
 * @param props prova: a prova.
 * @returns a lista do escopo.
 */
function Escopo({ prova }: { prova: ProvaResumo }) {
  return (
    <ul className="escopo">
      {prova.assuntos.map((a) => <li key={a.id}><strong>{a.nome}</strong>: {a.topicoLimite ? `até o tópico "${a.topicoLimite}"` : 'todos os tópicos'}</li>)}
      <li>{prova.quantidade} {prova.quantidade === 1 ? 'questão' : 'questões'}{prova.limiteMinutos ? `, ${prova.limiteMinutos} minutos` : ', sem limite de tempo'}</li>
    </ul>
  )
}

/**
 * Passo intermediário depois da última resposta: pede ao servidor o estado final da prova.
 * @param props aoMontar: recarrega a prova.
 * @returns um aviso de carregamento.
 */
function Encerrando({ aoMontar }: { aoMontar: () => void }) {
  useEffect(() => { aoMontar() }, [aoMontar])
  return <Carregando blocos={1} />
}

/**
 * Relógio regressivo. Mede o tempo pelo relógio do servidor (a diferença para o do aparelho é calculada uma vez); quando acaba, pede o estado final da prova. Avisa em texto quando restam 5 minutos e 1 minuto.
 * @param props prazo: instante em que o tempo acaba; agora: hora do servidor na resposta; aoAcabar: chamado quando o tempo se esgota (e repetido a cada 2 s até a prova constar como concluída).
 * @returns o relógio.
 */
function Relogio({ prazo, agora, aoAcabar }: { prazo: string; agora: string; aoAcabar: () => void }) {
  const deslocamento = useRef(Date.parse(agora) - Date.now())
  /**
   * Segundos que faltam, pelo relógio do servidor.
   * @returns os segundos restantes (negativos depois do prazo).
   */
  const restante = () => (Date.parse(prazo) - (Date.now() + deslocamento.current)) / 1000
  const [s, setS] = useState(restante)
  const [anuncio, setAnuncio] = useState('')
  useEffect(() => {
    const id = setInterval(() => setS(restante()), 1000)
    return () => clearInterval(id)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prazo])
  useEffect(() => {
    if (s <= 0) { aoAcabar(); const t = setInterval(aoAcabar, 2000); return () => clearInterval(t) }
    if (s <= 60) setAnuncio('Resta 1 minuto.')
    else if (s <= 300) setAnuncio('Restam 5 minutos.')
  }, [s <= 0, s <= 60, s <= 300]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className={`relogio-prova${s <= 60 ? ' urgente' : ''}`}>
      <span className="oculto">Tempo restante</span>
      <span role="timer" aria-live="off" className="relogio-valor">{relogio(s)}</span>
      <span role="status" className="oculto">{anuncio}</span>
    </div>
  )
}

/**
 * Resultado de uma prova concluída: nota, acertos por assunto, revisão questão a questão e as ações (refazer, voltar).
 * @param props aberta: prova concluída com as questões; aoRefeita: recebe a nova prova criada ao refazer.
 * @returns o resultado.
 */
function Fim({ aberta, aoRefeita }: { aberta: ProvaAberta; aoRefeita: (nova: ProvaResumo) => void }) {
  const { prova, questoes } = aberta
  const [refazendo, setRefazendo] = useState(false)
  const [erro, setErro] = useState('')
  const porAssunto = [...questoes.reduce((m, q) => m.set(q.assunto, { total: (m.get(q.assunto)?.total ?? 0) + 1, acertos: (m.get(q.assunto)?.acertos ?? 0) + (q.correta ? 1 : 0) }), new Map<string, { total: number; acertos: number }>())]
  const semResposta = prova.quantidade - prova.respondidas
  /**
   * Cria uma nova prova com as mesmas questões.
   * @returns promessa resolvida quando termina.
   */
  async function refazer() {
    setRefazendo(true); setErro('')
    try { aoRefeita(await api<ProvaResumo>(`/provas/${prova.id}/refazer`, { metodo: 'POST' })) } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível refazer a prova. Tente de novo.'); setRefazendo(false) }
  }
  return (
    <>
      <Resultado acertos={prova.acertos} total={prova.quantidade} titulo="Resultado da prova">
        <button type="button" onClick={refazer} disabled={refazendo}>{refazendo ? 'Aguarde…' : 'Refazer prova'}</button>
        <button type="button" className="secundario" onClick={() => irPara('/provas')}>Ver todas as provas</button>
      </Resultado>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {prova.concluidaEm && <p className="sub">Concluída em {dataCompleta(prova.concluidaEm)}.{semResposta > 0 ? ` ${semResposta} ${semResposta === 1 ? 'questão ficou sem resposta e contou' : 'questões ficaram sem resposta e contaram'} como erro.` : ''}</p>}
      {porAssunto.length > 1 && (
        <section className="secao" aria-labelledby="titulo-por-assunto">
          <h2 id="titulo-por-assunto">Por assunto</h2>
          <ul className="escopo">{porAssunto.map(([nome, v]) => <li key={nome}><strong>{nome}</strong>: {v.acertos} de {v.total}</li>)}</ul>
        </section>
      )}
      <section className="secao" aria-labelledby="titulo-revisao-prova">
        <h2 id="titulo-revisao-prova">Revisão das questões</h2>
        <ol className="revisao-prova">
          {questoes.map((q) => (
            <li key={q.id}>
              <p className="sub">{q.assunto} · {q.topico}</p>
              <p><strong>{q.enunciado}</strong></p>
              {q.respondida ? (
                <p className={q.correta ? 'ok' : 'erro'}>
                  {q.correta ? `Você acertou: ${LETRAS[q.alternativaEscolhida!]}. ${q.alternativas[q.alternativaEscolhida!]}`
                    : `Você marcou ${LETRAS[q.alternativaEscolhida!]}. ${q.alternativas[q.alternativaEscolhida!]}. Correta: ${LETRAS[q.respostaCorreta!]}. ${q.alternativas[q.respostaCorreta!]}`}
                </p>
              ) : <p className="erro">Sem resposta.</p>}
            </li>
          ))}
        </ol>
      </section>
    </>
  )
}

/**
 * Exclusão de uma prova, com confirmação. As respostas dadas continuam no histórico e no painel de desempenho.
 * @param props id: prova.
 * @returns o botão (ou a confirmação) de excluir.
 */
function ExcluirProva({ id }: { id: string }) {
  const [confirmando, setConfirmando] = useState(false)
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState('')
  /**
   * Exclui a prova e volta à lista.
   * @returns promessa resolvida quando termina.
   */
  async function excluir() {
    setOcupado(true); setErro('')
    try { await api(`/provas/${id}`, { metodo: 'DELETE' }); irPara('/provas') } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível excluir. Tente de novo.'); setOcupado(false) }
  }
  return (
    <section className="excluir-discreto" aria-label="Excluir prova">
      {!confirmando ? <button type="button" className="link" onClick={() => setConfirmando(true)}>Excluir esta prova</button> : (
        <div role="alert" className="aviso-ia">
          <p>Excluir esta prova? As respostas que você já deu continuam no seu histórico e no painel de desempenho.</p>
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
