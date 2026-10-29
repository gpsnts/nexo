"""Extração de texto de PDF, DOCX e TXT. O formato é decidido pelo conteúdo, nunca pela extensão."""
import io
import re
import zipfile
from collections import Counter
from dataclasses import dataclass
from statistics import median

import pdfplumber
from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph


class FormatoInvalido(Exception):
    """Arquivo que não é PDF, DOCX nem TXT legível."""


class SemTexto(Exception):
    """Arquivo válido, mas sem texto extraível (ex.: PDF escaneado)."""


@dataclass(frozen=True)
class Bloco:
    """Pedaço de texto de um documento: um parágrafo, uma linha de tabela ou um título.

    Attributes:
        texto: conteúdo do bloco.
        pagina: página do PDF onde o bloco está (nulo em DOCX e TXT).
        titulo: True se o bloco é um título.
        nivel: importância do título (1 = mais importante); só vale quando `titulo` é True.
    """
    texto: str
    pagina: int | None = None
    titulo: bool = False
    nivel: int | None = None  # 1 = título mais importante; só quando titulo


@dataclass(frozen=True)
class Documento:
    """Resultado da extração: o texto de um arquivo, em blocos, na ordem do documento.

    Attributes:
        tipo: formato detectado pelo conteúdo (`pdf`, `docx` ou `txt`).
        paginas: número real de páginas no PDF; nulo nos formatos sem paginação.
        blocos: os blocos de texto, em ordem.
    """
    tipo: str  # pdf | docx | txt
    paginas: int | None  # real no PDF; nulo nos formatos sem paginação
    blocos: list[Bloco]

    @property
    def caracteres(self) -> int:
        """Total de caracteres de texto do documento.

        Returns:
            A soma dos tamanhos dos textos de todos os blocos.
        """
        return sum(len(b.texto) for b in self.blocos)


MAX_DESCOMPACTADO = 100 * 1024 * 1024  # contra zip bomb em DOCX
MAX_PAGINAS_PROCESSAR = 200  # teto de segurança do serviço; o limite do produto (50) é do backend
TEXTO_MINIMO = 20  # abaixo disso o arquivo é tratado como sem texto


def detectar(dados: bytes) -> str:
    """Decide o formato do arquivo pelo conteúdo, nunca pela extensão.

    Um DOCX é um zip com `word/document.xml`; um TXT que começa com "PK" continua sendo texto. Confere também o tamanho descompactado do DOCX (proteção contra zip bomb).

    Args:
        dados: bytes do arquivo enviado.

    Returns:
        `"pdf"`, `"docx"` ou `"txt"`.

    Raises:
        FormatoInvalido: zip que não é DOCX, DOCX descompactado grande demais ou conteúdo binário.
    """
    if dados.startswith(b"%PDF-"):
        return "pdf"
    if dados[:4] in (b"PK\x03\x04", b"PK\x05\x06"):  # assinatura completa: um TXT que começa com "PK" continua sendo texto
        try:
            with zipfile.ZipFile(io.BytesIO(dados)) as z:
                if "word/document.xml" in z.namelist():
                    if sum(i.file_size for i in z.infolist()) > MAX_DESCOMPACTADO:
                        raise FormatoInvalido("DOCX descompactado grande demais")
                    return "docx"
        except zipfile.BadZipFile:
            pass
        raise FormatoInvalido("arquivo zip que não é DOCX")
    if b"\x00" in dados[:8192]:
        raise FormatoInvalido("conteúdo binário")
    return "txt"


def contar_paginas(dados: bytes) -> tuple[str, int | None, int | None]:
    """Mede o arquivo sem montar o documento inteiro, para o backend validar o limite de páginas antes de indexar.

    O backend estima as páginas de DOCX e TXT a partir dos caracteres.

    Args:
        dados: bytes do arquivo enviado.

    Returns:
        Tupla (tipo, páginas reais do PDF ou None, caracteres de DOCX/TXT ou None).

    Raises:
        FormatoInvalido: arquivo ilegível ou de formato não aceito.
    """
    tipo = detectar(dados)
    if tipo == "pdf":
        try:
            with pdfplumber.open(io.BytesIO(dados)) as pdf:
                return tipo, len(pdf.pages), None
        except Exception as e:
            raise FormatoInvalido(f"PDF ilegível: {e}") from e
    try:
        doc = {"docx": _docx, "txt": _txt}[tipo](dados)
    except Exception as e:
        raise FormatoInvalido(f"{tipo} ilegível: {e}") from e
    return tipo, None, doc.caracteres


def extrair(dados: bytes) -> Documento:
    """Extrai o texto do arquivo em blocos, na ordem do documento.

    Args:
        dados: bytes do arquivo enviado.

    Returns:
        O documento com tipo, páginas e blocos.

    Raises:
        FormatoInvalido: arquivo corrompido, de formato não aceito ou grande demais.
        SemTexto: arquivo válido, mas com menos de `TEXTO_MINIMO` caracteres (ex.: PDF escaneado).
    """
    tipo = detectar(dados)
    try:
        doc = {"pdf": _pdf, "docx": _docx, "txt": _txt}[tipo](dados)
    except (FormatoInvalido, SemTexto):
        raise
    except Exception as e:  # biblioteca falhou com arquivo corrompido
        raise FormatoInvalido(f"{tipo} ilegível: {e}") from e
    if doc.caracteres < TEXTO_MINIMO:
        raise SemTexto("sem texto extraível")
    return doc


