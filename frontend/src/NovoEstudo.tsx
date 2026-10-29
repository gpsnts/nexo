import { useDevolverFoco } from './foco'
import { useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import Cabecalho from './Cabecalho'
import Campo from './Campo'
import { IconeUpload } from './Icones'
import { AVISO_IA, avisarUso, erroArquivo, formatarTamanho, NOME_MAXIMO, ORIENTACAO_MAXIMA, TAMANHO_MAXIMO_MB, type Assunto } from './estudo'
import { useTitulo } from './titulo'
import { erroObrigatorio } from './validacao'

/**
 * Tela "Novo estudo": enviar um material ou informar um tópico livre, com a escolha da pasta.
 * @param props aoIrInicio, aoIrPerfil, aoSair: navegação do cabeçalho; aoAbrirMaterial: abre o material enviado; aoAbrirTopico: abre o tópico criado.
 * @returns a tela.
 */
export default function NovoEstudo({ aoIrInicio, aoIrPerfil, aoSair, aoAbrirMaterial, aoAbrirTopico }: {
  aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void; aoAbrirMaterial: (id: string) => void; aoAbrirTopico: (id: string) => void
}) {
  useTitulo('Novo estudo', true)
  const [assuntos, setAssuntos] = useState<Assunto[]>([])
  const [assunto, setAssunto] = useState('')
  useEffect(() => { api<Assunto[]>('/assuntos').then(setAssuntos).catch(() => {}) }, [])
  /**
   * Seletor de pasta.
   * @param id id do campo, único na tela.
   * @returns o campo com as pastas do usuário.
   */
  const seletor = (id: string) => (
    <div className="campo">
      <label htmlFor={id}>Pasta (opcional)</label>
      <select id={id} value={assunto} onChange={(e) => setAssunto(e.target.value)}>
        <option value="">Geral (sem pasta)</option>
        {assuntos.filter((a) => !a.padrao).map((a) => <option key={a.id} value={a.id}>{a.nome}</option>)}
      </select>
      <small>Crie pastas na tela inicial. Dá para mudar o estudo de pasta depois.</small>
    </div>
  )
  return (
    <div className="pagina">
      <Cabecalho atual="estudo" aoIrInicio={aoIrInicio} aoIrPerfil={aoIrPerfil} aoSair={aoSair} />
      <main id="conteudo" tabIndex={-1}>
        <h1 tabIndex={-1}>Novo estudo</h1>
        <div className="grade">
          <Envio seletor={seletor('assunto-envio')} assunto={assunto} aoEnviado={aoAbrirMaterial} />
          <TopicoLivre seletor={seletor('assunto-livre')} assunto={assunto} aoAbrirTopico={aoAbrirTopico} />
        </div>
      </main>
    </div>
  )
}

/**
 * Formulário de envio de material (arquivo escolhido ou arrastado).
 * @param props seletor: campo de pasta; assunto: pasta escolhida; aoEnviado: recebe o id do material enviado.
 * @returns a seção de envio.
 */
function Envio({ seletor, assunto, aoEnviado }: { seletor: React.ReactNode; assunto: string; aoEnviado: (id: string) => void }) {
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const botao = useDevolverFoco(enviando)
  const [existente, setExistente] = useState<string | null>(null)
  const campo = useRef<HTMLInputElement>(null)
  const [sobre, setSobre] = useState(false) // arquivo sendo arrastado por cima da área

  /**
   * Valida o arquivo e o envia.
   * @param e evento de envio do formulário.
   * @returns promessa resolvida quando termina.
   */
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
      <p className="sub privacidade">{AVISO_IA.material}</p>
      <form noValidate onSubmit={enviar}>
        <div className="campo">
          <label htmlFor="arquivo">Arquivo</label>
          {/* O controle nativo mostra "Choose File" no idioma do navegador: o botão é um rótulo próprio e o <input> fica só para leitores de tela e teclado. */}
          <div className={`zona${sobre ? ' sobre' : ''}`} onDragOver={(e) => { e.preventDefault(); setSobre(true) }} onDragLeave={() => setSobre(false)}
            onDrop={(e) => { e.preventDefault(); setSobre(false); const solto = e.dataTransfer.files?.[0]; if (solto) { setArquivo(solto); setErro('') } }}>
            <input id="arquivo" ref={campo} type="file" className="sr-only" accept=".pdf,.docx,.txt" aria-invalid={erro ? true : undefined} aria-describedby={erro ? 'erro-arquivo' : undefined}
              onChange={(e) => { setArquivo(e.target.files?.[0] ?? null); setErro('') }} />
            <IconeUpload tam={30} />
            <strong>Arraste o arquivo para cá</strong>
            <label htmlFor="arquivo" className="botao-arquivo">Escolher arquivo</label>
            <span className={arquivo ? 'nome-arquivo' : 'sub'}>{arquivo ? `${arquivo.name} · ${formatarTamanho(arquivo.size)}` : 'Nenhum arquivo escolhido'}</span>
          </div>
          {erro && <p id="erro-arquivo" role="alert" className="erro">{erro}</p>}
        </div>
        {seletor}
        {existente && <p><a href={`/materiais/${existente}`} onClick={(e) => { e.preventDefault(); aoEnviado(existente) }}>Abrir o material já enviado</a></p>}
        {enviando && <p className="sub" role="status">Enviando… arquivos grandes podem levar alguns segundos. Não feche a página.</p>}
        <button ref={botao} type="submit" disabled={enviando}>{enviando ? 'Enviando o arquivo…' : 'Enviar material'}</button>
      </form>
    </section>
  )
}

