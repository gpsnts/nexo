import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { LIMITE_LISTA, motivoDe, type Assunto, type Material, type TopicoLivre } from './estudo'
import { useTitulo } from './titulo'
import { dataCompleta } from './tempo'

const STATUS: Record<Material['status'], string> = { pendente: 'Na fila', processando: 'Processando', pronto: 'Pronto', falhou: 'Falhou' }

export default function Inicio({ nome, aoSair, aoAbrirPerfil, aoNovoEstudo, aoAbrirMaterial, aoAbrirTopico }: {
  nome: string; aoSair: () => void; aoAbrirPerfil: () => void; aoNovoEstudo: () => void; aoAbrirMaterial: (id: string) => void; aoAbrirTopico: (id: string) => void
}) {
  const [assuntos, setAssuntos] = useState<Assunto[] | null>(null)
  const [materiais, setMateriais] = useState<Material[] | null>(null)
  const [livres, setLivres] = useState<TopicoLivre[] | null>(null) // nulo = ainda carregando
  const [falhaMateriais, setFalhaMateriais] = useState(false)
  useTitulo('Início', true)
  const buscarMateriais = () => api<Material[]>(`/materiais?limite=${LIMITE_LISTA}`).then(setMateriais).catch(() => setFalhaMateriais(true))
  const tentarMateriais = () => { setFalhaMateriais(false); buscarMateriais() }
  useEffect(() => { api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => setAssuntos([])) }, [])
  useEffect(() => { buscarMateriais() }, [])
  useEffect(() => { api<TopicoLivre[]>(`/topicos/livres?limite=${LIMITE_LISTA}`).then(setLivres).catch(() => setLivres([])) }, []) // só complementa a tela: sem a lista, o resto segue funcionando
  // materiais e tópicos livres numa só lista, do mais novo ao mais antigo (a organização por assunto é da F5, ADR 0036)
  const estudos = materiais === null || livres === null ? null : [
    ...materiais.map((m) => ({ tipo: 'material' as const, m, em: m.criadoEm })),
    ...livres.map((t) => ({ tipo: 'livre' as const, t, em: t.criadoEm })),
  ].sort((a, b) => b.em.localeCompare(a.em))
  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={() => {}} aoIrPerfil={aoAbrirPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Olá, {nome}</h1>
        <h2>Seus assuntos</h2>
        <p className="sub dica">O assunto padrão guarda os estudos para os quais você não escolhe um assunto.</p>
        {assuntos === null ? <p className="sub" role="status">Carregando…</p> : <ul className="chips" aria-label="Assuntos">{assuntos.map((a) => <li key={a.id}><span>{a.nome}</span>{a.padrao && <small className="sub"> · padrão</small>}</li>)}</ul>}
        <div className="titulo-linha">
          <h2>Seus estudos</h2>
          <button type="button" onClick={aoNovoEstudo}>Novo estudo</button>
        </div>
        {falhaMateriais ? (
          <div role="alert"><p className="erro">Não foi possível carregar seus estudos.</p><button type="button" onClick={tentarMateriais}>Tentar de novo</button></div>
        ) : estudos === null ? <p className="sub" role="status">Carregando…</p> : estudos.length === 0 ? (
          <section className="vazio"><p className="sub">Você ainda não tem nenhum estudo. Envie um PDF, DOCX ou TXT em "Novo estudo" para gerar resumos e questões fiéis ao conteúdo, ou registre ali um tópico livre.</p></section>
        ) : (
          <ul className="materiais" aria-label="Estudos">
            {estudos.map((e) => e.tipo === 'material' ? (
              <li key={e.m.id}>
                <div className="material-linha">
                  <button type="button" className="link nome" onClick={() => aoAbrirMaterial(e.m.id)}>{e.m.nomeArquivo}</button>
                  <span className="selos">
                    <span className="selo-tipo">Material</span>
                    <span className={`selo-status ${e.m.status}`}>{STATUS[e.m.status]}</span>
                    {e.m.gerando && <span className="selo-status processando">Gerando…</span>}
                  </span>
                </div>
                <small className="sub">{dataCompleta(e.m.criadoEm)}{e.m.paginas ? ` · ${e.m.paginas} páginas` : ''}</small>
                {e.m.status === 'falhou' && (
                  <>
                    <p className="motivo">{motivoDe(e.m.motivoFalha)}</p>
                    <button type="button" className="secundario" onClick={aoNovoEstudo} aria-label={`Enviar novamente: ${e.m.nomeArquivo}`}>Enviar novamente</button>
                  </>
                )}
              </li>
            ) : (
              <li key={e.t.id}>
                <div className="material-linha">
                  <button type="button" className="link nome" onClick={() => aoAbrirTopico(e.t.id)}>{e.t.nome}</button>
                  <span className="selos">
                    <span className="selo-tipo">Tópico livre</span>
                    {e.t.gerando && <span className="selo-status processando">Gerando…</span>}
                  </span>
                </div>
                <small className="sub">{dataCompleta(e.t.criadoEm)} · {e.t.temResumo ? 'com resumo' : 'sem resumo'} · {e.t.totalQuestoes} {e.t.totalQuestoes === 1 ? 'questão' : 'questões'}</small>
              </li>
            ))}
          </ul>
        )}
        {estudos !== null && (materiais?.length ?? 0) >= LIMITE_LISTA && <p className="sub">Mostrando os {LIMITE_LISTA} materiais mais recentes.</p>}
        {estudos !== null && (livres?.length ?? 0) >= LIMITE_LISTA && <p className="sub">Mostrando os {LIMITE_LISTA} tópicos livres mais recentes.</p>}
      </main>
    </div>
  )
}
