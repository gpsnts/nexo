# Plataforma Nexo

Tutor de estudo guiado com IA. O estudante envia um material próprio (PDF, DOCX ou TXT) ou informa um tópico livre; o sistema divide o conteúdo em tópicos, gera **resumos e questões fiéis ao material**, verifica a fidelidade de cada questão antes de guardá-la, agenda revisões por repetição espaçada, monta provas por escopo ("estudei até este tópico") e mostra a evolução do desempenho.

Projeto de TCC (ULBRA, Análise e Desenvolvimento de Sistemas). Sistema publicado: **https://nexoapp.blog**.

## O que está implementado

- **Conta:** cadastro com confirmação por e-mail, login com JWT de curta duração e refresh rotativo em cookie, bloqueio após falhas, recuperação de senha, perfil, sessões ativas por dispositivo, exportação dos dados e exclusão da conta.
- **Material:** envio de PDF, DOCX e TXT (até 20 MB e 50 páginas) com barra de andamento; extração de texto, **tabelas** (em Markdown) e **figuras** (descritas por um modelo de linguagem com visão); divisão em tópicos revisável; tópico livre com orientações opcionais; pastas, histórico e exclusão de material.
- **Estudo:** resumo e questões por tópico (com verificação de fidelidade, descarte de questões repetidas por similaridade e cancelamento), dica e chat com o tutor, revisão por repetição espaçada (SM-2 adaptado a certo e errado), provas por escopo (inclusive com questões novas geradas na hora) e painel semanal de desempenho.
- **Controle de uso de IA:** cota semanal por conta, em créditos (a tela mostra só a porcentagem e quando renova), mais um limite por hora como proteção.
- **Administração:** painel `/admin` para quem tem o e-mail em `NEXO_ADMIN_EMAILS`: suspender e reativar contas (o corte é imediato), ajustar a cota de cada conta e excluir contas. O administrador vê só cadastro e consumo, nunca o conteúdo dos estudos. O cadastro continua aberto e imediato.
- **Operação:** ambiente de produção com HTTPS automático, backup do banco com teste de restauração, ferramenta de teste do envio de e-mail e e-mails da conta em HTML.

## Arquitetura

Três serviços e um banco, em um repositório:

- `backend/`: Kotlin + Spring Boot. API, autenticação, regras de negócio. **É o único que lê e grava no banco.**
- `servico-processamento/`: Python (FastAPI). Extrai texto, tabelas e figuras, divide em trechos, gera embeddings e chama o modelo de linguagem. **Não acessa o banco.**
- `frontend/`: React + Vite + TypeScript, responsivo (celular, tablet e desktop).
- `db/`: PostgreSQL 16+ com pgvector, migrações Flyway (`V1` a `V15`) e o validador do esquema.
- `e2e/`: testes de ponta a ponta em navegador (Playwright) contra o `docker compose`.
- `docker-compose.yml` (desenvolvimento) e `docker-compose.prod.yml` + `Caddyfile` (produção), `scripts/` (backup e teste de e-mail).

O isolamento entre contas é feito no próprio banco (Row-Level Security): o backend conecta com um papel sem privilégio de dono e informa o usuário em cada transação. A busca por similaridade é exata (sem índice aproximado), sempre filtrada por material e tópico. O modelo de linguagem e o de embedding são duas interfaces separadas e trocáveis; o nome do modelo é gravado nos registros.

## Como rodar (desenvolvimento)

Requisitos: Docker e Docker Compose.

```bash
cp .env.example .env        # o padrão é ambiente de desenvolvimento, com e-mail e IA simulados
docker compose up -d --build --quiet-pull
```

