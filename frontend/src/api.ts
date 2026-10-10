export class ErroApi extends Error {
  status: number
  codigo?: string
  espera?: number // segundos até poder tentar de novo (limite de tentativas)
  constructor(status: number, message: string, codigo?: string, espera?: number) {
    super(message)
    this.status = status
    this.codigo = codigo
    this.espera = espera
  }
}

export type Sessao = { token: string; nome: string }

// Token de acesso só em memória (ADR 0008). O refresh token vive em cookie HttpOnly, que o JavaScript não lê.
let token: string | null = null
export const definirToken = (t: string | null) => { token = t }

let renovando: Promise<Sessao> | null = null

/** Troca o cookie de refresh por um novo token de acesso. Chamadas simultâneas compartilham uma só renovação (o refresh é de uso único). */
export function renovarSessao(): Promise<Sessao> {
  renovando ??= api<Sessao>('/auth/refresh', { metodo: 'POST' })
    .then((s) => { token = s.token; return s })
    .finally(() => { renovando = null })
  return renovando
}

async function enviar(caminho: string, metodo: string, corpo: unknown) {
  return fetch(`/api${caminho}`, {
    method: metodo,
    headers: {
      ...(corpo ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: corpo ? JSON.stringify(corpo) : undefined,
  })
}

export async function api<T>(caminho: string, opcoes: { metodo?: string; corpo?: unknown } = {}): Promise<T> {
  const metodo = opcoes.metodo ?? (opcoes.corpo ? 'POST' : 'GET')
  let r = await enviar(caminho, metodo, opcoes.corpo)
  // Token de acesso vencido: renova uma vez e repete. As rotas /auth não entram nesse laço.
  if (r.status === 401 && token && !caminho.startsWith('/auth/')) {
    try {
      await renovarSessao()
      r = await enviar(caminho, metodo, opcoes.corpo)
    } catch {
      token = null
      aoPerderSessao()
    }
  }
  const dados = r.status === 204 || r.status === 202 ? null : await r.json().catch(() => null)
  if (!r.ok) throw new ErroApi(r.status, dados?.mensagem ?? 'Não foi possível concluir. Tente novamente.', dados?.codigo, dados?.esperaSegundos)
  return dados as T
}

let aoPerderSessao: () => void = () => {}
export const quandoPerderSessao = (f: () => void) => { aoPerderSessao = f }
