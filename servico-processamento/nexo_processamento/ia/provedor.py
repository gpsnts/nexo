"""Erros e tradução das respostas HTTP dos provedores de IA (a chave nunca entra nas mensagens)."""
import httpx


class ErroProvedor(RuntimeError):
    """O provedor recusou ou falhou. [retentavel]: vale repetir (5xx, rede, 429 de cota passageira); falso para chave, modelo e pedido inválidos ou sem crédito."""

    def __init__(self, codigo: str, mensagem: str, retentavel: bool):
        super().__init__(mensagem)
        self.codigo = codigo
        self.retentavel = retentavel


def _corpo(resp: httpx.Response) -> dict:
    try:
        d = resp.json()
        return d.get("error", {}) if isinstance(d, dict) and isinstance(d.get("error"), dict) else {}
    except ValueError:
        return {}


# Falta de crédito ou de cota: a OpenAI responde 429 com `type: insufficient_quota` e, hoje, `code: credit_balance_exhausted` (conferido com uma conta sem crédito).
CODIGOS_SEM_CREDITO = {"insufficient_quota", "credit_balance_exhausted", "billing_not_active", "billing_hard_limit_reached"}


def _sem_credito(e: dict) -> bool:
    return e.get("type") == "insufficient_quota" or e.get("code") in CODIGOS_SEM_CREDITO


def _chave_invalida(e: dict) -> bool:
    """O Google responde 400 (não 401) para chave inválida: `API key not valid` na mensagem ou `API_KEY_INVALID` nos detalhes."""
    detalhes = e.get("details") if isinstance(e.get("details"), list) else []
    return "api key not valid" in str(e.get("message", "")).lower() or any(isinstance(d, dict) and d.get("reason") == "API_KEY_INVALID" for d in detalhes)


def erro_da_resposta(resp: httpx.Response, o_que: str) -> ErroProvedor:
    """Traduz uma resposta HTTP que não foi 200 (formato da OpenAI e do Google)."""
    s = resp.status_code
    e = _corpo(resp)
    if _chave_invalida(e):
        return ErroProvedor("chave_recusada", f"{o_que}: o provedor recusou a chave de API (HTTP {s}). {str(e.get('message', ''))[:200]}", False)
    detalhe = str(e.get("message", resp.text))[:200]
    base = f"{o_que} (HTTP {s}): {detalhe}"
    if s == 429 and _sem_credito(e):
        return ErroProvedor("sem_credito", f"{o_que}: a conta do provedor está sem crédito ou sem cota (HTTP 429). {detalhe}", False)
    if s == 429:
        return ErroProvedor("limite_provedor", f"{o_que}: limite de requisições do provedor atingido, comum em plano gratuito (HTTP 429). {detalhe}", True)
    if s in (401, 403):
        return ErroProvedor("chave_recusada", f"{o_que}: o provedor recusou a chave de API ou o acesso (HTTP {s}). {detalhe}", False)
    if s == 404:
        return ErroProvedor("modelo_inexistente", f"{o_que}: modelo inexistente ou sem acesso nesta conta (HTTP 404). {detalhe}", False)
    if s in (408, 409) or s >= 500:
        return ErroProvedor("provedor_indisponivel", base, True)
    return ErroProvedor("pedido_recusado", base, False)


def segundos_de_espera(resp: httpx.Response) -> float | None:
    """Espera sugerida pelo provedor: cabeçalho `Retry-After` (OpenAI) ou `retryDelay` em `error.details` (Google, ex.: "34s")."""
    try:
        return float(resp.headers["retry-after"])
    except (KeyError, ValueError):
        pass
    for d in _corpo(resp).get("details") or []:
        atraso = d.get("retryDelay") if isinstance(d, dict) else None
        if isinstance(atraso, str) and atraso.endswith("s"):
            try:
                return float(atraso[:-1])
            except ValueError:
                return None
    return None
