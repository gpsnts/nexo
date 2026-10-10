# Casos de uso da Plataforma Nexo

Fonte da verdade: `docs/projeto.pdf` (seção 1). Este arquivo repete o texto dos 19 casos de uso em formato fácil de ler pelo Claude Code. RF = requisito funcional, RNF = requisito não funcional (ver `docs/requisitos.pdf`).

Atores: **Usuário** (estudante) e **Provedor do modelo de linguagem** (ator secundário, serviço externo de IA). Casos de uso marcados "(interno)" são executados pelo sistema e incluídos por outros.

## UC01 — Autenticar-se

- **Ator(es):** Usuário
- **Requisitos:** RF01
- **Descrição:** Permite criar uma conta ou entrar com e-mail e senha. Todo o conteúdo do estudante fica isolado na sua conta.
- **Pré-condição:** Nenhuma (ponto de entrada do sistema).

**Fluxo principal**

1. Usuário acessa a tela de login.
2. Usuário informa e-mail e senha.
3. Sistema valida as credenciais.
4. Sistema inicia a sessão e exibe a tela inicial.

**Fluxos alternativos**

- A1. Credenciais inválidas: o sistema exibe mensagem de erro e permite nova tentativa.
- A2. Primeiro acesso: o usuário escolhe "Criar conta" e informa nome, e-mail e senha; o sistema cria a conta (a senha é armazenada apenas como hash, nunca em texto puro) e o assunto padrão "Geral", e inicia a sessão.
- A3. E-mail já cadastrado: o sistema informa e sugere entrar com a conta existente.

**Pós-condição:** Sessão autenticada, com acesso às demais funcionalidades.

## UC02 — Enviar material de estudo

- **Ator(es):** Usuário
- **Requisitos:** RF02, RF04 (via UC04), RF10 (via UC05)
- **Descrição:** O estudante envia um material próprio para que resumos e questões sejam gerados com base especificamente nele. O material é indexado e dividido em tópicos, o que permite estudar e ser avaliado por partes do mesmo material. Um tópico também pode reunir partes de mais de um material.
- **Pré-condição:** Usuário autenticado (UC01).

**Fluxo principal**

1. Usuário acessa "Novo estudo" e escolhe "Enviar material".
2. Usuário seleciona o arquivo e, opcionalmente, o assunto de destino.
3. Sistema valida o formato (aceitos: PDF, DOCX e TXT), o tamanho (máximo de 20 megabytes), o número de páginas (máximo de 50) e se o arquivo é repetido (comparando o hash, código calculado a partir do conteúdo do arquivo).
4. Sistema grava o material com status "pendente" e executa "Indexar material" (UC04).
5. Sistema executa "Sugerir divisão em tópicos" (UC05) e apresenta a proposta.
6. Usuário revisa a proposta (renomeia, une, divide ou ajusta os limites dos tópicos) e confirma.
7. Sistema cria os tópicos e suas fontes (a faixa de trechos de cada tópico neste material) no assunto escolhido (ou em "Geral") e informa que o material está pronto.

**Fluxos alternativos**

- A1. Formato, tamanho ou número de páginas fora dos limites: o sistema informa os limites aceitos e solicita novo envio.
- A2. Arquivo repetido (mesmo hash de um material do usuário cujo status não é "falhou"): o sistema informa e oferece abrir o material existente.
- A3. Usuário recusa a divisão proposta: o material inteiro vira um único tópico.
- A4. Falha no processamento (ex.: arquivo sem texto extraível): o status do material passa a "falhou", o sistema informa o motivo e permite novo envio; no reenvio, o registro anterior com status "falhou" é removido (os registros das operações desse material são mantidos, sem a referência a ele).
- A5. Usuário associa a faixa de trechos a um tópico já existente, em vez de criar um novo: o sistema cria a fonte desse material no tópico, que passa a ter mais de um material, respeitando a regra de não sobreposição das faixas.

**Pós-condição:** Material com status "pronto", indexado e dividido em tópicos.

## UC03 — Informar tópico de estudo

- **Ator(es):** Usuário
- **Requisitos:** RF03
- **Descrição:** Sem material em mãos, o estudante digita livremente o tópico que quer estudar (ex.: "Movimento Retilíneo Uniforme"). O conteúdo é gerado a partir do conhecimento geral do modelo de linguagem.
- **Pré-condição:** Usuário autenticado (UC01).

