-- =====================================================================
-- Plataforma Nexo - Esquema do banco de dados (PostgreSQL 16 ou superior)
-- Extensões necessárias: vector (pgvector) e btree_gist.
-- Execute como o papel dono do esquema (papel de migração), nunca como nexo_app.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;      -- busca por similaridade de vetores
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- restrição de exclusão sobre faixas de trechos

-- Papel usado pela aplicação (backend). Defina LOGIN e senha conforme o ambiente.
-- Não pode ser dono das tabelas nem ter BYPASSRLS, ou a segurança em nível de linha não vale para ele.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexo_app') THEN
    CREATE ROLE nexo_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- Tabelas
-- Regras comuns: toda tabela tem criado_em e atualizado_em; toda tabela, exceto usuario,
-- tem usuario_id; toda tabela, exceto as três de junção, tem id (uuid gerado pelo backend).
-- ---------------------------------------------------------------------

CREATE TABLE usuario (
  id            uuid PRIMARY KEY,
  nome          text NOT NULL CHECK (btrim(nome) <> ''),
  email         text NOT NULL CHECK (email = lower(email)),
  senha_hash    text NOT NULL,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT usuario_email_unico UNIQUE (email)
);

CREATE TABLE material (
  id                   uuid PRIMARY KEY,
  usuario_id           uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  nome_arquivo         text NOT NULL,
  tipo_arquivo         text NOT NULL,
  tamanho_bytes        bigint NOT NULL CHECK (tamanho_bytes >= 0),
  quantidade_paginas   integer CHECK (quantidade_paginas >= 1),
  hash_arquivo         text NOT NULL,
  caminho_arquivo      text NOT NULL,
  status_processamento text NOT NULL CHECK (status_processamento IN ('pendente', 'processando', 'pronto', 'falhou')),
  criado_em            timestamptz NOT NULL DEFAULT now(),
  atualizado_em        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT material_id_usuario_unico UNIQUE (id, usuario_id)
);
-- Arquivo repetido só é bloqueado se o registro anterior não falhou (permite reenviar após falha).
CREATE UNIQUE INDEX material_hash_arquivo_unico ON material (usuario_id, hash_arquivo) WHERE status_processamento <> 'falhou';

CREATE TABLE trecho (
  id               uuid PRIMARY KEY,
  usuario_id       uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  material_id      uuid NOT NULL,
  ordem            integer NOT NULL CHECK (ordem >= 1),
  numero_pagina    integer CHECK (numero_pagina >= 1),
  -- texto: parágrafo do material; tabela: tabela em formato texto (Markdown); figura: descrição da imagem gerada por IA.
  tipo_conteudo    text NOT NULL CHECK (tipo_conteudo IN ('texto', 'tabela', 'figura')),
  conteudo         text NOT NULL,
  embedding        vector(1536) NOT NULL,
  modelo_embedding text NOT NULL,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  atualizado_em    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT trecho_ordem_no_material_unica UNIQUE (material_id, ordem),
  CONSTRAINT trecho_id_usuario_unico UNIQUE (id, usuario_id),
  FOREIGN KEY (material_id, usuario_id) REFERENCES material (id, usuario_id) ON DELETE CASCADE
);

CREATE TABLE assunto (
  id             uuid PRIMARY KEY,
  usuario_id     uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  nome           text NOT NULL CHECK (btrim(nome) <> ''),
  assunto_padrao boolean NOT NULL,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  atualizado_em  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT assunto_id_usuario_unico UNIQUE (id, usuario_id)
);
CREATE UNIQUE INDEX assunto_nome_unico ON assunto (usuario_id, lower(nome));
CREATE UNIQUE INDEX assunto_padrao_unico ON assunto (usuario_id) WHERE assunto_padrao;

CREATE TABLE topico (
  id            uuid PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  assunto_id    uuid NOT NULL,
  nome          text NOT NULL CHECK (btrim(nome) <> ''),
  ordem         integer NOT NULL CHECK (ordem >= 1),
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT topico_id_assunto_unico UNIQUE (id, assunto_id),
  CONSTRAINT topico_id_usuario_unico UNIQUE (id, usuario_id),
  -- Reordenar tópicos exige trocar valores dentro de uma transação: a unicidade é conferida só no fim dela.
  CONSTRAINT topico_ordem_no_assunto_unica UNIQUE (assunto_id, ordem) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (assunto_id, usuario_id) REFERENCES assunto (id, usuario_id)
);

