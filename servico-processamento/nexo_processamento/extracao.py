"""Extração de texto de PDF, DOCX e TXT. O formato é decidido pelo conteúdo, nunca pela extensão."""
import hashlib
import io
import re
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from statistics import median

import pdfplumber
from PIL import Image
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
    """Pedaço do documento: um parágrafo, um título, uma tabela inteira ou uma figura.

    Attributes:
        texto: conteúdo do bloco (tabela em Markdown; figura fica vazia até o modelo de visão descrevê-la).
        pagina: página do PDF onde o bloco está (nulo em DOCX e TXT).
        titulo: True se o bloco é um título.
        nivel: importância do título (1 = mais importante); só vale quando `titulo` é True.
        tipo: `texto`, `tabela` ou `figura`.
        imagem: PNG da figura, para o modelo de visão; só existe em blocos `figura` e não é gravada em lugar nenhum.
    """
    texto: str
    pagina: int | None = None
    titulo: bool = False
    nivel: int | None = None  # 1 = título mais importante; só quando titulo
    tipo: str = "texto"  # texto | tabela | figura
    imagem: bytes | None = field(default=None, repr=False, compare=False)


@dataclass(frozen=True)
class Documento:
    """Resultado da extração: o conteúdo de um arquivo, em blocos, na ordem do documento.

    Attributes:
        tipo: formato detectado pelo conteúdo (`pdf`, `docx` ou `txt`).
        paginas: número real de páginas no PDF; nulo nos formatos sem paginação.
        blocos: os blocos, em ordem.
        figuras_acima_do_limite: figuras encontradas além do limite por material (não foram lidas).
        figuras_com_falha: figuras que não puderam ser lidas do arquivo (imagem ilegível).
    """
    tipo: str  # pdf | docx | txt
    paginas: int | None  # real no PDF; nulo nos formatos sem paginação
    blocos: list[Bloco]
    figuras_acima_do_limite: int = 0
    figuras_com_falha: int = 0

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
LADO_MINIMO_FIGURA_PX = 48  # imagens menores (ícones, filetes, logotipos) não são figuras de conteúdo
LADO_MAXIMO_FIGURA_PX = 1600  # figuras maiores são reduzidas antes de ir ao modelo de visão
RESOLUCAO_FIGURA_DPI = 110  # resolução em que a região de uma figura do PDF é renderizada
PIXELS_MAXIMOS_FIGURA = 36_000_000  # imagem com mais pixels que isso nem é decodificada (um PNG de 250 KiB pode expandir para centenas de MiB)
MAX_FIGURAS_PADRAO = 30


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
        doc = {"docx": lambda d: _docx(d, 0), "txt": _txt}[tipo](dados)  # sem figuras: aqui só se contam caracteres
    except Exception as e:
        raise FormatoInvalido(f"{tipo} ilegível: {e}") from e
    return tipo, None, doc.caracteres


def extrair(dados: bytes, max_figuras: int = MAX_FIGURAS_PADRAO) -> Documento:
    """Extrai o conteúdo do arquivo em blocos (texto, tabelas e figuras), na ordem do documento.

    Args:
        dados: bytes do arquivo enviado.
        max_figuras: figuras lidas por arquivo; as demais só são contadas em `figuras_acima_do_limite`.

    Returns:
        O documento com tipo, páginas e blocos.

    Raises:
        FormatoInvalido: arquivo corrompido, de formato não aceito ou grande demais.
        SemTexto: arquivo válido, mas com menos de `TEXTO_MINIMO` caracteres (ex.: PDF escaneado).
    """
    tipo = detectar(dados)
    try:
        doc = {"pdf": lambda d: _pdf(d, max_figuras), "docx": lambda d: _docx(d, max_figuras), "txt": _txt}[tipo](dados)
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


