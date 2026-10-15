import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import { avisarUso, config, ETAPAS, motivoDe, type Andamento as Dados } from './estudo'

/** Barra de porcentagem da indexação (regra 14): consulta o andamento a cada poucos segundos e para quando termina ou falha. */
export default function Andamento({ materialId, aoTerminar, aoReenviar }: { materialId: string; aoTerminar: () => void; aoReenviar: () => void }) {
  const [dados, setDados] = useState<Dados | null>(null)
  const [erro, setErro] = useState(false)
  const [tentativa, setTentativa] = useState(0)

  useEffect(() => {
    let vivo = true
    let timer: ReturnType<typeof setTimeout>
    let primeiro = true
    const consultar = async () => {
      try {
        const d = await api<Dados>(`/materiais/${materialId}/andamento`)
        if (!vivo) return
        setErro(false); setDados(d)
        if (d.status !== 'processando' || primeiro) avisarUso() // a indexação cria os registros depois do envio: primeira resposta e fim
        primeiro = false
        if (d.status === 'pronto') return aoTerminar()
        if (d.status !== 'falhou') timer = setTimeout(consultar, config.intervaloAndamentoMs)
      } catch {
        if (vivo) setErro(true)
      }
    }
    consultar()
    return () => { vivo = false; clearTimeout(timer) }
  }, [materialId, aoTerminar, tentativa])

  const tentarDeNovo = useCallback(() => { setErro(false); setTentativa((n) => n + 1) }, [])

  if (erro)
    return (
      <div role="alert">
        <p className="erro">Não foi possível consultar o andamento.</p>
        <button type="button" onClick={tentarDeNovo}>Tentar de novo</button>
      </div>
    )
  if (!dados) return <p className="sub" role="status">Carregando…</p>
  if (dados.status === 'falhou')
    return (
      <div role="alert">
        <p className="erro">{motivoDe(dados.motivoFalha)}</p>
        <button type="button" onClick={aoReenviar}>Enviar novamente</button>
      </div>
    )
  return (
    <div>
      <progress className="barra" max={100} value={dados.percentual} aria-label="Andamento do processamento" aria-valuetext={`${dados.percentual}%`} />
      <p className="sub" role="status">{dados.percentual}% · {ETAPAS[dados.etapa] ?? dados.etapa}</p>
      <p className="sub">Você pode sair desta página: o processamento continua e o material aparece em "Seus materiais" quando ficar pronto.</p>
    </div>
  )
}
