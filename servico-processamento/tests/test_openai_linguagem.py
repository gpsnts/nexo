import json

import httpx
import pytest
from fastapi.testclient import TestClient

from conftest import H
from nexo_processamento import main
from nexo_processamento.config import criar_linguagem
from nexo_processamento.ia.linguagem import ModeloLinguagemOpenAI
from nexo_processamento.ia.provedor import ErroProvedor


def _modelo(handler, esperas=None, **kw):
    cliente = httpx.Client(base_url="http://x", transport=httpx.MockTransport(handler))
    return ModeloLinguagemOpenAI("chave-secreta", "modelo-x", cliente=cliente, espera=(esperas.append if esperas is not None else (lambda s: None)), **kw)


def _ok(texto="olá", entrada=11, saida=7, fim="stop"):
    return httpx.Response(200, json={"choices": [{"message": {"content": texto}, "finish_reason": fim}], "usage": {"prompt_tokens": entrada, "completion_tokens": saida}})


def test_pedido_e_resposta_no_formato_do_chat_completions():
    visto = {}

    def h(req: httpx.Request):
        visto["auth"] = req.headers["authorization"]; visto["url"] = str(req.url); visto["corpo"] = json.loads(req.read())
        return _ok("resposta", 11, 7)

    r = _modelo(h, max_tokens=999).gerar("faça isto")
    assert (r.texto, r.tokens_entrada, r.tokens_saida) == ("resposta", 11, 7)
    assert visto["auth"] == "Bearer chave-secreta" and visto["url"].endswith("/v1/chat/completions")
    assert visto["corpo"] == {"model": "modelo-x", "messages": [{"role": "user", "content": "faça isto"}], "max_completion_tokens": 999}
    assert "temperature" not in visto["corpo"] and "response_format" not in visto["corpo"]


@pytest.mark.parametrize("status,corpo,codigo,retentavel", [
    (401, {"error": {"message": "Incorrect API key provided: sk-abc"}}, "chave_recusada", False),
    (404, {"error": {"message": "The model `x` does not exist"}}, "modelo_inexistente", False),
    (429, {"error": {"message": "You exceeded your current quota", "code": "insufficient_quota"}}, "sem_credito", False),
    # formato real devolvido por uma conta sem crédito (conferido contra a API):
    (429, {"error": {"message": "You have no credits remaining. Add credits to continue using the API.", "type": "insufficient_quota", "code": "credit_balance_exhausted", "param": None}}, "sem_credito", False),
    (429, {"error": {"message": "Rate limit reached"}}, "limite_provedor", True),
    (500, {"error": {"message": "boom"}}, "provedor_indisponivel", True),
    (400, {"error": {"message": "Unsupported parameter"}}, "pedido_recusado", False),
])
def test_erros_do_provedor_viram_codigo_estavel_e_dizem_se_vale_repetir(status, corpo, codigo, retentavel):
    with pytest.raises(ErroProvedor) as e:
        _modelo(lambda req: httpx.Response(status, json=corpo)).gerar("x")
    assert e.value.codigo == codigo and e.value.retentavel is retentavel
    assert "chave-secreta" not in str(e.value)  # a chave nunca vai para a mensagem


def test_429_com_retry_after_curto_espera_e_tenta_de_novo_uma_vez():
    chamadas, esperas = [], []

    def h(req):
        chamadas.append(1)
        return httpx.Response(429, json={"error": {"message": "slow down"}}, headers={"retry-after": "3"}) if len(chamadas) == 1 else _ok("depois")

    assert _modelo(h, esperas).gerar("x").texto == "depois" and esperas == [3.0] and len(chamadas) == 2


def test_429_com_espera_longa_ou_repetido_nao_fica_esperando():
    esperas = []
    with pytest.raises(ErroProvedor) as e:
        _modelo(lambda req: httpx.Response(429, json={"error": {}}, headers={"retry-after": "600"}), esperas).gerar("x")
    assert e.value.codigo == "limite_provedor" and esperas == []
    n = []
    with pytest.raises(ErroProvedor):
        _modelo(lambda req: (n.append(1), httpx.Response(429, json={"error": {}}, headers={"retry-after": "1"}))[1], esperas).gerar("x")
    assert len(n) == 2  # uma nova tentativa só