-- Fontes de um tópico: faixa de trechos de cada material que o alimenta (um tópico pode ter vários materiais).
-- Tópico sem linhas aqui é um tópico livre (ou cujos materiais foram excluídos).
CREATE TABLE topico_fonte (
  id                   uuid PRIMARY KEY,
  usuario_id           uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id            uuid NOT NULL,
  material_id          uuid NOT NULL,
  ordem_trecho_inicial integer NOT NULL CHECK (ordem_trecho_inicial >= 1),
  ordem_trecho_final   integer NOT NULL,
  criado_em            timestamptz NOT NULL DEFAULT now(),
  atualizado_em        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT topico_fonte_um_por_material UNIQUE (topico_id, material_id),
  CONSTRAINT topico_fonte_faixa_coerente CHECK (ordem_trecho_final >= ordem_trecho_inicial),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id),
  -- Excluir o material remove as fontes que apontam para ele (e os trechos, por outra cascata).
  FOREIGN KEY (material_id, usuario_id) REFERENCES material (id, usuario_id) ON DELETE CASCADE,
  FOREIGN KEY (material_id, ordem_trecho_inicial) REFERENCES trecho (material_id, ordem),
  FOREIGN KEY (material_id, ordem_trecho_final) REFERENCES trecho (material_id, ordem),
  -- Faixas de trechos do mesmo material não se sobrepõem, mesmo entre tópicos diferentes (conferida no fim da transação).
  CONSTRAINT topico_fonte_faixas_sem_sobreposicao EXCLUDE USING gist (
    material_id WITH =, int4range(ordem_trecho_inicial, ordem_trecho_final, '[]') WITH &&
  ) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE resumo (
  id            uuid PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id     uuid NOT NULL,
  conteudo      text NOT NULL,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id)
);

CREATE TABLE questao (
  id               uuid PRIMARY KEY,
  usuario_id       uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id        uuid NOT NULL,
  enunciado        text NOT NULL,
  alternativas     jsonb NOT NULL,
  resposta_correta smallint NOT NULL,
  -- Detecção de repetição: SHA-256 do enunciado normalizado (repetição exata) e embedding do enunciado (repetição por sentido).
  enunciado_hash   text NOT NULL CHECK (enunciado_hash ~ '^[0-9a-f]{64}$'),
  embedding        vector(1536) NOT NULL,
  modelo_embedding text NOT NULL,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  atualizado_em    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT questao_id_usuario_unico UNIQUE (id, usuario_id),
  CONSTRAINT questao_enunciado_unico_no_topico UNIQUE (topico_id, enunciado_hash),
  CONSTRAINT questao_alternativas_validas CHECK (
    jsonb_typeof(alternativas) = 'array' AND jsonb_array_length(alternativas) BETWEEN 2 AND 5),
  CONSTRAINT questao_resposta_correta_valida CHECK (
    resposta_correta >= 0 AND resposta_correta < jsonb_array_length(alternativas)),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id)
);

CREATE TABLE questao_trecho (
  questao_id    uuid NOT NULL,
  trecho_id     uuid NOT NULL,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (questao_id, trecho_id),
  FOREIGN KEY (questao_id, usuario_id) REFERENCES questao (id, usuario_id) ON DELETE CASCADE,
  FOREIGN KEY (trecho_id, usuario_id) REFERENCES trecho (id, usuario_id) ON DELETE CASCADE
);

CREATE TABLE prova (
  id                    uuid PRIMARY KEY,
  usuario_id            uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  quantidade_solicitada integer NOT NULL CHECK (quantidade_solicitada >= 1),
  status                text NOT NULL CHECK (status IN ('gerada', 'em_andamento', 'concluida')),
  concluida_em          timestamptz,
  -- Provas separadas geradas no mesmo pedido compartilham o mesmo grupo_id (gerado pelo backend); nulo em prova única.
  grupo_id              uuid,
  criado_em             timestamptz NOT NULL DEFAULT now(),
  atualizado_em         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT prova_id_usuario_unico UNIQUE (id, usuario_id),
  CONSTRAINT prova_conclusao_coerente CHECK ((status = 'concluida') = (concluida_em IS NOT NULL))
);