/**
 * Formulário de tópico livre.
 * @param props seletor: campo de pasta; assunto: pasta escolhida; aoAbrirTopico: recebe o id do tópico criado.
 * @returns a seção do tópico livre.
 */
function TopicoLivre({ seletor, assunto, aoAbrirTopico }: { seletor: React.ReactNode; assunto: string; aoAbrirTopico: (id: string) => void }) {
  const [nome, setNome] = useState('')
  const [orientacao, setOrientacao] = useState('')
  const [erro, setErro] = useState('')
  const [erroServidor, setErroServidor] = useState('')
  const [salvando, setSalvando] = useState(false)
  const botao = useDevolverFoco(salvando)

  /**
   * Valida o nome e cria o tópico.
   * @param e evento de envio do formulário.
   * @returns promessa resolvida quando termina.
   */
  const criar = async (e: React.FormEvent) => {
    e.preventDefault()
    const problema = erroObrigatorio(nome, 'Informe o nome do tópico.') || (nome.trim().length > NOME_MAXIMO ? `O nome pode ter até ${NOME_MAXIMO} caracteres.` : '')
    setErro(problema); setErroServidor('')
    if (problema) return
    setSalvando(true)
    try {
      const criado = await api<{ id: string }>('/topicos', { corpo: { nome: nome.trim(), assuntoId: assunto || null, orientacao: orientacao.trim() || null } })
      aoAbrirTopico(criado.id)  // o próximo passo é gerar resumo e questões: leva direto ao tópico
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
      <p className="sub privacidade">{AVISO_IA.topicoLivre}</p>
      <form noValidate onSubmit={criar}>
        <Campo rotulo="Tópico" valor={nome} mudar={setNome} erro={erro} dica="Ex.: Movimento Retilíneo Uniforme" />
        <div className="campo">
          <label htmlFor="orientacao-livre">Orientações (opcional)</label>
          <textarea id="orientacao-livre" rows={3} maxLength={ORIENTACAO_MAXIMA} value={orientacao} onChange={(e) => setOrientacao(e.target.value)}
            placeholder="Ex.: inclua a situação da Espanha e de Portugal; questões no estilo da banca FCC ou do vestibular da UFRGS" />
          <small>Pontos que você quer ver, ou a linha da prova. Vale para o resumo, as questões e o chat, e dá para mudar depois. {orientacao.length}/{ORIENTACAO_MAXIMA}</small>
        </div>
        {seletor}
        {erroServidor && <p role="alert" className="erro">{erroServidor}</p>}
        <button ref={botao} type="submit" disabled={salvando}>{salvando ? 'Aguarde…' : 'Registrar tópico'}</button>
      </form>
    </section>
  )
}
