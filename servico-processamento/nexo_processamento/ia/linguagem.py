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
        return RespostaLinguagem(f"[falso] {prompt[:80]}", len(prompt.split()), 5)