def _txt(dados: bytes) -> Documento:
    """Lê um TXT: decodifica em UTF-8 (ou cp1252 se falhar) e separa os parágrafos por linha em branco.

    Args:
        dados: bytes do arquivo.

    Returns:
        Documento do tipo `txt`, sem páginas, com um bloco por parágrafo.
    """
    try:
        texto = dados.decode("utf-8-sig")
    except UnicodeDecodeError:
        texto = dados.decode("cp1252", errors="replace")
    paragrafos = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", texto.replace("\r\n", "\n"))]
    return Documento("txt", None, [Bloco(p) for p in paragrafos if p])


def _nivel_do_estilo(nome: str | None) -> int | None:
    """Converte o nome de um estilo de parágrafo do Word em nível de título.

    "Heading 2" ou "Título 2" valem 2; "Title" ou "Título" sem número valem 1; qualquer outro estilo não é título.

    Args:
        nome: nome do estilo do parágrafo (pode ser nulo).

    Returns:
        O nível (de 1 a 9) ou None se o estilo não é de título.
    """
    m = re.match(r"^(heading|t[ií]tulo|title)\s*(\d+)?$", (nome or "").strip().lower())
    if not m:
        return None
    return min(int(m.group(2)), 9) if m.group(2) else 1


def _docx(dados: bytes) -> Documento:
    """Lê um DOCX: parágrafos e tabelas na ordem do documento.

    A tabela entra como texto simples (uma linha por linha da tabela, células separadas por " | "); a conversão para Markdown e o tipo `tabela` ficam para depois. Célula mesclada conta uma vez.

    Args:
        dados: bytes do arquivo.

    Returns:
        Documento do tipo `docx`, sem páginas, com os títulos marcados pelo estilo do parágrafo.
    """
    doc = Document(io.BytesIO(dados))
    blocos = []
    for el in doc.element.body.iterchildren():
        if el.tag == qn("w:p"):
            p = Paragraph(el, doc)
            t = p.text.strip()
            if t:
                nivel = _nivel_do_estilo(p.style.name if p.style is not None else "")
                blocos.append(Bloco(t, None, nivel is not None, nivel))
        elif el.tag == qn("w:tbl"):
            for linha in Table(el, doc).rows:
                vistas, celulas = set(), []
                for c in linha.cells:  # célula mesclada aparece repetida: conta uma vez
                    if id(c._tc) not in vistas:
                        vistas.add(id(c._tc))
                        if c.text.strip():
                            celulas.append(c.text.strip().replace("\n", " "))
                if celulas:
                    blocos.append(Bloco(" | ".join(celulas)))
    return Documento("docx", None, blocos)


def _pdf(dados: bytes) -> Documento:
    """Lê um PDF linha a linha, com a posição e o tamanho da fonte de cada uma.

    O corpo do texto é o tamanho de fonte mais frequente (ponderado por caractere); linhas com fonte pelo menos 15% maior e até 120 caracteres são títulos, com nível pelo tamanho (até 3 níveis). Um espaço vertical maior que 60% da altura da linha abre parágrafo novo.

    Args:
        dados: bytes do arquivo.

    Returns:
        Documento do tipo `pdf` com o número real de páginas.

    Raises:
        FormatoInvalido: PDF com mais de `MAX_PAGINAS_PROCESSAR` páginas.
    """
    linhas_por_pagina = []
    with pdfplumber.open(io.BytesIO(dados)) as pdf:
        n = len(pdf.pages)
        if n > MAX_PAGINAS_PROCESSAR:
            raise FormatoInvalido("PDF com páginas demais")
        for i, pg in enumerate(pdf.pages, start=1):
            linhas = []
            for l in pg.extract_text_lines(return_chars=True):
                tam = median(c.get("size", 0) for c in l["chars"]) if l["chars"] else 0
                linhas.append((l["text"].strip(), round(tam, 1), l["top"], l["bottom"]))
            linhas_por_pagina.append((i, linhas))
    pesos: Counter = Counter()  # tamanho de fonte mais frequente, ponderado por caractere = tamanho do corpo
    for _, ls in linhas_por_pagina:
        for texto, tam, *_ in ls:
            pesos[tam] += len(texto)
    corpo = pesos.most_common(1)
    base = corpo[0][0] if corpo else 0
    tamanhos_titulo = sorted({t for _, ls in linhas_por_pagina for (x, t, *_r) in ls if x and base and t >= base * 1.15 and len(x) <= 120}, reverse=True)
    nivel_de = {t: min(i + 1, 3) for i, t in enumerate(tamanhos_titulo)}  # maior fonte = nível 1 (até 3 níveis)
    blocos: list[Bloco] = []
    for pag, ls in linhas_por_pagina:
        atual: list[str] = []
        anterior_fim = None
        altura_ant = 0.0

        def fechar():
            """Fecha o parágrafo em construção da página atual, se houver texto.

            Returns:
                Nada; acrescenta o bloco à lista `blocos` da função externa.
            """
            if atual:
                blocos.append(Bloco(" ".join(atual), pag))
                atual.clear()

        for texto, tam, topo, fim in ls:
            if not texto:
                continue
            titulo = base and tam >= base * 1.15 and len(texto) <= 120
            quebra = anterior_fim is not None and (topo - anterior_fim) > 0.6 * (altura_ant or 10)
            if titulo or quebra:
                fechar()
            if titulo:
                blocos.append(Bloco(texto, pag, True, nivel_de.get(tam, 1)))
                anterior_fim = None  # linha depois do título começa novo parágrafo
                continue
            atual.append(texto)
            anterior_fim, altura_ant = fim, fim - topo
        fechar()
    return Documento("pdf", n, blocos)
