FROM python:3.13-slim
WORKDIR /app
COPY pyproject.toml .
COPY nexo_processamento nexo_processamento
# O serviço lê arquivos de usuários: nunca como root.
RUN pip install --no-cache-dir . && useradd --system --no-create-home --shell /usr/sbin/nologin nexo
USER nexo
EXPOSE 8000
# Processos do servidor (o uvicorn lê esta variável): a extração de PDF usa CPU e, com um só processo, as indexações simultâneas esperam em fila.
ENV WEB_CONCURRENCY=2
CMD ["uvicorn", "nexo_processamento.main:app", "--host", "0.0.0.0", "--port", "8000"]
