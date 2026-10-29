"""API interna do serviço de processamento (FastAPI). Só o backend a chama, com o segredo compartilhado; o serviço não acessa o banco."""
import hmac
import os

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from typing import Annotated, Literal

from pydantic import BaseModel, Field, StringConstraints

from nexo_processamento import extracao, geracao, topicos
from nexo_processamento.config import criar_embedding, criar_linguagem
from nexo_processamento.divisao import dividir
from nexo_processamento.extracao import FormatoInvalido, SemTexto
from nexo_processamento.ia.provedor import ErroProvedor

app = FastAPI(title="Nexo - serviço de processamento")


@app.exception_handler(ErroProvedor)
def _erro_do_provedor(_, e: ErroProvedor):
    """5xx quando vale repetir (o backend repete até 3 vezes); 424 quando não vale (chave, modelo, crédito, pedido): o backend para na hora, com o código.

    Args:
        _: requisição (não usada).
        e: erro do provedor de IA.

    Returns:
        JSONResponse 503 (repetível) ou 424, com `detail.codigo` e `detail.mensagem`.
    """
    return JSONResponse(status_code=503 if e.retentavel else 424, content={"detail": {"codigo": e.codigo, "mensagem": str(e)}})

LOTE_MAXIMO = 64
TAMANHO_MAXIMO = int(os.getenv("PROCESSAMENTO_TAMANHO_MAXIMO", 25 * 1024 * 1024))
ALVO = int(os.getenv("TRECHO_ALVO", 1500))
MAXIMO = int(os.getenv("TRECHO_MAXIMO", 2000))


def autenticar(x_nexo_segredo: str | None = Header(default=None)):
    """Rede interna + segredo compartilhado com o backend. Sem segredo configurado, nada de interno funciona.

    Args:
        x_nexo_segredo: valor do cabeçalho `X-Nexo-Segredo`.

    Raises:
        HTTPException: 503 sem segredo configurado; 401 se o cabeçalho faltar ou não bater.
    """
    esperado = os.getenv("NEXO_PROCESSAMENTO_SEGREDO", "")
    if not esperado:
        raise HTTPException(503, "segredo interno não configurado")
    if not x_nexo_segredo or not hmac.compare_digest(x_nexo_segredo.encode(), esperado.encode()):
        raise HTTPException(401, "não autorizado")


@app.get("/saude")
def saude():
    """Verificação de saúde.

    Returns:
        JSON com `status` e os nomes dos modelos de linguagem e de embedding em uso.
    """
    return {"status": "UP", "modelo_linguagem": criar_linguagem().nome, "modelo_embedding": criar_embedding().nome}


def _ler(arquivo: UploadFile) -> bytes:
    """Lê o arquivo enviado respeitando o tamanho máximo.

    Args:
        arquivo: upload recebido.

    Returns:
        Os bytes do arquivo.

    Raises:
        HTTPException: 413 se passar do tamanho máximo.
    """
    dados = arquivo.file.read(TAMANHO_MAXIMO + 1)
    if len(dados) > TAMANHO_MAXIMO:
        raise HTTPException(413, "arquivo grande demais")
    return dados


@app.post("/interno/inspecionar", dependencies=[Depends(autenticar)])
def inspecionar(arquivo: UploadFile = File(...)):
    """Conta páginas e caracteres sem extrair o conteúdo (valida o limite antes de indexar).

    Args:
        arquivo: PDF, DOCX ou TXT.

    Returns:
        JSON com `tipo`, `paginas` e `caracteres`.

    Raises:
        HTTPException: 413 grande demais; 422 formato inválido.
    """
    dados = _ler(arquivo)
    try:
        tipo, paginas, caracteres = extracao.contar_paginas(dados)
    except FormatoInvalido as e:
        raise HTTPException(422, {"codigo": "formato_invalido", "mensagem": str(e)})
    return {"tipo": tipo, "paginas": paginas, "caracteres": caracteres}


@app.post("/interno/processar", dependencies=[Depends(autenticar)])
def processar(arquivo: UploadFile = File(...)):
    """Extrai o texto e divide em trechos.

    Args:
        arquivo: PDF, DOCX ou TXT.

    Returns:
        JSON com `tipo`, `paginas`, `caracteres` e a lista de `trechos` (ordem, página, conteúdo, título, nível).

    Raises:
        HTTPException: 413 grande demais; 422 formato inválido ou sem texto.
    """
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
    """Pedido de embeddings.

    Attributes:
        textos: de 1 a 64 textos.
    """
    textos: list[str] = Field(min_length=1, max_length=LOTE_MAXIMO)


@app.post("/interno/embeddings", dependencies=[Depends(autenticar)])
def embeddings(p: PedidoEmbeddings):
    """Gera embeddings de um lote de textos.

    Args:
        p: pedido validado.

    Returns:
        JSON com `vetores`, `tokens` e `modelo`.
    """
    modelo = criar_embedding()
    r = modelo.gerar(p.textos)
    return {"vetores": r.vetores, "tokens": r.tokens, "modelo": modelo.nome}


