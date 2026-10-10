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
    monkeypatch.setenv("EMBEDDING_PROVEDOR", "openai")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    with pytest.raises(ValueError):
        criar_embedding()
    monkeypatch.setenv("OPENAI_API_KEY", "k")
    assert criar_embedding().nome == "text-embedding-3-small"
    monkeypatch.setenv("EMBEDDING_PROVEDOR", "falso")
    assert criar_embedding().nome == "embedding-falso"


def test_saude():
    r = TestClient(app).get("/saude")
    assert r.status_code == 200 and r.json()["status"] == "UP"
