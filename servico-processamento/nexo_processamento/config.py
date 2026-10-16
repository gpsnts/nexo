import os
from functools import lru_cache

import httpx

from nexo_processamento.ia.embedding import DIMENSAO, EmbeddingFalso, EmbeddingOpenAI, ModeloEmbedding
from nexo_processamento.ia.gemini import EmbeddingGemini, ModeloLinguagemGemini
from nexo_processamento.ia.linguagem import ModeloLinguagem, ModeloLinguagemFalso, ModeloLinguagemOpenAI


def _temperatura() -> float | None:
    """`MODELO_LINGUAGEM_TEMPERATURA` (vazio = não enviar): valores baixos deixam o JSON dos modelos pequenos mais estável; modelos de raciocínio recusam."""
    v = os.getenv("MODELO_LINGUAGEM_TEMPERATURA", "").strip()
    return float(v) if v else None


def criar_embedding() -> ModeloEmbedding:
    p = os.getenv("MODELO_EMBEDDING_PROVEDOR", "falso")
    if p == "falso":
        return EmbeddingFalso()
    if p == "openai":
        chave = os.getenv("OPENAI_API_KEY")
        if not chave:
            raise ValueError("OPENAI_API_KEY é obrigatória com MODELO_EMBEDDING_PROVEDOR=openai")
        dimensoes = int(os.getenv("MODELO_EMBEDDING_DIMENSOES", str(DIMENSAO)))
        if dimensoes != DIMENSAO:
            raise ValueError(f"MODELO_EMBEDDING_DIMENSOES={dimensoes}: a coluna do banco é vector({DIMENSAO}); mudar exige uma migração e reindexar tudo")
        return _openai(chave, os.getenv("MODELO_EMBEDDING_NOME") or "text-embedding-3-small")
    if p == "gemini":
        chave = os.getenv("GEMINI_API_KEY")
        if not chave:
            raise ValueError("GEMINI_API_KEY é obrigatória com MODELO_EMBEDDING_PROVEDOR=gemini")
        dimensoes = int(os.getenv("MODELO_EMBEDDING_DIMENSOES", str(DIMENSAO)))
        if dimensoes != DIMENSAO:
            raise ValueError(f"MODELO_EMBEDDING_DIMENSOES={dimensoes}: a coluna do banco é vector({DIMENSAO}); mudar exige uma migração e reindexar tudo")
        return _gemini_embedding(chave, os.getenv("MODELO_EMBEDDING_NOME") or "gemini-embedding-2", os.getenv("GEMINI_BASE_URL"))
    raise ValueError(f"MODELO_EMBEDDING_PROVEDOR desconhecido: {p}")


@lru_cache(maxsize=4)
def _openai(chave: str, modelo: str) -> EmbeddingOpenAI:
    """Um só cliente HTTP por chave: criar um a cada requisição vazava conexões abertas."""
    return EmbeddingOpenAI(chave, modelo=modelo)


def criar_linguagem() -> ModeloLinguagem:
    p = os.getenv("MODELO_LINGUAGEM_PROVEDOR", "falso")
    if p == "falso":
        return ModeloLinguagemFalso()
    if p == "openai":
        chave = os.getenv("OPENAI_API_KEY")
        modelo = os.getenv("MODELO_LINGUAGEM_NOME")
        if not chave:
            raise ValueError("OPENAI_API_KEY é obrigatória com MODELO_LINGUAGEM_PROVEDOR=openai")
        if not modelo:
            raise ValueError("MODELO_LINGUAGEM_NOME é obrigatória com MODELO_LINGUAGEM_PROVEDOR=openai (confira o nome e o preço no painel do provedor)")
        return _linguagem_openai(chave, modelo, int(os.getenv("MODELO_LINGUAGEM_MAX_TOKENS", "4096")), os.getenv("OPENAI_BASE_URL"), _temperatura())
    if p == "gemini":
        chave = os.getenv("GEMINI_API_KEY")
        modelo = os.getenv("MODELO_LINGUAGEM_NOME")
        if not chave:
            raise ValueError("GEMINI_API_KEY é obrigatória com MODELO_LINGUAGEM_PROVEDOR=gemini")
        if not modelo:
            raise ValueError("MODELO_LINGUAGEM_NOME é obrigatória com MODELO_LINGUAGEM_PROVEDOR=gemini (confira o nome na documentação do Gemini)")
        return _linguagem_gemini(chave, modelo, int(os.getenv("MODELO_LINGUAGEM_MAX_TOKENS", "4096")), os.getenv("GEMINI_BASE_URL"), _temperatura())
    raise ValueError(f"MODELO_LINGUAGEM_PROVEDOR desconhecido: {p}")


@lru_cache(maxsize=4)
def _linguagem_openai(chave: str, modelo: str, max_tokens: int, base_url: str | None, temperatura: float | None) -> ModeloLinguagemOpenAI:
    """Um só cliente HTTP por configuração (como no embedding)."""
    cliente = httpx.Client(base_url=base_url or "https://api.openai.com", timeout=float(os.getenv("MODELO_LINGUAGEM_TIMEOUT_S", "90")))
    return ModeloLinguagemOpenAI(chave, modelo, max_tokens, cliente, temperatura=temperatura)


@lru_cache(maxsize=4)
def _linguagem_gemini(chave: str, modelo: str, max_tokens: int, base_url: str | None, temperatura: float | None) -> ModeloLinguagemGemini:
    cliente = httpx.Client(base_url=base_url or "https://generativelanguage.googleapis.com", timeout=float(os.getenv("MODELO_LINGUAGEM_TIMEOUT_S", "90")))
    return ModeloLinguagemGemini(chave, modelo, max_tokens, cliente, temperatura=temperatura)


@lru_cache(maxsize=4)
def _gemini_embedding(chave: str, modelo: str, base_url: str | None) -> EmbeddingGemini:
    return EmbeddingGemini(chave, modelo, httpx.Client(base_url=base_url or "https://generativelanguage.googleapis.com", timeout=60))
