# Plataforma Nexo

Tutor de estudo guiado com IA. O estudante envia um material próprio (PDF, DOCX ou TXT) ou informa um tópico livre; o sistema gera **resumos e questões fiéis ao material**, verifica a fidelidade de cada questão antes de guardá-la, agenda revisões por repetição espaçada, permite escolher quais tópicos revisar e mostra a evolução do desempenho.

Projeto de TCC (ULBRA, Análise e Desenvolvimento de Sistemas)

## Estado atual

Implementados: cadastro e login (JWT com refresh), perfil e sessões, isolamento por conta (RLS), envio e indexação de material com barra de progresso, divisão em tópicos, tópicos livres com orientações opcionais, resumo, questões e verificação de fidelidade, dica e chat com o tutor, revisão por repetição espaçada, painel de desempenho, pastas e cota semanal de uso de IA. Montagem de provas e extensões finais ainda não estão prontas.

O modelo de linguagem real (OpenAI) ainda **não** está integrado: por padrão o serviço usa um provedor falso que devolve conteúdo de demonstração, o suficiente para ver o fluxo inteiro funcionando.

## Arquitetura

Três serviços e um banco, em um repositório:

- `backend/`: Kotlin + Spring Boot. API, autenticação, regras de negócio. **É o único que lê e grava no banco.**
- `servico-processamento/`: Python (FastAPI). Extrai texto, divide em trechos, gera embeddings e chama o modelo de linguagem. Não acessa o banco.
- `frontend/`: React + Vite, responsivo (celular, tablet e desktop).
- `db/`: PostgreSQL 16+ com pgvector, migrações Flyway (`V1` a `V7`) e o validador do esquema.
- `e2e/`: testes de ponta a ponta em navegador (Playwright) contra o `docker compose`.

O isolamento entre contas é feito no próprio banco (Row-Level Security): o backend conecta com um papel sem privilégio de dono e informa o usuário em cada transação.

## Como rodar

Requisitos: Docker e Docker Compose.

```bash
cp .env.example .env        # ajuste os segredos; o padrão é ambiente de desenvolvimento com provedores falsos
docker compose up -d --build --quiet-pull
```

- Aplicação: http://localhost:3000 (o `/api` é encaminhado ao backend).
- Em desenvolvimento o e-mail é "falso": os links de confirmação saem no log (`docker compose logs backend | grep token=`).
- Recomeçar do zero (apaga o banco): `docker compose down -v`.

## Testes

```bash
cd backend && ./gradlew test -q                                    # precisa de Docker (Testcontainers)
cd servico-processamento && python3 -m venv .venv && .venv/bin/pip install -q -e '.[dev]' && .venv/bin/pytest -q
cd frontend && npm ci && npm test -- --run && npm run build
# esquema do banco (banco vazio, recém-migrado)
docker compose exec -T db psql -U nexo_dono -d nexo -v ON_ERROR_STOP=1 < db/validacao/verificar_esquema.sql | grep -c PASS   # 62
# ponta a ponta (compose no ar; o limite por IP das rotas públicas precisa estar alto)
NEXO_LIMITE_PUBLICO_REQUISICOES=100000 docker compose up -d
cd e2e && npm ci && npx playwright install chromium firefox && npx playwright test
```

O CI (`.github/workflows/ci.yml`) roda tudo isso. Os E2E rodam em Chrome, Firefox e perfil de celular; localmente só o Chrome e o celular foram executados até agora.

## Privacidade

Para gerar resumos, questões e divisões em tópicos, o sistema **envia trechos do material do usuário a um provedor de IA externo** (a OpenAI, na configuração prevista). As telas de cadastro, envio, tópico e revisão avisam isso. Dentro do Nexo o material é isolado por conta e pode ser excluído; o que o provedor receber segue as regras dele, por isso use material que você pode compartilhar e confira os termos de uso de dados da API antes de usar com outras pessoas. O sistema ainda não registra consentimento do usuário.

## Provedores de IA

O serviço de processamento lê `MODELO_EMBEDDING_PROVEDOR` e `MODELO_LINGUAGEM_PROVEDOR` (`falso` por padrão). O embedding real da OpenAI está implementado (`openai`, com `OPENAI_API_KEY`) e o modelo de linguagem real também, **mas ainda não foi executado contra a API real**: só contra um servidor que imita o formato. Em produção (`NEXO_AMBIENTE=producao`) a aplicação recusa subir com e-mail falso e recusa operações de IA se o serviço informar provedor falso.

