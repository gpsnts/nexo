import { useEffect, useState } from 'react'
import { api } from './api'
import Cabecalho from './Cabecalho'
import { useTitulo } from './titulo'

type Assunto = { id: string; nome: string; padrao: boolean }

export default function Inicio({ nome, aoSair, aoAbrirPerfil }: { nome: string; aoSair: () => void; aoAbrirPerfil: () => void }) {
  const [assuntos, setAssuntos] = useState<Assunto[] | null>(null)
  useTitulo('Início', true)
  useEffect(() => { api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => setAssuntos([])) }, [])
  return (
    <div className="pagina">
      <Cabecalho atual="inicio" aoIrInicio={() => {}} aoIrPerfil={aoAbrirPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Olá, {nome}</h1>
        <h2>Seus assuntos</h2>
        {assuntos === null ? <p className="sub" role="status">Carregando…</p> : <ul className="lista">{assuntos.map((a) => <li key={a.id}>{a.nome}</li>)}</ul>}
        <section className="vazio">
          <h2>Seus materiais</h2>
          <p className="sub">Você ainda não enviou nenhum material. Em breve você poderá enviar PDF, DOCX ou TXT para gerar resumos e questões fiéis ao conteúdo.</p>
        </section>
      </main>
    </div>
  )
}