CREATE TABLE prova_assunto (
  prova_id         uuid NOT NULL,
  assunto_id       uuid NOT NULL,
  usuario_id       uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_limite_id uuid,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  atualizado_em    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (prova_id, assunto_id),
  FOREIGN KEY (prova_id, usuario_id) REFERENCES prova (id, usuario_id),
  FOREIGN KEY (assunto_id, usuario_id) REFERENCES assunto (id, usuario_id),
  -- O tópico-limite precisa pertencer ao assunto da linha.
  FOREIGN KEY (topico_limite_id, assunto_id) REFERENCES topico (id, assunto_id)
);

CREATE TABLE prova_questao (
  prova_id      uuid NOT NULL,
  questao_id    uuid NOT NULL,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  ordem         integer NOT NULL CHECK (ordem >= 1),
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (prova_id, questao_id),
  CONSTRAINT prova_questao_ordem_unica UNIQUE (prova_id, ordem) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (prova_id, usuario_id) REFERENCES prova (id, usuario_id),
  FOREIGN KEY (questao_id, usuario_id) REFERENCES questao (id, usuario_id)
);

CREATE TABLE resposta (
  id                    uuid PRIMARY KEY,
  usuario_id            uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  questao_id            uuid NOT NULL,
  prova_id              uuid,
  alternativa_escolhida smallint NOT NULL CHECK (alternativa_escolhida >= 0),
  correta               boolean NOT NULL,
  respondida_em         timestamptz NOT NULL,
  criado_em             timestamptz NOT NULL DEFAULT now(),
  atualizado_em         timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (questao_id, usuario_id) REFERENCES questao (id, usuario_id),
  -- Resposta dada em prova: a questão precisa fazer parte da prova (nulo = resposta avulsa ou de revisão).
  FOREIGN KEY (prova_id, questao_id) REFERENCES prova_questao (prova_id, questao_id)
);

CREATE TABLE progresso_questao (
  id               uuid PRIMARY KEY,
  usuario_id       uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  questao_id       uuid NOT NULL,
  repeticoes       integer NOT NULL CHECK (repeticoes >= 0),
  intervalo_dias   integer NOT NULL CHECK (intervalo_dias BETWEEN 1 AND 365),
  fator_facilidade numeric(3, 2) NOT NULL CHECK (fator_facilidade BETWEEN 1.30 AND 2.50),
  ultima_revisao   date NOT NULL,
  proxima_revisao  date NOT NULL,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  atualizado_em    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT progresso_questao_unico UNIQUE (usuario_id, questao_id),
  CONSTRAINT progresso_datas_coerentes CHECK (proxima_revisao >= ultima_revisao),
  FOREIGN KEY (questao_id, usuario_id) REFERENCES questao (id, usuario_id)
);

CREATE TABLE registro_geracao (
  id                  uuid PRIMARY KEY,
  usuario_id          uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id           uuid,
  material_id         uuid,
  operacao            text NOT NULL CHECK (operacao IN ('indexacao_material', 'sugestao_topicos', 'resumo', 'questoes', 'verificacao_questao')),
  tipo_entrada        text NOT NULL CHECK (tipo_entrada IN ('material', 'topico_livre')),
  modelo_ia           text NOT NULL,
  status              text NOT NULL CHECK (status IN ('pendente', 'em_andamento', 'concluida', 'falhou')),
  tokens_entrada      integer NOT NULL CHECK (tokens_entrada >= 0),
  tokens_saida        integer NOT NULL CHECK (tokens_saida >= 0),
  custo_estimado_usd  numeric(10, 6) NOT NULL CHECK (custo_estimado_usd >= 0),
  questoes_aprovadas  integer NOT NULL CHECK (questoes_aprovadas >= 0),
  questoes_rejeitadas integer NOT NULL CHECK (questoes_rejeitadas >= 0),
  questoes_repetidas  integer NOT NULL CHECK (questoes_repetidas >= 0),
  progresso_percentual smallint NOT NULL CHECK (progresso_percentual BETWEEN 0 AND 100),
  finalizada_em       timestamptz,
  criado_em           timestamptz NOT NULL DEFAULT now(),
  atualizado_em       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT registro_finalizacao_coerente CHECK ((status IN ('concluida', 'falhou')) = (finalizada_em IS NOT NULL)),
  CONSTRAINT registro_progresso_coerente CHECK (status <> 'concluida' OR progresso_percentual = 100),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id),
  -- Preenchido nas operações que ainda não têm tópico; o backend anula ao excluir o material (UC17).
  FOREIGN KEY (material_id, usuario_id) REFERENCES material (id, usuario_id)
);

