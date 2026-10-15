import { useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Campo from './Campo'
import { avisarUso, erroArquivo, formatarTamanho, NOME_MAXIMO, TAMANHO_MAXIMO_MB, type Assunto } from './estudo'
import { useTitulo } from './titulo'
import { erroObrigatorio } from './validacao'

export default function NovoEstudo({ aoIrInicio, aoIrPerfil, aoSair, aoAbrirMaterial }: {
  aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirMaterial: (id: string) => void
}) {
  useTitulo('Novo estudo', true)
  const [assuntos, setAssuntos] = useState<Assunto[]>([])
  const [assunto, setAssunto] = useState('')
  useEffect(() => { api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => {}) }, [])
  const seletor = (id: string) => (
    <div className="campo">
      <label htmlFor={id}>Assunto (opcional)</label>
      <select id={id} value={assunto} onChange={(e) => setAssunto(e.target.value)}>
        <option value="">Geral (padrão)</option>
        {assuntos.filter((a) => !a.padrao).map((a) => <option key={a.id} value={a.id}>{a.nome}</option>)}
      </select>
    </div>
  )
  return (
    <div className="pagina">
      <Cabecalho atual="estudo" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Novo estudo</h1>
        <div className="grade">
          <Envio seletor={seletor('assunto-envio')} assunto={assunto} aoEnviado={aoAbrirMaterial} />
          <TopicoLivre seletor={seletor('assunto-livre')} assunto={assunto} />
        </div>
      </main>
    </div>
  )
}

function Envio({ seletor, assunto, aoEnviado }: { seletor: React.ReactNode; assunto: string; aoEnviado: (id: string) => void }) {
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [existente, setExistente] = useState<string | null>(null)
  const campo = useRef<HTMLInputElement>(null)

  const enviar = async (e: React.FormEvent) => {
    e.preventDefault()
    const problema = erroArquivo(arquivo)
    setErro(problema); setExistente(null)
    if (problema) return campo.current?.focus()
    setEnviando(true)
    try {
      const f = new FormData()
      f.append('arquivo', arquivo!)
      if (assunto) f.append('assuntoId', assunto)
      const r = await api<{ materialId: string }>('/materiais', { formulario: f })
      avisarUso()
      aoEnviado(r.materialId)
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível enviar. Tente novamente.')
      if (x instanceof ErroApi && x.codigo === 'material_repetido') setExistente((x.dados?.materialId as string | undefined) ?? null)
      setEnviando(false)
      avisarUso() // recusa por limite (429) também muda o que a barra mostra
    }
  }

  return (
    <section className="secao" aria-labelledby="titulo-envio">
      <h2 id="titulo-envio">Enviar material</h2>
      <p className="sub">PDF, DOCX ou TXT, até {TAMANHO_MAXIMO_MB} MB e 50 páginas.</p>
      <form noValidate onSubmit={enviar}>
        <div className="campo">
          <label htmlFor="arquivo">Arquivo</label>
          <input id="arquivo" ref={campo} type="file" accept=".pdf,.docx,.txt" aria-invalid={erro ? true : undefined} aria-describedby={erro ? 'erro-arquivo' : undefined}
            onChange={(e) => { setArquivo(e.target.files?.[0] ?? null); setErro('') }} />
          {arquivo && !erro && <small>{arquivo.name} · {formatarTamanho(arquivo.size)}</small>}
          {erro && <p id="erro-arquivo" role="alert" className="erro">{erro}</p>}
        </div>
        {seletor}
        {existente && <p><a href={`/materiais/${existente}`} onClick={(e) => { e.preventDefault(); aoEnviado(existente) }}>Abrir o material já enviado</a></p>}
        {enviando && <p className="sub" role="status">Enviando… arquivos grandes podem levar alguns segundos. Não feche a página.</p>}
        <button type="submit" disabled={enviando}>{enviando ? 'Enviando o arquivo…' : 'Enviar material'}</button>
      </form>
    </section>
  )
}

function TopicoLivre({ seletor, assunto }: { seletor: React.ReactNode; assunto: string }) {
  const [nome, setNome] = useState('')
  const [erro, setErro] = useState('')
  const [erroServidor, setErroServidor] = useState('')
  const [salvando, setSalvando] = useState(false)
  const [feito, setFeito] = useState('')

  const criar = async (e: React.FormEvent) => {
    e.preventDefault()
    const problema = erroObrigatorio(nome, 'Informe o nome do tópico.') || (nome.trim().length > NOME_MAXIMO ? `O nome pode ter até ${NOME_MAXIMO} caracteres.` : '')
    setErro(problema); setErroServidor(''); setFeito('')
    if (problema) return
    setSalvando(true)
    try {
      await api('/topicos', { corpo: { nome: nome.trim(), assuntoId: assunto || null } })
      setFeito(`Tópico "${nome.trim()}" registrado.`); setNome('')
    } catch (x) {
      setErroServidor(x instanceof ErroApi ? x.message : 'Não foi possível registrar. Tente novamente.')
    } finally {
      setSalvando(false)
    }
  }

  return (
    <section className="secao" aria-labelledby="titulo-livre">
      <h2 id="titulo-livre">Informar tópico</h2>
      <p className="sub">Sem material em mãos? Digite o tema que quer estudar.</p>
      <form noValidate onSubmit={criar}>
        <Campo rotulo="Tópico" valor={nome} mudar={setNome} erro={erro} dica="Ex.: Movimento Retilíneo Uniforme" />
        {seletor}
        {erroServidor && <p role="alert" className="erro">{erroServidor}</p>}
        {feito && <p role="status" className="ok">{feito}</p>}
        <button type="submit" disabled={salvando}>{salvando ? 'Aguarde…' : 'Registrar tópico'}</button>
      </form>
    </section>
  )
}