class TrechoResumo(BaseModel):
    """Resumo de um trecho para propor tópicos (sem o texto inteiro).

    Attributes:
        ordem: posição do trecho no material.
        nivel: nível do título, se houver.
        titulo: título do trecho, se houver.
        inicio: primeiras palavras do trecho.
    """
    ordem: int
    nivel: int | None = None
    titulo: str | None = None
    inicio: str | None = None


class PedidoTopicos(BaseModel):
    """Pedido de sugestão de tópicos.

    Attributes:
        trechos: resumos dos trechos do material.
    """
    trechos: list[TrechoResumo] = Field(min_length=1, max_length=2000)


class TopicoAtual(BaseModel):
    """Tópico existente, enviado para reorganização.

    Attributes:
        nome: nome do tópico.
        inicio: ordem do primeiro trecho.
        fim: ordem do último trecho.
        congelado: se já tem resumo ou questões (não pode mudar de faixa).
    """
    nome: str
    inicio: int
    fim: int
    congelado: bool = False


class PedidoReorganizar(BaseModel):
    """Pedido de reorganização dos tópicos com IA.

    Attributes:
        trechos: resumos dos trechos.
        atuais: tópicos atuais.
    """
    trechos: list[TrechoResumo] = Field(min_length=1, max_length=2000)
    atuais: list[TopicoAtual] = Field(min_length=1, max_length=500)


