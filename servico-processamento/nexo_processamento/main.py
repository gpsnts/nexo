import hmac
import os

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from typing import Annotated

from pydantic import BaseModel, Field, StringConstraints

from nexo_processamento import extracao, geracao, topicos
from nexo_processamento.config import criar_embedding, criar_linguagem
from nexo_processamento.divisao import dividir
from nexo_processamento.extracao import FormatoInvalido, SemTexto
from nexo_processamento.ia.provedor import ErroProvedor

app = FastAPI(title="Nexo - serviço de processamento")


@app.exception_handler(ErroProvedor)
def _erro_do_provedor(_, e: ErroProvedor):
    """5xx quando vale repetir (o backend repete até 3 vezes); 424 quando não vale (chave, modelo, crédito, pedido): o backend para na hora, com o código."""
    return JSONResponse(status_code=503 if e.retentavel else 424, content={"detail": {"codigo": e.codigo, "mensagem": str(e)}})

LOTE_MAXIMO = 64
TAMANHO_MAXIMO = int(os.getenv("PROCESSAMENTO_TAMANHO_MAXIMO", 25 * 1024 * 1024))
ALVO = int(os.getenv("TRECHO_ALVO", 1500))
MAXIMO = int(os.getenv("TRECHO_MAXIMO", 2000))


def autenticar(x_nexo_segredo: str | None = Header(default=None)):
    """Rede interna + segredo compartilhado com o backend. Sem segredo configurado, nada de interno funciona."""
    esperado = os.getenv("NEXO_PROCESSAMENTO_SEGREDO", "")
    if not esperado:
        raise HTTPException(503, "segredo interno não configurado")
    if not x_nexo_segredo or not hmac.compare_digest(x_nexo_segredo.encode(), esperado.encode()):
        raise HTTPException(401, "não autorizado")


@app.get("/saude")
def saude():
    return {"status": "UP", "modelo_linguagem": criar_linguagem().nome, "modelo_embedding": criar_embedding().nome}


def _ler(arquivo: UploadFile) -> bytes:
    dados = arquivo.file.read(TAMANHO_MAXIMO + 1)
    if len(dados) > TAMANHO_MAXIMO:
        raise HTTPException(413, "arquivo grande demais")
    return dados


@app.post("/interno/inspecionar", dependencies=[Depends(autenticar)])
def inspecionar(arquivo: UploadFile = File(...)):
    dados = _ler(arquivo)
    try:
        tipo, paginas, caracteres = extracao.contar_paginas(dados)
    except FormatoInvalido as e:
        raise HTTPException(422, {"codigo": "formato_invalido", "mensagem": str(e)})
    return {"tipo": tipo, "paginas": paginas, "caracteres": caracteres}


@app.post("/interno/processar", dependencies=[Depends(autenticar)])
def processar(arquivo: UploadFile = File(...)):
    dados = _ler(arquivo)
    try:
        doc = extracao.extrair(dados)
    except FormatoInvalido as e:
        raise HTTPException(422, {"codigo": "formato_invalido", "mensagem": str(e)})
    except SemTexto as e:
        raise HTTPException(422, {"codigo": "sem_texto", "mensagem": str(e)})
    trechos = dividir(doc.blocos, ALVO, MAXIMO)
    return {
        "tipo": doc.tipo,
        "paginas": doc.paginas,
        "caracteres": doc.caracteres,
        "trechos": [{"ordem": t.ordem, "numero_pagina": t.numero_pagina, "conteudo": t.conteudo, "titulo": t.titulo, "nivel": t.nivel} for t in trechos],
    }


class PedidoEmbeddings(BaseModel):
    textos: list[str] = Field(min_length=1, max_length=LOTE_MAXIMO)


@app.post("/interno/embeddings", dependencies=[Depends(autenticar)])
def embeddings(p: PedidoEmbeddings):
    modelo = criar_embedding()
    r = modelo.gerar(p.textos)
    return {"vetores": r.vetores, "tokens": r.tokens, "modelo": modelo.nome}


class TrechoResumo(BaseModel):
    ordem: int
    nivel: int | None = None
    titulo: str | None = None
    inicio: str | None = None


class PedidoTopicos(BaseModel):
    trechos: list[TrechoResumo] = Field(min_length=1, max_length=2000)


class TopicoAtual(BaseModel):
    nome: str
    inicio: int
    fim: int
    congelado: bool = False


class PedidoReorganizar(BaseModel):
    trechos: list[TrechoResumo] = Field(min_length=1, max_length=2000)
    atuais: list[TopicoAtual] = Field(min_length=1, max_length=500)


@app.post("/interno/reorganizar", dependencies=[Depends(autenticar)])
def reorganizar_topicos(p: PedidoReorganizar):
    modelo = criar_linguagem()
    pr = topicos.refinar(modelo, [t.model_dump() for t in p.trechos], [a.model_dump() for a in p.atuais])
    return {"topicos": pr.topicos, "tokens_entrada": pr.tokens_entrada, "tokens_saida": pr.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/topicos", dependencies=[Depends(autenticar)])
def sugerir_topicos(p: PedidoTopicos):
    modelo = criar_linguagem()
    pr = topicos.sugerir(modelo, [t.model_dump() for t in p.trechos])
    return {"topicos": pr.topicos, "tokens_entrada": pr.tokens_entrada, "tokens_saida": pr.tokens_saida, "modelo": modelo.nome}


class TrechoConteudo(BaseModel):
    ordem: int
    conteudo: str = Field(max_length=20000)


class PedidoResumo(BaseModel):
    topico: str = Field(min_length=1, max_length=200)
    trechos: list[TrechoConteudo] = Field(default_factory=list, max_length=500)


class PedidoQuestoes(PedidoResumo):
    quantidade: int = Field(ge=1, le=10)
    evitar: list[Annotated[str, StringConstraints(max_length=1000)]] = Field(default_factory=list, max_length=60)


class QuestaoVerificar(BaseModel):
    enunciado: str = Field(min_length=1, max_length=2000)
    alternativas: list[Annotated[str, StringConstraints(max_length=1000)]] = Field(min_length=2, max_length=5)
    correta: int = Field(ge=0)


class PedidoVerificar(PedidoResumo):
    questao: QuestaoVerificar


@app.post("/interno/resumo", dependencies=[Depends(autenticar)])
def resumo(p: PedidoResumo):
    modelo = criar_linguagem()
    r = geracao.resumir(modelo, p.topico, [t.model_dump() for t in p.trechos])
    return {"texto": r.texto, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/questoes", dependencies=[Depends(autenticar)])
def questoes(p: PedidoQuestoes):
    modelo = criar_linguagem()
    r = geracao.gerar_questoes(modelo, p.topico, [t.model_dump() for t in p.trechos], p.quantidade, p.evitar)
    return {"questoes": r.questoes, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/verificar", dependencies=[Depends(autenticar)])
def verificar(p: PedidoVerificar):
    if p.questao.correta >= len(p.questao.alternativas):
        raise HTTPException(422, {"codigo": "dados_invalidos", "mensagem": "índice da correta fora das alternativas"})
    modelo = criar_linguagem()
    q = p.questao
    r = geracao.verificar(modelo, p.topico, [t.model_dump() for t in p.trechos], q.enunciado, q.alternativas, q.correta)
    return {"sustentada": r.sustentada, "unica_correta": r.unica_correta, "ordens": r.ordens, "motivo": r.motivo,
            "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}
