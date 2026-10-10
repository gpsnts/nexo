# Modelo de dados (dicionário de dados)

Fonte da verdade: `docs/projeto.pdf` (seção 2) e o esquema executável `db/migrations/V1__esquema_inicial.sql`. O diagrama está em `docs/diagramas/ER_-_Plataforma_Nexo.png` (fonte editável: `.drawio`).

## Regras que valem para todas as tabelas

- Todas as tabelas possuem criado_em (timestamptz, obrigatória: data e hora de criação do registro, preenchida pelo banco) e atualizado_em (timestamptz, obrigatória: igual a criado_em na criação e renovada pelo banco, por gatilho, a cada alteração da linha).
- Todas as tabelas, exceto usuario, possuem usuario_id (uuid, obrigatória): dono do registro, chave estrangeira para usuario.
- Todas as tabelas, exceto as três de junção (questao_trecho, prova_assunto e prova_questao), possuem id (uuid, obrigatória): chave primária, gerada pelo backend; o banco não gera identificadores.
- Chaves estrangeiras entre tabelas de dados: em regra, são compostas com usuario_id (exemplo: questao (topico_id, usuario_id) referencia topico (id, usuario_id)), e cada tabela referenciada tem uma restrição de unicidade sobre (id, usuario_id). Assim, o banco impede que um registro referencie dados de outro usuário. Três famílias de chaves estrangeiras não incluem usuario_id e mesmo assim preservam o dono, porque a linha referenciada já pertence a um único usuário: (topico_limite_id, assunto_id) em prova_assunto, (prova_id, questao_id) em resposta e (material_id, ordem_trecho_inicial ou ordem_trecho_final) em topico_fonte.
- Exclusão: a chave estrangeira usuario_id de cada tabela usa ON DELETE CASCADE, de modo que excluir a conta remove todos os dados do usuário. trecho.material_id, topico_fonte.material_id, questao_trecho.questao_id e questao_trecho.trecho_id também usam ON DELETE CASCADE. Todas as demais chaves estrangeiras usam NO ACTION (impedem excluir um registro ainda referenciado; a verificação ocorre no fim da instrução, por isso a exclusão da conta não é bloqueada).
- Valores permitidos, intervalos numéricos e coerência entre colunas (por exemplo, status e datas de conclusão) são impostos por restrições CHECK do banco, e não apenas pelo backend.
- Isolamento por conta: todas as tabelas, exceto usuario, têm Row-Level Security (segurança em nível de linha do PostgreSQL) ativada, com política que só permite ler e gravar linhas cujo usuario_id seja o do usuário da sessão. A aplicação conecta com o papel nexo_app, que não é dono das tabelas nem ignora a segurança por linha; as migrações rodam com outro papel. Em cada transação, o backend executa SET LOCAL app.usuario_id com o identificador do usuário; sem isso, as consultas não retornam linhas e as gravações são recusadas.
- Índices: toda chave estrangeira e toda tabela, exceto usuario, têm índice iniciando na coluna correspondente (a segurança por linha filtra por usuario_id em toda consulta).
- Tabela de histórico do Flyway (`flyway_schema_history`): criada pelo Flyway, sem `usuario_id`, RLS nem gatilho; o `nexo_app` não tem acesso a ela (revogado em `db/callbacks/afterMigrate.sql`).

## Regras que ficam no backend, e não no banco

