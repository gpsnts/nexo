import { useEffect, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { agruparProvas, disponiveis, type Assunto, type ProvaResumo, type ProvasCriadas, type TopicoDaPasta } from './estudo'
import { Ilustracao } from './Icones'
import { irPara, urlPasta, urlProva } from './rotas'
import { dataCompleta } from './tempo'
import { useTitulo } from './titulo'

const MAXIMO_QUESTOES = 50  // igual ao padrão do backend; o servidor continua sendo a autoridade
const MAXIMO_MINUTOS = 480

/** Rótulo do estado de uma prova. */
const STATUS = { gerada: 'Não iniciada', em_andamento: 'Em andamento', concluida: 'Concluída' } as const

/**
 * Provas: lista (as do mesmo pedido juntas) e montagem de uma nova prova por escopo ("estudei até este tópico").
 * @param props aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho.
 * @returns a tela.
 */
export default function Provas({ aoIrInicio, aoIrPerfil, aoSair }: { aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void }) {
  const [provas, setProvas] = useState<ProvaResumo[] | null>(null)
  const [falha, setFalha] = useState(false)
  const [montando, setMontando] = useState(false)
  const [aviso, setAviso] = useState('')
  useTitulo('Provas', true)
  /**
   * Busca as provas do usuário.
   * @returns promessa resolvida quando termina.
   */
  const buscar = () => api<ProvaResumo[]>('/provas').then(setProvas).catch(() => setFalha(true))
  useEffect(() => { buscar() }, [])
  const grupos = provas === null ? null : agruparProvas(provas)

  return (
    <div className="pagina">
      <Cabecalho atual="provas" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <div className="titulo-linha">
          <h1>Provas</h1>
          {!montando && <button type="button" onClick={() => { setMontando(true); setAviso('') }}>Montar prova</button>}
        </div>
        <p className="sub">Escolha os assuntos e até que tópico você já estudou. A prova sorteia questões que já existem nos seus tópicos, sem chamar a IA.</p>
        {aviso && <p role="status" className="ok">{aviso}</p>}
        {montando && <Montar aoCancelar={() => setMontando(false)} aoCriar={(r) => {
          if (r.provas.length === 1) return irPara(urlProva(r.provas[0].id))
          setMontando(false); setAviso(`${r.provas.length} provas criadas, uma por assunto. Elas aparecem juntas abaixo.`); buscar()
        }} />}
        {falha ? (
          <div role="alert"><p className="erro">Não foi possível carregar as provas.</p><button type="button" onClick={() => { setFalha(false); buscar() }}>Tentar de novo</button></div>
        ) : grupos === null ? <Carregando /> : grupos.length === 0 ? (
          !montando && (
            <section className="vazio">
              <Ilustracao tipo="revisao" />
              <h2>Nenhuma prova ainda</h2>
              <p className="sub">Monte uma prova com questões dos tópicos que você já estudou. Para ter questões, gere-as antes em um tópico.</p>
            </section>
          )
        ) : (
          <ul className="lista lista-provas" aria-label="Provas">
            {grupos.map((g) => (
              <li key={g[0].grupoId ?? g[0].id}>
                {g.length > 1 && <p className="sub grupo-titulo">Pedido de {dataCompleta(g[0].criadaEm)}: {g.length} provas, uma por assunto</p>}
                {g.map((p) => <LinhaProva key={p.id} prova={p} />)}
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}

/**
 * Uma prova na lista: assuntos, andamento e botão para abrir.
 * @param props prova: a prova.
 * @returns a linha.
 */
function LinhaProva({ prova }: { prova: ProvaResumo }) {
  const nomes = prova.assuntos.map((a) => a.nome).join(', ')
  const acao = prova.status === 'concluida' ? 'Ver resultado' : prova.status === 'em_andamento' ? 'Continuar' : 'Começar'
  return (
    <div className="linha-prova">
      <div>
        <strong>{nomes}</strong>
        <div className="meta">
          <span>{STATUS[prova.status]}</span>
          <span>{prova.status === 'concluida' ? `${prova.acertos} de ${prova.quantidade} certas` : `${prova.respondidas} de ${prova.quantidade} respondidas`}</span>
          {prova.limiteMinutos && <span>{prova.limiteMinutos} min</span>}
          <span>{dataCompleta(prova.criadaEm)}</span>
        </div>
      </div>
      <button type="button" className={prova.status === 'concluida' ? 'secundario' : ''} aria-label={`${acao}: prova de ${nomes}`} onClick={() => irPara(urlProva(prova.id))}>{acao}</button>
    </div>
  )
}

/**
 * Formulário de montagem: assuntos, tópico-limite de cada um, quantidade, prova única ou separadas e limite de tempo.
 * @param props aoCancelar: fecha o formulário; aoCriar: recebe as provas criadas.
 * @returns o formulário.
 */
function Montar({ aoCancelar, aoCriar }: { aoCancelar: () => void; aoCriar: (r: ProvasCriadas) => void }) {
  const [pastas, setPastas] = useState<Assunto[] | null>(null)
  const [topicos, setTopicos] = useState<Record<string, TopicoDaPasta[]>>({})
  const [escolhidas, setEscolhidas] = useState<string[]>([]) // ordem em que foram marcadas (vale para repartir a sobra)
  const [limites, setLimites] = useState<Record<string, string>>({})
  const [quantidade, setQuantidade] = useState('10')
  const [separadas, setSeparadas] = useState(false)
  const [comTempo, setComTempo] = useState(false)
  const [minutos, setMinutos] = useState('30')
  const [erro, setErro] = useState('')
  const [faltam, setFaltam] = useState<number | null>(null) // questões disponíveis quando o pedido é grande demais
  const [enviando, setEnviando] = useState(false)
  useEffect(() => { api<Assunto[]>('/assuntos').then(setPastas).catch(() => setPastas([])) }, [])

  /**
   * Marca ou desmarca um assunto e, ao marcar, carrega os tópicos dele para o tópico-limite.
   * @param id assunto.
   * @returns promessa resolvida quando termina.
   */
  async function alternar(id: string) {
    setErro(''); setFaltam(null)
    if (escolhidas.includes(id)) return setEscolhidas((v) => v.filter((x) => x !== id))
    setEscolhidas((v) => [...v, id])
    if (!topicos[id]) {
      try { const t = await api<TopicoDaPasta[]>(`/assuntos/${id}/topicos`); setTopicos((m) => ({ ...m, [id]: t })) } catch { setTopicos((m) => ({ ...m, [id]: [] })) }
    }
  }
  /**
   * Valida e pede a(s) prova(s).
   * @param e evento de envio do formulário.
   * @returns promessa resolvida quando termina.
   */
  async function enviar(e: React.FormEvent) {
    e.preventDefault()
    const n = Number(quantidade), min = Number(minutos)
    if (escolhidas.length === 0) return setErro('Escolha ao menos um assunto.')
    if (!Number.isInteger(n) || n < 1 || n > MAXIMO_QUESTOES) return setErro(`Informe de 1 a ${MAXIMO_QUESTOES} questões.`)
    if (comTempo && (!Number.isInteger(min) || min < 1 || min > MAXIMO_MINUTOS)) return setErro(`O limite de tempo vai de 1 a ${MAXIMO_MINUTOS} minutos.`)
    setEnviando(true); setErro(''); setFaltam(null)
    try {
      aoCriar(await api<ProvasCriadas>('/provas', { corpo: {
        assuntos: escolhidas.map((id) => ({ assuntoId: id, topicoLimiteId: limites[id] || null })), quantidade: n, separadas: separadas && escolhidas.length > 1, limiteMinutos: comTempo ? min : null,
      } }))
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível montar a prova. Tente de novo.')
      if (x instanceof ErroApi && x.codigo === 'questoes_insuficientes') setFaltam(Number(x.dados?.disponiveis ?? 0))
    } finally { setEnviando(false) }
  }

  return (
    <form className="secao form-prova" onSubmit={enviar} noValidate aria-labelledby="titulo-montar">
      <h2 id="titulo-montar">Montar prova</h2>
      <fieldset>
        <legend>Assuntos (pastas)</legend>
        {pastas === null ? <Carregando blocos={1} /> : (
          <ul className="escolhas">
            {pastas.map((p) => {
              const marcada = escolhidas.includes(p.id)
              return (
                <li key={p.id}>
                  <label className="escolha-topico"><input type="checkbox" checked={marcada} onChange={() => alternar(p.id)} /><strong>{p.padrao ? 'Geral' : p.nome}</strong></label>
                  {marcada && (
                    <div className="campo">
                      <label htmlFor={`limite-${p.id}`}>Já estudei até o tópico</label>
                      <select id={`limite-${p.id}`} value={limites[p.id] ?? ''} onChange={(e) => setLimites((m) => ({ ...m, [p.id]: e.target.value }))}>
                        <option value="">Todos os tópicos</option>
                        {(topicos[p.id] ?? []).map((t) => <option key={t.id} value={t.id}>{t.ordem}. {t.nome} ({t.totalQuestoes})</option>)}
                      </select>
                      {topicos[p.id] && <small>{disponiveis(topicos[p.id], limites[p.id])} questões disponíveis{limites[p.id] ? ' até aí' : ''}. O número entre parênteses é o de questões de cada tópico.</small>}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </fieldset>
      <div className="campo">
        <label htmlFor="qtd-prova">Quantidade de questões</label>
        <input id="qtd-prova" type="number" inputMode="numeric" min={1} max={MAXIMO_QUESTOES} value={quantidade} onChange={(e) => setQuantidade(e.target.value)} />
        <small>De 1 a {MAXIMO_QUESTOES}. Com vários assuntos, a quantidade é dividida igualmente entre eles.</small>
      </div>
      {escolhidas.length > 1 && (
        <fieldset>
          <legend>Como montar</legend>
          <label className="opcao-radio"><input type="radio" name="modo-prova" checked={!separadas} onChange={() => setSeparadas(false)} />Uma prova com os assuntos misturados</label>
          <label className="opcao-radio"><input type="radio" name="modo-prova" checked={separadas} onChange={() => setSeparadas(true)} />Uma prova por assunto (cada uma com {Number(quantidade) || 'a quantidade de'} questões)</label>
        </fieldset>
      )}
      <div className="campo">
        <label className="escolha-topico"><input type="checkbox" checked={comTempo} onChange={(e) => setComTempo(e.target.checked)} />Limitar o tempo</label>
        {comTempo && (
          <>
            <label htmlFor="min-prova">Minutos para fazer a prova</label>
            <input id="min-prova" type="number" inputMode="numeric" min={1} max={MAXIMO_MINUTOS} value={minutos} onChange={(e) => setMinutos(e.target.value)} />
            <small>O relógio começa quando você tocar em "Começar prova". Ao acabar, a prova termina e o que ficou sem resposta conta como errado.</small>
          </>
        )}
      </div>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {faltam !== null && (
        <div className="acoes-linha">
          {faltam > 0 && <button type="button" className="secundario" onClick={() => { setQuantidade(String(faltam)); setErro(''); setFaltam(null) }}>Reduzir para {faltam} {faltam === 1 ? 'questão' : 'questões'}</button>}
          {escolhidas[0] && <button type="button" className="secundario" onClick={() => irPara(urlPasta(escolhidas[0]))}>Abrir os tópicos para gerar mais questões</button>}
        </div>
      )}
      <div className="acoes-linha">
        <button type="submit" disabled={enviando}>{enviando ? 'Aguarde…' : 'Gerar prova'}</button>
        <button type="button" className="link" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}