### Usar o Gemini (nível gratuito, para desenvolvimento)

Alternativa sem custo enquanto o projeto não está em produção. **Use só material seu**: no nível gratuito o Google pode usar o conteúdo enviado para melhorar seus produtos.

1. Crie a chave em **https://aistudio.google.com/apikey** (conta Google). No mesmo site, em AI Studio, confira os limites do nível gratuito e os modelos liberados para você (a documentação do Google não publica os números).
2. No `.env`:
   ```
   MODELO_LINGUAGEM_PROVEDOR=gemini
   MODELO_EMBEDDING_PROVEDOR=gemini
   GEMINI_API_KEY=...
   MODELO_LINGUAGEM_NOME=gemini-3.1-flash-lite      # ou gemini-3.5-flash-lite
   MODELO_EMBEDDING_NOME=gemini-embedding-2        # vazio também vale; alternativas: gemini-embedding-001, gemini-embedding-2-preview
   PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO=0
   PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO=0
   PRECO_EMBEDDING_USD_POR_MILHAO=0
   ```
   Os dois provedores são independentes: dá para usar o Gemini num e a OpenAI no outro.
3. Trocar o provedor do embedding exige reindexar: `docker compose down -v && docker compose up -d --build` e envie os materiais de novo.
4. Teste de fumaça: `cd servico-processamento && GEMINI_API_KEY=... MODELO_LINGUAGEM_NOME=... .venv/bin/pytest -m real -k gemini -q`.
5. Em produção volte à OpenAI trocando `gemini` por `openai`, a chave e os preços reais.

Ainda não foi executado contra a API real do Google, só contra um servidor que imita o formato documentado.

### Escolher o modelo de embedding

O padrão é o `text-embedding-3-small`. Para usar o `text-embedding-3-large`, no `.env`: `MODELO_EMBEDDING_PROVEDOR=openai`, `MODELO_EMBEDDING_NOME=text-embedding-3-large` e `PRECO_EMBEDDING_USD_POR_MILHAO=<valor da página de preços>` (sem o preço o modelo é recusado). A dimensão continua 1536 (a coluna do banco): a API reduz o vetor do `large` com o parâmetro `dimensions`. Trocar de modelo exige reindexar tudo; em desenvolvimento, `docker compose down -v` e reenvie os materiais.

### Testar o modelo de linguagem real (conta gratuita serve para o "Reorganizar com IA")

1. Em platform.openai.com crie uma chave de API e abra **Settings > Limits**: lá aparecem os modelos disponíveis e os limites da sua conta. Escolha um modelo pequeno e anote o nome exato.
2. No `.env`:
   ```
   MODELO_LINGUAGEM_PROVEDOR=openai
   OPENAI_API_KEY=sk-...
   MODELO_LINGUAGEM_NOME=<nome exato do modelo>
   PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO=0      # conta gratuita: 0 e 0 (o limite de gasto não age, mas não há cobrança)
   PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO=0        # conta paga: os valores da página de preços
   MODELO_EMBEDDING_PROVEDOR=falso                      # o "Reorganizar" não usa embeddings; deixe falso para não gastar nem esbarrar nos limites
   ```
   Sem os dois preços a aplicação **recusa** operações com o modelo real (o limite de gasto precisa do custo).
3. `docker compose up -d --build`.
4. Teste de fumaça do adaptador (1 chamada): `cd servico-processamento && OPENAI_API_KEY=sk-... MODELO_LINGUAGEM_NOME=... .venv/bin/pytest -m real -k linguagem -q`.
5. Pela tela: envie um material pequeno (poucas páginas) e use **Reorganizar com IA**. É uma chamada por clique. Gerar resumo e questões gasta mais chamadas (cerca de 11 para 5 questões, contando verificações e embeddings), e planos gratuitos costumam ter limites baixos por minuto.
6. Se algo falhar, a tela já diz o motivo (limite do plano, sem crédito, chave, modelo ou preço); o detalhe está em `docker compose logs backend processamento`.

Atenção: o teste envia trechos do seu material à OpenAI (o "Reorganizar" manda só o início de cada trecho).