- Regras aplicadas pelo backend, e não pelo banco: converter o e-mail para minúsculas e gerar o hash Argon2id da senha antes de gravar; impedir a exclusão do assunto padrão "Geral" (o banco permite excluir um assunto vazio); garantir que a divisão proposta no UC05 cubra todo o material, sem lacunas (o banco só impede sobreposição e trechos inexistentes); escolher as questões de uma prova entre os assuntos e até o tópico-limite definidos (UC10); atribuir o mesmo grupo_id às provas separadas geradas no mesmo pedido (UC10); gravar a questão somente após a aprovação (UC09); descartar a questão repetida, isto é, de enunciado com o mesmo hash ou com similaridade acima do limiar configurado em relação às questões do mesmo tópico, calculando o hash sobre o enunciado normalizado (UC07); calcular o agendamento (UC12); conduzir as mudanças de status das operações e das provas; atualizar o progresso_percentual das operações a cada etapa; aplicar o limite de requisições por usuário contando registros de registro_geracao em uma janela de tempo; aplicar o limite de gasto por usuário somando custo_estimado_usd de registro_geracao em uma janela de tempo (ao atingir o limite, a operação não é iniciada e o usuário é avisado); anular a referência ao material nos registros de operação ao excluí-lo (as fontes dos tópicos saem em cascata); remover o arquivo do armazenamento depois da exclusão do material.

Nas tabelas abaixo, "Obrigatória" = Sim quando a coluna não aceita nulo. Além das colunas listadas, valem as colunas e regras comuns acima.

## usuario

Pessoa que possui conta na plataforma.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `nome` | `text` | Sim | Nome de exibição do usuário. |
| `email` | `text` | Sim | E-mail de acesso. O banco exige que esteja em minúsculas. |
| `senha_hash` | `text` | Sim | Hash da senha (algoritmo Argon2id). A senha em texto puro nunca é armazenada. |

- O e-mail é único. O id desta tabela é o identificador do dono usado em todas as demais tabelas (coluna usuario_id).

## material

Arquivo de estudo enviado pelo usuário.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `nome_arquivo` | `text` | Sim | Nome original do arquivo. |
| `tipo_arquivo` | `text` | Sim | Tipo de mídia do arquivo (exemplo: application/pdf). |
| `tamanho_bytes` | `bigint` | Sim | Tamanho do arquivo em bytes (maior ou igual a 0). |
| `quantidade_paginas` | `integer` | Não | Número de páginas (maior ou igual a 1); nulo em formatos sem páginas, como texto simples. |
| `hash_arquivo` | `text` | Sim | Hash SHA-256 do conteúdo, usado para detectar envio repetido. |
| `caminho_arquivo` | `text` | Sim | Local de armazenamento do arquivo no servidor. |
| `status_processamento` | `text` | Sim | Valores permitidos: pendente, processando, pronto, falhou. |

- Único: (usuario_id, hash_arquivo), apenas para materiais cujo status_processamento não é falhou. Assim, um arquivo que falhou pode ser reenviado.

## trecho

Parte do texto de um material, com seu embedding (vetor numérico que representa o significado do texto), usada na busca por similaridade.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `material_id` | `uuid` | Sim | Material de origem. |
| `ordem` | `integer` | Sim | Posição do trecho no material, a partir de 1. |
| `numero_pagina` | `integer` | Não | Página de origem (maior ou igual a 1); nulo em formatos sem páginas. |
| `tipo_conteudo` | `text` | Sim | Valores permitidos: texto, tabela, figura. |
| `conteudo` | `text` | Sim | Em texto, o texto do trecho; em tabela, a tabela convertida em texto (Markdown); em figura, a descrição da imagem gerada por modelo de linguagem com visão (a imagem em si não é armazenada). |
| `embedding` | `vector(1536)` | Sim | Embedding do conteúdo. A dimensão (1536, no exemplo) é a do modelo de embedding adotado e é fixa na coluna. |
| `modelo_embedding` | `text` | Sim | Nome do modelo que gerou o embedding. |

- Único: (material_id, ordem).
- Tabelas e figuras entram na mesma sequência de trechos, na posição em que aparecem no material; por isso as faixas dos tópicos também as incluem.
- A busca por similaridade é exata (sem índice aproximado) e sempre filtrada por material_id, que tem índice.
- Reindexar atualiza embedding e modelo_embedding no lugar. Os trechos não são apagados nem recriados, porque as faixas das fontes dos tópicos (topico_fonte) referenciam a ordem deles.

