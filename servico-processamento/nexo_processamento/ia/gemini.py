"""Adaptadores do Gemini (Google AI Studio): modelo de linguagem (`generateContent`) e embedding (`batchEmbedContents`). Chave no cabeçalho `x-goog-api-key`."""
import math
import time

import httpx

from nexo_processamento.ia.embedding import DIMENSAO, RespostaEmbedding
from nexo_processamento.ia.linguagem import ESPERA_MAXIMA_429_S, RespostaLinguagem
from nexo_processamento.ia.provedor import ErroProvedor, erro_da_resposta, segundos_de_espera

URL_PADRAO = "https://generativelanguage.googleapis.com"


def _enviar(cliente: httpx.Client, caminho: str, chave: str, corpo: dict, o_que: str, espera) -> httpx.Response:
    """POST com o tratamento comum: rede vira erro repetível; 429 com espera curta sugerida pelo provedor espera e tenta UMA vez de novo."""
    for tentativa in (1, 2):
        try:
            r = cliente.post(caminho, headers={"x-goog-api-key": chave}, json=corpo)
        except httpx.HTTPError as e:
            raise ErroProvedor("provedor_indisponivel", f"{o_que} inacessível ({type(e).__name__})", True) from e
        if r.status_code == 429 and tentativa == 1 and (s := segundos_de_espera(r)) is not None and s <= ESPERA_MAXIMA_429_S:
            espera(s)
            continue
        break
    if r.status_code != 200:
        raise erro_da_resposta(r, f"Falha no {o_que}")
    return r


class ModeloLinguagemGemini:
    """
    `models/{modelo}:generateContent`. Os modelos Flash "pensam" por padrão: os tokens de raciocínio (`thoughtsTokenCount`) contam como saída e entram no
    teto `maxOutputTokens`; se a resposta vier cortada (`MAX_TOKENS`), aumente MODELO_LINGUAGEM_MAX_TOKENS.
    """

    def __init__(self, chave: str, modelo: str, max_tokens: int = 4096, cliente: httpx.Client | None = None, espera=time.sleep, temperatura: float | None = None):
        self.nome = modelo
        self._chave = chave
        self._max_tokens = max_tokens
        self._temperatura = temperatura
        self._cliente = cliente or httpx.Client(base_url=URL_PADRAO, timeout=90)
        self._espera = espera

    def gerar(self, prompt: str, imagens: list[bytes] | None = None):
        if imagens:
            raise NotImplementedError("descrição de figuras é a F6c")
        corpo = {"contents": [{"parts": [{"text": prompt}]}], "generationConfig": {"maxOutputTokens": self._max_tokens}}
        if self._temperatura is not None:
            corpo["generationConfig"]["temperature"] = self._temperatura
        r = _enviar(self._cliente, f"/v1beta/models/{self.nome}:generateContent", self._chave, corpo, "modelo de linguagem", self._espera)
        try:
            d = r.json()
            uso = d["usageMetadata"]
            entrada = int(uso["promptTokenCount"])
            saida = int(uso.get("candidatesTokenCount", 0)) + int(uso.get("thoughtsTokenCount", 0))
            candidatos = d.get("candidates") or []
        except (ValueError, KeyError, TypeError) as e:
            raise ErroProvedor("resposta_inesperada", "Resposta do modelo de linguagem em formato inesperado", True) from e
        if not candidatos:
            motivo = (d.get("promptFeedback") or {}).get("blockReason", "sem candidatos")
            raise ErroProvedor("resposta_vazia", f"O modelo de linguagem não respondeu (bloqueio ou resposta vazia: {motivo})", False)
        c = candidatos[0]
        texto = "".join(p.get("text", "") for p in (c.get("content") or {}).get("parts", []) if isinstance(p, dict))
        if c.get("finishReason") == "MAX_TOKENS":
            raise ErroProvedor("resposta_cortada", f"Resposta cortada pelo limite de {self._max_tokens} tokens: aumente MODELO_LINGUAGEM_MAX_TOKENS (os modelos Flash gastam tokens pensando)", False)
        if not texto.strip():
            raise ErroProvedor("resposta_vazia", f"O modelo de linguagem não devolveu texto (finishReason={c.get('finishReason')})", False)
        return RespostaLinguagem(texto, entrada, saida)


class EmbeddingGemini:
    """
    `models/{modelo}:batchEmbedContents` reduzido a 1536 dimensões (a coluna do banco) por `outputDimensionality`. O vetor é normalizado aqui: o
    `gemini-embedding-001` exige normalização manual fora de 3072 dimensões e o `gemini-embedding-2*` já a faz (normalizar de novo é inofensivo).
    O `gemini-embedding-001` recebe `taskType` SEMANTIC_SIMILARITY (a interface não distingue consulta de documento); o `-2` não aceita o campo.
    """

    dimensao = DIMENSAO

    def __init__(self, chave: str, modelo: str = "gemini-embedding-2", cliente: httpx.Client | None = None, espera=time.sleep):
        self.nome = modelo
        self._chave = chave
        self._cliente = cliente or httpx.Client(base_url=URL_PADRAO, timeout=60)
        self._espera = espera

    def _item(self, texto: str) -> dict:
        config = {"outputDimensionality": DIMENSAO}
        if self.nome == "gemini-embedding-001":
            config["taskType"] = "SEMANTIC_SIMILARITY"
        return {"model": f"models/{self.nome}", "content": {"parts": [{"text": texto}]}, "embedContentConfig": config}

    def gerar(self, textos: list[str]) -> RespostaEmbedding:
        r = _enviar(self._cliente, f"/v1beta/models/{self.nome}:batchEmbedContents", self._chave, {"requests": [self._item(t) for t in textos]}, "modelo de embedding", self._espera)
        try:
            d = r.json()
            vetores = [[float(x) for x in e["values"]] for e in d["embeddings"]]
            tokens = int(d["usageMetadata"]["promptTokenCount"])
        except (ValueError, KeyError, TypeError) as e:
            raise ErroProvedor("resposta_inesperada", "Resposta do modelo de embedding em formato inesperado", True) from e
        if len(vetores) != len(textos) or any(len(v) != DIMENSAO for v in vetores):
            raise ErroProvedor("resposta_inesperada", f"Embedding fora do esperado: {len(vetores)} vetores para {len(textos)} textos, dimensão {len(vetores[0]) if vetores else 0} (esperada {DIMENSAO})", True)
        return RespostaEmbedding([_normalizar(v) for v in vetores], tokens)


def _normalizar(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v] if n else v
