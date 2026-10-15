import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { motivoDe, type Assunto, type Material } from './estudo'
import { useTitulo } from './titulo'
import { dataCompleta } from './tempo'

const STATUS: Record<Material['status'], string> = { pendente: 'Na fila', processando: 'Processando', pronto: 'Pronto', falhou: 'Falhou' }

export default function Inicio({ nome, aoSair, aoAbrirPerfil, aoNovoEstudo, aoAbrirMaterial }: {
  nome: string; aoSair: () => void; aoAbrirPerfil: () => void; aoNovoEstudo: () => void; aoAbrirMaterial: (id: string) => void
}) {
  const [assuntos, setAssuntos] = useState<Assunto[] | null>(null)
  const [materiais, setMateriais] = useState<Material[] | null>(null)
  const [falhaMateriais, setFalhaMateriais] = useState(false)
  useTitulo('Início', true)
  const buscarMateriais = () => api<Material[]>('/materiais').then(setMateriais).catch(() => setFalhaMateriais(true))
  const tentarMateriais = () => { setFalhaMateriais(false); buscarMateriais() }
  useEffect(() => { api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => setAssuntos([])) }, [])
  useEffect(() => { buscarMateriais() }, [])
  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={() => {}} aoIrPerfil={aoAbrirPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Olá, {nome}</h1>
        <h2>Seus assuntos</h2>
        {assuntos === null ? <p className="sub" role="status">Carregando…</p> : <ul className="lista">{assuntos.map((a) => <li key={a.id}>{a.nome}</li>)}</ul>}
        <div className="titulo-linha">
          <h2>Seus materiais</h2>
          <button type="button" onClick={aoNovoEstudo}>Novo estudo</button>
        </div>
        {falhaMateriais ? (
          <div role="alert"><p className="erro">Não foi possível carregar seus materiais.</p><button type="button" onClick={tentarMateriais}>Tentar de novo</button></div>
        ) : materiais === null ? <p className="sub" role="status">Carregando…</p> : materiais.length === 0 ? (
          <section className="vazio"><p className="sub">Você ainda não enviou nenhum material. Envie um PDF, DOCX ou TXT em "Novo estudo" para gerar resumos e questões fiéis ao conteúdo.</p></section>
        ) : (
          <ul className="materiais">
            {materiais.map((m) => (
              <li key={m.id}>
                <div className="material-linha">
                  <button type="button" className="link nome" onClick={() => aoAbrirMaterial(m.id)}>{m.nomeArquivo}</button>
                  <span className={`selo-status ${m.status}`}>{STATUS[m.status]}</span>
                </div>
                <small className="sub">{dataCompleta(m.criadoEm)}{m.paginas ? ` · ${m.paginas} páginas` : ''}</small>
                {m.status === 'falhou' && <p className="motivo">{motivoDe(m.motivoFalha)}</p>}
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}
