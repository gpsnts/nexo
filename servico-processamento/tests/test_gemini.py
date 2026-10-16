import json
import math

import httpx
import pytest

from nexo_processamento.config import criar_embedding, criar_linguagem
from nexo_processamento.ia.gemini import EmbeddingGemini, ModeloLinguagemGemini
from nexo_processamento.ia.provedor import ErroProvedor


def _cliente(handler):
    return httpx.Client(base_url="http://x", transport=httpx.MockTransport(handler))


def _llm(handler, esperas=None, **kw):
    return ModeloLinguagemGemini("chave-secreta", "gemini-teste", cliente=_cliente(handler), espera=(esperas.append if esperas is not None else (lambda s: None)), **kw)


def _ok(texto="olá", entrada=11, saida=7, pensando=0, fim="STOP"):
    uso = {"promptTokenCount": entrada, "candidatesTokenCount": saida}
    if pensando:
        uso["thoughtsTokenCount"] = pensando
    return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": texto}]}, "finishReason": fim}], "usageMetadata": uso})


def _erro(status, mensagem, estado, detalhes=None):
    return httpx.Response(status, json={"error": {"code": status, "message": mensagem, "status": estado, **({"details": detalhes} if detalhes else {})}})


def test_linguagem_pedido_no_formato_do_generate_content_e_tokens_de_raciocinio_somam_na_saida():
    visto = {}

    def h(req: httpx.Request):
        visto["chave"] = req.headers["x-goog-api-key"]; visto["url"] = str(req.url); visto["corpo"] = json.loads(req.read())
        return _ok("resposta", 11, 7, pensando=30)

    r = _llm(h, max_tokens=999).gerar("faça isto")
    assert (r.texto, r.tokens_entrada, r.tokens_saida) == ("resposta", 11, 37)
    assert visto["chave"] == "chave-secreta" and visto["url"].endswith("/v1beta/models/gemini-teste:generateContent") and "key=" not in visto["url"]
    assert visto["corpo"] == {"contents": [{"parts": [{"text": "faça isto"}]}], "generationConfig": {"maxOutputTokens": 999}}


@pytest.mark.parametrize("resposta,codigo,retentavel", [
    (_erro(400, "API key not valid. Please pass a valid API key.", "INVALID_ARGUMENT", [{"reason": "API_KEY_INVALID"}]), "chave_recusada", False),
    (_erro(403, "Permission denied", "PERMISSION_DENIED"), "chave_recusada", False),
    (_erro(404, "models/xyz is not found for API version v1beta", "NOT_FOUND"), "modelo_inexistente", False),
    (_erro(429, "You exceeded your current quota", "RESOURCE_EXHAUSTED"), "limite_provedor", True),
    (_erro(503, "The model is overloaded", "UNAVAILABLE"), "provedor_indisponivel", True),
    (_erro(400, "Unable to process input image", "INVALID_ARGUMENT"), "pedido_recusado", False),
])
def test_linguagem_erros_do_google_viram_codigos_estaveis(resposta, codigo, retentavel):
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: resposta).gerar("x")
    assert e.value.codigo == codigo and e.value.retentavel is retentavel
    assert "chave-secreta" not in str(e.value)


def test_429_com_retry_delay_curto_espera_e_tenta_de_novo_uma_vez_e_longo_nao_espera():
    n, esperas = [], []

    def h(req):
        n.append(1)
        return _erro(429, "quota", "RESOURCE_EXHAUSTED", [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "12s"}]) if len(n) == 1 else _ok("depois")

    assert _llm(h, esperas).gerar("x").texto == "depois" and esperas == [12.0] and len(n) == 2
    esperas.clear()
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: _erro(429, "quota", "RESOURCE_EXHAUSTED", [{"retryDelay": "300s"}]), esperas).gerar("x")
    assert e.value.codigo == "limite_provedor" and esperas == []


def test_linguagem_cortada_bloqueada_vazia_e_rede():
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: _ok("parcial", fim="MAX_TOKENS"), max_tokens=50).gerar("x")
    assert e.value.codigo == "resposta_cortada" and "MODELO_LINGUAGEM_MAX_TOKENS" in str(e.value) and not e.value.retentavel
    bloqueio = httpx.Response(200, json={"promptFeedback": {"blockReason": "SAFETY"}, "usageMetadata": {"promptTokenCount": 3}})
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: bloqueio).gerar("x")
    assert e.value.codigo == "resposta_vazia" and "SAFETY" in str(e.value)
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: _ok("  ", fim="OTHER")).gerar("x")
    assert e.value.codigo == "resposta_vazia"
    with pytest.raises(ErroProvedor) as e:
        _llm(lambda req: httpx.Response(200, json={"foo": 1})).gerar("x")
    assert e.value.codigo == "resposta_inesperada" and e.value.retentavel

    def queda(req):
        raise httpx.ConnectTimeout("t")

    with pytest.raises(ErroProvedor) as e:
        _llm(queda).gerar("x")
    assert e.value.codigo == "provedor_indisponivel" and e.value.retentavel
    with pytest.raises(NotImplementedError):
        _llm(lambda req: _ok()).gerar("x", imagens=[b"png"])


def _emb(handler, modelo="gemini-embedding-2"):
    return EmbeddingGemini("chave-secreta", modelo, _cliente(handler), espera=lambda s: None)


def _vetores(n, valor=3.0):
    return httpx.Response(200, json={"embeddings": [{"values": [valor] * 1536} for _ in range(n)], "usageMetadata": {"promptTokenCount": 9}})


