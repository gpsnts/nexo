-- o usuário é informado do motivo da falha. Guarda-se um código estável (a mensagem é do backend e do frontend).
-- Coluna em tabela existente: a RLS, o gatilho de atualizado_em e o GRANT de `material` já valem.
ALTER TABLE material ADD COLUMN motivo_falha text;
ALTER TABLE material ADD CONSTRAINT material_motivo_so_na_falha CHECK (motivo_falha IS NULL OR status_processamento = 'falhou');