## assunto

Matéria que agrupa tópicos.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `nome` | `text` | Sim | Nome do assunto. |
| `assunto_padrao` | `boolean` | Sim | Verdadeiro apenas para o assunto "Geral", criado junto com a conta. |

- Único por usuário, sem diferenciar maiúsculas de minúsculas: (usuario_id, nome).
- No máximo um assunto com assunto_padrao verdadeiro por usuário.

## topico

Unidade de estudo: um tema livre ou partes de um ou mais materiais (o vínculo com os materiais fica em topico_fonte).

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `assunto_id` | `uuid` | Sim | Assunto ao qual o tópico pertence. |
| `nome` | `text` | Sim | Nome do tópico. |
| `ordem` | `integer` | Sim | Posição do tópico dentro do assunto, a partir de 1. |

- Único: (assunto_id, ordem). A unicidade é conferida só no fim da transação, o que permite trocar ou deslocar a ordem de vários tópicos de uma vez.
- Tópico sem linhas em topico_fonte é um tópico livre (gerado pelo conhecimento geral do modelo) ou perdeu todos os seus materiais.

## topico_fonte

Fonte de um tópico: a faixa de trechos de um material que alimenta o tópico. Um tópico pode ter várias fontes, de materiais diferentes.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `topico_id` | `uuid` | Sim | Tópico alimentado. |
| `material_id` | `uuid` | Sim | Material de origem. |
| `ordem_trecho_inicial` | `integer` | Sim | Ordem do primeiro trecho do material que pertence ao tópico (maior ou igual a 1). |
| `ordem_trecho_final` | `integer` | Sim | Ordem do último trecho do material que pertence ao tópico (maior ou igual ao inicial). |

- Único: (topico_id, material_id): um tópico tem no máximo uma faixa por material.
- Chaves estrangeiras (material_id, ordem_trecho_inicial) e (material_id, ordem_trecho_final) para trecho (material_id, ordem): a faixa só pode apontar para trechos existentes do mesmo material.
- As faixas de um mesmo material não se sobrepõem, nem entre tópicos diferentes (restrição de exclusão, conferida no fim da transação, o que permite ajustar a fronteira entre dois tópicos em duas atualizações).
- material_id usa ON DELETE CASCADE: excluir o material remove as fontes que apontam para ele; o tópico permanece, sem essa fonte.

## resumo

Resumo gerado para um tópico.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `topico_id` | `uuid` | Sim | Tópico resumido. |
| `conteudo` | `text` | Sim | Texto do resumo. |

- Cada nova geração cria um novo registro; o mais recente (maior criado_em) é o exibido.

## questao

Questão de múltipla escolha aprovada na verificação de fidelidade.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `topico_id` | `uuid` | Sim | Tópico ao qual a questão pertence. |
| `enunciado` | `text` | Sim | Texto da pergunta. |
| `alternativas` | `jsonb` | Sim | Lista ordenada com o texto de cada alternativa, de 2 a 5 itens (jsonb é o tipo do PostgreSQL para dados JSON). |
| `resposta_correta` | `smallint` | Sim | Posição da alternativa correta na lista, a partir de 0. |
| `enunciado_hash` | `text` | Sim | Hash SHA-256 (64 caracteres hexadecimais minúsculos) do enunciado normalizado (minúsculas, sem acentos, sem pontuação e com espaços únicos); detecta repetição exata. |
| `embedding` | `vector(1536)` | Sim | Embedding do enunciado, com a mesma dimensão do embedding dos trechos; detecta repetição por sentido. |
| `modelo_embedding` | `text` | Sim | Nome do modelo que gerou o embedding do enunciado. |

