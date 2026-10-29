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
