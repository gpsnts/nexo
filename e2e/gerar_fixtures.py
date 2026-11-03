"""Gera os PDFs de fixtures dos testes (reportlab, o mesmo do serviço de processamento): python gerar_fixtures.py"""
import os
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

pasta = os.path.join(os.path.dirname(__file__), "fixtures")


def com_texto(nome: str, paginas: int, rotulo: str):
    """Gera um PDF com texto, com um capítulo a cada 5 páginas.

    Args:
        nome: nome do arquivo em `fixtures`.
        paginas: quantidade de páginas.
        rotulo: texto do título dos capítulos.
    """
    c = canvas.Canvas(os.path.join(pasta, nome), pagesize=A4)
    for pg in range(1, paginas + 1):
        c.setFont("Helvetica-Bold", 20)
        if (pg - 1) % 5 == 0:
            c.drawString(60, 790, f"Capitulo {(pg - 1) // 5 + 1}: {rotulo}")
        c.setFont("Helvetica", 11)
        y = 760 if (pg - 1) % 5 == 0 else 800
        for par in range(4):
            for linha in range(5):
                c.drawString(60, y, f"Pagina {pg}, paragrafo {par}, linha {linha}: o movimento retilineo uniforme tem velocidade constante.")
                y -= 14
            y -= 14
        c.showPage()
    c.save()


com_texto("apostila-50-paginas.pdf", 50, "Fundamentos da Fisica")
com_texto("longo-51-paginas.pdf", 51, "Excesso de paginas")
c = canvas.Canvas(os.path.join(pasta, "escaneado.pdf"), pagesize=A4)  # só desenho: sem texto extraível
for _ in range(2):
    c.rect(100, 100, 300, 300)
    c.showPage()
c.save()


def ruido(semente: int, lado: int = 120) -> bytes:
    """PNG de ruído, diferente a cada semente (imagem repetida é tratada como decoração e não conta como figura).

    Args:
        semente: define o ruído.
        lado: lado da imagem em pixels.

    Returns:
        Os bytes do PNG.
    """
    import io
    import random

    from PIL import Image

    r = random.Random(semente)
    img = Image.new("RGB", (lado, lado))
    img.putdata([(r.randrange(256), r.randrange(256), r.randrange(256)) for _ in range(lado * lado)])
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def com_tabela_e_figuras(nome: str, figuras: int):
    """Gera um PDF com texto, uma tabela com bordas e imagens distintas.

    Args:
        nome: nome do arquivo em `fixtures`.
        figuras: quantidade de imagens distintas (uma por página, depois do texto).
    """
    import io

    from reportlab.lib import colors
    from reportlab.lib.styles import getSampleStyleSheet
    from reportlab.platypus import Image as Imagem
    from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Table, TableStyle

    estilo = getSampleStyleSheet()["Normal"]
    itens = []
    for i in range(figuras):
        itens.append(Paragraph(f"Pagina {i + 1}: o movimento retilineo uniforme tem velocidade constante. " * 6, estilo))
        if i == 0:
            t = Table([["Cidade", "Habitantes"], ["Curitiba", "1900000"], ["Londrina", "580000"]])
            t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
            itens.append(t)
        itens.append(Imagem(io.BytesIO(ruido(i)), width=120, height=120))
        itens.append(PageBreak())
    SimpleDocTemplate(os.path.join(pasta, nome), pagesize=A4).build(itens)


com_tabela_e_figuras("tabela-e-figura.pdf", 1)
com_tabela_e_figuras("figuras-demais.pdf", 32)
