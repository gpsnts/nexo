"""Descrição das figuras de um material pelo modelo de linguagem com visão. Só o texto da descrição é guardado; a imagem não."""
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from nexo_processamento.extracao import Bloco
from nexo_processamento.ia.linguagem import ModeloLinguagem

PROMPT_FIGURA = (
    "Descreva a figura de um material de estudo para que alguém consiga responder perguntas sobre ela sem vê-la. "
    "Transcreva os textos, rótulos, legendas e números visíveis. Se for um gráfico, diga os eixos, as séries e a tendência; "
    "se for um diagrama, os elementos e como se relacionam; se for uma tabela em imagem, reproduza os dados. "
    "Não invente o que não está visível. Responda em português, em um único parágrafo de até 1200 caracteres, sem Markdown."
)
TRABALHADORES = 4  # chamadas ao modelo de visão em paralelo
FALHAS_SEGUIDAS_PARA_DESISTIR = 3  # modelo fora do ar ou sem suporte a imagem: não vale insistir nas demais


@dataclass(frozen=True)
class FigurasDescritas:
    """Resultado da descrição das figuras de um documento.

    Attributes:
        blocos: os blocos na mesma ordem, com cada figura trocada pela sua descrição; figuras que falharam saem da lista.
        tokens_entrada: tokens de entrada gastos com o modelo de visão.
        tokens_saida: tokens de saída gastos.
        com_falha: quantas figuras o modelo não conseguiu descrever.
        tentou: se alguma chamada ao modelo foi feita (define se há modelo a registrar).
    """
    blocos: list[Bloco]
    tokens_entrada: int
    tokens_saida: int
    com_falha: int
    tentou: bool


def descrever(blocos: list[Bloco], modelo: ModeloLinguagem | None) -> FigurasDescritas:
    """Troca cada bloco `figura` pela descrição gerada pelo modelo de visão.

    As chamadas são feitas em paralelo. Falha em uma figura (erro do provedor, resposta vazia, modelo sem suporte a imagem) a descarta e conta; depois de algumas falhas seguidas as demais nem são tentadas.

    Args:
        blocos: blocos do documento; os `figura` trazem o PNG em `imagem`.
        modelo: modelo de linguagem com visão; nulo se não foi possível criá-lo (todas as figuras contam como falha).

    Returns:
        Os blocos com as descrições no lugar das figuras, os tokens gastos e a quantidade de falhas.
    """
    posicoes = [i for i, b in enumerate(blocos) if b.tipo == "figura"]
    if not posicoes:
        return FigurasDescritas(blocos, 0, 0, 0, False)
    trava = threading.Lock()
    seguidas = 0
    tentou = False

    def uma(i: int):
        """Descreve a figura da posição `i`.

        Args:
            i: posição do bloco `figura` na lista.

        Returns:
            A resposta do modelo, ou nulo se falhou ou se o modelo já foi dado como indisponível.
        """
        nonlocal seguidas, tentou
        if modelo is None:
            return None
        with trava:
            if seguidas >= FALHAS_SEGUIDAS_PARA_DESISTIR:
                return None
            tentou = True
        try:
            r = modelo.gerar(PROMPT_FIGURA, imagens=[blocos[i].imagem])
            ok = bool(r.texto.strip())
        except Exception:  # qualquer falha do provedor: a figura é ignorada, a indexação segue
            r, ok = None, False
        with trava:
            seguidas = 0 if ok else seguidas + 1
        return r if ok else None

    with ThreadPoolExecutor(max_workers=TRABALHADORES) as pool:
        respostas = list(pool.map(uma, posicoes))
    novos: list[Bloco | None] = list(blocos)
    entrada = saida = falhas = 0
    for i, r in zip(posicoes, respostas):
        if r is None:
            falhas += 1
            novos[i] = None
            continue
        entrada += r.tokens_entrada
        saida += r.tokens_saida
        b = blocos[i]
        novos[i] = Bloco(f"Figura: {r.texto.strip()}", b.pagina, tipo="figura")
    return FigurasDescritas([b for b in novos if b is not None], entrada, saida, falhas, tentou)