@app.post("/interno/reorganizar", dependencies=[Depends(autenticar)])
def reorganizar_topicos(p: PedidoReorganizar):
    """Pede ao modelo uma nova divisão em tópicos, preservando os congelados.

    Args:
        p: pedido validado.

    Returns:
        JSON com `topicos`, tokens e `modelo`.
    """
    modelo = criar_linguagem()
    pr = topicos.refinar(modelo, [t.model_dump() for t in p.trechos], [a.model_dump() for a in p.atuais])
    return {"topicos": pr.topicos, "tokens_entrada": pr.tokens_entrada, "tokens_saida": pr.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/topicos", dependencies=[Depends(autenticar)])
def sugerir_topicos(p: PedidoTopicos):
    """Pede ao modelo a divisão do material em tópicos.

    Args:
        p: pedido validado.

    Returns:
        JSON com `topicos`, tokens e `modelo`.
    """
    modelo = criar_linguagem()
    pr = topicos.sugerir(modelo, [t.model_dump() for t in p.trechos])
    return {"topicos": pr.topicos, "tokens_entrada": pr.tokens_entrada, "tokens_saida": pr.tokens_saida, "modelo": modelo.nome}


class TrechoConteudo(BaseModel):
    """Trecho com o texto, usado como contexto das gerações.

    Attributes:
        ordem: posição do trecho no material.
        conteudo: texto do trecho.
    """
    ordem: int
    conteudo: str = Field(max_length=20000)


class PedidoResumo(BaseModel):
    """Pedido com tópico e trechos de contexto.

    Attributes:
        topico: nome do tópico.
        trechos: trechos do material (vazio = tópico livre).
        orientacao: orientação opcional do estudante (foco, banca, estilo); só resumo, questões e chat a usam.
    """
    topico: str = Field(min_length=1, max_length=200)
    trechos: list[TrechoConteudo] = Field(default_factory=list, max_length=500)
    orientacao: str | None = Field(default=None, max_length=500)


class PedidoQuestoes(PedidoResumo):
    """Pedido de geração de questões.

    Attributes:
        quantidade: de 1 a 10.
        dificuldade: facil, media ou dificil.
        evitar: enunciados já existentes, para não repetir.
    """
    quantidade: int = Field(ge=1, le=10)
    dificuldade: Literal["facil", "media", "dificil"] = "media"
    evitar: list[Annotated[str, StringConstraints(max_length=1000)]] = Field(default_factory=list, max_length=60)


class QuestaoVerificar(BaseModel):
    """Questão a verificar ou a dar dica.

    Attributes:
        enunciado: texto da pergunta.
        alternativas: de 2 a 5 alternativas.
        correta: índice da alternativa correta.
    """
    enunciado: str = Field(min_length=1, max_length=2000)
    alternativas: list[Annotated[str, StringConstraints(max_length=1000)]] = Field(min_length=2, max_length=5)
    correta: int = Field(ge=0)


class PedidoVerificar(PedidoResumo):
    """Pedido de verificação de fidelidade.

    Attributes:
        questao: questão a verificar contra os trechos.
    """
    questao: QuestaoVerificar


@app.post("/interno/resumo", dependencies=[Depends(autenticar)])
def resumo(p: PedidoResumo):
    """Gera o resumo do tópico.

    Args:
        p: pedido validado.

    Returns:
        JSON com `texto`, tokens e `modelo`.
    """
    modelo = criar_linguagem()
    r = geracao.resumir(modelo, p.topico, [t.model_dump() for t in p.trechos], p.orientacao)
    return {"texto": r.texto, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/questoes", dependencies=[Depends(autenticar)])
def questoes(p: PedidoQuestoes):
    """Gera questões do tópico.

    Args:
        p: pedido validado.

    Returns:
        JSON com `questoes`, tokens e `modelo`.
    """
    modelo = criar_linguagem()
    r = geracao.gerar_questoes(modelo, p.topico, [t.model_dump() for t in p.trechos], p.quantidade, p.evitar, p.dificuldade, p.orientacao)
    return {"questoes": r.questoes, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/verificar", dependencies=[Depends(autenticar)])
def verificar(p: PedidoVerificar):
    """Verifica se a questão é sustentada pelos trechos e tem uma única correta.

    Args:
        p: pedido validado.

    Returns:
        JSON com `sustentada`, `unica_correta`, `ordens`, `motivo`, tokens e `modelo`.

    Raises:
        HTTPException: 422 se o índice da correta estiver fora das alternativas.
    """
    if p.questao.correta >= len(p.questao.alternativas):
        raise HTTPException(422, {"codigo": "dados_invalidos", "mensagem": "índice da correta fora das alternativas"})
    modelo = criar_linguagem()
    q = p.questao
    r = geracao.verificar(modelo, p.topico, [t.model_dump() for t in p.trechos], q.enunciado, q.alternativas, q.correta)
    return {"sustentada": r.sustentada, "unica_correta": r.unica_correta, "ordens": r.ordens, "motivo": r.motivo,
            "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


class PedidoDica(PedidoResumo):
    """Pedido de dica para uma questão.

    Attributes:
        questao: questão para a qual dar a dica.
    """
    questao: QuestaoVerificar


class Fala(BaseModel):
    """Mensagem de um chat.

    Attributes:
        papel: usuario ou assistente.
        conteudo: texto da mensagem.
    """
    papel: Literal["usuario", "assistente"]
    conteudo: str = Field(min_length=1, max_length=4000)


class PedidoChat(PedidoResumo):
    """Pedido de resposta do chat do tópico.

    Attributes:
        historico: conversa anterior (até 40 falas).
        pergunta: nova pergunta do estudante.
    """
    historico: list[Fala] = Field(default_factory=list, max_length=40)
    pergunta: str = Field(min_length=1, max_length=2000)


@app.post("/interno/dica", dependencies=[Depends(autenticar)])
def dica(p: PedidoDica):
    """Gera uma dica leve, sem revelar a resposta.

    Args:
        p: pedido validado.

    Returns:
        JSON com `texto`, tokens e `modelo`.

    Raises:
        HTTPException: 422 se o índice da correta estiver fora das alternativas.
    """
    if p.questao.correta >= len(p.questao.alternativas):
        raise HTTPException(422, {"codigo": "dados_invalidos", "mensagem": "índice da correta fora das alternativas"})
    modelo = criar_linguagem()
    q = p.questao
    r = geracao.dar_dica(modelo, p.topico, [t.model_dump() for t in p.trechos], q.enunciado, q.alternativas, q.correta)
    return {"texto": r.texto, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


@app.post("/interno/chat", dependencies=[Depends(autenticar)])
def chat(p: PedidoChat):
    """Responde a pergunta com base nos trechos e no histórico.

    Args:
        p: pedido validado.

    Returns:
        JSON com `texto`, tokens e `modelo`.
    """
    modelo = criar_linguagem()
    r = geracao.conversar(modelo, p.topico, [t.model_dump() for t in p.trechos], [f.model_dump() for f in p.historico], p.pergunta, p.orientacao)
    return {"texto": r.texto, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}


class PedidoNome(BaseModel):
    """Pedido de nome para o estudo.

    Attributes:
        topicos: nomes dos tópicos.
        trechos: alguns trechos de amostra.
    """
    topicos: list[Annotated[str, StringConstraints(max_length=300)]] = Field(default_factory=list, max_length=200)
    trechos: list[TrechoConteudo] = Field(default_factory=list, max_length=10)


@app.post("/interno/nome", dependencies=[Depends(autenticar)])
def nome_do_estudo(p: PedidoNome):
    """Sugere um nome curto para o estudo.

    Args:
        p: pedido validado.

    Returns:
        JSON com `texto`, tokens e `modelo`.

    Raises:
        HTTPException: 502 se o modelo não sugerir nome.
    """
    modelo = criar_linguagem()
    r = geracao.sugerir_nome(modelo, p.topicos, [t.model_dump() for t in p.trechos])
    if not r.texto:
        raise HTTPException(502, {"codigo": "resposta_vazia", "mensagem": "o modelo não sugeriu um nome"})
    return {"texto": r.texto, "tokens_entrada": r.tokens_entrada, "tokens_saida": r.tokens_saida, "modelo": modelo.nome}
