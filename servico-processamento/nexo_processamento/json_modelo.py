"""Leitura do JSON que o modelo devolve no meio de texto: o primeiro valor JSON válido do tipo esperado, sem se confundir com colchetes ou chaves da prosa."""
import json
from collections.abc import Callable


def primeiro_json(texto: str, abre: str, aceita: Callable[[object], bool]):
    """Acha o primeiro valor JSON válido do tipo esperado dentro de um texto qualquer.

    A resposta de um modelo costuma vir no meio de prosa; esta função tenta decodificar a partir de cada abertura (`[` ou `{`) até achar um valor aceito, sem se confundir com colchetes ou chaves do texto.

    Args:
        texto: resposta do modelo.
        abre: caractere que abre o valor procurado (`[` para lista, `{` para objeto).
        aceita: predicado que diz se o valor decodificado tem o formato esperado.

    Returns:
        O primeiro valor aceito, ou None se não houver nenhum.
    """
    decodificador = json.JSONDecoder()
    i = texto.find(abre)
    while i != -1:
        try:
            valor, _ = decodificador.raw_decode(texto, i)
            if aceita(valor):
                return valor
        except ValueError:
            pass
        i = texto.find(abre, i + 1)
    return None


def lista_de_objetos(v: object) -> bool:
    """Diz se um valor decodificado é uma lista só de objetos JSON.

    Args:
        v: valor já decodificado.

    Returns:
        True se `v` é lista e todos os itens são dicionários.
    """
    return isinstance(v, list) and all(isinstance(x, dict) for x in v)


def objeto(v: object) -> bool:
    """Diz se um valor decodificado é um objeto JSON.

    Args:
        v: valor já decodificado.

    Returns:
        True se `v` é um dicionário.
    """
    return isinstance(v, dict)
