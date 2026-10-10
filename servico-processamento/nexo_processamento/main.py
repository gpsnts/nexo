from fastapi import FastAPI

from nexo_processamento.config import criar_embedding, criar_linguagem

app = FastAPI(title="Nexo - serviço de processamento")


@app.get("/saude")
def saude():
    return {"status": "UP", "modelo_linguagem": criar_linguagem().nome, "modelo_embedding": criar_embedding().nome}