- Aplicação: http://localhost:3000 (o `/api` é encaminhado ao backend).
- O e-mail é simulado: o link de confirmação sai no log do backend (`docker compose logs backend | grep -o 'verificar-email?token=[A-Za-z0-9_-]*' | tail -1`; abra `http://localhost:3000/` seguido do trecho impresso).
- A IA é simulada: o serviço devolve conteúdo de demonstração, o suficiente para ver o fluxo inteiro sem chave de API.
- Para ver o painel de administração, ponha seu e-mail em `NEXO_ADMIN_EMAILS` no `.env` antes de subir e cadastre-se com ele.
- Recomeçar do zero (apaga o banco): `docker compose down -v`.

## Testes

```bash
cd backend && ./gradlew test -q                                    # precisa de Docker (Testcontainers)
cd servico-processamento && python3 -m venv .venv && .venv/bin/pip install -q -e '.[dev]' && .venv/bin/pytest -q
cd frontend && npm ci && npm test -- --run && npm run build
# esquema do banco (banco vazio, recém-migrado)
docker compose exec -T db psql -U nexo_dono -d nexo -v ON_ERROR_STOP=1 < db/validacao/verificar_esquema.sql | grep -c PASS   # 126
# ponta a ponta (compose no ar; o limite por IP das rotas públicas precisa estar alto)
NEXO_LIMITE_PUBLICO_REQUISICOES=100000 docker compose up -d
cd e2e && npm ci && npx playwright install chromium && npx playwright test
```

Os testes de ponta a ponta marcados com `@ia` dependem de IA real e **não rodam por padrão** (`NEXO_E2E_IA=1` os liga). O CI (`.github/workflows/ci.yml`) roda os demais. Localmente os testes de navegador foram executados em Chrome e no perfil de celular; o Firefox roda só no CI.

## Provedores de IA

O serviço de processamento lê `MODELO_EMBEDDING_PROVEDOR` e `MODELO_LINGUAGEM_PROVEDOR` (`falso` por padrão; `openai` ou `gemini` para IA real). Chaves e preços ficam só no `.env`.

### OpenAI (usada em produção)

```
MODELO_EMBEDDING_PROVEDOR=openai
MODELO_LINGUAGEM_PROVEDOR=openai
OPENAI_API_KEY=sk-...
MODELO_LINGUAGEM_NOME=<nome exato do modelo>
PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO=<valor da página de preços>
PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO=<valor da página de preços>
```

Sem os dois preços a aplicação **recusa** operações com o modelo de linguagem real (o controle de uso precisa do custo). O embedding padrão é o `text-embedding-3-small` (1536 dimensões, igual à coluna do banco); o `text-embedding-3-large` funciona em 1536 dimensões com `MODELO_EMBEDDING_NOME=text-embedding-3-large` e `PRECO_EMBEDDING_USD_POR_MILHAO`. Trocar o modelo de embedding exige reindexar tudo. `MODELO_LINGUAGEM_MAX_TOKENS` ajusta o teto de cada resposta (modelos de raciocínio gastam tokens pensando). Um teste de fumaça do adaptador (1 chamada): `cd servico-processamento && OPENAI_API_KEY=sk-... MODELO_LINGUAGEM_NOME=... .venv/bin/pytest -m real -k linguagem -q`.

### Gemini (alternativa com nível gratuito, para desenvolvimento)

**Use só material seu**: no nível gratuito o Google pode usar o conteúdo enviado para melhorar seus produtos. Crie a chave em https://aistudio.google.com/apikey e, no `.env`:

```
MODELO_LINGUAGEM_PROVEDOR=gemini
MODELO_EMBEDDING_PROVEDOR=gemini
GEMINI_API_KEY=...
MODELO_LINGUAGEM_NOME=gemini-3.1-flash-lite
MODELO_EMBEDDING_NOME=gemini-embedding-2
PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO=0
PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO=0
PRECO_EMBEDDING_USD_POR_MILHAO=0
```

Os dois provedores são independentes. Este adaptador foi testado apenas contra um servidor que imita o formato documentado, não contra a API real do Google.

## Privacidade

