import Carregando from './Esqueleto'
import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { Ilustracao } from './Icones'
import type { Revisao as RevisaoDados } from './estudo'
import Responder, { Resultado } from './Responder'
import { irPara, urlTopicoGerar } from './rotas'
import { useTitulo } from './titulo'

/**
 * Revisão adaptativa: até 20 questões vencidas, dos tópicos mais urgentes.
 * @param props aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho.
 * @returns a tela.
 */
export default function Revisao({ aoIrInicio, aoIrPerfil, aoSair }: { aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void }) {
  const [dados, setDados] = useState<RevisaoDados | null>(null)
  const [falha, setFalha] = useState(false)
  const [comecou, setComecou] = useState(false)
  const [escolhidos, setEscolhidos] = useState<Set<string> | null>(null) // nulo = todos os tópicos
  const [sessao, setSessao] = useState<RevisaoDados['questoes'] | null>(null) // questões congeladas ao começar
  const [iniciando, setIniciando] = useState(false)
  const [erroInicio, setErroInicio] = useState('')
  useTitulo('Revisar', true)
  /**
   * Busca a sessão de revisão.
   * @returns promessa resolvida quando termina.
   */
  const buscar = () => api<RevisaoDados>('/revisao').then(setDados).catch(() => setFalha(true))
  /** Busca de novo depois de uma falha ou ao começar outra revisão. */
  const carregar = () => { setFalha(false); buscar() }
  useEffect(() => { buscar() }, [])
  const marcados = dados ? dados.topicos.filter((t) => escolhidos === null || escolhidos.has(t.id)) : []
  const todos = dados !== null && marcados.length === dados.topicos.length
  const vencidasMarcadas = marcados.reduce((n, t) => n + t.vencidas, 0)
  const nSessao = todos ? dados!.questoes.length : Math.min(vencidasMarcadas, 20)
  /**
   * Marca ou desmarca um tópico da sessão.
   * @param id tópico.
   */
  const alternar = (id: string) => {
    const atual = new Set(marcados.map((t) => t.id))
    if (atual.has(id)) atual.delete(id); else atual.add(id)
    setEscolhidos(atual.size === dados!.topicos.length ? null : atual)
  }
  /**
   * Começa a sessão: com todos os tópicos usa as questões já carregadas; com uma escolha, pede ao servidor só as dos tópicos marcados.
   * @returns promessa resolvida quando a sessão começa ou falha.
   */
  const comecar = async () => {
    setErroInicio('')
    if (todos) { setSessao(dados!.questoes); setComecou(true); return }
    setIniciando(true)
    try {
      const r = await api<RevisaoDados>(`/revisao?topicos=${marcados.map((t) => t.id).join(',')}`)
      setSessao(r.questoes); setComecou(true)
    } catch { setErroInicio('Não foi possível montar a sessão. Tente de novo.') } finally { setIniciando(false) }
  }
  return (
    <div className="pagina">
      <Cabecalho atual="revisar" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1>Revisar</h1>
        {falha && <div role="alert"><p className="erro">Não foi possível carregar a revisão.</p><button type="button" onClick={carregar}>Tentar de novo</button></div>}
        {!falha && dados === null && <Carregando />}
        {dados && dados.questoes.length === 0 && (
          <div className="vazio" role="status">
            <Ilustracao tipo="revisao" />
            <p><strong>Nenhuma questão para revisar agora.</strong></p>
            <p className="sub">As revisões aparecem aqui na data agendada, depois que você responde questões. Que tal treinar com questões novas?</p>
            <NovasQuestoes topicos={dados.recentes ?? []} aoIrInicio={aoIrInicio} />
          </div>
        )}
        {dados && dados.questoes.length > 0 && !comecou && (
          <>
            <p className="sub">{nSessao} {nSessao === 1 ? 'questão' : 'questões'} nesta sessão, começando pelos tópicos mais urgentes. Desmarque tópicos para revisar só os que quiser.</p>
            <ul className="lista lista-revisao" aria-label="Tópicos a revisar">
              {dados.topicos.map((t) => (
                <li key={t.id}>
                  <label className="escolha-topico"><input type="checkbox" checked={escolhidos === null || escolhidos.has(t.id)} onChange={() => alternar(t.id)} /><strong>{t.nome}</strong></label>
                  <small className="sub">{t.vencidas} {t.vencidas === 1 ? 'vencida' : 'vencidas'} · {Math.round(t.taxaErro * 100)}% de erros</small>
                  <button type="button" className="secundario pequeno" aria-label={`Gerar novas questões de ${t.nome}`} onClick={() => irPara(urlTopicoGerar(t.id))}>Gerar novas questões</button>
                </li>
              ))}
            </ul>
            {erroInicio && <p role="alert" className="erro">{erroInicio}</p>}
            <button type="button" onClick={comecar} disabled={marcados.length === 0 || iniciando}>{iniciando ? 'Aguarde…' : 'Começar revisão'}</button>
            {marcados.length === 0 && <p className="sub" role="status">Marque ao menos um tópico.</p>}
          </>
        )}
        {dados && comecou && (
          <Responder questoes={sessao ?? dados.questoes} fim={({ acertos, total, proxima }) => (
            <Resultado acertos={acertos} total={total} proxima={proxima} titulo="Resumo da sessão">
              <button type="button" onClick={() => { setComecou(false); setSessao(null); setEscolhidos(null); setDados(null); carregar() }}>Nova revisão</button>
              <NovasQuestoes topicos={topicosDaSessao(sessao ?? dados.questoes)} aoIrInicio={aoIrInicio} semInicio />
              <button type="button" className="link" onClick={() => irPara('/desempenho')}>Ver desempenho</button>
              <button type="button" className="link" onClick={aoIrInicio}>Voltar ao início</button>
            </Resultado>
          )} />
        )}
      </main>
    </div>
  )
}

/**
 * Os tópicos que apareceram na sessão, sem repetir, na ordem em que apareceram.
 * @param questoes questões da sessão.
 * @returns tópicos (id e nome).
 */
const topicosDaSessao = (questoes: RevisaoDados['questoes']) => [...new Map(questoes.map((q) => [q.topicoId, { id: q.topicoId, nome: q.topico ?? '' }])).values()]

/**
 * Atalho sempre disponível: gerar questões novas do mesmo conteúdo (a revisão em si só repete questões já respondidas).
 * @param props topicos: tópicos oferecidos; aoIrInicio: vai ao início; semInicio: omite o botão de ir ao início.
 * @returns os botões de atalho, ou nada.
 */
function NovasQuestoes({ topicos, aoIrInicio, semInicio = false }: { topicos: { id: string; nome: string }[]; aoIrInicio: () => void; semInicio?: boolean }) {
  if (topicos.length === 0) return semInicio ? null : <button type="button" onClick={aoIrInicio}>Ir ao início</button>
  return (
    <div className="novas-questoes">
      {topicos.map((t) => <button key={t.id} type="button" className="secundario" aria-label={`Gerar novas questões de ${t.nome}`} onClick={() => irPara(urlTopicoGerar(t.id))}>{topicos.length === 1 ? 'Gerar novas questões' : `Gerar novas questões de ${t.nome}`}</button>)}
      {!semInicio && <button type="button" className="link" onClick={aoIrInicio}>Ir ao início</button>}
    </div>
  )
}
