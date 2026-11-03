"""Tabelas e figuras: extração de PDF e DOCX, descrição pelo modelo de visão, limites e falhas."""
import io
import random
import time

import pytest
from docx import Document
from docx.shared import Inches
from PIL import Image
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.utils import ImageReader
from reportlab.platypus import Image as ImagemPdf
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Table, TableStyle

from nexo_processamento import extracao, main
from nexo_processamento.ia.linguagem import RespostaLinguagem
from conftest import H

ESTILO = getSampleStyleSheet()


def png(semente: int, lado: int = 160) -> bytes:
    """PNG de ruído, diferente a cada semente (imagens iguais são tratadas como decoração)."""
    r = random.Random(semente)
    img = Image.new("RGB", (lado, lado))
    img.putdata([(r.randrange(256), r.randrange(256), r.randrange(256)) for _ in range(lado * lado)])
    b = io.BytesIO()
    img.save(b, format="PNG")
    return b.getvalue()


def tabela_pdf(linhas):
    t = Table(linhas, repeatRows=1)  # tabela que passa de página repete o cabeçalho, como em documentos reais
    t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
    return t


def pdf_com(*itens) -> bytes:
    buf = io.BytesIO()
    SimpleDocTemplate(buf, pagesize=A4).build(list(itens))
    return buf.getvalue()


def par(texto):
    return Paragraph(texto, ESTILO["Normal"])


def figura(semente=1):
    return ImagemPdf(io.BytesIO(png(semente)), width=150, height=150)


CIDADES = [["Cidade", "Habitantes"], ["Curitiba", "1900000"], ["Londrina", "580000"], ["Maringa", "430000"]]


def processar(cliente, dados, **campos):
    r = cliente.post("/interno/processar", files={"arquivo": ("a.bin", dados)}, data=campos, headers=H)
    assert r.status_code == 200, r.text
    return r.json()


def tipos(j):
    return [t["tipo_conteudo"] for t in j["trechos"]]


def test_pdf_com_tabela_e_figura_gera_trechos_dos_dois_tipos_na_ordem(cliente):
    j = processar(cliente, pdf_com(par("Introducao ao assunto das cidades. " * 6), tabela_pdf(CIDADES), figura(), par("Conclusao sobre o assunto estudado. " * 6)))
    assert tipos(j) == ["texto", "tabela", "figura", "texto"]
    tabela = j["trechos"][1]["conteudo"]
    assert tabela.startswith("| Cidade | Habitantes |\n| --- | --- |\n| Curitiba | 1900000 |")
    assert "Curitiba" not in j["trechos"][0]["conteudo"] + j["trechos"][3]["conteudo"], "a linha da tabela não repete no texto"
    assert j["trechos"][2]["conteudo"].startswith("Figura: [Descrição de demonstração")
    assert j["avisos"] == {"figuras_acima_do_limite": 0, "figuras_com_falha": 0}
    assert j["visao"]["modelo"] == "linguagem-falso" and j["visao"]["tokens_entrada"] > 0 and j["visao"]["tokens_saida"] > 0


def test_a_pergunta_sobre_a_tabela_cai_no_trecho_da_tabela(cliente):
    # busca lexical: só o trecho da tabela traz a cidade e o número (a busca por sentido depende do modelo de embedding real)
    j = processar(cliente, pdf_com(par("Texto geral sobre geografia. " * 8), tabela_pdf(CIDADES), par("Outro texto geral qualquer. " * 8)))
    achados = [t for t in j["trechos"] if "Londrina" in t["conteudo"] and "580000" in t["conteudo"]]
    assert [t["tipo_conteudo"] for t in achados] == ["tabela"]


def test_falha_do_modelo_de_visao_nao_derruba_a_indexacao(cliente, monkeypatch):
    class Quebrado:
        nome = "visao-quebrada"

        def gerar(self, prompt, imagens=None):
            raise RuntimeError("provedor fora do ar")

    monkeypatch.setattr(main, "criar_linguagem", lambda: Quebrado())
    j = processar(cliente, pdf_com(par("Texto antes. " * 10), figura(1), par("Texto depois. " * 10)))
    assert "figura" not in tipos(j) and j["avisos"]["figuras_com_falha"] == 1
    assert j["visao"]["tokens_entrada"] == 0


def test_modelo_mal_configurado_vira_aviso_e_o_texto_segue(cliente, monkeypatch):
    def quebra():
        raise ValueError("preço ausente")

    monkeypatch.setattr(main, "criar_linguagem", quebra)
    j = processar(cliente, pdf_com(par("Texto antes. " * 10), figura(1)))
    assert tipos(j) == ["texto"] and j["avisos"]["figuras_com_falha"] == 1 and j["visao"]["modelo"] is None