def _tabela_markdown(linhas: list[list[str | None]]) -> str | None:
    """Converte as linhas de uma tabela em Markdown, com a primeira linha como cabeçalho.

    Args:
        linhas: células por linha (nulo = célula vazia).

    Returns:
        O texto em Markdown, ou nulo se for uma grade sem dados (menos de 2 linhas ou 2 colunas).
    """
    limpas = [[(c or "").replace("\n", " ").replace("|", "\\|").strip() for c in l] for l in linhas]
    limpas = [l for l in limpas if any(l)]
    colunas = max((len(l) for l in limpas), default=0)
    if len(limpas) < 2 or colunas < 2:
        return None
    limpas = [l + [""] * (colunas - len(l)) for l in limpas]
    saida = ["| " + " | ".join(limpas[0]) + " |", "| " + " | ".join(["---"] * colunas) + " |"]
    saida += ["| " + " | ".join(l) + " |" for l in limpas[1:]]
    return "\n".join(saida)


class _ImagemPequena(Exception):
    """A imagem é pequena demais para ser uma figura de conteúdo (ícone, filete, logotipo)."""


def _pequena(dados: bytes) -> bool:
    """Diz se uma imagem é pequena demais para ser figura, lendo só o cabeçalho (sem decodificar os pixels).

    Args:
        dados: bytes da imagem.

    Returns:
        Verdadeiro se algum lado é menor que `LADO_MINIMO_FIGURA_PX`; falso também quando a imagem não pode ser lida (a falha é tratada depois, com aviso).
    """
    try:
        return min(Image.open(io.BytesIO(dados)).size) < LADO_MINIMO_FIGURA_PX
    except Exception:
        return False


def _png(dados: bytes) -> bytes:
    """Normaliza uma imagem para PNG, reduzida ao lado máximo.

    Args:
        dados: bytes da imagem em qualquer formato que a biblioteca de imagens leia.

    Returns:
        Os bytes do PNG.

    Raises:
        _ImagemPequena: algum lado menor que `LADO_MINIMO_FIGURA_PX`.
        Exception: imagem ilegível, em formato sem suporte (ex.: EMF) ou com pixels demais.
    """
    img = Image.open(io.BytesIO(dados))
    if img.width * img.height > PIXELS_MAXIMOS_FIGURA:  # o tamanho vem do cabeçalho, antes de decodificar
        raise ValueError("imagem grande demais")
    img.load()
    if min(img.size) < LADO_MINIMO_FIGURA_PX:
        raise _ImagemPequena()
    if img.mode not in ("RGB", "L", "RGBA"):
        img = img.convert("RGB")
    img.thumbnail((LADO_MAXIMO_FIGURA_PX, LADO_MAXIMO_FIGURA_PX))
    saida = io.BytesIO()
    img.save(saida, format="PNG")
    return saida.getvalue()


