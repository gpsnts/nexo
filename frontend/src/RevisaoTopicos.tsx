import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ErroApi } from './api'
import { avisarUso, ajustar, dividir, errosDeNome, faixasDe, igual, novaChave, rotuloPaginas, unir, umSo, type Faixa, type Rascunho, type Topico, type Trecho } from './estudo'
import { focarPrimeiroInvalido } from './validacao'

/** Revisão da proposta (UC02 passo 6): edita tudo no navegador e grava de uma vez (o servidor aplica numa única transação). */
export default function RevisaoTopicos({ materialId, nomeArquivo }: { materialId: string; nomeArquivo: string }) {
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
  const secao = useRef<HTMLElement>(null)

  const carregar = useCallback(
    () => api<Topico[]>(`/materiais/${materialId}/topicos`).then((t) => { const f = faixasDe(t); setSalvo(f); setFaixas(f) }).catch(() => setFalha(true)),
    [materialId],
  )
  useEffect(() => { carregar() }, [carregar])
  useEffect(() => { api<(number | null)[]>(`/materiais/${materialId}/paginas`).then(setPaginas).catch(() => {}) }, [materialId]) // só enfeite: sem páginas a tela mostra só os trechos

  const alterado = salvo !== null && !igual(faixas, salvo)
  // Sair da página com alterações não salvas: o navegador pergunta antes de descartar (Voltar e links internos não passam por aqui).
  useEffect(() => {
    if (!alterado) return
    const avisar = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', avisar)
    return () => window.removeEventListener('beforeunload', avisar)
  }, [alterado])

  if (falha) return <div role="alert"><p className="erro">Não foi possível carregar os tópicos.</p><button type="button" onClick={() => { setFalha(false); carregar() }}>Tentar de novo</button></div>
  if (!salvo) return <p className="sub" role="status">Carregando…</p>

  const total = faixas[faixas.length - 1]?.fim ?? 0
  const mudar = (novo: Faixa[]) => { setFaixas(novo); setErrosNome({}); setOk(''); setErro('') }

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
    <section ref={secao} aria-labelledby="titulo-revisao">
      <h2 id="titulo-revisao">Tópicos do material</h2>
      <p className="sub">
        O material foi dividido em {faixas.length} {faixas.length === 1 ? 'tópico' : 'tópicos'} ({total} trechos, os pedaços numerados do texto). Renomeie, una, divida ou ajuste onde cada tópico termina. Nada é salvo antes de confirmar.
      </p>
      <div className="acoes-linha">
        <button type="button" onClick={reorganizarComIa} disabled={alterado || salvando || pensando} aria-describedby="dica-ia">{pensando ? 'A IA está analisando…' : 'Reorganizar com IA'}</button>
        <button type="button" className="secundario" onClick={() => mudar(umSo(faixas, nomeArquivo.replace(/\.[^.]+$/, '')))} disabled={faixas.length < 2 || salvando || faixas.some((f) => f.bloqueado)}>Tratar como um tópico só</button>
      </div>
      {pensando ? <p id="dica-ia" className="sub" role="status">A IA está analisando o material… isso pode levar alguns segundos.</p> : (
        <p id="dica-ia" className="sub">
          {alterado
            ? (avisoIa ? 'Confirme ou desfaça o rascunho para pedir outra reorganização.' : 'Salve ou desfaça suas alterações antes de usar a IA: ela reorganiza a divisão que está salva.')
            : 'A IA reavalia a divisão e propõe uma versão melhor. Você revisa antes de salvar; tópicos que já têm resumo ou questões não mudam de limites.'}
        </p>
      )}
      {avisoIa && <p className="aviso-ia" role="status">{avisoIa}</p>}
      <ol className="topicos">
        {faixas.map((f, i) => (
          <li key={f.chave} className="topico">
            <TopicoLinha f={f} i={i} ultimo={i === faixas.length - 1} proximo={faixas[i + 1]} erro={errosNome[i]} materialId={materialId} paginas={paginas}
              aoRenomear={(nome) => mudar(faixas.map((x, k) => (k === i ? { ...x, nome } : x)))}
              aoAjustar={(fim) => mudar(ajustar(faixas, i, fim))}
              aoUnir={() => mudar(unir(faixas, i))}
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
    </section>
  )
}

function TopicoLinha({ f, i, ultimo, proximo, erro, materialId, paginas, aoRenomear, aoAjustar, aoUnir, aoDividir }: {
  f: Faixa; i: number; ultimo: boolean; proximo?: Faixa; erro?: string; materialId: string; paginas: (number | null)[]
  aoRenomear: (n: string) => void; aoAjustar: (fim: number) => void; aoUnir: () => void; aoDividir: (apos: number) => void
}) {
  const [dividindo, setDividindo] = useState(false)
  const [apos, setApos] = useState(f.inicio)
  const [trechos, setTrechos] = useState<Trecho[] | null>(null)
  const [aberto, setAberto] = useState(false)
  const podeDividir = f.fim > f.inicio && !f.bloqueado
  const fronteiraTravada = f.bloqueado || !!proximo?.bloqueado
  const n = `${i + 1}`
  const rotuloPag = rotuloPaginas(paginas, f.inicio, f.fim)
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
          ? <input type="number" inputMode="numeric" min={f.inicio} max={proximo.fim - 1} value={f.fim} disabled={fronteiraTravada} aria-label={`Último trecho do tópico ${n}`}
              onChange={(e) => { const v = Number(e.target.value); if (Number.isInteger(v)) aoAjustar(v) }} />
          : f.fim}
        {rotuloPag && <span className="sub"> · {rotuloPag}</span>}
      </p>
      {f.bloqueado && <p className="sub">Este tópico já tem resumo ou questões: só o nome pode mudar.</p>}
      <div className="acoes-linha">
        {!ultimo && <button type="button" className="secundario" onClick={aoUnir} disabled={fronteiraTravada} aria-label={`Unir com o próximo (tópico ${n})`}>Unir com o próximo</button>}
        {podeDividir && !dividindo && <button type="button" className="secundario" onClick={() => { setApos(f.inicio); setDividindo(true) }} aria-label={`Dividir (tópico ${n})`}>Dividir</button>}
      </div>
      {dividindo && (
        <div className="campo">
          <label htmlFor={`apos-${f.chave}`}>Dividir depois do trecho</label>
          <input id={`apos-${f.chave}`} type="number" inputMode="numeric" min={f.inicio} max={f.fim - 1} value={apos} onChange={(e) => setApos(Number(e.target.value))} />
          <div className="acoes-linha">
            <button type="button" onClick={() => { aoDividir(apos); setDividindo(false) }} disabled={!Number.isInteger(apos) || apos < f.inicio || apos >= f.fim}>Dividir aqui</button>
            <button type="button" className="secundario" onClick={() => setDividindo(false)}>Cancelar</button>
          </div>
        </div>
      )}
      <details onToggle={(e) => ver((e.currentTarget as HTMLDetailsElement).open)}>
        <summary>Ver trechos do tópico {n}</summary>
        {!aberto ? null : trechos === null ? <p className="sub" role="status">Carregando…</p> : (
          <ol className="trechos" start={f.inicio}>
            {trechos.map((t) => <li key={t.ordem}>{t.numeroPagina ? <small>pág. {t.numeroPagina} · </small> : null}{t.previa}</li>)}
          </ol>
        )}
      </details>
    </>
  )
}