def test_depois_de_tres_falhas_seguidas_as_demais_nem_sao_tentadas(cliente, monkeypatch):
    chamadas = []

    class Quebrado:
        nome = "q"

        def gerar(self, prompt, imagens=None):
            chamadas.append(1)
            raise NotImplementedError()

    monkeypatch.setattr(main, "criar_linguagem", lambda: Quebrado())
    j = processar(cliente, pdf_com(*[x for i in range(8) for x in (par(f"Texto {i}. " * 5), figura(i))]))
    assert j["avisos"]["figuras_com_falha"] == 8
    assert len(chamadas) < 8


def test_figuras_acima_do_limite_sao_contadas_e_nao_descritas(cliente):
    j = processar(cliente, pdf_com(*[x for i in range(4) for x in (par(f"Texto {i}. " * 5), figura(i))]), max_figuras="2")
    assert tipos(j).count("figura") == 2 and j["avisos"]["figuras_acima_do_limite"] == 2


def test_imagem_repetida_em_varias_paginas_e_decoracao_inclusive_a_primeira(cliente):
    itens = []
    for i in range(3):
        itens += [figura(99), par(f"Pagina {i} com texto de estudo. " * 6), PageBreak()]
    itens.insert(0, figura(5))  # uma figura de verdade, que aparece uma vez só
    j = processar(cliente, pdf_com(*itens))
    assert tipos(j).count("figura") == 1 and j["avisos"] == {"figuras_acima_do_limite": 0, "figuras_com_falha": 0}
    assert j["visao"]["tokens_entrada"] > 0, "só a figura única foi ao modelo; o timbre repetido não, nem na primeira vez"


def test_imagem_pequena_nao_e_figura(cliente):
    pequena = ImagemPdf(io.BytesIO(png(5, 60)), width=20, height=20)
    j = processar(cliente, pdf_com(par("Texto de estudo. " * 10), pequena))
    assert "figura" not in tipos(j) and j["avisos"]["figuras_com_falha"] == 0


def test_tabela_grande_e_cortada_com_o_cabecalho_em_cada_pedaco(cliente):
    linhas = [["Codigo", "Descricao"]] + [[f"C{i:04d}", "descricao longa de um item " * 3] for i in range(60)]
    j = processar(cliente, pdf_com(par("Catalogo de itens do almoxarifado. " * 6), tabela_pdf(linhas)))
    partes = [t["conteudo"] for t in j["trechos"] if t["tipo_conteudo"] == "tabela"]
    assert len(partes) > 1 and all(p.startswith("| Codigo | Descricao |\n| --- | --- |") for p in partes)
    assert sum(p.count("| C0") for p in partes) == 60


def test_docx_com_tabela_e_imagem(cliente):
    d = Document()
    d.add_paragraph("Introducao do documento com texto suficiente. " * 6)
    t = d.add_table(rows=2, cols=2)
    for i, r in enumerate(t.rows):
        for j, c in enumerate(r.cells):
            c.text = ["Item", "Valor", "Agua", "10"][i * 2 + j]
    d.add_picture(io.BytesIO(png(7)), width=Inches(1.5))
    d.add_paragraph("Fim do documento com texto suficiente. " * 6)
    buf = io.BytesIO()
    d.save(buf)
    j = processar(cliente, buf.getvalue())
    assert tipos(j) == ["texto", "tabela", "figura", "texto"]
    assert j["trechos"][1]["conteudo"] == "| Item | Valor |\n| --- | --- |\n| Agua | 10 |"
    assert j["avisos"]["figuras_com_falha"] == 0


def test_docx_com_imagem_ilegivel_conta_falha(cliente):
    d = Document()
    d.add_paragraph("Texto do documento com conteudo suficiente. " * 6)
    d.add_picture(io.BytesIO(png(3)), width=Inches(1))
    buf = io.BytesIO()
    d.save(buf)
    dados = buf.getvalue()
    # troca os bytes do PNG dentro do pacote por lixo: a imagem existe, mas não abre
    import zipfile

    entrada = io.BytesIO(dados)
    saida = io.BytesIO()
    with zipfile.ZipFile(entrada) as zin, zipfile.ZipFile(saida, "w") as zout:
        for item in zin.infolist():
            conteudo = zin.read(item.filename)
            zout.writestr(item, b"nao e uma imagem" if item.filename.startswith("word/media/") else conteudo)
    j = processar(cliente, saida.getvalue())
    assert "figura" not in tipos(j) and j["avisos"]["figuras_com_falha"] == 1


