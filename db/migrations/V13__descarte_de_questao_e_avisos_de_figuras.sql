-- Descartes de questões repetidas e avisos de figuras ignoradas.
-- descarte_questao guarda a similaridade de cada questão descartada por repetição, para calibrar o limiar com dados reais.
-- Não copia o enunciado: só aponta para a questão parecida (que vira nula se ela for excluída).
-- Chave composta para a referência do descarte ao registro da operação.
ALTER TABLE registro_geracao ADD CONSTRAINT registro_geracao_id_usuario_unico UNIQUE (id, usuario_id);

CREATE TABLE descarte_questao (
  id                  uuid PRIMARY KEY,
  usuario_id          uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  topico_id           uuid NOT NULL,
  registro_geracao_id uuid NOT NULL,
  questao_parecida_id uuid,
  motivo              text NOT NULL CHECK (motivo IN ('hash', 'cosseno')),
  similaridade        real NOT NULL CHECK (similaridade BETWEEN 0 AND 1),
  criado_em           timestamptz NOT NULL DEFAULT now(),
  atualizado_em       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT descarte_questao_id_usuario_unico UNIQUE (id, usuario_id),
  FOREIGN KEY (topico_id, usuario_id) REFERENCES topico (id, usuario_id) ON DELETE CASCADE,
  FOREIGN KEY (registro_geracao_id, usuario_id) REFERENCES registro_geracao (id, usuario_id) ON DELETE CASCADE,
  FOREIGN KEY (questao_parecida_id, usuario_id) REFERENCES questao (id, usuario_id) ON DELETE SET NULL (questao_parecida_id)
);
CREATE INDEX descarte_questao_registro_idx ON descarte_questao (registro_geracao_id, usuario_id);
CREATE INDEX descarte_questao_topico_idx ON descarte_questao (topico_id, usuario_id);

CREATE TRIGGER descarte_questao_atualizado_em BEFORE UPDATE ON descarte_questao FOR EACH ROW EXECUTE FUNCTION definir_atualizado_em();
ALTER TABLE descarte_questao ENABLE ROW LEVEL SECURITY;
CREATE POLICY isolamento_por_conta ON descarte_questao
  USING (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid)
  WITH CHECK (usuario_id = nullif(current_setting('app.usuario_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON descarte_questao TO nexo_app;

-- Figuras que a indexação não descreveu: as acima do limite por material e as que o modelo de visão não conseguiu descrever.
-- Coluna em tabela existente: a RLS, o gatilho e o GRANT de `material` já valem.
ALTER TABLE material ADD COLUMN figuras_acima_do_limite integer NOT NULL DEFAULT 0 CHECK (figuras_acima_do_limite >= 0);
ALTER TABLE material ADD COLUMN figuras_com_falha integer NOT NULL DEFAULT 0 CHECK (figuras_com_falha >= 0);
