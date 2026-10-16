import os
import time
from dataclasses import dataclass
from typing import Protocol

import httpx

from nexo_processamento.ia.provedor import ErroProvedor, erro_da_resposta, segundos_de_espera


@dataclass(frozen=True)
class RespostaLinguagem:
    texto: str
    tokens_entrada: int
    tokens_saida: int


class ModeloLinguagem(Protocol):
    nome: str

    def gerar(self, prompt: str, imagens: list[bytes] | None = None) -> RespostaLinguagem: ...


ESPERA_MAXIMA_429_S = 20.0  # só espera o Retry-After quando for curto; senão devolve o erro (a tela mostra mensagem e o usuário tenta de novo)


class ModeloLinguagemOpenAI:
    """
    Chat Completions da OpenAI (`/v1/chat/completions`). Sem `temperature` (salvo configurada) e sem `response_format`, de propósito: modelos de raciocínio recusam
    `temperature` diferente do padrão, e o modo JSON só devolve objeto (as sugestões de tópicos e as questões são arrays); o JSON é lido de forma
    tolerante em `json_modelo`. `max_completion_tokens` limita o gasto de cada chamada (em modelo de raciocínio os tokens de raciocínio contam aqui).
    """

    def __init__(self, chave: str, modelo: str, max_tokens: int = 4096, cliente: httpx.Client | None = None, espera=time.sleep, temperatura: float | None = None):
        self.nome = modelo
        self._chave = chave
        self._max_tokens = max_tokens
        self._temperatura = temperatura  # só quando configurada (MODELO_LINGUAGEM_TEMPERATURA): modelos de raciocínio recusam valor fora do padrão
        self._cliente = cliente or httpx.Client(base_url="https://api.openai.com", timeout=90)  # abaixo dos 2 min do backend
        self._espera = espera

    def gerar(self, prompt: str, imagens: list[bytes] | None = None) -> RespostaLinguagem:
        if imagens:
            raise NotImplementedError("descrição de figuras é a F6c")
        corpo = {"model": self.nome, "messages": [{"role": "user", "content": prompt}], "max_completion_tokens": self._max_tokens}
        if self._temperatura is not None:
            corpo["temperature"] = self._temperatura
        for tentativa in (1, 2):
            try:
                r = self._cliente.post("/v1/chat/completions", headers={"Authorization": f"Bearer {self._chave}"}, json=corpo)
            except httpx.HTTPError as e:
                raise ErroProvedor("provedor_indisponivel", f"Modelo de linguagem inacessível ({type(e).__name__})", True) from e
            if r.status_code == 429 and tentativa == 1 and (espera := segundos_de_espera(r)) is not None and espera <= ESPERA_MAXIMA_429_S:
                self._espera(espera)
                continue
            break
        if r.status_code != 200:
            raise erro_da_resposta(r, "Falha no modelo de linguagem")
        try:
            d = r.json()
            escolha = d["choices"][0]
            texto = escolha["message"].get("content")
            uso = d["usage"]
            tokens = (int(uso["prompt_tokens"]), int(uso["completion_tokens"]))
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise ErroProvedor("resposta_inesperada", "Resposta do modelo de linguagem em formato inesperado", True) from e
        if escolha.get("finish_reason") == "length":
            raise ErroProvedor("resposta_cortada", f"Resposta cortada pelo limite de {self._max_tokens} tokens: aumente MODELO_LINGUAGEM_MAX_TOKENS (modelos de raciocínio gastam tokens pensando)", False)
        if not texto:
            raise ErroProvedor("resposta_vazia", "O modelo de linguagem não devolveu texto (recusa ou resposta vazia)", False)
        return RespostaLinguagem(texto, tokens[0], tokens[1])


class ModeloLinguagemFalso:
    nome = "linguagem-falso"

    def gerar(self, prompt: str, imagens: list[bytes] | None = None) -> RespostaLinguagem:
        # Pedido de reorganização: o falso "aprova" a divisão atual (devolve o JSON que recebeu), para o fluxo poder ser demonstrado sem provedor real.
        marcador = "ATUAIS_JSON: "
        if marcador in prompt:
            linha = prompt.split(marcador, 1)[1].split("\n", 1)[0]
            return RespostaLinguagem(linha, len(prompt.split()), len(linha.split()))
        if "TAREFA: QUESTOES" in prompt or "TAREFA: VERIFICAR" in prompt or "TAREFA: RESUMO" in prompt:
            texto = _falso_geracao(prompt)
            return RespostaLinguagem(texto, len(prompt.split()), len(texto.split()))
        return RespostaLinguagem(f"[falso] {prompt[:80]}", len(prompt.split()), 5)


def _falso_geracao(prompt: str) -> str:
    """Respostas determinísticas e válidas para demonstrar resumo, questões e verificação sem provedor real (ADR 0026). O conteúdo é só um eco do material."""
    import json
    import re
    import time

    if "LENTO" in prompt:  # só no provedor falso: um tópico com "LENTO" no nome simula um modelo lento (para demonstrar e testar o cancelamento)
        time.sleep(float(os.getenv("FALSO_ATRASO_S", "6")))
    ordens = json.loads(re.search(r"ORDENS_DISPONIVEIS: (\[.*?\])", prompt).group(1)) if "ORDENS_DISPONIVEIS" in prompt else []
    trecho = re.search(r"<<<TRECHO ordem=\d+>>>\n(.*?)\n<<<FIM>>>", prompt, re.S)
    eco = " ".join(trecho.group(1).split())[:160] if trecho else "conhecimento geral"
    if "TAREFA: RESUMO" in prompt:
        return f"[Resumo de demonstração, sem modelo real] {eco}"
    if "TAREFA: VERIFICAR" in prompt:
        return json.dumps({"sustentada": True, "unica_correta": True, "ordens": ordens[:1], "motivo": "falso"})
    n = int(re.search(r"QUANTIDADE: (\d+)", prompt).group(1))
    return json.dumps([
        {"enunciado": f"[Demonstração] Questão {i + 1}: qual afirmação corresponde ao material? ({eco[:60]})", "correta": f"Trecho: {eco}",
         "erradas": [f"Afirmação falsa A{i}", f"Afirmação falsa B{i}", f"Afirmação falsa C{i}"], "ordens": ordens[:1]}
        for i in range(n)
    ], ensure_ascii=False)