**Fluxo principal**

1. Usuário acessa "Novo estudo" e escolhe "Informar tópico".
2. Usuário digita o nome do tópico e, opcionalmente, escolhe o assunto.
3. Sistema registra o tópico ao final do assunto escolhido (ou de "Geral"), sem material associado.

**Fluxos alternativos**

- A1. Campo vazio: o sistema impede o envio e solicita o nome do tópico.

**Pós-condição:** Tópico registrado e disponível para geração de resumo e questões.

## UC04 — Indexar material (interno)

- **Ator(es):** — (caso de uso interno, incluído por UC02)
- **Requisitos:** RF04
- **Descrição:** Prepara o material para consulta por similaridade. O serviço de processamento (em Python) extrai o texto, as tabelas e as figuras, divide em trechos e gera os embeddings (vetores numéricos que representam o significado de cada trecho) e devolve o resultado ao backend, que é o único componente que grava no banco de dados (PostgreSQL com a extensão pgvector, usada para busca vetorial).
- **Pré-condição:** Arquivo válido recebido em UC02, com material em status "pendente".

**Fluxo principal**

1. Backend altera o status do material para "processando", cria o registro da operação indexacao_material, ligado ao material (status pendente, depois em_andamento) e envia o arquivo ao serviço de processamento. Durante todo o processamento, o backend atualiza o percentual de andamento do registro (extração, divisão, embeddings em lotes e gravação), que o frontend consulta para exibir uma barra de progresso.
2. Serviço extrai o texto, as tabelas e as figuras, registrando o número da página de cada parte (quando o formato tem páginas). Cada tabela é convertida em texto; cada figura é descrita por um modelo de linguagem com visão, até o limite de figuras por material (padrão: 30, valor configurável); as figuras além do limite são ignoradas e o usuário é informado.
3. Serviço divide o conteúdo em trechos, respeitando a estrutura (parágrafos, seções, tabelas e figuras), classifica cada trecho como texto, tabela ou figura e gera o embedding de cada um.
4. Backend grava os trechos, os embeddings e o nome do modelo de embedding utilizado.
5. Backend soma tokens e custo estimado ao registro, marca-o como concluida (com finalizada_em e andamento em 100%) e altera o status do material para "pronto".

**Fluxos alternativos**

- A1. Material sem texto extraível (ex.: PDF escaneado, que contém apenas imagens): o status do material passa a "falhou", o registro da operação passa a falhou e o usuário é informado.
- A2. Modelo de embedding substituído: o sistema reindexa o material atualizando embedding e modelo_embedding de cada trecho no lugar; os trechos não são apagados nem recriados, porque as faixas dos tópicos dependem da ordem deles.
- A3. Falha ao descrever uma figura (modelo de linguagem indisponível ou erro): a figura é ignorada, o processamento continua e o usuário é informado de quantas figuras ficaram de fora.

**Pós-condição:** Material consultável por busca de similaridade.

## UC05 — Sugerir divisão em tópicos (interno)

- **Ator(es):** Provedor do modelo de linguagem (ator secundário) — incluído por UC02
- **Requisitos:** RF10, RF16
- **Descrição:** Propõe como dividir o material em tópicos (ex.: capítulos ou seções), indicando o primeiro e o último trecho de cada um. É isso que permite ao estudante delimitar "até onde estudou" dentro de um mesmo material.
- **Pré-condição:** Material indexado (UC04).

**Fluxo principal**

1. Sistema cria o registro da operação sugestao_topicos, ligado ao material, e identifica a estrutura do material (títulos, seções e páginas).
2. Sistema pede ao modelo de linguagem uma proposta de tópicos, com nome e limites (ordem do trecho inicial e do trecho final).
3. Sistema valida que os tópicos cobrem todo o material, sem lacunas e sem sobreposição.
4. Sistema marca o registro como concluida e devolve a proposta ao UC02.

**Fluxos alternativos**

- A1. Material sem estrutura clara ou falha no modelo de linguagem: o sistema propõe a divisão em blocos de tamanho semelhante.

**Pós-condição:** Proposta de tópicos com limites, disponível para confirmação.

## UC06 — Gerar resumo

- **Ator(es):** Usuário
- **Requisitos:** RF05
- **Descrição:** Gera um resumo do conteúdo estudado, a partir do material enviado ou do tópico informado.
- **Pré-condição:** Existe um material (UC02) ou tópico (UC03).

