/** Erro de uma chamada à API, com o status HTTP e os campos do corpo de erro do backend. */
export class ErroApi extends Error {
  status: number
  codigo?: string
  espera?: number // segundos até poder tentar de novo (limite de tentativas)
  dados?: Record<string, unknown> // corpo completo do erro (ex.: materialId em material_repetido)
  constructor(status: number, message: string, codigo?: string, espera?: number, dados?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.codigo = codigo
    this.espera = espera
    this.dados = dados
  }
}

/** Sessão devolvida no login e no refresh: token de acesso e nome do usuário. */
export type Sessao = { token: string; nome: string }

// Token de acesso só em memória. O refresh token vive em cookie HttpOnly, que o JavaScript não lê.
let token: string | null = null
/**
 * Guarda o token de acesso em memória (nunca em storage).
 * @param t token, ou nulo para limpar.
 */
export const definirToken = (t: string | null) => { token = t }

let renovando: Promise<Sessao> | null = null

/**
 * Troca o cookie de refresh por um novo token de acesso. Chamadas simultâneas compartilham uma só renovação (o refresh é de uso único).
 * @returns a sessão renovada.
 */
export function renovarSessao(): Promise<Sessao> {
  renovando ??= api<Sessao>('/auth/refresh', { metodo: 'POST' })
    .then((s) => { token = s.token; return s })
    .finally(() => { renovando = null })
  return renovando
}

/**
 * Faz a requisição com o token de acesso.
 * @param caminho caminho da API, sem o prefixo `/api`.
 * @param metodo método HTTP.
 * @param corpo objeto enviado como JSON, se houver.
 * @param formulario corpo multipart, se houver.
 * @returns a resposta HTTP.
 */
async function enviar(caminho: string, metodo: string, corpo: unknown, formulario?: FormData) {
  return fetch(`/api${caminho}`, {
    method: metodo,
    headers: {
      // Com FormData o navegador monta o Content-Type (multipart com o boundary); não definir aqui.
      ...(corpo ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: formulario ?? (corpo ? JSON.stringify(corpo) : undefined),
  })
}

/**
 * Chama a API. `formulario` envia multipart (upload de material) com o mesmo tratamento de 401 e de erros.
 * @param caminho caminho da API, sem o prefixo `/api`.
 * @param opcoes método, corpo JSON ou formulário.
 * @returns o corpo JSON da resposta (nulo em 204).
 * @throws ErroApi se a resposta não for 2xx.
 */
export async function api<T>(caminho: string, opcoes: { metodo?: string; corpo?: unknown; formulario?: FormData } = {}): Promise<T> {
  const metodo = opcoes.metodo ?? (opcoes.corpo || opcoes.formulario ? 'POST' : 'GET')
  let r = await enviar(caminho, metodo, opcoes.corpo, opcoes.formulario)
  // Token de acesso vencido: renova uma vez e repete. As rotas /auth não entram nesse laço.
  if (r.status === 401 && token && !caminho.startsWith('/auth/')) {
    try {
      await renovarSessao()
      r = await enviar(caminho, metodo, opcoes.corpo, opcoes.formulario)
    } catch {
      token = null
      aoPerderSessao()
    }
  }
  const dados = r.status === 204 ? null : await r.json().catch(() => null)
  // 413 do nginx vem em HTML (sem mensagem): é o limite de corpo do envio de material.
  const padrao = r.status === 413 ? 'O arquivo é grande demais.' : 'Não foi possível concluir. Tente novamente.'
  if (!r.ok) throw new ErroApi(r.status, dados?.mensagem ?? padrao, dados?.codigo, dados?.esperaSegundos, dados ?? undefined)
  return dados as T
}

let aoPerderSessao: () => void = () => {}
/**
 * Registra o que fazer quando a sessão não puder ser renovada.
 * @param f função chamada ao perder a sessão.
 */
export const quandoPerderSessao = (f: () => void) => { aoPerderSessao = f }
