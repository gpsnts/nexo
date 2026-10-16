"""Leitura do JSON que o modelo devolve no meio de texto: o primeiro valor JSON válido do tipo esperado, sem se confundir com colchetes ou chaves da prosa."""
import json
from collections.abc import Callable


def primeiro_json(texto: str, abre: str, aceita: Callable[[object], bool]):
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
    return isinstance(v, list) and all(isinstance(x, dict) for x in v)


def objeto(v: object) -> bool:
    return isinstance(v, dict)
