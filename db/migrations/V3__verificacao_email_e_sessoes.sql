-- F1c: verificação de e-mail no cadastro (ADR 0014) e dados de dispositivo para a lista de sessões (ADR 0015).

-- Contas anteriores à verificação ficam como verificadas (não há como pedir confirmação retroativa de quem já usa a conta).
ALTER TABLE usuario ADD COLUMN email_verificado_em timestamptz;
UPDATE usuario SET email_verificado_em = criado_em;

ALTER TABLE token_conta DROP CONSTRAINT token_conta_tipo_check;
ALTER TABLE token_conta ADD CONSTRAINT token_conta_tipo_check
  CHECK (tipo IN ('redefinicao_senha', 'confirmacao_email', 'verificacao_email'));

-- Uma "sessão" na tela é uma família (um login). Cada linha da família repete o dispositivo e o IP do pedido que a criou
-- (login ou renovação) e inicio_em é herdado na rotação, para mostrar "conectado desde" e "última atividade" (criado_em).
ALTER TABLE sessao
  ADD COLUMN dispositivo text,
  ADD COLUMN ip          text,
  ADD COLUMN inicio_em   timestamptz NOT NULL DEFAULT now();
