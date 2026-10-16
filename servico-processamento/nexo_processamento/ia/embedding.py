import hashlib
import httpx

from nexo_processamento.ia.provedor import ErroProvedor, erro_da_resposta
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


MODELOS_COM_DIMENSOES = {"text-embedding-3-small", "text-embedding-3-large"}  # só estes aceitam o parâmetro `dimensions`


class EmbeddingOpenAI:
    """
    Embeddings da OpenAI. A coluna do banco é `vector(1536)`: o `text-embedding-3-large` (3072 por padrão) é reduzido pela própria API com `dimensions`,
    sem mexer no esquema. Trocar de modelo exige reindexar tudo (os vetores de modelos diferentes não se comparam; CLAUDE.md, regra 5).
    """

    dimensao = DIMENSAO

    def __init__(self, chave: str, cliente: httpx.Client | None = None, modelo: str = "text-embedding-3-small"):
        self.nome = modelo
        self._cliente = cliente or httpx.Client(base_url="https://api.openai.com", timeout=60)
        self._chave = chave

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        try:
            r = self._cliente.post(
                "/v1/embeddings",
                headers={"Authorization": f"Bearer {self._chave}"},
                json={"model": self.nome, "input": textos, **({"dimensions": DIMENSAO} if self.nome in MODELOS_COM_DIMENSOES else {})},
            )
        except httpx.HTTPError as e:
            raise ErroProvedor("provedor_indisponivel", f"Modelo de embedding inacessível ({type(e).__name__})", True) from e
        if r.status_code != 200:
            raise erro_da_resposta(r, "Falha no modelo de embedding")
        d = r.json()
        itens = sorted(d["data"], key=lambda i: i["index"])
        return RespostaEmbedding([i["embedding"] for i in itens], d["usage"]["total_tokens"])
