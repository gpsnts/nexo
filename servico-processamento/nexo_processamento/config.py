"""Escolha dos provedores de IA do serviço de processamento a partir de variáveis de ambiente.

Cada fábrica devolve um objeto que obedece à interface de linguagem ou de embedding; o resto do serviço nunca sabe qual provedor está por trás.
Os clientes HTTP são criados uma vez por configuração (`lru_cache`), para não vazar conexões.
"""
import os
from functools import lru_cache

import httpx

from nexo_processamento.ia.embedding import DIMENSAO, EmbeddingFalso, EmbeddingOpenAI, ModeloEmbedding
from nexo_processamento.ia.gemini import EmbeddingGemini, ModeloLinguagemGemini
from nexo_processamento.ia.linguagem import ModeloLinguagem, ModeloLinguagemFalso, ModeloLinguagemOpenAI


def _temperatura() -> float | None:
    """Lê a temperatura do modelo de linguagem da variável `MODELO_LINGUAGEM_TEMPERATURA`.

    Valores baixos deixam o JSON dos modelos pequenos mais estável; modelos de raciocínio recusam valor diferente do padrão.

    Returns:
        A temperatura como número, ou None quando a variável está vazia (a chamada ao provedor não envia o parâmetro).
    """
    v = os.getenv("MODELO_LINGUAGEM_TEMPERATURA", "").strip()
    return float(v) if v else None


def criar_embedding() -> ModeloEmbedding:
    """Cria o modelo de embedding do provedor configurado em `MODELO_EMBEDDING_PROVEDOR` (`falso`, `openai` ou `gemini`).

    Exige a chave do provedor e confere que `MODELO_EMBEDDING_DIMENSOES` bate com a coluna `vector` do banco (trocar exige migração e reindexação).

    Returns:
        Um objeto que cumpre a interface `ModeloEmbedding` (nome do modelo e `gerar`).

    Raises:
        ValueError: provedor desconhecido, chave ausente ou dimensão diferente da do banco.
    """
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
    """Cria (ou reaproveita) o cliente de embedding da OpenAI.

    Um só cliente HTTP por chave e modelo: criar um a cada requisição vazava conexões abertas.

    Args:
        chave: chave de API da OpenAI.
        modelo: nome do modelo de embedding (ex.: `text-embedding-3-small`).

    Returns:
        O cliente `EmbeddingOpenAI`, em cache.
    """
    return EmbeddingOpenAI(chave, modelo=modelo)


def criar_linguagem() -> ModeloLinguagem:
    """Cria o modelo de linguagem do provedor configurado em `MODELO_LINGUAGEM_PROVEDOR` (`falso`, `openai` ou `gemini`).

    Para os provedores reais exige a chave e `MODELO_LINGUAGEM_NOME`; `MODELO_LINGUAGEM_MAX_TOKENS` (padrão 4096) limita a resposta de cada chamada.

    Returns:
        Um objeto que cumpre a interface `ModeloLinguagem` (nome do modelo e `gerar`).

    Raises:
        ValueError: provedor desconhecido, chave ausente ou nome do modelo ausente.
    """
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
    """Cria (ou reaproveita) o cliente de linguagem da OpenAI.

    Um só cliente HTTP por configuração (como no embedding).

    Args:
        chave: chave de API da OpenAI.
        modelo: nome do modelo de linguagem.
        max_tokens: limite de tokens da resposta de cada chamada.
        base_url: endereço da API (nulo usa o oficial; serve para servidor simulado nos testes).
        temperatura: temperatura a enviar, ou None para não enviar.

    Returns:
        O cliente `ModeloLinguagemOpenAI`, em cache.
    """
    cliente = httpx.Client(base_url=base_url or "https://api.openai.com", timeout=float(os.getenv("MODELO_LINGUAGEM_TIMEOUT_S", "90")))
    return ModeloLinguagemOpenAI(chave, modelo, max_tokens, cliente, temperatura=temperatura)


@lru_cache(maxsize=4)
def _linguagem_gemini(chave: str, modelo: str, max_tokens: int, base_url: str | None, temperatura: float | None) -> ModeloLinguagemGemini:
    """Cria (ou reaproveita) o cliente de linguagem do Gemini.

    Args:
        chave: chave de API do Gemini.
        modelo: nome do modelo de linguagem.
        max_tokens: limite de tokens da resposta de cada chamada.
        base_url: endereço da API (nulo usa o oficial).
        temperatura: temperatura a enviar, ou None para não enviar.

    Returns:
        O cliente `ModeloLinguagemGemini`, em cache.
    """
    cliente = httpx.Client(base_url=base_url or "https://generativelanguage.googleapis.com", timeout=float(os.getenv("MODELO_LINGUAGEM_TIMEOUT_S", "90")))
    return ModeloLinguagemGemini(chave, modelo, max_tokens, cliente, temperatura=temperatura)


@lru_cache(maxsize=4)
def _gemini_embedding(chave: str, modelo: str, base_url: str | None) -> EmbeddingGemini:
    """Cria (ou reaproveita) o cliente de embedding do Gemini.

    Args:
        chave: chave de API do Gemini.
        modelo: nome do modelo de embedding.
        base_url: endereço da API (nulo usa o oficial).

    Returns:
        O cliente `EmbeddingGemini`, em cache.
    """
    return EmbeddingGemini(chave, modelo, httpx.Client(base_url=base_url or "https://generativelanguage.googleapis.com", timeout=60))