def test_tabela_de_uma_coluna_ou_linha_e_diagramacao_e_continua_texto():
    assert extracao._tabela_markdown([["so uma coluna"], ["outra"]]) is None
    assert extracao._tabela_markdown([["a", "b"]]) is None
    assert extracao._tabela_markdown([["a|b", None], ["1", "2"]]) == "| a\\|b |  |\n| --- | --- |\n| 1 | 2 |"


def test_pdf_cheio_de_tabelas_e_figuras_mede_tempo_e_respeita_o_limite(cliente):
    paginas = 50
    itens = []
    for i in range(paginas):
        itens += [par(f"Capitulo {i} com bastante texto de estudo sobre o assunto. " * 12), tabela_pdf([["Campo", "Valor"]] + [[f"c{i}{k}", str(k * i)] for k in range(8)]), figura(1000 + i), PageBreak()]
    dados = pdf_com(*itens)
    t0 = time.perf_counter()
    j = processar(cliente, dados)
    seg = time.perf_counter() - t0
    print(f"MEDIDO pdf de {paginas} paginas ({len(dados) // 1024} KiB) com {paginas} tabelas e {paginas} figuras: {seg:.1f} s, {len(j['trechos'])} trechos")
    assert j["paginas"] == paginas
    assert tipos(j).count("tabela") == paginas
    assert tipos(j).count("figura") == 30 and j["avisos"]["figuras_acima_do_limite"] == 20
    assert seg < 120


def test_divisao_leva_o_titulo_final_junto_da_tabela_e_deixa_o_texto_sem_ele():
    from nexo_processamento.divisao import dividir
    from nexo_processamento.extracao import Bloco

    trechos = dividir([Bloco("Texto curto de abertura.", 1), Bloco("Tabela 2 - Populacoes", 1, True, 2), Bloco("| a | b |\n| --- | --- |\n| 1 | 2 |", 1, tipo="tabela"), Bloco("Depois da tabela. " * 5, 1)])
    assert [t.tipo_conteudo for t in trechos] == ["texto", "tabela", "texto"]
    assert "Tabela 2" not in trechos[0].conteudo
    assert trechos[1].conteudo.startswith("Tabela 2 - Populacoes\n\n| a | b |") and trechos[1].titulo == "Tabela 2 - Populacoes" and trechos[1].nivel == 2


def test_inspecao_de_docx_nao_decodifica_figuras(monkeypatch):
    d = Document()
    d.add_paragraph("Texto do documento com conteudo suficiente. " * 6)
    d.add_picture(io.BytesIO(png(11)), width=Inches(1))
    buf = io.BytesIO()
    d.save(buf)
    monkeypatch.setattr(extracao, "_png", lambda *_: (_ for _ in ()).throw(AssertionError("não devia decodificar na inspeção")))
    assert extracao.contar_paginas(buf.getvalue())[0] == "docx"


def test_docx_com_imagem_pequena_nao_e_figura_nem_falha(cliente):
    d = Document()
    d.add_paragraph("Texto do documento com conteudo suficiente. " * 6)
    d.add_picture(io.BytesIO(png(4, 30)), width=Inches(1))
    buf = io.BytesIO()
    d.save(buf)
    j = processar(cliente, buf.getvalue())
    assert "figura" not in tipos(j) and j["avisos"] == {"figuras_acima_do_limite": 0, "figuras_com_falha": 0}


def test_docx_com_imagem_de_milhoes_de_pixels_e_ignorada_sem_decodificar(cliente):
    grande = Image.new("RGB", (7000, 7000), (255, 255, 255))  # 49 milhões de pixels em poucos KiB de PNG
    buf0 = io.BytesIO()
    grande.save(buf0, format="PNG")
    d = Document()
    d.add_paragraph("Texto do documento com conteudo suficiente. " * 6)
    d.add_picture(io.BytesIO(buf0.getvalue()), width=Inches(1))
    buf = io.BytesIO()
    d.save(buf)
    j = processar(cliente, buf.getvalue())
    assert "figura" not in tipos(j) and j["avisos"]["figuras_com_falha"] == 1


def test_pdf_com_figura_de_pagina_gigante_nao_renderiza_e_conta_falha(cliente):
    from reportlab.pdfgen import canvas

    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=(9000, 9000))  # página de 9000 pt: renderizada a 110 dpi daria ~190 milhões de pixels
    c.setFont("Helvetica", 12)
    c.drawString(100, 8900, "Texto de estudo numa pagina muito grande. " * 3)
    c.drawImage(ImageReader(io.BytesIO(png(8))), 100, 100, width=8500, height=8500)
    c.showPage()
    c.save()
    j = processar(cliente, buf.getvalue())
    assert "figura" not in tipos(j) and j["avisos"]["figuras_com_falha"] == 1


