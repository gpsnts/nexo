"""Interface de modelo de embedding e seus adaptadores (falso e OpenAI). A dimensão é fixa em 1536, a da coluna `vector` do banco."""
import hashlib
import httpx

from nexo_processamento.ia.provedor import ErroProvedor, erro_da_resposta
from dataclasses import dataclass
from typing import Protocol

DIMENSAO = 1536  # deve bater com vector(1536) no V1


@dataclass(frozen=True)
class RespostaEmbedding:
    """Resultado de uma chamada de embedding.

    Attributes:
        vetores: um vetor por texto enviado, na mesma ordem.
        tokens: tokens de entrada consumidos (base do custo).
    """

    vetores: list[list[float]]
    tokens: int


class ModeloEmbedding(Protocol):
    """Contrato de um modelo de embedding, separado do modelo de linguagem.

    Attributes:
        nome: identificador do modelo, gravado em `modelo_embedding`.
        dimensao: tamanho dos vetores devolvidos.
    """

    nome: str
    dimensao: int

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        """Gera os embeddings de uma lista de textos.

        Args:
            textos: textos a vetorizar.

        Returns:
            RespostaEmbedding com um vetor por texto, na mesma ordem, e os tokens gastos.
        """
        ...


class EmbeddingFalso:
    """Embedding determinístico (hash SHA-256 repetido) para desenvolvimento e testes, sem provedor real."""
    nome = "embedding-falso"
    dimensao = DIMENSAO

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        """Gera vetores determinísticos a partir do hash de cada texto.

        Args:
            textos: textos a vetorizar.

        Returns:
            RespostaEmbedding com vetores de 1536 valores em [0, 1] e tokens contados por palavras.
        """
        def vetor(t: str) -> list[float]:
            """Vetor determinístico de um texto.

            Args:
                t: texto.

            Returns:
                Lista de 1536 floats derivados do SHA-256 de `t`.
            """
            h = hashlib.sha256(t.encode()).digest()
            return [h[i % 32] / 255 for i in range(DIMENSAO)]

        return RespostaEmbedding([vetor(t) for t in textos], sum(len(t.split()) for t in textos))


MODELOS_COM_DIMENSOES = {"text-embedding-3-small", "text-embedding-3-large"}  # só estes aceitam o parâmetro `dimensions`


class EmbeddingOpenAI:
    """
    Embeddings da OpenAI. A coluna do banco é `vector(1536)`: o `text-embedding-3-large` (3072 por padrão) é reduzido pela própria API com `dimensions`,
    sem mexer no esquema. Trocar de modelo exige reindexar tudo (os vetores de modelos diferentes não se comparam).
    """

    dimensao = DIMENSAO

    def __init__(self, chave: str, cliente: httpx.Client | None = None, modelo: str = "text-embedding-3-small"):
        """Cria o adaptador.

        Args:
            chave: chave de API da OpenAI.
            cliente: cliente HTTP (injetável nos testes); por padrão aponta para a API da OpenAI.
            modelo: nome do modelo de embedding.
        """
        self.nome = modelo
        self._cliente = cliente or httpx.Client(base_url="https://api.openai.com", timeout=60)
        self._chave = chave

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        """Chama `/v1/embeddings`.

        Args:
            textos: textos a vetorizar.

        Returns:
            RespostaEmbedding com os vetores na ordem dos textos e o total de tokens.

        Raises:
            ErroProvedor: rede indisponível ou resposta diferente de 200.
        """
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
