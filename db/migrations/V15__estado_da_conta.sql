-- Estado da conta (suspensão pelo administrador) e cota de IA por usuário.
-- A tabela usuario não tem RLS (é lida antes de haver usuário autenticado), então nenhuma política muda.
ALTER TABLE usuario
  ADD COLUMN status_conta text NOT NULL DEFAULT 'ativo' CHECK (status_conta IN ('ativo', 'suspenso')),
  ADD COLUMN cota_creditos integer CHECK (cota_creditos >= 0);  -- nulo = cota padrão da aplicação; 0 = sem IA
