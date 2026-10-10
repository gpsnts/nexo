import os

from nexo_processamento.ia.embedding import EmbeddingFalso, EmbeddingOpenAI, ModeloEmbedding
from nexo_processamento.ia.linguagem import ModeloLinguagem, ModeloLinguagemFalso


def criar_embedding() -> ModeloEmbedding:
    p = os.getenv("EMBEDDING_PROVEDOR", "falso")
    if p == "falso":
        return EmbeddingFalso()
    if p == "openai":
        chave = os.getenv("OPENAI_API_KEY")
        if not chave:
            raise ValueError("OPENAI_API_KEY é obrigatória com EMBEDDING_PROVEDOR=openai")
        return EmbeddingOpenAI(chave)
    raise ValueError(f"EMBEDDING_PROVEDOR desconhecido: {p}")


def criar_linguagem() -> ModeloLinguagem:
    p = os.getenv("MODELO_LINGUAGEM_PROVEDOR", "falso")
    if p == "falso":
        return ModeloLinguagemFalso()
    raise ValueError(f"MODELO_LINGUAGEM_PROVEDOR desconhecido: {p}")  # provedor real entra na F2/F3
