import Carregando from './Esqueleto'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import { AVISO_IA, avisarUso, ajustar, criarNaLacuna, dividir, errosDeNome, excluir, faixasDe, igual, lacunas, novaChave, rotuloPaginas, unir, umSo, type Faixa, type Rascunho, type Topico, type TopicoCandidato, type Trecho } from './estudo'
import { focarPrimeiroInvalido } from './validacao'

/**
 * Revisão da proposta: edita tudo no navegador e grava de uma vez (o servidor aplica numa única transação).
 * @param props materialId: material; nomeArquivo: arquivo do material; aoAbrirTopico: abre um tópico já salvo.
 * @returns a seção de revisão dos tópicos.
 */
export default function RevisaoTopicos({ materialId, nomeArquivo, aoAbrirTopico }: { materialId: string; nomeArquivo: string; aoAbrirTopico: (id: string) => void }) {
  const [salvo, setSalvo] = useState<Faixa[] | null>(null)
  const [faixas, setFaixas] = useState<Faixa[]>([])
  const [paginas, setPaginas] = useState<(number | null)[]>([])
  const [falha, setFalha] = useState(false)
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  const [salvando, setSalvando] = useState(false)
  const [pensando, setPensando] = useState(false) // a IA está montando o rascunho
  const [avisoIa, setAvisoIa] = useState('')
  const [errosNome, setErrosNome] = useState<Record<number, string>>({})
  const [candidatos, setCandidatos] = useState<TopicoCandidato[]>([])
  const [alvo, setAlvo] = useState('')
  const [associando, setAssociando] = useState(false)
  const secao = useRef<HTMLElement>(null)

  const carregar = useCallback(
    () => api<Topico[]>(`/materiais/${materialId}/topicos`).then((t) => { const f = faixasDe(t); setSalvo(f); setFaixas(f) }).catch(() => setFalha(true)),
    [materialId],
  )
  useEffect(() => { carregar() }, [carregar])
  useEffect(() => { api<(number | null)[]>(`/materiais/${materialId}/paginas`).then(setPaginas).catch(() => {}) }, [materialId]) // só enfeite: sem páginas a tela mostra só os trechos

  // Tópicos da mesma pasta que aceitam receber os trechos de fora; recarrega depois de cada gravação.
  useEffect(() => { api<TopicoCandidato[]>(`/materiais/${materialId}/topicos-candidatos`).then(setCandidatos).catch(() => setCandidatos([])) }, [materialId, salvo])

  const alterado = salvo !== null && !igual(faixas, salvo)
  // Sair da página com alterações não salvas: o navegador pergunta antes de descartar (Voltar e links internos não passam por aqui).
  useEffect(() => {
    if (!alterado) return
    /**
     * Pede ao navegador que confirme antes de sair com alterações não salvas.
     * @param e evento beforeunload.
     */
    const avisar = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', avisar)
    return () => window.removeEventListener('beforeunload', avisar)
  }, [alterado])

  if (falha) return <div role="alert"><p className="erro">Não foi possível carregar os tópicos.</p><button type="button" onClick={() => { setFalha(false); carregar() }}>Tentar de novo</button></div>
  if (!salvo) return <Carregando />

  // `paginas` tem um item por trecho; se não carregou, o último trecho usado é o melhor que se sabe
  const total = Math.max(paginas.length, faixas[faixas.length - 1]?.fim ?? 0)
  const fora = lacunas(faixas, total)
  /**
   * Acrescenta uma faixa de trechos de fora a um tópico que já existe (o servidor confere sobreposição e congelamento).
   * @param a primeiro trecho da faixa.
   * @param b último trecho da faixa.
   * @returns promessa resolvida quando termina.
   */
  async function associar(a: number, b: number) {
    const nome = candidatos.find((c) => c.id === alvo)?.nome ?? ''
    setAssociando(true); setErro(''); setOk('')
    try {
      await api(`/topicos/${alvo}/fontes`, { corpo: { materialId, inicio: a, fim: b } })
      await carregar(); setAlvo(''); setOk(`${a === b ? `Trecho ${a} acrescentado` : `Trechos ${a} a ${b} acrescentados`} ao tópico "${nome}".`)
    } catch (x) { setErro(x instanceof ErroApi ? x.message : 'Não foi possível acrescentar os trechos. Tente de novo.') } finally { setAssociando(false) }
  }
  /**
   * Troca as faixas em edição e limpa mensagens.
   * @param novo novas faixas.
   */
  const mudar = (novo: Faixa[]) => { setFaixas(novo); setErrosNome({}); setOk(''); setErro('') }

  /**
   * Valida os nomes e grava a divisão numa só chamada.
   * @returns promessa resolvida quando termina.
   */
  const confirmar = async () => {
    const e = errosDeNome(faixas)
    setErrosNome(e)
    if (Object.keys(e).length) return focarPrimeiroInvalido(secao.current) // leva o foco ao primeiro nome inválido (teclado e leitor de tela)
    setSalvando(true); setErro(''); setOk('')
    try {
      const t = await api<Topico[]>(`/materiais/${materialId}/topicos`, { metodo: 'PUT', corpo: { topicos: faixas.map((f) => ({ id: f.id, nome: f.nome.trim(), inicio: f.inicio, fim: f.fim })) } })
      const f = faixasDe(t); setSalvo(f); setFaixas(f); setOk('Tópicos salvos.'); setAvisoIa('')
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível salvar. Tente novamente.')
    } finally {
      setSalvando(false)
    }
  }

  // "Reorganizar com IA": o servidor devolve um RASCUNHO (nada é gravado); ele entra na tela como alteração não salva, para revisar e confirmar.
  /**
   * "Reorganizar com IA": o servidor devolve um RASCUNHO (nada é gravado); ele entra na tela como alteração não salva, para revisar e confirmar.
   * @returns promessa resolvida quando termina.
   */
  const reorganizarComIa = async () => {
    setPensando(true); setErro(''); setOk(''); setAvisoIa('')
    try {
      const r = await api<Rascunho>(`/materiais/${materialId}/topicos/reorganizar`, { metodo: 'POST' })
      if (!r.mudou) return setAvisoIa('A IA manteve a divisão atual: não encontrou o que melhorar.')
      setErrosNome({})
      setFaixas(r.topicos.map((t) => ({ chave: t.id ?? novaChave(), id: t.id, nome: t.nome, inicio: t.inicio, fim: t.fim, bloqueado: t.temConteudo })))
      setAvisoIa(`Proposta da IA carregada como rascunho (de ${salvo.length} para ${r.topicos.length} ${r.topicos.length === 1 ? 'tópico' : 'tópicos'}). Revise, ajuste se quiser e confirme; nada foi salvo ainda.`)
    } catch (x) {
      setErro(x instanceof ErroApi ? x.message : 'Não foi possível consultar a IA. Tente novamente.')
    } finally {
      setPensando(false)
      avisarUso()
    }
  }

  return (
    <>
    {salvo && salvo.length > 0 && (
      <section className="estudar" aria-labelledby="titulo-estudar">
        <h2 id="titulo-estudar">Estudar</h2>
        <p className="sub">Escolha um tópico para gerar resumo e questionário. {alterado ? 'Salve ou desfaça as alterações abaixo para continuar.' : ''}</p>
        <ul className="cartoes">
          {salvo.filter((f) => f.id).map((f) => (
            <li key={f.id}>
              <button type="button" className="cartao-topico" disabled={alterado} onClick={() => aoAbrirTopico(f.id!)} aria-label={`Estudar: ${f.nome}`}>
                <strong>{f.nome}</strong>
                <small className="sub">Trechos {f.inicio} a {f.fim}{f.bloqueado ? ' · já tem conteúdo' : ''}</small>
                <span className="chamada">{f.bloqueado ? 'Abrir o tópico' : 'Gerar resumo e questões'}</span>
              </button>
            </li>
          ))}
        </ul>
      </section>
    )}
    <section ref={secao} aria-labelledby="titulo-revisao" className="editor">
      {/* aberto na primeira revisão; recolhido quando já há conteúdo gerado (a divisão nem pode mais mudar nesses tópicos) */}
      <details open={!salvo.some((f) => f.bloqueado)}>
      <summary><h2 id="titulo-revisao">Tópicos do material</h2></summary>
      <p className="sub">
        O material foi dividido em {faixas.length} {faixas.length === 1 ? 'tópico' : 'tópicos'} ({total} trechos, os pedaços numerados do texto). Renomeie, una, divida, ajuste onde cada tópico termina ou tire do estudo o que não interessa (capa, sumário, referências). Nada é salvo antes de confirmar.
      </p>
      <div className="acoes-linha">
        <button type="button" onClick={reorganizarComIa} disabled={alterado || salvando || pensando} aria-describedby="dica-ia">{pensando ? 'A IA está analisando…' : 'Reorganizar com IA'}</button>
        <button type="button" className="secundario" onClick={() => mudar(umSo(faixas, nomeArquivo.replace(/\.[^.]+$/, ''), total))} disabled={(faixas.length < 2 && fora.length === 0) || salvando || faixas.some((f) => f.bloqueado)}>Tratar como um tópico só</button>
      </div>
      {pensando ? <p id="dica-ia" className="sub" role="status">A IA está analisando o material… isso pode levar alguns segundos.</p> : (
        <p id="dica-ia" className="sub">
          {alterado
            ? (avisoIa ? 'Confirme ou desfaça o rascunho para pedir outra reorganização.' : 'Salve ou desfaça suas alterações antes de usar a IA ou de estudar um tópico: a IA reorganiza a divisão que está salva.')
            : `A IA reavalia a divisão e propõe uma versão melhor. Você revisa antes de salvar; tópicos que já têm resumo ou questões não mudam de limites. ${AVISO_IA.reorganizar}`}
        </p>
      )}
      {avisoIa && <p className="aviso-ia" role="status">{avisoIa}</p>}
      {fora.length > 0 && (
        <div className="aviso-ia" role="status">
          <p>Fora dos tópicos (não entram em resumo nem em questões): {fora.map(([a, b]) => (a === b ? `trecho ${a}` : `trechos ${a} a ${b}`)).join('; ')}.</p>
          <div className="acoes-linha">
            {fora.map(([a, b]) => <button key={a} type="button" className="secundario" onClick={() => mudar(criarNaLacuna(faixas, a, b))}>{a === b ? `Voltar a usar o trecho ${a}` : `Voltar a usar os trechos ${a} a ${b}`}</button>)}
          </div>
          {!alterado && candidatos.length > 0 && (
            <div className="associar">
              <div className="campo">
                <label htmlFor="alvo-fonte">Ou use estes trechos em um tópico que já existe nesta pasta</label>
                <select id="alvo-fonte" value={alvo} onChange={(e) => setAlvo(e.target.value)}>
                  <option value="">Escolha o tópico…</option>
                  {candidatos.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
                </select>
              </div>
              <div className="acoes-linha">
                {fora.map(([a, b]) => <button key={`u${a}`} type="button" className="secundario" disabled={!alvo || associando} onClick={() => associar(a, b)}>{a === b ? `Usar o trecho ${a} nesse tópico` : `Usar os trechos ${a} a ${b} nesse tópico`}</button>)}
              </div>
            </div>
          )}
        </div>
      )}
      <ol className="topicos">
        {faixas.map((f, i) => (
          <li key={f.chave} className="topico">
            <TopicoLinha f={f} i={i} ultimo={i === faixas.length - 1} proximo={faixas[i + 1]} erro={errosNome[i]} materialId={materialId} paginas={paginas} podeEstudar={!!(salvo && igual(salvo, faixas) && f.id)} aoEstudar={() => aoAbrirTopico(f.id!)}
              aoRenomear={(nome) => mudar(faixas.map((x, k) => (k === i ? { ...x, nome } : x)))}
              aoAjustar={(fim) => mudar(ajustar(faixas, i, fim))}
              aoUnir={() => mudar(unir(faixas, i))}
              aoExcluir={() => mudar(excluir(faixas, i))} unicoTopico={faixas.length === 1}
              aoDividir={(apos) => mudar(dividir(faixas, i, apos))} />
          </li>
        ))}
      </ol>
      <div className="barra-acoes">
        <div className="estado-salvar">
          {erro ? <p role="alert" className="erro">{erro}</p> : ok && !alterado ? <p role="status" className="ok">{ok}</p> : alterado ? <p className="sub">Alterações não salvas.</p> : null}
        </div>
        <button type="button" className="secundario" onClick={() => { mudar(salvo); setAvisoIa('') }} disabled={!alterado || salvando}>Desfazer alterações</button>
        <button type="button" onClick={confirmar} disabled={!alterado || salvando}>{salvando ? 'Aguarde…' : 'Confirmar tópicos'}</button>
      </div>
      </details>
    </section>
    </>
  )
}

/**
 * Um tópico da lista: nome, limites, prévia dos trechos e ações de unir, dividir, ajustar e excluir.
 * @param props f: faixa do tópico; i: posição; ultimo: se é o último; proximo: faixa seguinte; erro: erro do nome; materialId: material; paginas: página de cada trecho; podeEstudar: se já pode abrir; aoEstudar: abre o tópico; aoRenomear, aoAjustar, aoUnir, aoDividir, aoExcluir: ações de edição; unicoTopico: se é o único tópico.
 * @returns os controles do tópico.
 */
function TopicoLinha({ f, i, ultimo, proximo, erro, materialId, paginas, podeEstudar, aoEstudar, aoRenomear, aoAjustar, aoUnir, aoDividir, aoExcluir, unicoTopico }: {
  f: Faixa; i: number; ultimo: boolean; proximo?: Faixa; erro?: string; materialId: string; paginas: (number | null)[]; podeEstudar: boolean; aoEstudar: () => void
  aoRenomear: (n: string) => void; aoAjustar: (fim: number) => void; aoUnir: () => void; aoDividir: (apos: number) => void; aoExcluir: () => void; unicoTopico: boolean
}) {
  const [dividindo, setDividindo] = useState(false)
  const [apos, setApos] = useState(String(f.inicio))   // texto: o campo precisa aceitar valores incompletos enquanto se digita
  const [textoFim, setTextoFim] = useState<string | null>(null)
  const [trechos, setTrechos] = useState<Trecho[] | null>(null)
  const [aberto, setAberto] = useState(false)
  const podeDividir = f.fim > f.inicio && !f.bloqueado
  const colado = !!proximo && f.fim + 1 === proximo.inicio
  const fronteiraTravada = f.bloqueado || (colado && !!proximo?.bloqueado)
  const n = `${i + 1}`
  const rotuloPag = rotuloPaginas(paginas, f.inicio, f.fim)
  /**
   * Abre ou fecha a prévia dos trechos, carregando-a na primeira abertura.
   * @param abriu novo estado.
   */
  const ver = (abriu: boolean) => {
    setAberto(abriu)
    if (abriu && !trechos) api<Trecho[]>(`/materiais/${materialId}/trechos?de=${f.inicio}&ate=${f.fim}`).then(setTrechos).catch(() => setTrechos([]))
  }
  return (
    <>
      <div className="campo">
        <label htmlFor={`nome-${f.chave}`}>Nome do tópico {n}</label>
        <input id={`nome-${f.chave}`} value={f.nome} onChange={(e) => aoRenomear(e.target.value)} aria-invalid={erro ? true : undefined} aria-describedby={erro ? `erro-${f.chave}` : undefined} />
        {erro && <small id={`erro-${f.chave}`} role="alert" className="erro">{erro}</small>}
      </div>
      <p className="faixa">
        Trechos {f.inicio} a{' '}
        {!ultimo && proximo
          ? <input type="text" inputMode="numeric" pattern="[0-9]*" autoComplete="off" className="campo-trecho" value={textoFim ?? String(f.fim)} disabled={fronteiraTravada}
              aria-label={`Último trecho do tópico ${n}`} aria-invalid={textoFim !== null && Number(textoFim) !== f.fim ? true : undefined} aria-describedby={`dica-fim-${n}`}
              onFocus={(e) => e.currentTarget.select()}
              onChange={(e) => {
                const digitos = e.target.value.replace(/\D/g, '').slice(0, 6)   // só números; vazio e valores fora do limite ficam como rascunho até sair do campo
                setTextoFim(digitos)
                if (digitos) aoAjustar(Number(digitos))
              }}
              onBlur={() => setTextoFim(null)} />
          : f.fim}
        {rotuloPag && <span className="sub"> · {rotuloPag}</span>}
      </p>
      {textoFim !== null && Number(textoFim) !== f.fim && proximo && <p id={`dica-fim-${n}`} className="erro dica-fim" role="status">O último trecho deste tópico vai de {f.inicio} a {colado ? proximo.fim - 1 : proximo.inicio - 1}.</p>}
      {f.bloqueado && <p className="sub">Este tópico já tem resumo ou questões: só o nome pode mudar.</p>}
      <div className="acoes-linha">
        <button type="button" className="secundario" onClick={aoEstudar} disabled={!podeEstudar} aria-label={`Estudar o tópico ${n}: resumo e questões`}>Estudar</button>
        {!ultimo && <button type="button" className="secundario" onClick={aoUnir} disabled={fronteiraTravada || !colado} aria-label={`Unir com o próximo (tópico ${n})`}>Unir com o próximo</button>}
        <button type="button" className="secundario" onClick={aoExcluir} disabled={f.bloqueado || unicoTopico} aria-label={`Tirar o tópico ${n} do estudo`}>Tirar do estudo</button>
        {podeDividir && !dividindo && <button type="button" className="secundario" onClick={() => { setApos(String(f.inicio)); setDividindo(true) }} aria-label={`Dividir (tópico ${n})`}>Dividir</button>}
      </div>
      {dividindo && (
        <div className="campo">
          <label htmlFor={`apos-${f.chave}`}>Dividir depois do trecho</label>
          <input id={`apos-${f.chave}`} type="text" inputMode="numeric" pattern="[0-9]*" autoComplete="off" value={apos} onFocus={(e) => e.currentTarget.select()} onChange={(e) => setApos(e.target.value.replace(/\D/g, '').slice(0, 6))} aria-describedby={`dica-apos-${f.chave}`} />
          <small id={`dica-apos-${f.chave}`}>Entre {f.inicio} e {f.fim - 1}.</small>
          <div className="acoes-linha">
            <button type="button" onClick={() => { aoDividir(Number(apos)); setDividindo(false) }} disabled={!apos || Number(apos) < f.inicio || Number(apos) >= f.fim}>Dividir aqui</button>
            <button type="button" className="secundario" onClick={() => setDividindo(false)}>Cancelar</button>
          </div>
        </div>
      )}
      <details onToggle={(e) => ver((e.currentTarget as HTMLDetailsElement).open)}>
        <summary>Ver trechos do tópico {n}</summary>
        {!aberto ? null : trechos === null ? <Carregando /> : (
          <ol className="trechos" start={f.inicio}>
            {trechos.map((t) => <li key={t.ordem}>{t.numeroPagina ? <small>pág. {t.numeroPagina} · </small> : null}{t.previa}</li>)}
          </ol>
        )}
      </details>
    </>
  )
}
