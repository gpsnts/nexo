from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class RespostaLinguagem:
    texto: str
    tokens_entrada: int
    tokens_saida: int


class ModeloLinguagem(Protocol):
    nome: str

    def gerar(self, prompt: str, imagens: list[bytes] | None = None) -> RespostaLinguagem: ...


class ModeloLinguagemFalso:
    nome = "linguagem-falso"

    def gerar(self, prompt: str, imagens: list[bytes] | None = None) -> RespostaLinguagem:
        # Pedido de reorganização: o falso "aprova" a divisão atual (devolve o JSON que recebeu), para o fluxo poder ser demonstrado sem provedor real.
        marcador = "ATUAIS_JSON: "
        if marcador in prompt:
            linha = prompt.split(marcador, 1)[1].split("\n", 1)[0]
            return RespostaLinguagem(linha, len(prompt.split()), len(linha.split()))
        return RespostaLinguagem(f"[falso] {prompt[:80]}", len(prompt.split()), 5)
