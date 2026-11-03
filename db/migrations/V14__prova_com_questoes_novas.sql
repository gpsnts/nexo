-- Prova com questões novas: a prova pode ser montada em segundo plano (status montando), com orientação do estudante para as questões
-- novas. Coluna em tabela existente: a RLS, o gatilho de atualizado_em e o GRANT de `prova` já valem.
ALTER TABLE prova DROP CONSTRAINT prova_status_check;
ALTER TABLE prova ADD CONSTRAINT prova_status_check CHECK (status IN ('montando', 'gerada', 'em_andamento', 'concluida', 'falhou'));
-- Antes: "gerada" = sem início. Agora também "montando" e "falhou" não têm início.
ALTER TABLE prova DROP CONSTRAINT prova_inicio_coerente;
ALTER TABLE prova ADD CONSTRAINT prova_inicio_coerente CHECK ((status IN ('montando', 'gerada', 'falhou')) = (iniciada_em IS NULL));
ALTER TABLE prova ADD COLUMN orientacao text CHECK (orientacao IS NULL OR (btrim(orientacao) <> '' AND char_length(orientacao) <= 500));
ALTER TABLE prova ADD COLUMN questoes_novas integer NOT NULL DEFAULT 0 CHECK (questoes_novas >= 0);
ALTER TABLE prova ADD COLUMN questoes_geradas integer NOT NULL DEFAULT 0 CHECK (questoes_geradas >= 0);
ALTER TABLE prova ADD COLUMN motivo_falha text CHECK (motivo_falha IS NULL OR status = 'falhou');
-- Questões geradas não passam das pedidas.
ALTER TABLE prova ADD CONSTRAINT prova_geradas_dentro_do_pedido CHECK (questoes_geradas <= questoes_novas);