**Fluxo principal**

1. Usuário seleciona o material ou tópico e escolhe "Gerar resumo".
2. Sistema cria o registro da operação resumo (status pendente, depois em_andamento).
3. Sistema executa o caso de uso incluído "Obter conteúdo-base" (UC08).
4. Sistema gera o resumo a partir do conteúdo-base.
5. Sistema grava o resumo (cada nova geração cria um novo resumo), marca o registro como concluida e exibe o resumo.

**Fluxos alternativos**

- A1. Falha ou indisponibilidade do serviço do modelo de linguagem: o registro passa a falhou, o sistema exibe mensagem clara e permite nova tentativa.
- A2. Limite de requisições por usuário atingido (padrão: 30 operações por hora, valor configurável): o sistema informa e orienta a aguardar.
- A3. Limite de gasto por usuário atingido (valor e janela configuráveis): o sistema informa, não inicia a operação e orienta a aguardar a próxima janela.

**Pós-condição:** Resumo disponível para consulta.

## UC07 — Gerar questões

- **Ator(es):** Usuário
- **Requisitos:** RF06, RF09 (via UC09)
- **Descrição:** Gera questões de múltipla escolha sobre o conteúdo estudado. Cada questão passa por verificação de fidelidade e de repetição antes de ser gravada e exibida. Também pode ser acionado pela geração de provas quando faltarem questões (extensão).
- **Pré-condição:** Existe um material (UC02) ou tópico (UC03).

**Fluxo principal**

1. Usuário seleciona o material ou tópico, informa a quantidade e escolhe "Gerar questões".
2. Sistema cria o registro da operação questoes (status pendente, depois em_andamento) e atualiza o percentual de andamento a cada questão concluída.
3. Sistema executa "Obter conteúdo-base" (UC08).
4. Sistema gera as questões.
5. Sistema executa "Verificar fidelidade da questão" (UC09) para cada questão.
6. Sistema verifica se cada questão aprovada é repetida: calcula o hash do enunciado normalizado e compara o embedding do enunciado com o das questões já gravadas do mesmo tópico; se o hash for igual ou a similaridade atingir o limiar configurado (padrão sugerido: 0,90), a questão é descartada.
7. Sistema grava as questões aprovadas e não repetidas (com o hash e o embedding do enunciado), marca o registro como concluida e as apresenta.

**Fluxos alternativos**

- A1. Questão reprovada na verificação: é descartada e uma nova é gerada, até o limite de tentativas do UC09.
- A2. Falha no modelo de linguagem, limite de requisições ou limite de gasto: mesma tratativa do UC06.
- A3. Questão repetida: é descartada, contabilizada no registro (questões repetidas) e uma nova é gerada, com o mesmo limite de tentativas por questão do UC09 (padrão: 3).

**Pós-condição:** Questões disponíveis para serem respondidas (UC11).

## UC08 — Obter conteúdo-base (interno)

- **Ator(es):** Provedor do modelo de linguagem (ator secundário) — incluído por UC06 e UC07
- **Requisitos:** RF07, RF08
- **Descrição:** Define a estratégia de geração. Com material, busca por similaridade os trechos mais relevantes (técnica de geração aumentada por recuperação, RAG), limitados às fontes do tópico (cada material e a parte dele que pertence ao tópico), e os usa como base, mantendo fidelidade à fonte. Sem material, usa o conhecimento geral do modelo sobre o tópico.
- **Pré-condição:** Solicitação vinda de UC06 ou UC07, com material ou tópico associado.

**Fluxo principal**

1. Sistema verifica se há material associado.
2. Com material: busca, apenas dentro das fontes do tópico (em cada material, a faixa de trechos do tópico; para a geração pedida sobre o material inteiro, todo o material), os trechos mais similares, reúne os mais similares entre as fontes e os envia ao modelo como contexto.
3. Sem material: solicita ao modelo o conteúdo geral sobre o tópico.
4. Sistema soma os tokens e o custo estimado ao registro da operação que o chamou (UC06 ou UC07).
5. Sistema devolve o conteúdo-base (e a lista dos trechos usados) ao caso de uso solicitante.

**Fluxos alternativos**

- A1. Nenhum trecho suficientemente relevante: o sistema informa e sugere reformular o tópico ou enviar outro material.
- A2. Embeddings gerados por outro modelo: o sistema reindexa o material (UC04) antes da busca.
- A3. Falha no modelo de linguagem: o erro é repassado ao caso de uso solicitante, que marca o registro da sua operação como falhou.