-- ---------------------------------------------------------------------
-- Índices de apoio às chaves estrangeiras e às consultas por usuário
-- ---------------------------------------------------------------------
CREATE INDEX material_usuario_idx          ON material (usuario_id);
CREATE INDEX trecho_usuario_idx            ON trecho (usuario_id);
CREATE INDEX topico_usuario_idx            ON topico (usuario_id);
CREATE INDEX topico_fonte_usuario_idx      ON topico_fonte (usuario_id);
CREATE INDEX topico_fonte_faixa_inicial_idx ON topico_fonte (material_id, ordem_trecho_inicial);
CREATE INDEX topico_fonte_faixa_final_idx  ON topico_fonte (material_id, ordem_trecho_final);
CREATE INDEX resumo_topico_idx             ON resumo (topico_id, usuario_id);
CREATE INDEX resumo_usuario_idx            ON resumo (usuario_id);
CREATE INDEX questao_topico_idx            ON questao (topico_id, usuario_id);
CREATE INDEX questao_usuario_idx           ON questao (usuario_id);
CREATE INDEX questao_trecho_trecho_idx     ON questao_trecho (trecho_id, usuario_id);
CREATE INDEX questao_trecho_usuario_idx    ON questao_trecho (usuario_id);
CREATE INDEX prova_usuario_idx             ON prova (usuario_id);
CREATE INDEX prova_grupo_idx               ON prova (usuario_id, grupo_id);
CREATE INDEX prova_assunto_assunto_idx     ON prova_assunto (assunto_id, usuario_id);
CREATE INDEX prova_assunto_limite_idx      ON prova_assunto (topico_limite_id, assunto_id);
CREATE INDEX prova_assunto_usuario_idx     ON prova_assunto (usuario_id);
CREATE INDEX prova_questao_questao_idx     ON prova_questao (questao_id, usuario_id);
CREATE INDEX prova_questao_usuario_idx     ON prova_questao (usuario_id);
CREATE INDEX resposta_questao_idx          ON resposta (questao_id, usuario_id);
CREATE INDEX resposta_prova_questao_idx    ON resposta (prova_id, questao_id);
CREATE INDEX resposta_usuario_data_idx     ON resposta (usuario_id, respondida_em);
CREATE INDEX progresso_questao_questao_idx ON progresso_questao (questao_id, usuario_id);
CREATE INDEX progresso_proxima_revisao_idx ON progresso_questao (usuario_id, proxima_revisao);
CREATE INDEX registro_topico_idx           ON registro_geracao (topico_id, usuario_id);
CREATE INDEX registro_material_idx         ON registro_geracao (material_id, usuario_id);
CREATE INDEX registro_usuario_data_idx     ON registro_geracao (usuario_id, criado_em);

-- ---------------------------------------------------------------------
-- atualizado_em: atualizado pelo banco a cada alteração de linha
-- ---------------------------------------------------------------------
CREATE FUNCTION definir_atualizado_em() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.atualizado_em := now();
  RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history' LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION definir_atualizado_em()', t || '_atualizado_em', t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- Segurança em nível de linha (Row-Level Security) em todas as tabelas, exceto usuario.
-- O backend deve executar "SET LOCAL app.usuario_id = '<uuid>'" no início de CADA transação.
-- Sem esse valor, as consultas não retornam linhas.
-- ---------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND tablename NOT IN ('usuario', 'flyway_schema_history') LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY isolamento_por_conta ON %I
      USING (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid)
      WITH CHECK (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;

-- Permissões do papel da aplicação
GRANT USAGE ON SCHEMA public TO nexo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nexo_app;