Para gerar resumos, questões e divisões em tópicos, o sistema **envia trechos do material do usuário a um provedor de IA externo** (a OpenAI, na configuração de produção). As telas de cadastro, envio, tópico e revisão avisam isso. Dentro do Nexo o material é isolado por conta e pode ser excluído a qualquer momento; o que o provedor receber segue as regras dele, por isso use material que você pode compartilhar e confira os termos de uso de dados da API antes de usar com outras pessoas.

## Produção (VPS com Docker Compose e Caddy)

O arquivo `docker-compose.prod.yml` é uma sobreposição do `docker-compose.yml`: com `COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml` no `.env`, todo comando `docker compose` usa os dois. Em produção só o Caddy publica portas (80 e 443): o banco, o backend e o serviço de processamento ficam na rede interna, com reinício automático e verificação de saúde. A aplicação recusa subir com configuração só de desenvolvimento (`NEXO_AMBIENTE=producao`: e-mail simulado, cookie sem `Secure`, endereço sem HTTPS ou segredos de exemplo).

```bash
git clone https://github.com/gpsnts/nexo.git && cd nexo
cp env.producao.exemplo .env && chmod 600 .env    # preencha o .env no servidor (segredos nunca vão para o repositório)
docker compose up -d --build
docker compose ps                                  # todos saudáveis; "migracao" termina e sai
```

Requisitos: Docker Engine com Compose 2.24 ou mais novo, um domínio apontando para o servidor (registro A) e as portas 80 e 443 liberadas.

- **`.env` de produção:** `env.producao.exemplo` lista todas as variáveis e como gerar cada segredo (`openssl rand -base64 48 | tr -d '/+=\n'`). Obrigatórios: `NEXO_DOMINIO`, `NEXO_ACME_EMAIL`, `NEXO_ADMIN_EMAILS`, as senhas do banco, `NEXO_JWT_SEGREDO`, `NEXO_PROCESSAMENTO_SEGREDO` e os dados do SMTP.
- **E-mail:** use um serviço de envio com SMTP (não monte servidor próprio) e autentique o domínio (SPF/DKIM/DMARC). Teste com as mesmas credenciais do backend: `docker compose run --rm testar-smtp seu@email.com` (mostra conexão, criptografia, login e envio, sem exibir a senha).
- **Backup:** `scripts/backup.sh` (dump do banco, arquivos enviados e contagem de linhas por tabela, em `~/nexo-backups`), `scripts/testar-backup.sh` (restaura em um banco descartável e compara as tabelas) e `scripts/restaurar.sh` (restauração real, com confirmação digitada). Agende no `crontab` (por exemplo, o backup às 3h e o teste de restauração aos domingos) e **copie os backups para fora do servidor**.
- **Atualizar:** `scripts/backup.sh`, `git pull` e `docker compose up -d --build`. As migrações do banco rodam sozinhas no serviço `migracao`.
- **Trocar um segredo:** edite o `.env` e recrie o serviço (`docker compose up -d backend`). Trocar `NEXO_JWT_SEGREDO` encerra todas as sessões; a senha do papel `nexo_app` exige rodar `docker compose run --rm migracao` antes de reiniciar o backend.
- **Rede lenta no servidor** (o build falha com `connection timed out`): construa as imagens na sua máquina (`docker compose build`), exporte com `docker save`, envie com `rsync --partial --append-verify` e carregue no servidor com `docker load` antes de `docker compose up -d --no-build --pull never`.
- **Ensaio local da produção:** `docker-compose.ensaio.yml` acrescenta um servidor de e-mail local (Mailpit) e roda com `localhost` no lugar do domínio. Use sempre um projeto Compose separado (`-p nexo-ensaio`) e um arquivo de variáveis fora do repositório, para não tocar no banco de desenvolvimento.

O arquivo `.env` do servidor é a única fonte de segredos; o backend conecta ao banco com o papel `nexo_app` e o serviço de processamento nunca recebe credenciais do banco.
