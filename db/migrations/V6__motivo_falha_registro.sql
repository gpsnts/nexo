-- Cancelamento de geração: o registro falhou por quê? Código estável (a mensagem é do backend e do frontend).
-- Coluna em tabela existente: a RLS, o gatilho de atualizado_em e o GRANT de `registro_geracao` já valem.
ALTER TABLE registro_geracao ADD COLUMN motivo_falha text;
ALTER TABLE registro_geracao ADD CONSTRAINT registro_motivo_valido CHECK (motivo_falha IN ('falha_ia', 'cancelada', 'interrompido', 'erro_interno'));
ALTER TABLE registro_geracao ADD CONSTRAINT registro_motivo_so_na_falha CHECK (motivo_falha IS NULL OR status = 'falhou');