def _docx(dados: bytes, max_figuras: int = MAX_FIGURAS_PADRAO) -> Documento:
    """Lê um DOCX: parágrafos, tabelas e figuras na ordem do documento.

    Cada tabela vira um bloco `tabela` em Markdown; cada imagem embutida vira um bloco `figura`, logo depois do parágrafo em que aparece. Célula mesclada conta uma vez. A imagem que aparece mais de uma vez (logotipo em vários lugares) é decoração e é ignorada por inteiro.

    Args:
        dados: bytes do arquivo.
        max_figuras: figuras lidas; as demais só são contadas.

    Returns:
        Documento do tipo `docx`, sem páginas, com os títulos marcados pelo estilo do parágrafo.
    """
    doc = Document(io.BytesIO(dados))
    blocos = []
    usos: Counter = Counter()  # quantas vezes cada imagem aparece: a que se repete (timbre, logotipo) é decoração
    for blip in doc.element.body.iter(qn("a:blip")):
        try:
            usos[hashlib.md5(doc.part.related_parts[blip.get(qn("r:embed"))].blob).hexdigest()] += 1
        except KeyError:
            pass
    lidas = acima = falha = 0
    for el in doc.element.body.iterchildren():
        if el.tag == qn("w:p"):
            p = Paragraph(el, doc)
            t = p.text.strip()
            if t:
                nivel = _nivel_do_estilo(p.style.name if p.style is not None else "")
                blocos.append(Bloco(t, None, nivel is not None, nivel))
            for blip in el.iter(qn("a:blip")):
                rid = blip.get(qn("r:embed"))
                try:
                    bruto = doc.part.related_parts[rid].blob
                except KeyError:
                    falha += 1
                    continue
                if usos[hashlib.md5(bruto).hexdigest()] > 1 or _pequena(bruto):
                    continue  # decoração (repetida ou minúscula): não conta nem no limite
                if lidas >= max_figuras:
                    acima += 1
                    continue
                try:
                    blocos.append(Bloco("", None, tipo="figura", imagem=_png(bruto)))
                    lidas += 1
                except _ImagemPequena:
                    pass
                except Exception:  # EMF/WMF e imagens corrompidas: a figura é ignorada com aviso
                    falha += 1
        elif el.tag == qn("w:tbl"):
            linhas = []
            for linha in Table(el, doc).rows:
                vistas_celulas, celulas = set(), []
                for c in linha.cells:  # célula mesclada aparece repetida: conta uma vez
                    if id(c._tc) not in vistas_celulas:
                        vistas_celulas.add(id(c._tc))
                        celulas.append(c.text)
                linhas.append(celulas)
            md = _tabela_markdown(linhas)
            if md:
                blocos.append(Bloco(md, None, tipo="tabela"))
            else:  # grade de uma linha ou coluna: é diagramação, o texto segue como parágrafos
                blocos += [Bloco(" | ".join(x.strip() for x in l if x.strip())) for l in linhas if any(x.strip() for x in l)]
    return Documento("docx", None, blocos, acima, falha)


def _celulas(chars: list[dict], tam: float) -> list[tuple[float, float, str]]:
    """Separa uma linha de texto em células pelos vãos largos entre os caracteres.

    Args:
        chars: caracteres da linha (com `x0`, `x1` e `text`).
        tam: tamanho da fonte da linha; um vão maior que 1,2 vez esse tamanho separa duas células.

    Returns:
        Lista de `(x0, x1, texto)`, uma por célula, da esquerda para a direita.
    """
    celulas: list[list] = []
    for c in sorted(chars, key=lambda c: c["x0"]):
        if celulas and c["x0"] - celulas[-1][1] <= 1.2 * tam:
            celulas[-1][1] = c["x1"]
            celulas[-1][2] += c["text"]
        else:
            celulas.append([c["x0"], c["x1"], c["text"]])
    return [(a, b, t.strip()) for a, b, t in celulas if t.strip()]


def _alinhadas(linhas: list[list[tuple[float, float, str]]]) -> bool:
    """Diz se as células das linhas formam colunas: em cada coluna, as bordas esquerdas (ou as direitas) quase coincidem.

    Args:
        linhas: células de cada linha, todas com o mesmo número de células.

    Returns:
        True se toda coluna está alinhada pela esquerda ou pela direita (tolerância de 8 pontos).
    """
    for j in range(len(linhas[0])):
        esq = [l[j][0] for l in linhas]
        dir_ = [l[j][1] for l in linhas]
        if max(esq) - min(esq) > 8 and max(dir_) - min(dir_) > 8:
            return False
    return True