**Pós-condição:** Conteúdo-base disponível; tokens e custo somados ao registro da operação que chamou.

## UC09 — Verificar fidelidade da questão (interno)

- **Ator(es):** Provedor do modelo de linguagem (ator secundário) — incluído por UC07
- **Requisitos:** RF09
- **Descrição:** Confere se cada questão gerada é sustentada pelo conteúdo de origem, descartando as que não atendem. Sem material, avalia a consistência interna da questão.
- **Pré-condição:** Questão gerada em UC07.

**Fluxo principal**

1. Sistema cria o registro da operação verificacao_questao (status pendente, depois em_andamento) no início da verificação de uma geração.
2. Para cada questão, o sistema pergunta ao modelo se a resposta correta é sustentada pelos trechos usados na geração.
3. Aprovada: a questão é gravada e ligada aos trechos que a sustentam.
4. Reprovada: é descartada (não é gravada) e uma nova geração é solicitada, até 3 tentativas por questão (valor padrão, configurável).
5. Ao terminar, o sistema grava no registro as quantidades de questões aprovadas e rejeitadas e o marca como concluida.

**Fluxos alternativos**

- A1. Tentativas esgotadas: a questão não é exibida e a rejeição é contabilizada.

**Pós-condição:** Apenas questões aprovadas são gravadas; a taxa de rejeição (rejeitadas divididas pelo total de aprovadas e rejeitadas) fica disponível.

## UC10 — Gerar prova por escopo

- **Ator(es):** Usuário
- **Requisitos:** RF15, RF16, RF17, RF18
- **Descrição:** Monta uma prova escolhendo os assuntos, o ponto de estudo já alcançado em cada um, a quantidade de questões e se os assuntos serão misturados ou separados.
- **Pré-condição:** Existe ao menos um assunto com tópicos e questões.

**Fluxo principal**

1. Usuário escolhe "Gerar prova" e seleciona um ou mais assuntos.
2. Para cada assunto, define o tópico-limite, isto é, até qual tópico já estudou (sem limite, entram todos os tópicos do assunto).
3. Usuário informa a quantidade de questões.
4. Usuário escolhe uma prova única, com assuntos misturados, ou provas separadas por assunto.
5. Sistema divide a quantidade pedida igualmente entre os assuntos (as questões que sobrarem da divisão vão, uma a uma, aos assuntos na ordem escolhida) e sorteia, em cada assunto, questões dos tópicos cuja posição é menor ou igual à do tópico-limite; se um assunto tiver menos questões do que a sua cota, a sobra é redistribuída entre os demais.
6. Sistema sorteia a ordem das questões, grava a(s) prova(s) com status "gerada" e as apresenta. Em provas separadas, cada prova recebe a quantidade total informada, e as provas do mesmo pedido recebem o mesmo identificador de grupo e são apresentadas juntas.

**Fluxos alternativos**

- A1. Questões insuficientes (soma das questões elegíveis menor que a quantidade pedida): o sistema oferece gerar novas questões (UC07, extensão) ou reduzir a quantidade.

**Pós-condição:** Prova(s) criada(s), pronta(s) para serem respondidas em UC11.

## UC11 — Responder questões

- **Ator(es):** Usuário
- **Requisitos:** RF11
- **Descrição:** O estudante responde a questões avulsas, de uma prova ou de uma sessão de revisão. Cada resposta é registrada e o sistema informa o acerto e o gabarito.
- **Pré-condição:** Existem questões geradas (UC07), uma prova (UC10) ou uma sessão de revisão (UC13).

**Fluxo principal**

1. Sistema apresenta uma questão.
2. Usuário seleciona uma alternativa.
3. Sistema confere a resposta e mostra o acerto ou erro e a alternativa correta.
4. Sistema registra a resposta (alternativa escolhida, acerto, data e hora e a prova, quando houver).
5. Sistema executa "Atualizar agendamento de revisão" (UC12).
6. Repete até o fim da sessão e exibe o resultado; se for uma prova, altera o status para "em andamento" na primeira resposta e para "concluída" (gravando a data e hora de conclusão) ao responder a última questão.

**Fluxos alternativos**

- A1. Abandono da sessão: as respostas já registradas são mantidas e a prova permanece "em andamento".

