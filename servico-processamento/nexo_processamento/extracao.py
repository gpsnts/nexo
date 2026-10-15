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
    texto: str
    pagina: int | None = None
    titulo: bool = False
    nivel: int | None = None  # 1 = título mais importante; só quando titulo


@dataclass(frozen=True)
class Documento:
    tipo: str  # pdf | docx | txt
    paginas: int | None  # real no PDF; nulo nos formatos sem paginação
    blocos: list[Bloco]

    @property
    def caracteres(self) -> int:
        return sum(len(b.texto) for b in self.blocos)


MAX_DESCOMPACTADO = 100 * 1024 * 1024  # contra zip bomb em DOCX
MAX_PAGINAS_PROCESSAR = 200  # teto de segurança do serviço; o limite do produto (50) é do backend
TEXTO_MINIMO = 20  # abaixo disso o arquivo é tratado como sem texto


def detectar(dados: bytes) -> str:
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
    """(tipo, páginas reais do PDF, caracteres de DOCX/TXT). O backend estima as páginas de DOCX/TXT a partir dos caracteres."""
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
    try:
        texto = dados.decode("utf-8-sig")
    except UnicodeDecodeError:
        texto = dados.decode("cp1252", errors="replace")
    paragrafos = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", texto.replace("\r\n", "\n"))]
    return Documento("txt", None, [Bloco(p) for p in paragrafos if p])


def _nivel_do_estilo(nome: str | None) -> int | None:
    """"Heading 2" / "Título 2" -> 2; "Title" / "Título" sem número -> 1; outro estilo -> None (não é título)."""
    m = re.match(r"^(heading|t[ií]tulo|title)\s*(\d+)?$", (nome or "").strip().lower())
    if not m:
        return None
    return min(int(m.group(2)), 9) if m.group(2) else 1


def _docx(dados: bytes) -> Documento:
    """Parágrafos e tabelas na ordem do documento. A tabela entra como texto simples (uma linha por linha da tabela, células separadas
    por " | "); a conversão para Markdown e o tipo `tabela` ficam para a F6c."""
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
