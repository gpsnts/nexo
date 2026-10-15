"""Divisão em trechos respeitando a estrutura: parágrafos inteiros, título abre trecho novo, sem sobreposição de texto."""
import re
from dataclasses import dataclass

from nexo_processamento.extracao import Bloco


@dataclass(frozen=True)
class Trecho:
    ordem: int
    numero_pagina: int | None
    conteudo: str
    titulo: str | None = None  # título que abre o trecho (apoia a sugestão de tópicos)
    nivel: int | None = None  # nível (1 = capítulo) desse título; nulo se o trecho não abre com título


def _cortar(texto: str, maximo: int) -> list[str]:
    """Parágrafo maior que o máximo: corta em fim de frase (ou, sem frase, no limite)."""
    partes, atual = [], ""
    for frase in re.split(r"(?<=[.!?])\s+", texto):
        while len(frase) > maximo:
            if atual:
                partes.append(atual)
                atual = ""
            partes.append(frase[:maximo])
            frase = frase[maximo:]
        if atual and len(atual) + 1 + len(frase) > maximo:
            partes.append(atual)
            atual = frase
        else:
            atual = f"{atual} {frase}".strip()
    if atual:
        partes.append(atual)
    return partes


def dividir(blocos: list[Bloco], alvo: int = 1500, maximo: int = 2000, minimo: int = 300) -> list[Trecho]:
    trechos: list[Trecho] = []
    atual: list[str] = []
    pagina: int | None = None
    titulo: str | None = None
    nivel: int | None = None

    def fechar():
        nonlocal atual, titulo, nivel
        if atual:
            trechos.append(Trecho(len(trechos) + 1, pagina, "\n\n".join(atual), titulo, nivel))
        atual, titulo, nivel = [], None, None

    for b in blocos:
        if b.titulo:
            # Título abre trecho novo, mas só se o que veio antes já tem corpo: seções de poucas linhas (ou títulos seguidos) se juntam,
            # senão um documento cheio de títulos vira centenas de trechos minúsculos (embedding caro e tópicos fragmentados).
            # Exceção: título de nível 1 (capítulo/seção principal) SEMPRE abre trecho novo, para que a fronteira de um tópico caia num limite de trecho.
            if atual and (b.nivel == 1 or sum(len(x) for x in atual) >= minimo):
                fechar()
            if not atual:
                atual, pagina, titulo, nivel = [b.texto], b.pagina, b.texto, b.nivel
            else:
                atual.append(b.texto)
            continue
        for parte in _cortar(b.texto, maximo):
            tamanho = sum(len(x) for x in atual) + 2 * len(atual)
            if atual and tamanho + len(parte) > alvo:
                fechar()
            if not atual:
                pagina = b.pagina
            atual.append(parte)
    fechar()
    return trechos
