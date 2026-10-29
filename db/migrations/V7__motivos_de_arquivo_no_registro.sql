-- A indexação que falha por problema do arquivo (sem texto extraível, formato inválido) deixa o mesmo código no registro da operação
-- que o material já guarda (V5), em vez de ficar sem motivo. Só amplia a lista permitida; linhas existentes continuam válidas.
ALTER TABLE registro_geracao DROP CONSTRAINT registro_motivo_valido;
ALTER TABLE registro_geracao ADD CONSTRAINT registro_motivo_valido
  CHECK (motivo_falha IN ('falha_ia', 'cancelada', 'interrompido', 'erro_interno', 'sem_texto', 'formato_invalido'));