def _tabelas_sem_bordas(linhas: list[dict], livre) -> list[tuple[tuple[float, float, float, float], str]]:
    """Acha tabelas sem bordas: 3 ou mais linhas seguidas com o mesmo número de células (2 ou mais) alinhadas em colunas.

    É conservador de propósito: texto corrido quase nunca tem vãos largos repetidos e alinhados, e uma célula com quebra de linha fica como texto.

    Args:
        linhas: linhas da página, como devolvidas pelo pdfplumber com os caracteres.
        livre: função que diz se uma linha está fora de qualquer tabela já achada.

    Returns:
        Lista de `(caixa, markdown)`, uma por tabela achada.
    """
    achadas = []
    corrida: list[tuple[dict, list]] = []

    def fechar():
        """Fecha a corrida de linhas em construção e a guarda se formar uma tabela.

        Returns:
            Nada; acrescenta a tabela a `achadas` e esvazia a corrida.
        """
        if len(corrida) >= 3 and _alinhadas([c for _, c in corrida]):
            md = _tabela_markdown([[t for _, _, t in c] for _, c in corrida])
            if md:
                ls = [l for l, _ in corrida]
                achadas.append(((min(l["x0"] for l in ls), min(l["top"] for l in ls), max(l["x1"] for l in ls), max(l["bottom"] for l in ls)), md))
        corrida.clear()

    for l in linhas:
        tam = median(c.get("size", 0) for c in l["chars"]) if l["chars"] else 0
        cel = _celulas(l["chars"], tam) if tam and livre(l) else []
        if len(cel) < 2:
            fechar()
            continue
        if corrida and (len(cel) != len(corrida[-1][1]) or l["top"] - corrida[-1][0]["bottom"] > 1.5 * (l["bottom"] - l["top"])):
            fechar()
        corrida.append((l, cel))
    fechar()
    return achadas


def _unir_tabelas_entre_paginas(blocos: list[Bloco]) -> list[Bloco]:
    """Junta a tabela que continua na página seguinte em um único bloco.

    Dois blocos `tabela` seguidos, em páginas consecutivas e com o mesmo número de colunas, são a mesma tabela; se a segunda repete o cabeçalho da primeira (cabeçalho repetido a cada página), ele é descartado.

    Args:
        blocos: blocos do PDF, em ordem.

    Returns:
        Os blocos com as continuações fundidas.
    """
    saida: list[Bloco] = []
    for b in blocos:
        a = saida[-1] if saida else None
        if a and a.tipo == b.tipo == "tabela" and a.pagina is not None and b.pagina == a.pagina + 1:
            la, lb = a.texto.split("\n"), b.texto.split("\n")
            if la[1].count("---") == lb[1].count("---"):
                corpo = lb[2:]
                if lb[0] == la[0]:
                    pass  # cabeçalho repetido: já está no começo
                else:
                    corpo = [lb[0]] + corpo
                saida[-1] = Bloco("\n".join(la + corpo), b.pagina, tipo="tabela")
                continue
        saida.append(b)
    return saida


