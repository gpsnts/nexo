import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import Carregando from './Esqueleto'
import { capaDe, LIMITE_LISTA, motivoDe, nomeDoEstudo, type Assunto, type Material, type Revisao, type TopicoLivre } from './estudo'
import { IconeCheck, IconeDocumento, IconeFaisca, Ilustracao, Rede } from './Icones'
import Pastas, { TODAS } from './Pastas'
import SeletorPasta from './SeletorPasta'
import { irPara, urlTopicoGerar } from './rotas'
import { dataCompleta } from './tempo'
import { useTitulo } from './titulo'

const STATUS: Record<Material['status'], string> = { pendente: 'Na fila', processando: 'Processando', pronto: 'Pronto', falhou: 'Falhou' }

/**
 * Tela inicial: painel "Hoje" da revisão, pastas e lista de estudos (materiais e tópicos livres), com filtro por pasta.
 * @param props nome: nome do usuário; aoSair, aoAbrirPerfil, aoNovoEstudo: navegação; aoAbrirMaterial, aoAbrirTopico: abrem um estudo.
 * @returns a tela.
 */
export default function Inicio({ nome, aoSair, aoAbrirPerfil, aoNovoEstudo, aoAbrirMaterial, aoAbrirTopico }: {
  nome: string; aoSair: () => void; aoAbrirPerfil: () => void; aoNovoEstudo: () => void; aoAbrirMaterial: (id: string) => void; aoAbrirTopico: (id: string) => void
}) {
  const [assuntos, setAssuntos] = useState<Assunto[] | null>(null)
  const [materiais, setMateriais] = useState<Material[] | null>(null)
  const [livres, setLivres] = useState<TopicoLivre[] | null>(null) // nulo = ainda carregando
  const [revisao, setRevisao] = useState<Revisao | null>(null)   // só enfeita o início: sem ela o resto funciona
  const [falhaMateriais, setFalhaMateriais] = useState(false)
  const [filtro, setFiltro] = useState(TODAS)
  useTitulo('Início', true)
  /**
   * Busca os materiais do usuário.
   * @returns promessa resolvida quando termina.
   */
  const buscarMateriais = () => api<Material[]>(`/materiais?limite=${LIMITE_LISTA}`).then(setMateriais).catch(() => setFalhaMateriais(true))
  /**
   * Busca os tópicos livres do usuário.
   * @returns promessa resolvida quando termina.
   */
  const buscarLivres = () => api<TopicoLivre[]>(`/topicos/livres?limite=${LIMITE_LISTA}`).then(setLivres).catch(() => setLivres([]))
  /** Busca os materiais de novo depois de uma falha. */
  const tentarMateriais = () => { setFalhaMateriais(false); buscarMateriais() }
  /**
   * Busca as pastas do usuário.
   * @returns promessa resolvida quando termina.
   */
  const carregarPastas = () => api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => setAssuntos([]))
  useEffect(() => { carregarPastas() }, [])
  useEffect(() => { buscarMateriais() }, [])
  useEffect(() => { buscarLivres() }, [])
  useEffect(() => { api<Revisao>('/revisao?resumo=true').then(setRevisao).catch(() => {}) }, [])

  // materiais e tópicos livres numa só lista, do mais novo ao mais antigo
  const estudos = materiais === null || livres === null ? null : [
    ...materiais.map((m) => ({ tipo: 'material' as const, m, em: m.criadoEm })),
    ...livres.map((t) => ({ tipo: 'livre' as const, t, em: t.criadoEm })),
  ].sort((a, b) => b.em.localeCompare(a.em))
  const geralId = assuntos?.find((a) => a.padrao)?.id
  /**
   * Nome de uma pasta.
   * @param id pasta.
   * @returns o nome, ou nada se a pasta não for conhecida.
   */
  const nomeDaPasta = (id?: string | null) => assuntos?.find((a) => a.id === id)?.nome
  /**
   * Pasta de um estudo (a do primeiro tópico, no caso de um material com tópicos em várias pastas).
   * @param e material ou tópico livre da lista.
   * @returns id da pasta, ou nulo.
   */
  const pastaDe = (e: NonNullable<typeof estudos>[number]) => (e.tipo === 'material' ? e.m.assuntoId : e.t.assuntoId)
  // sem pasta conhecida (material ainda processando) aparece em qualquer filtro
  /**
   * Diz se o estudo aparece na pasta filtrada (um material aparece em toda pasta onde tem tópicos).
   * @param e material ou tópico livre da lista.
   * @returns verdadeiro se o estudo pertence ao filtro.
   */
  const naPasta = (e: NonNullable<typeof estudos>[number]) => (e.tipo === 'material' ? (e.m.assuntoIds?.length ? e.m.assuntoIds.includes(filtro) : pastaDe(e) === filtro) : pastaDe(e) === filtro)
  const visiveis = estudos === null ? null : estudos.filter((e) => filtro === TODAS || !pastaDe(e) || naPasta(e))
  /**
   * Nome da pasta a mostrar no cartão, só quando o filtro é "Todos" e a pasta não é a padrão.
   * @param e material ou tópico livre da lista.
   * @returns o nome da pasta, ou nada.
   */
  const pasta = (e: NonNullable<typeof estudos>[number]) => (filtro === TODAS && pastaDe(e) && pastaDe(e) !== geralId ? nomeDaPasta(pastaDe(e)) : undefined)
  const vencidas = revisao?.topicos.reduce((n, t) => n + t.vencidas, 0) ?? 0
  const alvoNovas = revisao?.topicos[0] ?? revisao?.recentes?.[0]   // o tópico mais urgente ou, sem nada vencido, o estudado há mais pouco

  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={() => {}} aoIrPerfil={aoAbrirPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Olá, {nome}</h1>
        {revisao && (
          <section className="hoje" aria-labelledby="titulo-hoje">
            <div>
              <h2 id="titulo-hoje">{vencidas > 0 ? `${vencidas} ${vencidas === 1 ? 'questão para revisar' : 'questões para revisar'} hoje` : 'Suas revisões estão em dia'}</h2>
              <p>{vencidas > 0 ? `Começando por ${revisao.topicos.slice(0, 2).map((t) => t.nome).join(' e ')}. A sessão tem até 20 questões.` : 'Nada vence hoje. Veja como foi a sua semana ou avance em um estudo.'}</p>
              <div className="acoes-linha">
                {vencidas > 0 ? <button type="button" onClick={() => irPara('/revisar')}>Revisar agora</button> : <button type="button" onClick={() => irPara('/desempenho')}>Ver desempenho</button>}
                {alvoNovas && <button type="button" className="sobre-cor" onClick={() => irPara(urlTopicoGerar(alvoNovas.id))}>Gerar novas questões</button>}
              </div>
            </div>
            <div className="hoje-numero" aria-hidden="true">{vencidas > 0 ? <div><strong>{vencidas}</strong><span>vencidas</span></div> : <IconeCheck />}</div>
            <Rede className="rede" />
          </section>
        )}
        {assuntos === null ? <Carregando blocos={1} /> : <Pastas pastas={assuntos} atual={filtro} aoEscolher={setFiltro} aoMudar={() => { carregarPastas(); buscarMateriais(); buscarLivres() }} />}
        <div className="titulo-linha">
          <h2>Seus estudos</h2>
          <button type="button" className="link" onClick={() => irPara('/historico')}>Ver histórico completo</button>
        </div>
        {falhaMateriais ? (
          <div role="alert"><p className="erro">Não foi possível carregar seus estudos.</p><button type="button" onClick={tentarMateriais}>Tentar de novo</button></div>
        ) : visiveis === null ? <Carregando /> : estudos!.length === 0 ? (
          <section className="vazio">
            <Ilustracao tipo="estudos" />
            <h2>Comece pelo seu primeiro estudo</h2>
            <p className="sub">Envie um PDF, DOCX ou TXT para gerar resumos e questões fiéis ao conteúdo, ou registre um tópico livre.</p>
            <button type="button" onClick={aoNovoEstudo}>Criar meu primeiro estudo</button>
          </section>
        ) : (
          visiveis.length === 0 ? <p className="sub" role="status">Nenhum estudo nesta pasta ainda. Abra um estudo e escolha a pasta dele, ou crie um novo.</p> : (
          <ul className="materiais" aria-label="Estudos">
            {visiveis.map((e) => e.tipo === 'material' ? (
              <li key={e.m.id}>
                <div className={`capa capa-${capaDe(nomeDoEstudo(e.m))}`}><IconeDocumento tam={30} /><span className="selo-tipo">Material</span></div>
                <div className="corpo-card material-linha">
                  <button type="button" className="link nome" onClick={() => aoAbrirMaterial(e.m.id)}>{nomeDoEstudo(e.m)}</button>
                  <div className="selos">
                    <span className={`selo-status ${e.m.status}`}>{STATUS[e.m.status]}</span>
                    {e.m.gerando && <span className="selo-status processando">Gerando…</span>}
                  </div>
                  <div className="meta">
                    <span>{dataCompleta(e.m.criadoEm)}</span>
                    {e.m.paginas ? <span>{e.m.paginas} páginas</span> : null}
                    {e.m.titulo ? <span>Arquivo {e.m.nomeArquivo}</span> : null}
                    {pasta(e) ? <span>Pasta {pasta(e)}</span> : null}
                  </div>
                  {assuntos && assuntos.length > 1 && <SeletorPasta id={`pasta-m-${e.m.id}`} pastas={assuntos} atual={e.m.assuntoId} nome={nomeDoEstudo(e.m)} caminho={`/materiais/${e.m.id}/assunto`} desabilitado={e.m.status !== 'pronto'} aoMudar={() => { carregarPastas(); buscarMateriais(); buscarLivres() }} />}
                  {e.m.status === 'falhou' && (
                    <>
                      <p className="motivo">{motivoDe(e.m.motivoFalha)}</p>
                      <button type="button" className="secundario" onClick={aoNovoEstudo} aria-label={`Enviar novamente: ${nomeDoEstudo(e.m)}`}>Enviar novamente</button>
                    </>
                  )}
                </div>
              </li>
            ) : (
              <li key={e.t.id}>
                <div className={`capa capa-${capaDe(e.t.nome)}`}><IconeFaisca tam={30} /><span className="selo-tipo">Tópico livre</span></div>
                <div className="corpo-card material-linha">
                  <button type="button" className="link nome" onClick={() => aoAbrirTopico(e.t.id)}>{e.t.nome}</button>
                  {e.t.gerando && <div className="selos"><span className="selo-status processando">Gerando…</span></div>}
                  <div className="meta">
                    <span>{dataCompleta(e.t.criadoEm)}</span>
                    <span>{e.t.temResumo ? 'com resumo' : 'sem resumo'}</span>
                    <span>{e.t.totalQuestoes} {e.t.totalQuestoes === 1 ? 'questão' : 'questões'}</span>
                    {pasta(e) ? <span>Pasta {pasta(e)}</span> : null}
                  </div>
                  {assuntos && assuntos.length > 1 && <SeletorPasta id={`pasta-t-${e.t.id}`} pastas={assuntos} atual={e.t.assuntoId} nome={e.t.nome} caminho={`/topicos/${e.t.id}/assunto`} aoMudar={() => { carregarPastas(); buscarMateriais(); buscarLivres() }} />}
                </div>
              </li>
            ))}
          </ul>
          )
        )}
        {estudos !== null && (materiais?.length ?? 0) >= LIMITE_LISTA && <p className="sub">Mostrando os {LIMITE_LISTA} materiais mais recentes.</p>}
        {estudos !== null && (livres?.length ?? 0) >= LIMITE_LISTA && <p className="sub">Mostrando os {LIMITE_LISTA} tópicos livres mais recentes.</p>}
      </main>
    </div>
  )
}
