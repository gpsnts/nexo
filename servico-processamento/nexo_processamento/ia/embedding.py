import hashlib
import httpx
from dataclasses import dataclass
from typing import Protocol

DIMENSAO = 1536  # deve bater com vector(1536) no V1


@dataclass(frozen=True)
class RespostaEmbedding:
    vetores: list[list[float]]
    tokens: int


class ModeloEmbedding(Protocol):
    nome: str
    dimensao: int

    def gerar(self, textos: list[str]) -> RespostaEmbedding: ...


class EmbeddingFalso:
    nome = "embedding-falso"
    dimensao = DIMENSAO

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        def vetor(t: str) -> list[float]:
            h = hashlib.sha256(t.encode()).digest()
            return [h[i % 32] / 255 for i in range(DIMENSAO)]

        return RespostaEmbedding([vetor(t) for t in textos], sum(len(t.split()) for t in textos))


class EmbeddingOpenAI:
    nome = "text-embedding-3-small"
    dimensao = DIMENSAO

    def __init__(self, chave: str, cliente: httpx.Client | None = None):
        self._cliente = cliente or httpx.Client(base_url="https://api.openai.com", timeout=60)
        self._chave = chave

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        r = self._cliente.post(
            "/v1/embeddings",
            headers={"Authorization": f"Bearer {self._chave}"},
            json={"model": self.nome, "input": textos},
        )
        if r.status_code != 200:
            raise RuntimeError(f"Falha no modelo de embedding (HTTP {r.status_code}): {r.text[:200]}")
        d = r.json()
        itens = sorted(d["data"], key=lambda i: i["index"])
        return RespostaEmbedding([i["embedding"] for i in itens], d["usage"]["total_tokens"])