def test_embedding_lote_com_dimensao_1536_normaliza_e_conta_tokens():
    visto = {}

    def h(req: httpx.Request):
        visto["url"] = str(req.url); visto["chave"] = req.headers["x-goog-api-key"]; visto["corpo"] = json.loads(req.read())
        return _vetores(2)

    r = _emb(h).gerar(["a", "b"])
    assert visto["url"].endswith("/v1beta/models/gemini-embedding-2:batchEmbedContents") and visto["chave"] == "chave-secreta"
    itens = visto["corpo"]["requests"]
    assert [i["content"]["parts"][0]["text"] for i in itens] == ["a", "b"]
    assert all(i["model"] == "models/gemini-embedding-2" and i["embedContentConfig"] == {"outputDimensionality": 1536} for i in itens)
    assert r.tokens == 9 and len(r.vetores) == 2 and len(r.vetores[0]) == 1536
    assert math.isclose(sum(x * x for x in r.vetores[0]), 1.0, rel_tol=1e-9), "vetor normalizado (o 001 não normaliza fora de 3072)"


def test_embedding_001_leva_task_type_e_o_2_nao():
    corpos = {}

    def h(req):
        corpos[len(corpos)] = json.loads(req.read())
        return _vetores(1)

    _emb(h, "gemini-embedding-001").gerar(["a"])
    _emb(h, "gemini-embedding-2-preview").gerar(["a"])
    assert corpos[0]["requests"][0]["embedContentConfig"]["taskType"] == "SEMANTIC_SIMILARITY"
    assert "taskType" not in corpos[1]["requests"][0]["embedContentConfig"]


def test_embedding_resposta_fora_do_esperado_e_erros():
    for resposta in (_vetores(1), httpx.Response(200, json={"embeddings": [{"values": [1.0, 2.0]}, {"values": [1.0, 2.0]}], "usageMetadata": {"promptTokenCount": 1}}),
                     httpx.Response(200, json={"foo": 1})):
        with pytest.raises(ErroProvedor) as e:
            _emb(lambda req: resposta).gerar(["a", "b"])
        assert e.value.codigo == "resposta_inesperada" and e.value.retentavel
    with pytest.raises(ErroProvedor) as e:
        _emb(lambda req: _erro(429, "quota", "RESOURCE_EXHAUSTED")).gerar(["a"])
    assert e.value.codigo == "limite_provedor"


def test_fabricas_do_gemini_exigem_chave_e_nome_e_recusam_dimensao_diferente(monkeypatch):
    for v in ("GEMINI_API_KEY", "MODELO_LINGUAGEM_NOME", "MODELO_EMBEDDING_NOME", "MODELO_EMBEDDING_DIMENSOES", "GEMINI_BASE_URL"):
        monkeypatch.delenv(v, raising=False)
    monkeypatch.setenv("MODELO_LINGUAGEM_PROVEDOR", "gemini"); monkeypatch.setenv("MODELO_EMBEDDING_PROVEDOR", "gemini")
    with pytest.raises(ValueError, match="GEMINI_API_KEY"):
        criar_linguagem()
    with pytest.raises(ValueError, match="GEMINI_API_KEY"):
        criar_embedding()
    monkeypatch.setenv("GEMINI_API_KEY", "k")
    with pytest.raises(ValueError, match="MODELO_LINGUAGEM_NOME"):
        criar_linguagem()
    monkeypatch.setenv("MODELO_LINGUAGEM_NOME", "gemini-teste")
    assert criar_linguagem().nome == "gemini-teste"
    assert criar_embedding().nome == "gemini-embedding-2"  # padrão do embedding (existe sem "preview" na lista de modelos da chave)
    monkeypatch.setenv("MODELO_EMBEDDING_NOME", "gemini-embedding-001")
    assert criar_embedding().nome == "gemini-embedding-001"
    monkeypatch.setenv("MODELO_EMBEDDING_DIMENSOES", "3072")
    with pytest.raises(ValueError, match="vector\\(1536\\)"):
        criar_embedding()


@pytest.mark.real
def test_linguagem_gemini_real():
    """Smoke test com a chave real: `GEMINI_API_KEY=... MODELO_LINGUAGEM_NOME=... .venv/bin/pytest -m real -k gemini -q`."""
    import os

    r = ModeloLinguagemGemini(os.environ["GEMINI_API_KEY"], os.environ["MODELO_LINGUAGEM_NOME"], int(os.getenv("MODELO_LINGUAGEM_MAX_TOKENS", "4096"))).gerar('Responda só com este JSON, sem mais nada: {"ok": true}')
    assert '"ok"' in r.texto and r.tokens_entrada > 0


@pytest.mark.real
def test_embedding_gemini_real():
    import os

    r = EmbeddingGemini(os.environ["GEMINI_API_KEY"], os.getenv("MODELO_EMBEDDING_NOME") or "gemini-embedding-2").gerar(["olá", "mundo"])
    assert len(r.vetores) == 2 and len(r.vetores[0]) == 1536 and r.tokens > 0


def test_gemini_temperatura_vai_na_generation_config_so_quando_configurada():
    corpos = []

    def h(req):
        corpos.append(json.loads(req.read()))
        return _ok()

    _llm(h).gerar("x")
    _llm(h, temperatura=0.3).gerar("x")
    assert "temperature" not in corpos[0]["generationConfig"] and corpos[1]["generationConfig"]["temperature"] == 0.3
