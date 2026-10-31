-- Prova com limite de tempo opcional: o relógio começa quando a prova é iniciada (ou na primeira resposta).
ALTER TABLE prova ADD COLUMN limite_minutos integer CHECK (limite_minutos IS NULL OR limite_minutos BETWEEN 1 AND 480);
ALTER TABLE prova ADD COLUMN iniciada_em timestamptz;
-- Prova só fica "gerada" enquanto não foi iniciada; depois disso sempre tem o instante de início.
ALTER TABLE prova ADD CONSTRAINT prova_inicio_coerente CHECK ((status = 'gerada') = (iniciada_em IS NULL));
