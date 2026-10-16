import json
import httpx
import pytest
from fastapi.testclient import TestClient

from nexo_processamento.config import criar_embedding
from nexo_processamento.ia.embedding import DIMENSAO, EmbeddingFalso, EmbeddingOpenAI
from nexo_processamento.ia.linguagem import ModeloLinguagemFalso
from nexo_processamento.main import app


def test_embedding_falso_deterministico():
    a = EmbeddingFalso().gerar(["x", "y"])
    assert a == EmbeddingFalso().gerar(["x", "y"])
    assert len(a.vetores[0]) == DIMENSAO and a.vetores[0] != a.vetores[1]


def test_linguagem_falso():
    r = ModeloLinguagemFalso().gerar("olá mundo")
    assert r.texto.startswith("[falso]") and r.tokens_entrada == 2


def _openai(handler):
    return EmbeddingOpenAI("k", httpx.Client(base_url="http://x", transport=httpx.MockTransport(handler)))


def test_openai_payload_e_ordem():
    vistos = {}

    def h(req: httpx.Request):
        vistos["auth"] = req.headers["authorization"]
        vistos["json"] = req.read()
        return httpx.Response(200, json={"data": [{"index": 1, "embedding": [2.0]}, {"index": 0, "embedding": [1.0]}],
                                         "usage": {"total_tokens": 7}})

    r = _openai(h).gerar(["a", "b"])
    assert r.vetores == [[1.0], [2.0]] and r.tokens == 7
    assert vistos["auth"] == "Bearer k" and b"text-embedding-3-small" in vistos["json"]


def test_openai_erro_http_vira_excecao_clara():
    with pytest.raises(RuntimeError, match="HTTP 401"):
        _openai(lambda req: httpx.Response(401, text="no")).gerar(["a"])


def test_fabrica(monkeypatch):
    monkeypatch.setenv("MODELO_EMBEDDING_PROVEDOR", "openai")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    with pytest.raises(ValueError):
        criar_embedding()
    monkeypatch.setenv("OPENAI_API_KEY", "k")
    assert criar_embedding().nome == "text-embedding-3-small"
    monkeypatch.setenv("MODELO_EMBEDDING_PROVEDOR", "falso")
    assert criar_embedding().nome == "embedding-falso"


def test_saude():
    r = TestClient(app).get("/saude")
    assert r.status_code == 200 and r.json()["status"] == "UP"


@pytest.mark.real
def test_embedding_openai_real():
    import os

    r = EmbeddingOpenAI(os.environ["OPENAI_API_KEY"]).gerar(["olá", "mundo"])
    assert len(r.vetores) == 2 and len(r.vetores[0]) == DIMENSAO


def test_modelo_do_embedding_e_configuravel_e_large_pede_1536_dimensoes():
    visto = {}

    def h(req: httpx.Request):
        visto["json"] = json.loads(req.read())
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0]}], "usage": {"total_tokens": 3}})

    for modelo in ("text-embedding-3-small", "text-embedding-3-large"):
        e = EmbeddingOpenAI("k", httpx.Client(base_url="http://x", transport=httpx.MockTransport(h)), modelo=modelo)
        e.gerar(["a"])
        assert e.nome == modelo and visto["json"]["model"] == modelo and visto["json"]["dimensions"] == 1536
    e = EmbeddingOpenAI("k", httpx.Client(base_url="http://x", transport=httpx.MockTransport(h)), modelo="text-embedding-ada-002")
    e.gerar(["a"])
    assert "dimensions" not in visto["json"]  # o ada-002 não aceita o parâmetro


def test_fabrica_escolhe_o_modelo_pelo_ambiente_e_recusa_dimensao_diferente_da_coluna(monkeypatch):
    monkeypatch.setenv("MODELO_EMBEDDING_PROVEDOR", "openai"); monkeypatch.setenv("OPENAI_API_KEY", "k")
    monkeypatch.delenv("MODELO_EMBEDDING_NOME", raising=False); monkeypatch.delenv("MODELO_EMBEDDING_DIMENSOES", raising=False)
    assert criar_embedding().nome == "text-embedding-3-small"
    monkeypatch.setenv("MODELO_EMBEDDING_NOME", "text-embedding-3-large")
    assert criar_embedding().nome == "text-embedding-3-large"
    monkeypatch.setenv("MODELO_EMBEDDING_DIMENSOES", "3072")
    with pytest.raises(ValueError, match="vector\\(1536\\)"):
        criar_embedding()


def test_temperatura_vem_do_ambiente(monkeypatch):
    from nexo_processamento.config import _temperatura

    monkeypatch.delenv("MODELO_LINGUAGEM_TEMPERATURA", raising=False)
    assert _temperatura() is None
    monkeypatch.setenv("MODELO_LINGUAGEM_TEMPERATURA", " ")
    assert _temperatura() is None
    monkeypatch.setenv("MODELO_LINGUAGEM_TEMPERATURA", "0.2")
    assert _temperatura() == 0.2
