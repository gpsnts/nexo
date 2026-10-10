-- A limpeza diária apaga contas que nunca confirmaram o e-mail (ADR 0014); o índice parcial evita varrer toda a tabela usuario.
CREATE INDEX usuario_nao_verificado_idx ON usuario (criado_em) WHERE email_verificado_em IS NULL;