- resposta_correta é maior ou igual a 0 e menor que o número de alternativas.
- Só são gravadas questões aprovadas e não repetidas; as rejeitadas não são armazenadas.
- Único: (topico_id, enunciado_hash): o banco impede duas questões de enunciado idêntico no mesmo tópico.
- Repetição por sentido (regra do backend, o banco não impõe): antes de gravar, o backend compara o embedding do novo enunciado com o das questões do mesmo tópico (busca exata, filtrada por topico_id) e descarta a questão se a similaridade (cosseno) atingir o limiar configurado (padrão sugerido: 0,90).
- Trocar o modelo de embedding exige reindexar também os enunciados das questões (embedding e modelo_embedding atualizados no lugar).

## questao_trecho

Liga cada questão aos trechos que a sustentam (tabela de junção).

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `questao_id` | `uuid` | Sim | Questão. Compõe a chave primária. |
| `trecho_id` | `uuid` | Sim | Trecho de apoio. Compõe a chave primária. |

- Chave primária composta: (questao_id, trecho_id).
- Pode não haver linhas: questões de tópico livre ou cujo material foi excluído não têm trechos de apoio.

## prova

Conjunto de questões montado pelo usuário.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `quantidade_solicitada` | `integer` | Sim | Quantidade de questões pedida (maior ou igual a 1). A quantidade efetiva é o número de linhas em prova_questao. |
| `status` | `text` | Sim | Valores permitidos: gerada, em_andamento, concluida. |
| `concluida_em` | `timestamptz` | Não | Data e hora da conclusão. É preenchida se, e somente se, o status é concluida. |
| `grupo_id` | `uuid` | Não | Identifica o pedido que gerou a prova. Provas separadas geradas no mesmo pedido têm o mesmo grupo_id (gerado pelo backend, sem tabela própria); nulo em prova única. |


## prova_assunto

Assuntos que compõem uma prova e o limite de estudo em cada um (tabela de junção).

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `prova_id` | `uuid` | Sim | Prova. Compõe a chave primária. |
| `assunto_id` | `uuid` | Sim | Assunto. Compõe a chave primária. |
| `topico_limite_id` | `uuid` | Não | Último tópico (na ordem do assunto) incluído na prova. Nulo significa sem limite: todos os tópicos do assunto. |

- Chave primária composta: (prova_id, assunto_id).
- Chave estrangeira (topico_limite_id, assunto_id) para topico (id, assunto_id): garante que o tópico-limite pertence ao assunto. Por isso, um tópico usado como limite não pode ser movido para outro assunto.

## prova_questao

Questões que compõem uma prova (tabela de junção).

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `prova_id` | `uuid` | Sim | Prova. Compõe a chave primária. |
| `questao_id` | `uuid` | Sim | Questão. Compõe a chave primária. |
| `ordem` | `integer` | Sim | Posição da questão na prova, a partir de 1. |

- Chave primária composta: (prova_id, questao_id).
- Único: (prova_id, ordem), conferido só no fim da transação (permite inverter ou deslocar a ordem).

## resposta

Resposta dada pelo usuário a uma questão.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `questao_id` | `uuid` | Sim | Questão respondida. |
| `prova_id` | `uuid` | Não | Prova em que foi respondida. Nulo em resposta avulsa ou de revisão. |
| `alternativa_escolhida` | `smallint` | Sim | Posição da alternativa escolhida, a partir de 0. |
| `correta` | `boolean` | Sim | Verdadeiro quando a alternativa escolhida é a correta; gravado no momento da resposta. |
| `respondida_em` | `timestamptz` | Sim | Data e hora da resposta. |

- Chave estrangeira (prova_id, questao_id) para prova_questao: uma resposta dada em prova só pode ser de questão que faz parte da prova.

## progresso_questao

Agendamento de revisão de uma questão para um usuário (variação do algoritmo SM-2, descrita no UC12).

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `questao_id` | `uuid` | Sim | Questão agendada. |
| `repeticoes` | `integer` | Sim | Acertos consecutivos (maior ou igual a 0); zerado a cada erro. Inicial: 0. |
| `intervalo_dias` | `integer` | Sim | Dias entre a última e a próxima revisão, de 1 a 365. Inicial: 1. |
| `fator_facilidade` | `numeric(3,2)` | Sim | Multiplicador do intervalo. Inicial: 2,50; mínimo 1,30; máximo 2,50. |
| `ultima_revisao` | `date` | Sim | Data da última resposta que alterou o agendamento (fuso horário America/Sao_Paulo). |
| `proxima_revisao` | `date` | Sim | Data da próxima revisão: ultima_revisao mais intervalo_dias. |

