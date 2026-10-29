-- Bloqueio de login, sessões de refresh token e tokens de uso único da conta.

ALTER TABLE usuario
  ADD COLUMN falhas_login  integer NOT NULL DEFAULT 0 CHECK (falhas_login >= 0),
  ADD COLUMN bloqueado_ate timestamptz;

-- Refresh tokens. Só o hash SHA-256 é guardado. Rotação: cada uso revoga o token e cria outro na mesma família.
CREATE TABLE sessao (
  id            uuid PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  familia_id    uuid NOT NULL,
  token_hash    text NOT NULL,
  expira_em     timestamptz NOT NULL,
  revogado_em   timestamptz,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sessao_token_hash_unico UNIQUE (token_hash)
);
CREATE INDEX sessao_usuario_idx ON sessao (usuario_id);
CREATE INDEX sessao_familia_idx ON sessao (familia_id);

-- Redefinição de senha e confirmação de troca de e-mail. Só o hash do token é guardado; uso único.
CREATE TABLE token_conta (
  id            uuid PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  tipo          text NOT NULL CHECK (tipo IN ('redefinicao_senha', 'confirmacao_email')),
  token_hash    text NOT NULL,
  novo_email    text CHECK (novo_email = lower(novo_email)),
  expira_em     timestamptz NOT NULL,
  usado_em      timestamptz,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT token_conta_token_hash_unico UNIQUE (token_hash),
  CONSTRAINT token_conta_novo_email_so_na_confirmacao CHECK ((tipo = 'confirmacao_email') = (novo_email IS NOT NULL))
);
CREATE INDEX token_conta_usuario_idx ON token_conta (usuario_id);

CREATE TRIGGER sessao_atualizado_em BEFORE UPDATE ON sessao FOR EACH ROW EXECUTE FUNCTION definir_atualizado_em();
CREATE TRIGGER token_conta_atualizado_em BEFORE UPDATE ON token_conta FOR EACH ROW EXECUTE FUNCTION definir_atualizado_em();

-- Sem RLS de propósito (como usuario): são lidas antes de haver usuário autenticado, pelo hash de um segredo de 256 bits.
GRANT SELECT, INSERT, UPDATE, DELETE ON sessao, token_conta TO nexo_app;