def _pdf(dados: bytes, max_figuras: int = MAX_FIGURAS_PADRAO) -> Documento:
    """Lê um PDF linha a linha, com a posição e o tamanho da fonte de cada uma, mais as tabelas e as figuras.

    O corpo do texto é o tamanho de fonte mais frequente (ponderado por caractere); linhas com fonte pelo menos 15% maior e até 120 caracteres são títulos, com nível pelo tamanho (até 3 níveis). Um espaço vertical maior que 60% da altura da linha abre parágrafo novo. Tabelas com bordas, e as sem bordas cujas células ficam alinhadas em colunas (3 linhas ou mais), viram um bloco `tabela` em Markdown (as linhas dentro delas saem do texto); a tabela que continua na página seguinte vira um bloco só; imagens viram blocos `figura`, na posição vertical em que aparecem. Imagens pequenas ou que aparecem mais de uma vez no documento (logotipo, timbre) são ignoradas, inclusive a primeira ocorrência.

    Args:
        dados: bytes do arquivo.
        max_figuras: figuras lidas; as demais só são contadas.

    Returns:
        Documento do tipo `pdf` com o número real de páginas.

    Raises:
        FormatoInvalido: PDF com mais de `MAX_PAGINAS_PROCESSAR` páginas.
    """
    paginas = []
    lidas = acima = falha = 0
    with pdfplumber.open(io.BytesIO(dados)) as pdf:
        n = len(pdf.pages)
        if n > MAX_PAGINAS_PROCESSAR:
            raise FormatoInvalido("PDF com páginas demais")
        usos: Counter = Counter()  # quantas vezes cada imagem aparece: a que se repete (timbre, logotipo) é decoração
        for i, pg in enumerate(pdf.pages, start=1):
            for im in pg.images:
                usos[_marca_da_imagem(im, i)] += 1
        for i, pg in enumerate(pdf.pages, start=1):
            objetos: list[tuple[float, Bloco]] = []
            caixas = []
            for tabela in pg.find_tables():
                md = _tabela_markdown(tabela.extract())
                if md:
                    caixas.append(tabela.bbox)
                    objetos.append((tabela.bbox[1], Bloco(md, i, tipo="tabela")))
            for im in pg.images:
                caixa = (max(im["x0"], 0), max(im["top"], 0), min(im["x1"], pg.width), min(im["bottom"], pg.height))
                if caixa[2] - caixa[0] < 24 or caixa[3] - caixa[1] < 24:
                    continue
                if usos[_marca_da_imagem(im, i)] > 1:
                    continue
                if lidas >= max_figuras:
                    acima += 1
                    continue
                escala = RESOLUCAO_FIGURA_DPI / 72
                if (caixa[2] - caixa[0]) * (caixa[3] - caixa[1]) * escala * escala > PIXELS_MAXIMOS_FIGURA:  # região enorme: não vale renderizar
                    falha += 1
                    continue
                try:
                    objetos.append((caixa[1], Bloco("", i, tipo="figura", imagem=_png_da_pagina(pg, caixa))))
                    lidas += 1
                except _ImagemPequena:
                    pass
                except Exception:
                    falha += 1
            brutas = pg.extract_text_lines(return_chars=True)
            dentro = lambda l, cs: any(c[0] <= (l["x0"] + l["x1"]) / 2 <= c[2] and c[1] <= (l["top"] + l["bottom"]) / 2 <= c[3] for c in cs)
            for caixa, md in _tabelas_sem_bordas(brutas, lambda l: not dentro(l, caixas)):
                caixas.append(caixa)
                objetos.append((caixa[1], Bloco(md, i, tipo="tabela")))
            linhas = []
            for l in brutas:
                cx, cy = (l["x0"] + l["x1"]) / 2, (l["top"] + l["bottom"]) / 2
                if any(c[0] <= cx <= c[2] and c[1] <= cy <= c[3] for c in caixas):
                    continue  # linha de tabela: já está no bloco da tabela
                tam = median(c.get("size", 0) for c in l["chars"]) if l["chars"] else 0
                linhas.append((l["text"].strip(), round(tam, 1), l["top"], l["bottom"]))
            paginas.append((i, linhas, sorted(objetos, key=lambda o: o[0]), float(pg.height)))
    paginas = _sem_cabecalho_e_rodape(paginas)
    pesos: Counter = Counter()  # tamanho de fonte mais frequente, ponderado por caractere = tamanho do corpo
    for _, ls, _o, _a in paginas:
        for texto, tam, *_ in ls:
            pesos[tam] += len(texto)
    corpo = pesos.most_common(1)
    base = corpo[0][0] if corpo else 0
    tamanhos_titulo = sorted({t for _, ls, _o, _a in paginas for (x, t, *_r) in ls if x and base and t >= base * 1.15 and len(x) <= 120}, reverse=True)
    nivel_de = {t: min(i + 1, 3) for i, t in enumerate(tamanhos_titulo)}  # maior fonte = nível 1 (até 3 níveis)
    blocos: list[Bloco] = []
    for pag, ls, objetos, _altura in paginas:
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

        k = 0
        for texto, tam, topo, fim in ls:
            while k < len(objetos) and objetos[k][0] <= topo:  # tabela ou figura que começa acima desta linha
                fechar()
                blocos.append(objetos[k][1])
                anterior_fim = None
                k += 1
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
        blocos += [o for _, o in objetos[k:]]
    return Documento("pdf", n, _unir_tabelas_entre_paginas(blocos), acima, falha)