- Único: (usuario_id, questao_id). O registro é criado na primeira resposta à questão.
- proxima_revisao é maior ou igual a ultima_revisao.
- A prioridade do tópico não é gravada: é calculada na consulta (questões com revisão vencida, depois taxa de erro).

## registro_geracao

Registro de cada operação que usa o modelo de linguagem ou de embedding. Serve à auditoria, ao custo, à taxa de rejeição, ao limite de requisições por usuário e ao acompanhamento do andamento da operação.

| Coluna | Tipo | Obrigatória | Descrição |
|---|---|---|---|
| `topico_id` | `uuid` | Não | Tópico a que a operação se refere. Nulo quando ainda não há tópico (indexacao_material e sugestao_topicos). |
| `material_id` | `uuid` | Não | Material a que a operação se refere. Preenchido em indexacao_material e sugestao_topicos, que ainda não têm tópico; nulo nas demais. Passa a nulo se o material for excluído. |
| `operacao` | `text` | Sim | Valores permitidos: indexacao_material, sugestao_topicos, resumo, questoes, verificacao_questao. |
| `tipo_entrada` | `text` | Sim | Valores permitidos: material, topico_livre. |
| `modelo_ia` | `text` | Sim | Nome do modelo de linguagem ou de embedding utilizado. |
| `status` | `text` | Sim | Valores permitidos: pendente, em_andamento, concluida, falhou. |
| `tokens_entrada` | `integer` | Sim | Tokens enviados ao modelo (maior ou igual a 0; 0 até a conclusão). |
| `tokens_saida` | `integer` | Sim | Tokens recebidos do modelo (maior ou igual a 0; 0 até a conclusão). |
| `custo_estimado_usd` | `numeric(10,6)` | Sim | Custo estimado, em dólares americanos (maior ou igual a 0). |
| `questoes_aprovadas` | `integer` | Sim | Questões aprovadas na verificação (preenchido na operação verificacao_questao; 0 nas demais). |
| `questoes_rejeitadas` | `integer` | Sim | Questões reprovadas na verificação (preenchido na operação verificacao_questao; 0 nas demais). |
| `questoes_repetidas` | `integer` | Sim | Questões descartadas por repetição (preenchido na operação questoes; 0 nas demais). |
| `progresso_percentual` | `smallint` | Sim | Andamento da operação, de 0 a 100; é 100 quando o status é concluida. Atualizado pelo backend a cada etapa e consultado pelo frontend para exibir a barra de progresso. |
| `finalizada_em` | `timestamptz` | Não | Data e hora do término. É preenchida se, e somente se, o status é concluida ou falhou. |

- Cada operação tem o seu próprio registro. Os tokens e o custo das chamadas feitas dentro de uma operação (por exemplo, obter o conteúdo-base) são somados ao registro da operação que as chamou.
- material_id é opcional e, quando preenchido, forma com usuario_id uma chave estrangeira para material (id, usuario_id). Na exclusão do material, o backend anula essa coluna (UC17); assim, o registro da operação, com seus tokens e custo, é mantido.
- progresso_percentual: na indexacao_material, o backend calcula o percentual por etapas (extração, divisão em trechos, embeddings em lotes e gravação) e o frontend consulta o registro a cada poucos segundos; nas demais operações pode ser calculado pelo número de itens concluídos.
- Taxa de rejeição = soma de questoes_rejeitadas dividida pela soma de (questoes_aprovadas + questoes_rejeitadas), considerando apenas registros da operação verificacao_questao.
