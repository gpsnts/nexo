-- Roda após cada migrate (idempotente), com o papel dono. Liga o login do nexo_app e fecha o histórico do Flyway.
ALTER ROLE nexo_app LOGIN PASSWORD '${nexo_app_senha}';
REVOKE ALL ON flyway_schema_history FROM nexo_app;