**Pós-condição:** Respostas registradas e agendamento de revisão atualizado.

## UC12 — Atualizar agendamento de revisão (interno)

- **Ator(es):** — (caso de uso interno, incluído por UC11)
- **Requisitos:** RF12
- **Descrição:** Reagenda a próxima revisão da questão respondida, com uma variação do algoritmo SM-2 (método de repetição espaçada) adaptada a respostas binárias (certo ou errado). Ao contrário do SM-2 original, o fator de facilidade também diminui quando a resposta é incorreta. O agendamento é por questão; a prioridade do tópico é calculada em UC13. As datas usam o fuso horário America/Sao_Paulo.
- **Pré-condição:** Resposta registrada em UC11.

**Fluxo principal**

1. Sistema localiza o agendamento da questão para o usuário; se não existir (primeira resposta), cria-o com repetições 0, intervalo de 1 dia e fator de facilidade 2,50.
2. Resposta correta dada antes da data da próxima revisão, e que não é a primeira: o agendamento não é alterado.
3. Demais respostas corretas: soma 1 às repetições; o intervalo passa a 1 dia na 1ª repetição, 6 dias na 2ª e, da 3ª em diante, o intervalo anterior multiplicado pelo fator de facilidade anterior à atualização, arredondado para o inteiro mais próximo (metade para cima) e limitado a 365 dias; depois, o fator aumenta 0,10 (máximo 2,50).
4. Resposta incorreta (sempre reagenda): zera as repetições, define o intervalo em 1 dia e reduz o fator em 0,20 (mínimo 1,30).
5. Sistema grava a data da última revisão (hoje) e a da próxima (hoje mais o intervalo).

**Fluxos alternativos**

- A1. Primeira resposta da questão: o agendamento é criado no passo 1 e as regras dos passos 3 e 4 valem a partir dele.

**Pós-condição:** Agendamento da questão atualizado.

## UC13 — Realizar revisão adaptativa

- **Ator(es):** Usuário
- **Requisitos:** RF12, RF13
- **Descrição:** Inicia uma sessão de revisão com os tópicos priorizados pelo sistema. A prioridade do tópico é calculada na consulta: primeiro vêm os tópicos com mais questões com revisão vencida (data da próxima revisão menor ou igual a hoje) e, em caso de empate, os de maior taxa de erro (respostas incorretas divididas pelo total de respostas do tópico).
- **Pré-condição:** Existem questões com histórico de respostas.

**Fluxo principal**

1. Usuário escolhe "Revisar".
2. Sistema calcula a prioridade de cada tópico e os lista em ordem decrescente.
3. Sistema monta a sessão com até 20 questões vencidas (valor padrão, configurável), começando pelos tópicos mais prioritários.
4. Usuário responde às questões (inclui UC11).
5. Sistema exibe o resumo da sessão.

**Fluxos alternativos**

- A1. Nenhuma questão com revisão vencida: o sistema informa e sugere estudar novo conteúdo.

**Pós-condição:** Sessão concluída; agendamentos atualizados.

## UC14 — Consultar painel de desempenho

- **Ator(es):** Usuário
- **Requisitos:** RF14
- **Descrição:** Exibe a evolução do desempenho por assunto ao longo do tempo, destacando pontos fortes e fracos.
- **Pré-condição:** Existe ao menos uma resposta registrada.

**Fluxo principal**

1. Usuário acessa "Desempenho".
2. Sistema consulta o histórico de respostas.
3. Sistema calcula, por assunto e por tópico, o percentual de acertos em cada semana (de segunda a domingo).
4. Sistema exibe gráficos de evolução e a lista dos tópicos com menor percentual de acertos.

**Fluxos alternativos**

- A1. Dados insuficientes: o sistema informa que ainda não há histórico para exibir.

**Pós-condição:** Usuário identifica o que precisa revisar.

## UC15 — Organizar conteúdo por assunto

- **Ator(es):** Usuário
- **Requisitos:** RF10
- **Descrição:** Cria assuntos (matérias), agrupa neles os tópicos e define a ordem entre eles. Essa organização é a base do escopo das provas.
- **Pré-condição:** Existe ao menos um tópico (todo tópico pertence a um assunto; o padrão é "Geral").

**Fluxo principal**

1. Usuário acessa "Meus assuntos".
2. Usuário cria um assunto ou seleciona um existente.
3. Usuário move tópicos para o assunto e define a ordem entre eles.
4. Sistema salva a organização.

