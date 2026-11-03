"""Divisão em trechos respeitando a estrutura: parágrafos inteiros, título abre trecho novo, sem sobreposição de texto."""
import re
from dataclasses import dataclass

from nexo_processamento.extracao import Bloco


@dataclass(frozen=True)
class Trecho:
    """Pedaço de texto do material, a unidade de busca e de geração (tabela `trecho`).

    Attributes:
        ordem: posição do trecho no material, a partir de 1.
        numero_pagina: página onde o trecho começa (nulo em DOCX e TXT).
        conteudo: texto do trecho (parágrafos inteiros, separados por linha em branco).
        titulo: título que abre o trecho (apoia a sugestão de tópicos).
        nivel: nível desse título (1 = capítulo); nulo se o trecho não abre com título.
        tipo_conteudo: `texto`, `tabela` ou `figura`.
    """
    ordem: int
    numero_pagina: int | None
    conteudo: str
    titulo: str | None = None  # título que abre o trecho (apoia a sugestão de tópicos)
    nivel: int | None = None  # nível (1 = capítulo) desse título; nulo se o trecho não abre com título
    tipo_conteudo: str = "texto"  # texto | tabela | figura


def _cortar(texto: str, maximo: int) -> list[str]:
    """Corta um parágrafo maior que o máximo em fim de frase (ou, sem frase, no limite).

    Args:
        texto: parágrafo a cortar.
        maximo: tamanho máximo, em caracteres, de cada pedaço.

    Returns:
        Os pedaços, em ordem, cada um com no máximo `maximo` caracteres.
    """
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


def _partes_da_tabela(markdown: str, maximo: int) -> list[str]:
    """Corta uma tabela grande em pedaços de linhas inteiras, repetindo o cabeçalho em cada um.

    Args:
        markdown: tabela em Markdown (cabeçalho, linha de traços e linhas de dados).
        maximo: tamanho máximo, em caracteres, de cada pedaço.

    Returns:
        Os pedaços; uma tabela que cabe volta inteira. Uma linha maior que o máximo fica sozinha (o embedding aceita bem mais que isso).
    """
    if len(markdown) <= maximo:
        return [markdown]
    linhas = markdown.split("\n")
    cabecalho, dados = "\n".join(linhas[:2]), linhas[2:]
    partes, atual = [], []
    for l in dados:
        if atual and len(cabecalho) + sum(len(x) + 1 for x in atual) + len(l) > maximo:
            partes.append(cabecalho + "\n" + "\n".join(atual))
            atual = []
        atual.append(l)
    if atual:
        partes.append(cabecalho + "\n" + "\n".join(atual))
    return partes


def dividir(blocos: list[Bloco], alvo: int = 1500, maximo: int = 2000, minimo: int = 300) -> list[Trecho]:
    """Divide os blocos extraídos em trechos respeitando a estrutura.

    Parágrafos ficam inteiros; título abre trecho novo, mas só se o que veio antes já tem corpo (seções de poucas linhas se juntam, para um documento cheio de títulos não virar centenas de trechos minúsculos); título de nível 1 sempre abre trecho novo, para a fronteira de um tópico cair num limite de trecho. Não há sobreposição de texto.

    Args:
        blocos: blocos do documento, em ordem (parágrafos e títulos).
        alvo: tamanho desejado de um trecho, em caracteres.
        maximo: tamanho máximo de um parágrafo antes de ser cortado.
        minimo: corpo mínimo que um trecho precisa ter para um título (que não seja de nível 1) abrir o seguinte.

    Returns:
        Os trechos numerados a partir de 1, com página, título e nível quando o trecho abre com título. Tabela e figura são trechos próprios (tipo `tabela` e `figura`); se só havia título aberto antes deles, o título entra no trecho e o dá nome.
    """
    trechos: list[Trecho] = []
    atual: list[str] = []
    marcas: list[Bloco | None] = []  # um item por parte de `atual`: o bloco, se a parte é título; nulo se é corpo
    pagina: int | None = None
    titulo: str | None = None
    nivel: int | None = None

    def fechar():
        """Fecha o trecho em construção (se houver texto) e zera o estado para o próximo.

        Returns:
            Nada; acrescenta o trecho à lista `trechos` da função externa.
        """
        nonlocal atual, titulo, nivel, marcas
        if atual:
            trechos.append(Trecho(len(trechos) + 1, pagina, "\n\n".join(atual), titulo, nivel))
        atual, titulo, nivel, marcas = [], None, None, []

    for b in blocos:
        if b.tipo != "texto":
            # Tabela e figura são trechos próprios. Os títulos que fecham o trecho em construção (o rótulo "Tabela 2 - ...") vão junto com ela,
            # mesmo quando vinham depois de algum texto; o texto anterior fecha sem eles.
            if b.tipo == "tabela":
                partes = _partes_da_tabela(b.texto, maximo)
            else:
                partes = _cortar(b.texto, maximo)[:1]  # descrição de figura: só o início, se passar do máximo
            if not partes:
                continue
            cauda = []
            while marcas and marcas[-1] is not None:
                cauda.insert(0, (atual.pop(), marcas.pop()))
            if atual:  # sobrou corpo: ele fecha sozinho e a cauda de títulos vira o início do trecho do objeto
                fechar()
                if cauda:
                    titulo, nivel, pagina = cauda[0][1].texto, cauda[0][1].nivel, cauda[0][1].pagina
            atual += [t for t, _ in cauda]
            marcas += [m for _, m in cauda]
            for j, parte in enumerate(partes):
                if j == 0 and atual:
                    pagina = pagina if pagina is not None else b.pagina
                    trechos.append(Trecho(len(trechos) + 1, pagina, "\n\n".join(atual + [parte]), titulo, nivel, b.tipo))
                    atual, titulo, nivel, marcas = [], None, None, []
                else:
                    trechos.append(Trecho(len(trechos) + 1, b.pagina, parte, None, None, b.tipo))
            continue
        if b.titulo:
            # Título abre trecho novo, mas só se o que veio antes já tem corpo: seções de poucas linhas (ou títulos seguidos) se juntam,
            # senão um documento cheio de títulos vira centenas de trechos minúsculos (embedding caro e tópicos fragmentados).
            # Exceção: título de nível 1 (capítulo/seção principal) SEMPRE abre trecho novo, para que a fronteira de um tópico caia num limite de trecho.
            if atual and (b.nivel == 1 or sum(len(x) for x in atual) >= minimo):
                fechar()
            if not atual:
                atual, pagina, titulo, nivel, marcas = [b.texto], b.pagina, b.texto, b.nivel, [b]
            else:
                atual.append(b.texto)
                marcas.append(b)
            continue
        for parte in _cortar(b.texto, maximo):
            tamanho = sum(len(x) for x in atual) + 2 * len(atual)
            if atual and tamanho + len(parte) > alvo:
                fechar()
            if not atual:
                pagina = b.pagina
            atual.append(parte)
            marcas.append(None)
    fechar()
    return trechos
