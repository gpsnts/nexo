-- Validação do esquema da Plataforma Nexo (único validador do esquema; amplie-o a cada nova regra de banco).
-- Cobre: tópico com vários materiais (topico_fonte), repetição de questões, andamento das operações,
-- grupo de provas, tipo de conteúdo do trecho, isolamento por conta (RLS) e exclusões em cascata.
-- Uso: banco VAZIO, recém-migrado com db/migrations/V1__esquema_inicial.sql, conectado como superusuário:
--   psql -v ON_ERROR_STOP=1 -f db/validacao/verificar_esquema.sql
-- Cada verificação imprime PASS; a primeira falha interrompe o script com código de saída diferente de zero.
-- O script cria dados de teste e remove tudo ao final (inclusive as funções auxiliares).
\set ON_ERROR_STOP 1
\pset tuples_only on
CREATE FUNCTION public.t_err(descr text, cmd text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE falhou boolean := false;
BEGIN
  BEGIN EXECUTE cmd; falhou := true;
  EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'PASS | % | %', descr, left(SQLERRM,70); END;
  IF falhou THEN RAISE EXCEPTION 'FAIL | % | esperava erro e nao houve', descr; END IF;
END $$;
CREATE FUNCTION public.t_ok(descr text, cmd text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE cmd; RAISE NOTICE 'PASS | %', descr;
EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'FAIL | % | %', descr, left(SQLERRM,120);
END $$;
CREATE FUNCTION public.t_eq(descr text, got bigint, want bigint) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF got = want THEN RAISE NOTICE 'PASS | % (=%)', descr, got;
  ELSE RAISE EXCEPTION 'FAIL | % obtido % esperado %', descr, got, want; END IF;
END $$;

-- preparação (papel de migração)
INSERT INTO usuario(id,nome,email,senha_hash) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','A','a@x.com','h'),('bbbbbbbb-0000-0000-0000-000000000002','B','b@x.com','h');
INSERT INTO assunto(id,usuario_id,nome,assunto_padrao) VALUES ('10000001-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','Geral',true),('10000002-0000-0000-0000-000000000002','bbbbbbbb-0000-0000-0000-000000000002','Geral',true);
INSERT INTO material(id,usuario_id,nome_arquivo,tipo_arquivo,tamanho_bytes,hash_arquivo,caminho_arquivo,status_processamento) VALUES
 ('10000003-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-000000000001','m1.pdf','application/pdf',1,'h1','/m1','pronto'),
 ('10000004-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000001','m2.pdf','application/pdf',1,'h2','/m2','pronto'),
 ('10000005-0000-0000-0000-000000000005','bbbbbbbb-0000-0000-0000-000000000002','mb.pdf','application/pdf',1,'h3','/mb','pronto');
INSERT INTO trecho(id,usuario_id,material_id,ordem,tipo_conteudo,conteudo,embedding,modelo_embedding)
 SELECT gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000003-0000-0000-0000-000000000003',g,(ARRAY['texto','tabela','figura'])[1+g%3],'c'||g,array_fill(0.01::real, ARRAY[1536])::vector,'m' FROM generate_series(1,10) g;
INSERT INTO trecho(id,usuario_id,material_id,ordem,tipo_conteudo,conteudo,embedding,modelo_embedding)
 SELECT gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000004-0000-0000-0000-000000000004',g,'texto','c'||g,array_fill(0.01::real, ARRAY[1536])::vector,'m' FROM generate_series(1,5) g;
INSERT INTO trecho(id,usuario_id,material_id,ordem,tipo_conteudo,conteudo,embedding,modelo_embedding)
 SELECT gen_random_uuid(),'bbbbbbbb-0000-0000-0000-000000000002','10000005-0000-0000-0000-000000000005',g,'texto','c'||g,array_fill(0.01::real, ARRAY[1536])::vector,'m' FROM generate_series(1,5) g;
INSERT INTO topico(id,usuario_id,assunto_id,nome,ordem) VALUES
 ('10000006-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-000000000001','10000001-0000-0000-0000-000000000001','T1',1),('10000007-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-000000000001','10000001-0000-0000-0000-000000000001','T2',2),('10000008-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-000000000001','10000001-0000-0000-0000-000000000001','T3 livre',3),
 ('10000009-0000-0000-0000-000000000009','bbbbbbbb-0000-0000-0000-000000000002','10000002-0000-0000-0000-000000000002','TB',1);

-- 1) tópico com vários materiais
SELECT t_ok('tópico alimentado por 2 materiais (M1 1-4 e M2 1-3)',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES
 (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','10000003-0000-0000-0000-000000000003',1,4),(gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','10000004-0000-0000-0000-000000000004',1,3)$q$);
SELECT t_ok('outro tópico usa faixa vizinha do mesmo material (M1 5-8)',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000007-0000-0000-0000-000000000007','10000003-0000-0000-0000-000000000003',5,8)$q$);
SELECT t_err('mesmo material duas vezes no mesmo tópico',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','10000003-0000-0000-0000-000000000003',9,10)$q$);
SELECT t_err('faixa sobreposta entre tópicos do mesmo material',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000008-0000-0000-0000-000000000008','10000003-0000-0000-0000-000000000003',8,10); SET CONSTRAINTS ALL IMMEDIATE$q$);
SELECT t_err('faixa apontando para trecho inexistente',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000008-0000-0000-0000-000000000008','10000004-0000-0000-0000-000000000004',4,99)$q$);
SELECT t_err('faixa invertida (final < inicial)',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000008-0000-0000-0000-000000000008','10000004-0000-0000-0000-000000000004',5,4)$q$);
SELECT t_err('fonte de um usuário apontando para material de outro',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000008-0000-0000-0000-000000000008','10000005-0000-0000-0000-000000000005',1,2)$q$);
SELECT t_err('tipo_conteudo inválido em trecho',
 $q$INSERT INTO trecho(id,usuario_id,material_id,ordem,tipo_conteudo,conteudo,embedding,modelo_embedding) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000004-0000-0000-0000-000000000004',6,'video','x',array_fill(0.01::real, ARRAY[1536])::vector,'m')$q$);
SELECT t_err('apagar trechos de material que tem fonte referenciando',
 $q$DELETE FROM trecho WHERE material_id='10000004-0000-0000-0000-000000000004'$q$);

-- 2) questões: repetição
SELECT t_ok('questão aprovada gravada',
 $q$INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES ('1000000a-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','Q?','["a","b"]',0,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',array_fill(0.01::real, ARRAY[1536])::vector,'m')$q$);
SELECT t_err('mesmo enunciado (hash) no mesmo tópico',
 $q$INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','Q?','["a","b"]',0,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',array_fill(0.01::real, ARRAY[1536])::vector,'m')$q$);
SELECT t_ok('mesmo hash em outro tópico é permitido',
 $q$INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000007-0000-0000-0000-000000000007','Q?','["a","b"]',0,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',array_fill(0.01::real, ARRAY[1536])::vector,'m')$q$);
SELECT t_err('hash fora do formato SHA-256',
 $q$INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','Z','["a","b"]',0,'xyz',array_fill(0.01::real, ARRAY[1536])::vector,'m')$q$);
INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES ('1000000b-0000-0000-0000-00000000000b','aaaaaaaa-0000-0000-0000-000000000001','10000006-0000-0000-0000-000000000006','Outra','["a","b"]',1,'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',array_fill(0.02::real, ARRAY[1536])::vector,'m');
SELECT t_eq('busca por similaridade de enunciados filtrada por tópico (cosseno)',
 (SELECT count(*) FROM questao WHERE topico_id='10000006-0000-0000-0000-000000000006' AND 1-(embedding <=> array_fill(0.01::real, ARRAY[1536])::vector) >= 0.90), 2);
INSERT INTO questao_trecho(questao_id,trecho_id,usuario_id) SELECT '1000000a-0000-0000-0000-00000000000a',id,'aaaaaaaa-0000-0000-0000-000000000001' FROM trecho WHERE material_id='10000003-0000-0000-0000-000000000003' AND ordem IN (1,2);

-- 3) registro_geracao: progresso e repetidas
SELECT t_ok('indexação em andamento com 40%',
 $q$INSERT INTO registro_geracao(id,usuario_id,material_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual) VALUES ('1000000e-0000-0000-0000-00000000000e','aaaaaaaa-0000-0000-0000-000000000001','10000003-0000-0000-0000-000000000003','indexacao_material','material','m','em_andamento',0,0,0,0,0,0,40)$q$);
SELECT t_err('progresso acima de 100',
 $q$INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','resumo','topico_livre','m','pendente',0,0,0,0,0,0,101)$q$);
SELECT t_err('concluída com progresso menor que 100',
 $q$INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','resumo','topico_livre','m','concluida',0,0,0,0,0,0,90,now())$q$);
SELECT t_ok('concluída com 100% e questões repetidas contadas',
 $q$INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES ('1000000f-0000-0000-0000-00000000000f','aaaaaaaa-0000-0000-0000-000000000001','questoes','topico_livre','m','concluida',10,10,0.01,5,0,2,100,now())$q$);

-- 4) provas separadas no mesmo pedido
SELECT t_ok('duas provas separadas com o mesmo grupo_id',
 $q$INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,grupo_id) VALUES ('1000000c-0000-0000-0000-00000000000c','aaaaaaaa-0000-0000-0000-000000000001',5,'gerada','10000010-0000-0000-0000-000000000010'),('1000000d-0000-0000-0000-00000000000d','aaaaaaaa-0000-0000-0000-000000000001',5,'gerada','10000010-0000-0000-0000-000000000010')$q$);
SELECT t_eq('provas do grupo', (SELECT count(*) FROM prova WHERE grupo_id='10000010-0000-0000-0000-000000000010'), 2);

-- 4b) autenticação (V2): sessões, tokens de conta e bloqueio de login
SELECT t_ok('sessão de refresh gravada',
 $q$INSERT INTO sessao(id,usuario_id,familia_id,token_hash,expira_em) VALUES ('20000001-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',gen_random_uuid(),'hash-1',now()+interval '7 days')$q$);
SELECT t_err('hash de refresh token repetido', $q$INSERT INTO sessao(id,usuario_id,familia_id,token_hash,expira_em) VALUES (gen_random_uuid(),'bbbbbbbb-0000-0000-0000-000000000002',gen_random_uuid(),'hash-1',now())$q$);
SELECT t_ok('token de redefinição de senha', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES ('20000002-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','redefinicao_senha','hash-2',now()+interval '30 minutes')$q$);
SELECT t_ok('token de confirmação de e-mail com novo e-mail', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,novo_email,expira_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','confirmacao_email','hash-3','novo@x.com',now()+interval '1 day')$q$);
SELECT t_err('confirmação de e-mail sem novo e-mail', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','confirmacao_email','hash-4',now())$q$);
SELECT t_err('redefinição com novo e-mail', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,novo_email,expira_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','redefinicao_senha','hash-5','a@x.com',now())$q$);
SELECT t_ok('token de verificação de e-mail do cadastro', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES (gen_random_uuid(),'bbbbbbbb-0000-0000-0000-000000000002','verificacao_email','hash-9',now()+interval '1 day')$q$);
SELECT t_err('verificação de e-mail com novo e-mail', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,novo_email,expira_em) VALUES (gen_random_uuid(),'bbbbbbbb-0000-0000-0000-000000000002','verificacao_email','hash-10','x@x.com',now())$q$);
SELECT t_err('tipo de token inválido', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','outro','hash-6',now())$q$);
SELECT t_err('novo e-mail fora de minúsculas', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,novo_email,expira_em) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','confirmacao_email','hash-7','Novo@X.com',now())$q$);
SELECT t_err('token_conta de usuário inexistente', $q$INSERT INTO token_conta(id,usuario_id,tipo,token_hash,expira_em) VALUES (gen_random_uuid(),gen_random_uuid(),'redefinicao_senha','hash-8',now())$q$);
SELECT t_err('falhas_login negativo', $q$UPDATE usuario SET falhas_login = -1 WHERE id='bbbbbbbb-0000-0000-0000-000000000002'$q$);
SELECT t_ok('contagem de falhas e bloqueio', $q$UPDATE usuario SET falhas_login = 5, bloqueado_ate = now()+interval '15 minutes' WHERE id='bbbbbbbb-0000-0000-0000-000000000002'$q$);
SELECT t_ok('e-mail verificado e dados do dispositivo na sessão', $q$UPDATE usuario SET email_verificado_em = now() WHERE id='bbbbbbbb-0000-0000-0000-000000000002'; UPDATE sessao SET dispositivo='Firefox em Linux', ip='127.0.0.1' WHERE id='20000001-0000-0000-0000-000000000001'$q$);
SELECT t_eq('conta nova nasce sem e-mail verificado', (SELECT count(*) FROM usuario WHERE email_verificado_em IS NULL AND id='aaaaaaaa-0000-0000-0000-000000000001'), 1);
SELECT t_eq('índice parcial das contas não verificadas (V4)', (SELECT count(*) FROM pg_indexes WHERE indexname='usuario_nao_verificado_idx' AND indexdef LIKE '%email_verificado_em IS NULL%'), 1);
SELECT t_ok('gatilho renova atualizado_em da sessão', $q$UPDATE sessao SET revogado_em = now() WHERE id='20000001-0000-0000-0000-000000000001'$q$);

-- 5) isolamento por conta (papel da aplicação)
SET ROLE nexo_app;
SELECT t_eq('sem app.usuario_id: nenhuma linha de topico_fonte', (SELECT count(*) FROM topico_fonte), 0);
SELECT set_config('app.usuario_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT t_eq('usuário A enxerga as 3 fontes dele', (SELECT count(*) FROM topico_fonte), 3);
SELECT set_config('app.usuario_id','bbbbbbbb-0000-0000-0000-000000000002',false);
SELECT t_eq('usuário B não enxerga fontes de A', (SELECT count(*) FROM topico_fonte), 0);
SELECT t_eq('usuário B não enxerga questões de A', (SELECT count(*) FROM questao), 0);
SELECT t_err('usuário B não grava fonte em nome de A',
 $q$INSERT INTO topico_fonte(id,usuario_id,topico_id,material_id,ordem_trecho_inicial,ordem_trecho_final) VALUES (gen_random_uuid(),'aaaaaaaa-0000-0000-0000-000000000001','10000008-0000-0000-0000-000000000008','10000004-0000-0000-0000-000000000004',4,5)$q$);
SELECT t_eq('nexo_app não lê o histórico do Flyway (se existir)', (SELECT count(*) FROM pg_class WHERE relname='flyway_schema_history' AND has_table_privilege('nexo_app', oid, 'SELECT')), 0);

-- 6) exclusão de material (como usuário A, papel da aplicação)
SELECT set_config('app.usuario_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT t_err('excluir material sem anular registro_geracao.material_id', $q$DELETE FROM material WHERE id='10000003-0000-0000-0000-000000000003'$q$);
UPDATE registro_geracao SET material_id = NULL WHERE material_id='10000003-0000-0000-0000-000000000003';
SELECT t_ok('excluir material M1 depois de anular o registro', $q$DELETE FROM material WHERE id='10000003-0000-0000-0000-000000000003'$q$);
SELECT t_eq('trechos de M1 saíram em cascata', (SELECT count(*) FROM trecho WHERE material_id='10000003-0000-0000-0000-000000000003'), 0);
SELECT t_eq('fontes de M1 saíram em cascata (restam só as de M2)', (SELECT count(*) FROM topico_fonte), 1);
SELECT t_eq('tópicos continuam existindo', (SELECT count(*) FROM topico), 3);
SELECT t_eq('questões continuam existindo', (SELECT count(*) FROM questao), 3);
SELECT t_eq('ligações questao_trecho de M1 saíram', (SELECT count(*) FROM questao_trecho), 0);
SELECT t_eq('registro da indexação mantido', (SELECT count(*) FROM registro_geracao WHERE id='1000000e-0000-0000-0000-00000000000e'), 1);

-- 7) exclusão da conta
SELECT t_eq('nexo_app lê e grava sessao e token_conta (sem RLS, ADR 0010)', (SELECT count(*) FROM sessao) + (SELECT count(*) FROM token_conta), 4);
SELECT t_ok('excluir conta A em uma instrução', $q$DELETE FROM usuario WHERE id='aaaaaaaa-0000-0000-0000-000000000001'$q$);
RESET ROLE;
SELECT t_eq('sessões e tokens de A saíram em cascata', (SELECT count(*) FROM sessao WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001') + (SELECT count(*) FROM token_conta WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001'), 0);
SELECT t_eq('sobrou nada de A (fontes, questões, provas, registros, trechos)',
 (SELECT (SELECT count(*) FROM topico_fonte WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001')+(SELECT count(*) FROM questao WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001')+(SELECT count(*) FROM prova WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001')+(SELECT count(*) FROM registro_geracao WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001')+(SELECT count(*) FROM trecho WHERE usuario_id='aaaaaaaa-0000-0000-0000-000000000001')), 0);
SELECT t_eq('dados de B intactos', (SELECT count(*) FROM trecho WHERE usuario_id='bbbbbbbb-0000-0000-0000-000000000002'), 5);

-- limpeza
DELETE FROM usuario WHERE id='bbbbbbbb-0000-0000-0000-000000000002';
DROP FUNCTION public.t_err(text, text);
DROP FUNCTION public.t_ok(text, text);
DROP FUNCTION public.t_eq(text, bigint, bigint);