**Fluxos alternativos**

- A1. Nome de assunto já existente: o sistema sugere reutilizar o assunto em vez de duplicá-lo.
- A2. Tentativa de remover o assunto padrão "Geral": o sistema não permite.
- A3. Tópico usado como tópico-limite de alguma prova: o sistema não permite movê-lo para outro assunto e informa as provas afetadas.
- A4. Usuário adiciona ou remove fontes de um tópico (partes de materiais): o sistema valida que a faixa não se sobrepõe a outra já usada do mesmo material e salva; um tópico sem fontes passa a ser um tópico livre.

**Pós-condição:** Conteúdo organizado por assunto, disponível para UC10.

## UC16 — Consultar histórico de materiais e tópicos

- **Ator(es):** Usuário
- **Requisitos:** RF19
- **Descrição:** Lista os materiais e tópicos já cadastrados, permitindo reutilizá-los em novas gerações de conteúdo ou provas.
- **Pré-condição:** Usuário autenticado (UC01).

**Fluxo principal**

1. Usuário acessa "Histórico".
2. Sistema lista materiais e tópicos por data e assunto.
3. Usuário abre um item para ver resumo e questões existentes ou gerar novo conteúdo.

**Fluxos alternativos**

- A1. Histórico vazio: o sistema orienta a iniciar um novo estudo.

**Pós-condição:** Item reaproveitado em nova geração ou prova.

## UC17 — Excluir material

- **Ator(es):** Usuário
- **Requisitos:** RNF de retenção e privacidade de dados (Lei Geral de Proteção de Dados, LGPD)
- **Descrição:** Remove definitivamente um material enviado, com o arquivo, seus trechos e embeddings. Tópicos, questões, provas e registros de operações já criados são mantidos, sem a referência ao material (um tópico que tem outros materiais continua alimentado por eles).
- **Pré-condição:** Material existente na conta do usuário.

**Fluxo principal**

1. Usuário seleciona o material e escolhe "Excluir".
2. Sistema pede confirmação, informando que a ação é irreversível.
3. Usuário confirma.
4. Em uma única transação, o backend anula nos registros de operação a referência ao material e exclui o material (as fontes dos tópicos, os trechos e as ligações das questões com esses trechos são excluídos em cascata).
5. Após a confirmação da transação, o backend remove o arquivo do armazenamento (se a remoção falhar, é repetida).
6. Sistema confirma a exclusão.

**Fluxos alternativos**

- A1. Usuário cancela a confirmação: nada é alterado.

**Pós-condição:** Dados do material removidos da plataforma.

## UC18 — Excluir conta e dados

- **Ator(es):** Usuário
- **Requisitos:** RNF de retenção e privacidade de dados (Lei Geral de Proteção de Dados, LGPD)
- **Descrição:** Remove definitivamente a conta e todos os dados do usuário: materiais e arquivos, trechos, assuntos, tópicos, questões, provas, respostas, agendamentos e registros de operações.
- **Pré-condição:** Usuário autenticado (UC01).

**Fluxo principal**

1. Usuário escolhe "Excluir conta".
2. Sistema informa que a ação é irreversível e pede a senha.
3. Usuário confirma com a senha.
4. Sistema remove os arquivos e exclui o usuário; todos os registros ligados a ele são excluídos em cascata.
5. Sistema encerra a sessão e confirma a exclusão.

**Fluxos alternativos**

- A1. Senha incorreta ou cancelamento: nada é alterado.

**Pós-condição:** Conta e dados removidos da plataforma.

## UC19 — Exportar dados de estudo

- **Ator(es):** Usuário
- **Requisitos:** RNF de portabilidade de dados
- **Descrição:** Exporta o histórico de desempenho e as questões geradas em arquivo CSV ou JSON (formatos abertos de dados), evitando dependência exclusiva da plataforma.
- **Pré-condição:** Usuário autenticado (UC01).

**Fluxo principal**

1. Usuário escolhe "Exportar dados".
2. Usuário seleciona o conteúdo (desempenho, questões) e o formato.
3. Sistema gera o arquivo.
4. Sistema disponibiliza o download.

**Fluxos alternativos**

- A1. Sem dados para exportar: o sistema informa o usuário.

**Pós-condição:** Arquivo de exportação entregue ao usuário.
