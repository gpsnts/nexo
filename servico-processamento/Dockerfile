FROM python:3.13-slim
WORKDIR /app
COPY pyproject.toml .
COPY nexo_processamento nexo_processamento
RUN pip install --no-cache-dir .
EXPOSE 8000
CMD ["uvicorn", "nexo_processamento.main:app", "--host", "0.0.0.0", "--port", "8000"]