def _sem_cabecalho_e_rodape(paginas: list) -> list:
    """Tira das páginas o cabeçalho e o rodapé repetidos (nome da instituição, título do documento, "- 15 -").

    Uma linha na faixa superior ou inferior da página (12% da altura) cujo texto se repete em pelo menos metade das páginas (e em pelo menos 3) é cabeçalho ou rodapé; numeração de página ("- 15 -", "Página 3 de 20") conta como a mesma linha apesar de o número mudar. Sem isso, eles viram "títulos" e trechos de lixo em todas as páginas.

    Args:
        paginas: tuplas (número, linhas, objetos, altura da página) montadas por `_pdf`.

    Returns:
        As mesmas tuplas, sem as linhas repetidas das faixas de margem.
    """
    def chave(texto: str) -> str:
        """Texto de uma linha na forma usada para reconhecê-la em outras páginas.

        Args:
            texto: texto da linha.

        Returns:
            O texto em minúsculas e com espaços únicos; numeração de página sai com os números trocados por `#`.
        """
        limpo = re.sub(r"\s+", " ", texto.strip().lower())
        numerado = re.sub(r"\d+", "#", limpo)
        # numeração de página ("- 15 -", "Página 3 de 20") muda a cada página: compara sem os números; o resto do texto compara por inteiro
        return numerado if re.fullmatch(r"[\W_]*(p[áa]g(ina|\.)?\s*)?#\s*((de|/)\s*#)?[\W_]*", numerado) else limpo

    def na_margem(topo: float, base: float, altura: float) -> bool:
        """Diz se uma linha está na faixa de cabeçalho ou rodapé.

        Args:
            topo: distância do topo da página ao topo da linha.
            base: distância do topo da página à base da linha.
            altura: altura da página.

        Returns:
            Verdadeiro se a linha está nos 12% superiores ou inferiores.
        """
        return topo < 0.12 * altura or base > 0.88 * altura

    paginas_com: Counter = Counter()
    for _, linhas, _o, altura in paginas:
        for k in {chave(t) for t, _tam, topo, base in linhas if t and na_margem(topo, base, altura)}:
            paginas_com[k] += 1
    minimo = max(3, (len(paginas) + 1) // 2)
    repetidas = {k for k, n in paginas_com.items() if n >= minimo}
    if not repetidas:
        return paginas
    return [(i, [l for l in linhas if not (l[0] and na_margem(l[2], l[3], altura) and chave(l[0]) in repetidas)], objetos, altura) for i, linhas, objetos, altura in paginas]


def _marca_da_imagem(im: dict, pagina: int) -> str:
    """Identifica o conteúdo de uma imagem do PDF, para reconhecer a mesma imagem repetida.

    Args:
        im: imagem como o pdfplumber a descreve.
        pagina: página onde ela aparece (usada só se o conteúdo não puder ser lido).

    Returns:
        O hash do conteúdo bruto; sem conteúdo legível, um identificador único da posição (a imagem nunca é tida por repetida).
    """
    try:
        return hashlib.md5(im["stream"].get_rawdata()).hexdigest()
    except Exception:
        return f"{pagina}-{im.get('x0')}-{im.get('top')}"


def _png_da_pagina(pagina, caixa: tuple[float, float, float, float]) -> bytes:
    """Recorta a região de uma imagem da página e a normaliza para PNG.

    Args:
        pagina: página do pdfplumber.
        caixa: região (x0, topo, x1, base) em pontos.

    Returns:
        Os bytes do PNG.

    Raises:
        _ImagemPequena: a região renderizada é pequena demais para ser uma figura.
        Exception: falha ao renderizar.
    """
    buf = io.BytesIO()
    pagina.crop(caixa).to_image(resolution=RESOLUCAO_FIGURA_DPI).original.save(buf, format="PNG")
    return _png(buf.getvalue())