def test_icone_pequeno_depois_do_limite_nao_conta_como_figura_acima_do_limite(cliente):
    d = Document()
    d.add_paragraph("Texto do documento com conteudo suficiente. " * 6)
    for semente, lado in ((21, 160), (22, 160), (23, 30), (24, 30)):  # duas figuras de verdade e dois ícones distintos
        d.add_picture(io.BytesIO(png(semente, lado)), width=Inches(1))
    buf = io.BytesIO()
    d.save(buf)
    j = processar(cliente, buf.getvalue(), max_figuras="1")
    assert tipos(j).count("figura") == 1 and j["avisos"] == {"figuras_acima_do_limite": 1, "figuras_com_falha": 0}


def _pdf_com_cabecalho(paginas: int, cabecalho: bool = True) -> bytes:
    from reportlab.pdfgen import canvas

    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    for i in range(1, paginas + 1):
        if cabecalho:
            c.setFont("Helvetica-Bold", 18)
            c.drawString(120, 800, "UNIVERSIDADE EXEMPLO DO BRASIL")
            c.setFont("Helvetica", 9)
            c.drawString(260, 30, f"- {i} -")
        c.setFont("Helvetica-Bold", 16)
        c.drawString(60, 700, f"Capitulo {i}: assunto unico {i}")
        c.setFont("Helvetica", 11)
        for k in range(6):
            c.drawString(60, 670 - k * 14, f"Pagina {i} linha {k}: texto de estudo sobre o assunto {i} com varias palavras.")
        c.showPage()
    c.save()
    return buf.getvalue()


def test_cabecalho_e_rodape_repetidos_nao_viram_titulo_nem_trecho(cliente):
    j = processar(cliente, _pdf_com_cabecalho(8))
    conteudo = "\n".join(t["conteudo"] for t in j["trechos"])
    assert "UNIVERSIDADE EXEMPLO" not in conteudo and "- 3 -" not in conteudo
    assert all(t["titulo"] is None or "UNIVERSIDADE" not in t["titulo"] for t in j["trechos"])
    assert "Capitulo 5: assunto unico 5" in conteudo, "o conteúdo de cada página continua"


def test_texto_de_margem_que_nao_se_repete_fica(cliente):
    j = processar(cliente, _pdf_com_cabecalho(2))  # só 2 páginas: abaixo do mínimo para ser tratado como cabeçalho
    assert "UNIVERSIDADE EXEMPLO DO BRASIL" in "\n".join(t["conteudo"] for t in j["trechos"])


def tabela_sem_bordas(linhas):
    return Table(linhas, colWidths=[110, 110, 110])  # sem GRID: só o alinhamento das colunas separa as células


def test_tabela_que_atravessa_a_pagina_vira_um_so_trecho_com_um_cabecalho(cliente):
    linhas = [["Codigo", "Item"]] + [[f"C{i:03d}", f"item {i}"] for i in range(80)]
    t = Table(linhas, repeatRows=1)
    t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
    j = processar(cliente, pdf_com(par("Catalogo curto de itens. " * 6), t))
    partes = [x["conteudo"] for x in j["trechos"] if x["tipo_conteudo"] == "tabela"]
    assert len(partes) == 1, "a continuação na página seguinte não vira outra tabela"
    assert partes[0].count("| Codigo | Item |") == 1 and partes[0].count("| C0") == 80


def test_tabela_sem_bordas_alinhada_em_colunas_vira_tabela(cliente):
    linhas = [["Cidade", "Estado", "Habitantes"], ["Curitiba", "Parana", "1900000"], ["Londrina", "Parana", "580000"], ["Maringa", "Parana", "430000"]]
    j = processar(cliente, pdf_com(par("Observe a distribuicao das cidades a seguir. " * 4), tabela_sem_bordas(linhas), par("Depois da tabela segue um comentario sobre os dados. " * 4)))
    assert tipos(j) == ["texto", "tabela", "texto"]
    assert j["trechos"][1]["conteudo"].startswith("| Cidade | Estado | Habitantes |\n| --- | --- | --- |\n| Curitiba | Parana | 1900000 |")
    assert "Londrina" not in j["trechos"][0]["conteudo"] + j["trechos"][2]["conteudo"]


def test_texto_corrido_nao_e_confundido_com_tabela_sem_bordas(cliente):
    texto = " ".join(f"Frase numero {i} que fala de um assunto qualquer com varias palavras." for i in range(40))
    j = processar(cliente, pdf_com(par(texto), par(texto)))
    assert "tabela" not in tipos(j)
