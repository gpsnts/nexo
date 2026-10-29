-- Dica opcional por questão e chat com o tutor por tópico.
-- A dica é gerada sob demanda e guardada na questão (a segunda leitura não chama o modelo). Cada dica e cada mensagem do chat é uma operação com IA
-- e tem o seu registro: amplia a lista de operações. Linhas existentes continuam válidas.
ALTER TABLE questao ADD COLUMN dica text CHECK (dica IS NULL OR btrim(dica) <> '');

ALTER TABLE registro_geracao DROP CONSTRAINT registro_geracao_operacao_check;
ALTER TABLE registro_geracao ADD CONSTRAINT registro_geracao_operacao_check
  CHECK (operacao IN ('indexacao_material', 'sugestao_topicos', 'resumo', 'questoes', 'verificacao_questao', 'dica', 'chat'));

-- Histórico do chat por tópico (uma conversa por tópico). Apagar o tópico ou a conta apaga as mensagens.
CREATE TABLE mensagem_chat (
  id            uuid PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id     uuid NOT NULL,
  papel         text NOT NULL CHECK (papel IN ('usuario', 'assistente')),
  conteudo      text NOT NULL CHECK (btrim(conteudo) <> ''),
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT mensagem_chat_id_usuario_unico UNIQUE (id, usuario_id),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id) ON DELETE CASCADE
);
CREATE INDEX mensagem_chat_topico_idx ON mensagem_chat (topico_id, usuario_id, criado_em);

-- Itens obrigatórios de toda tabela nova com usuario_id: gatilho de atualizado_em, RLS e permissão.
CREATE TRIGGER mensagem_chat_atualizado_em BEFORE UPDATE ON mensagem_chat FOR EACH ROW EXECUTE FUNCTION definir_atualizado_em();
ALTER TABLE mensagem_chat ENABLE ROW LEVEL SECURITY;
CREATE POLICY isolamento_por_conta ON mensagem_chat
  USING (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid)
  WITH CHECK (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON mensagem_chat TO nexo_app;
