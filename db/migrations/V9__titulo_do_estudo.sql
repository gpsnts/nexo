-- O estudante pode dar um nome ao estudo (material) diferente do nome do arquivo, à mão ou sugerido pela IA.
-- Nulo = usa o nome do arquivo. A sugestão da IA é uma operação com IA como as outras: amplia a lista de operações.
ALTER TABLE material ADD COLUMN titulo text CHECK (titulo IS NULL OR btrim(titulo) <> '');

ALTER TABLE registro_geracao DROP CONSTRAINT registro_geracao_operacao_check;
ALTER TABLE registro_geracao ADD CONSTRAINT registro_geracao_operacao_check
  CHECK (operacao IN ('indexacao_material', 'sugestao_topicos', 'resumo', 'questoes', 'verificacao_questao', 'dica', 'chat', 'nome_estudo'));