def test_resposta_cortada_vazia_ou_inesperada():
    with pytest.raises(ErroProvedor) as e:
        _modelo(lambda req: _ok("{\"parcial\": ", fim="length"), max_tokens=50).gerar("x")
    assert e.value.codigo == "resposta_cortada" and "MODELO_LINGUAGEM_MAX_TOKENS" in str(e.value) and not e.value.retentavel
    with pytest.raises(ErroProvedor) as e:
        _modelo(lambda req: _ok(None)).gerar("x")
    assert e.value.codigo == "resposta_vazia"
    with pytest.raises(ErroProvedor) as e:
        _modelo(lambda req: httpx.Response(200, json={"foo": 1})).gerar("x")
    assert e.value.codigo == "resposta_inesperada" and e.value.retentavel


def test_falha_de_rede_e_retentavel_e_imagens_ficam_para_a_f6c():
    def h(req):
        raise httpx.ConnectTimeout("t")

    with pytest.raises(ErroProvedor) as e:
        _modelo(h).gerar("x")
    assert e.value.codigo == "provedor_indisponivel" and e.value.retentavel
    with pytest.raises(NotImplementedError):
        _modelo(lambda req: _ok()).gerar("x", imagens=[b"png"])


def test_fabrica_exige_chave_e_nome_do_modelo(monkeypatch):
    monkeypatch.setenv("MODELO_LINGUAGEM_PROVEDOR", "openai")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False); monkeypatch.delenv("MODELO_LINGUAGEM_NOME", raising=False)
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        criar_linguagem()
    monkeypatch.setenv("OPENAI_API_KEY", "k")
    with pytest.raises(ValueError, match="MODELO_LINGUAGEM_NOME"):
        criar_linguagem()
    monkeypatch.setenv("MODELO_LINGUAGEM_NOME", "meu-modelo")
    m = criar_linguagem()
    assert m.nome == "meu-modelo" and criar_linguagem() is m  # um cliente por configuração
    monkeypatch.setenv("MODELO_LINGUAGEM_PROVEDOR", "falso")
    assert criar_linguagem().nome == "linguagem-falso"


def test_rotas_devolvem_503_quando_vale_repetir_e_424_com_o_codigo_quando_nao_vale(monkeypatch):
    class Quebrado:
        nome = "q"

        def __init__(self, erro):
            self.erro = erro

        def gerar(self, prompt, imagens=None):
            raise self.erro

    cliente = TestClient(main.app)
    corpo = {"topico": "T", "trechos": []}
    monkeypatch.setattr(main, "criar_linguagem", lambda: Quebrado(ErroProvedor("limite_provedor", "devagar", True)))
    r = cliente.post("/interno/resumo", json=corpo, headers=H)
    assert r.status_code == 503 and r.json()["detail"]["codigo"] == "limite_provedor"
    monkeypatch.setattr(main, "criar_linguagem", lambda: Quebrado(ErroProvedor("sem_credito", "sem crédito", False)))
    r = cliente.post("/interno/resumo", json=corpo, headers=H)
    assert r.status_code == 424 and r.json()["detail"] == {"codigo": "sem_credito", "mensagem": "sem crédito"}


@pytest.mark.real
def test_linguagem_openai_real():
    """Smoke test com a conta real: `OPENAI_API_KEY=... MODELO_LINGUAGEM_NOME=... .venv/bin/pytest -m real -k linguagem`."""
    import os

    m = ModeloLinguagemOpenAI(os.environ["OPENAI_API_KEY"], os.environ["MODELO_LINGUAGEM_NOME"], int(os.getenv("MODELO_LINGUAGEM_MAX_TOKENS", "4096")))
    r = m.gerar('Responda só com este JSON, sem mais nada: {"ok": true}')
    assert '"ok"' in r.texto and r.tokens_entrada > 0 and r.tokens_saida > 0


def test_temperatura_so_vai_no_pedido_quando_configurada():
    corpos = []

    def h(req):
        corpos.append(json.loads(req.read()))
        return _ok()

    _modelo(h).gerar("x")
    _modelo(h, temperatura=0.2).gerar("x")
    assert "temperature" not in corpos[0] and corpos[1]["temperature"] == 0.2
