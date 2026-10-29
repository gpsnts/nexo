package br.nexo.material

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Tópico lido do banco.
 * @property id identificador.
 * @property assuntoId pasta.
 * @property nome nome.
 * @property orientacao orientação do estudante para o conteúdo gerado, se houver.
 */
data class TopicoLinha(val id: UUID, val assuntoId: UUID, val nome: String, val orientacao: String? = null)
/**
 * Fonte de um tópico: material e faixa de trechos.
 * @property materialId material.
 * @property nomeArquivo nome do arquivo.
 * @property inicio primeira ordem de trecho.
 * @property fim última ordem de trecho.
 * @property titulo nome dado ao estudo, se houver.
 */
data class FonteLinha(val materialId: UUID, val nomeArquivo: String, val inicio: Int, val fim: Int, val titulo: String? = null)
/**
 * Tópico livre para a lista inicial.
 * @property id identificador.
 * @property nome nome.
 * @property criadoEm criação.
 * @property totalQuestoes questões gravadas.
 * @property temResumo se tem resumo.
 * @property gerando se há geração em curso.
 * @property assuntoId pasta.
 */
data class TopicoLivreDto(val id: UUID, val nome: String, val criadoEm: Instant, val totalQuestoes: Int, val temResumo: Boolean, val gerando: Boolean = false, val assuntoId: UUID? = null)
/**
 * Trecho usado como base de uma geração.
 * @property id identificador.
 * @property materialId material.
 * @property ordem posição no material.
 * @property conteudo texto.
 */
data class TrechoBase(val id: UUID, val materialId: UUID, val ordem: Int, val conteudo: String)
/**
 * Registro de operação com IA de um tópico.
 * @property id identificador.
 * @property topicoId tópico.
 * @property operacao resumo, questoes ou verificacao_questao.
 * @property status pendente, em_andamento, concluida ou falhou.
 * @property progresso 0 a 100.
 * @property modelo modelo gravado.
 * @property aprovadas questões aprovadas.
 * @property rejeitadas questões rejeitadas.
 * @property repetidas questões repetidas.
 * @property tokensEntrada tokens do prompt.
 * @property tokensSaida tokens gerados.
 * @property custo custo estimado em US$.
 * @property criadoEm criação.
 * @property atualizadoEm última atualização.
 * @property motivo motivo da falha.
 */
data class RegistroTopico(
    val id: UUID, val topicoId: UUID, val operacao: String, val status: String, val progresso: Int, val modelo: String,
    val aprovadas: Int, val rejeitadas: Int, val repetidas: Int, val tokensEntrada: Int, val tokensSaida: Int, val custo: BigDecimal,
    val criadoEm: Instant, val atualizadoEm: Instant, val motivo: String?,
)
/**
 * Questão lida do banco.
 * @property id identificador.
 * @property enunciado pergunta.
 * @property alternativas alternativas em JSON.
 * @property respostaCorreta índice da correta.
 * @property criadoEm criação.
 * @property dica dica gravada.
 * @property dificuldade nível.
 */
data class QuestaoLinha(val id: UUID, val enunciado: String, val alternativas: String, val respostaCorreta: Int, val criadoEm: Instant, val dica: String?, val dificuldade: String = "media")
/**
 * Trecho que apoia uma questão.
 * @property questaoId questão.
 * @property nomeArquivo arquivo do material.
 * @property ordem posição do trecho.
 * @property numeroPagina página, se houver.
 */
data class ApoioLinha(val questaoId: UUID, val nomeArquivo: String, val ordem: Int, val numeroPagina: Int?)

/** Totais de uma operação com IA, gravados de uma vez a cada etapa (valores absolutos, não incrementos). */
class Acumulado {
    var tokensEntrada = 0; var tokensSaida = 0; var custo: BigDecimal = BigDecimal.ZERO
    var aprovadas = 0; var rejeitadas = 0; var repetidas = 0
}

/** JDBC de resumo, questões e registros por tópico. Sempre em transação gerenciada: a RLS filtra a conta, então nenhuma consulta repete `usuario_id`. */
@Repository
class GeracaoDados(private val jdbc: JdbcTemplate) {
    private val abertas = "('pendente','em_andamento')"
    private val operacoes = "('resumo','questoes','verificacao_questao')"

    /**
     * Lê um tópico.
     * @param id tópico.
     * @return o tópico, ou nulo se não for do usuário (RLS).
     */
    fun topico(id: UUID): TopicoLinha? =
        jdbc.query("SELECT id, assunto_id, nome, orientacao FROM topico WHERE id = ?", { rs, _ -> TopicoLinha(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) }, id).firstOrNull()

    /**
     * Trava a linha do tópico até o fim da transação: dois pedidos ao mesmo tempo não passam juntos pela checagem de "já há geração em andamento".
     * @param id tópico.
     * @return o tópico, ou nulo se não for do usuário.
     */
    fun travarTopico(id: UUID): TopicoLinha? =
        jdbc.query("SELECT id, assunto_id, nome, orientacao FROM topico WHERE id = ? FOR UPDATE", { rs, _ -> TopicoLinha(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) }, id).firstOrNull()

    /**
     * Tópicos sem fonte (livres), do mais novo ao mais antigo.
     * @param limite máximo de itens.
     * @return os tópicos livres.
     */
    fun topicosLivres(limite: Int): List<TopicoLivreDto> = jdbc.query(
        """SELECT t.id, t.nome, t.criado_em, (SELECT count(*) FROM questao q WHERE q.topico_id = t.id) AS questoes, EXISTS (SELECT 1 FROM resumo r WHERE r.topico_id = t.id) AS resumo, t.assunto_id
           FROM topico t WHERE NOT EXISTS (SELECT 1 FROM topico_fonte f WHERE f.topico_id = t.id) ORDER BY t.criado_em DESC LIMIT ?""",
        { rs, _ -> TopicoLivreDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4), rs.getBoolean(5), false, rs.getObject(6, UUID::class.java)) }, limite,
    )

    /**
     * Tópicos com resumo ou questões sendo gerados agora (operação aberta com pulso recente: uma abandonada num reinício não conta).
     * @param pulsoSegundos idade máxima do último pulso.
     * @return ids dos tópicos.
     */
    fun topicosGerando(pulsoSegundos: Long): Set<UUID> = jdbc.query(
        "SELECT DISTINCT topico_id FROM registro_geracao WHERE topico_id IS NOT NULL AND operacao IN $operacoes AND status IN $abertas AND atualizado_em > now() - make_interval(secs => ?)",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, pulsoSegundos.toDouble(),
    ).toSet()

    /**
     * Materiais cujos tópicos estão gerando conteúdo agora.
     * @param pulsoSegundos idade máxima do último pulso.
     * @return ids dos materiais.
     */
    fun materiaisGerando(pulsoSegundos: Long): Set<UUID> = jdbc.query(
        """SELECT DISTINCT f.material_id FROM registro_geracao r JOIN topico_fonte f ON f.topico_id = r.topico_id
           WHERE r.operacao IN $operacoes AND r.status IN $abertas AND r.atualizado_em > now() - make_interval(secs => ?)""",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, pulsoSegundos.toDouble(),
    ).toSet()

    /**
     * Gerações (resumo ou questões) abertas do usuário, com pulso recente: uma abandonada num reinício não conta.
     * @param pulsoSegundos idade máxima do último pulso.
     * @return quantidade de gerações abertas.
     */
    fun emAndamentoDoUsuario(pulsoSegundos: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM registro_geracao WHERE operacao IN ('resumo','questoes') AND status IN $abertas AND atualizado_em > now() - make_interval(secs => ?)",
        Int::class.java, pulsoSegundos.toDouble(),
    )!!

    /**
     * Registro de um pedido que nunca começou (fila cheia): some, em vez de ficar como falha e gastar a cota do usuário.
     * @param ids registros a remover (só os ainda abertos).
     */
    fun removerRegistrosAbertos(ids: List<UUID>) {
        ids.forEach { jdbc.update("DELETE FROM registro_geracao WHERE id = ? AND status IN $abertas", it) }
    }

    /**
     * Fontes de um tópico, por ordem de trecho.
     * @param topicoId tópico.
     * @return as fontes (vazia em tópico livre).
     */
    fun fontes(topicoId: UUID): List<FonteLinha> = jdbc.query(
        """SELECT f.material_id, m.nome_arquivo, f.ordem_trecho_inicial, f.ordem_trecho_final, m.titulo FROM topico_fonte f JOIN material m ON m.id = f.material_id
           WHERE f.topico_id = ? ORDER BY f.ordem_trecho_inicial""",
        { rs, _ -> FonteLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)) }, topicoId,
    )

    /**
     * Soma dos caracteres de todas as faixas das fontes: decide se o tópico cabe inteiro no contexto.
     * @param topicoId tópico.
     * @return total de caracteres.
     */
    fun caracteresDasFaixas(topicoId: UUID): Long = jdbc.queryForObject(
        """SELECT coalesce(sum(length(t.conteudo)), 0) FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ?""",
        Long::class.java, topicoId,
    )!!

    /**
     * Converte uma linha em [TrechoBase].
     * @param rs resultado posicionado na linha.
     * @param n número da linha (não usado).
     * @return o trecho.
     */
    private fun trecho(rs: java.sql.ResultSet, n: Int) = TrechoBase(rs.getObject("id", UUID::class.java), rs.getObject("material_id", UUID::class.java), rs.getInt("ordem"), rs.getString("conteudo"))

    /**
     * Todos os trechos das faixas do tópico, em ordem.
     * @param topicoId tópico.
     * @return os trechos.
     */
    fun trechosDasFaixas(topicoId: UUID): List<TrechoBase> = jdbc.query(
        """SELECT t.id, t.material_id, t.ordem, t.conteudo FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ? ORDER BY f.ordem_trecho_inicial, t.ordem""",
        ::trecho, topicoId,
    )

    /**
     * Busca vetorial EXATA (sem índice aproximado), filtrada por material e pela faixa de cada fonte do tópico.
     * @param topicoId tópico.
     * @param vetor embedding do nome do tópico.
     * @param limite máximo de trechos.
     * @param naoCobertosPrimeiro se trechos ainda não citados por nenhuma questão vêm antes.
     * @return os trechos mais similares.
     */
    fun trechosMaisSimilares(topicoId: UUID, vetor: List<Double>, limite: Int, naoCobertosPrimeiro: Boolean = false): List<TrechoBase> = jdbc.query(
        """SELECT t.id, t.material_id, t.ordem, t.conteudo FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ?
           ORDER BY ${if (naoCobertosPrimeiro) "EXISTS (SELECT 1 FROM questao_trecho qt WHERE qt.trecho_id = t.id)," else ""} t.embedding <=> ?::vector, t.material_id, t.ordem LIMIT ?""",
        ::trecho, topicoId, vetor.joinToString(",", "[", "]"), limite,
    )

    // ---- registros
    /**
     * Cria um registro pendente de operação do tópico.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param topicoId tópico.
     * @param livre se é tópico livre.
     * @param operacao resumo, questoes ou verificacao_questao.
     * @param modelo modelo gravado.
     */
    fun criarRegistro(id: UUID, usuario: UUID, topicoId: UUID, livre: Boolean, operacao: String, modelo: String) {
        jdbc.update(
            """INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual)
               VALUES (?,?,?,?,?,?,'pendente',0,0,0,0,0,0,0)""",
            id, usuario, topicoId, operacao, if (livre) "topico_livre" else "material", modelo,
        )
    }

    /**
     * Grava o estado da operação. Em `concluida` o progresso é 100 e em `em_andamento` nunca passa de 99 (CHECK do banco). Só mexe em operação ainda aberta: uma operação cancelada (ou dada como abandonada) não é reaberta nem sobrescrita pela tarefa que ainda corria.
     * @param id registro.
     * @param status novo status.
     * @param pct progresso.
     * @param modelo modelo usado.
     * @param a tokens, custo e contagens acumulados.
     * @param motivo código do motivo, se falhou.
     * @return falso quando a operação já não estava aberta.
     */
    fun gravar(id: UUID, status: String, pct: Int, modelo: String, a: Acumulado, motivo: String? = null): Boolean = jdbc.update(
        """UPDATE registro_geracao SET status=?, progresso_percentual=?, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?,
                  questoes_aprovadas=?, questoes_rejeitadas=?, questoes_repetidas=?, motivo_falha=?, finalizada_em = CASE WHEN ? IN ('concluida','falhou') THEN now() ELSE NULL END
           WHERE id=? AND status IN $abertas""",
        status, if (status == "concluida") 100 else pct.coerceIn(0, 99), modelo, a.tokensEntrada, a.tokensSaida, a.custo, a.aprovadas, a.rejeitadas, a.repetidas,
        if (status == "falhou") motivo else null, status, id,
    ) > 0

    /**
     * Diz se o registro ainda está aberto.
     * @param id registro.
     * @return verdadeiro se pendente ou em andamento.
     */
    fun estaAberto(id: UUID): Boolean = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM registro_geracao WHERE id = ? AND status IN $abertas)", Boolean::class.java, id)!!

    /**
     * O que uma tarefa cancelada gastou até parar (a chamada ao modelo em curso não se interrompe): só tokens e custo, o estado fica como o cancelamento deixou.
     * @param id registro.
     * @param a totais acumulados.
     */
    fun gravarConsumo(id: UUID, a: Acumulado) {
        jdbc.update("UPDATE registro_geracao SET tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, questoes_aprovadas=?, questoes_rejeitadas=?, questoes_repetidas=? WHERE id=?",
            a.tokensEntrada, a.tokensSaida, a.custo, a.aprovadas, a.rejeitadas, a.repetidas, id)
    }

    /**
     * Converte uma linha em [RegistroTopico].
     * @param rs resultado posicionado na linha.
     * @param n número da linha (não usado).
     * @return o registro.
     */
    private fun registro(rs: java.sql.ResultSet, n: Int) = RegistroTopico(
        rs.getObject("id", UUID::class.java), rs.getObject("topico_id", UUID::class.java), rs.getString("operacao"), rs.getString("status"), rs.getInt("progresso_percentual"),
        rs.getString("modelo_ia"), rs.getInt("questoes_aprovadas"), rs.getInt("questoes_rejeitadas"), rs.getInt("questoes_repetidas"), rs.getInt("tokens_entrada"),
        rs.getInt("tokens_saida"), rs.getBigDecimal("custo_estimado_usd"), rs.getTimestamp("criado_em").toInstant(), rs.getTimestamp("atualizado_em").toInstant(), rs.getString("motivo_falha"),
    )

    /**
     * Lê um registro de operação de tópico.
     * @param id registro.
     * @return o registro, ou nulo se não existir.
     */
    fun registro(id: UUID): RegistroTopico? =
        jdbc.query("SELECT * FROM registro_geracao WHERE id = ? AND topico_id IS NOT NULL AND operacao IN $operacoes", ::registro, id).firstOrNull()

    /**
     * Registros abertos de um tópico.
     * @param topicoId tópico.
     * @return os registros pendentes ou em andamento.
     */
    fun registrosAbertos(topicoId: UUID): List<RegistroTopico> =
        jdbc.query("SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao IN $operacoes AND status IN $abertas ORDER BY criado_em", ::registro, topicoId)

    /**
     * A verificação criada junto com a geração de questões (um pedido por tópico por vez, então a primeira criada depois dela é a dela).
     * @param questoes registro de questões.
     * @return o registro de verificação, ou nulo.
     */
    fun verificacaoDe(questoes: RegistroTopico): RegistroTopico? = jdbc.query(
        "SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao = 'verificacao_questao' AND criado_em >= ? ORDER BY criado_em LIMIT 1", ::registro,
        questoes.topicoId, java.sql.Timestamp.from(questoes.criadoEm),
    ).firstOrNull()

    /**
     * Falha registros abertos.
     * @param ids registros.
     * @param motivo código do motivo.
     */
    fun falharAbertos(ids: List<UUID>, motivo: String) {
        ids.forEach { jdbc.update("UPDATE registro_geracao SET status='falhou', motivo_falha=?, finalizada_em=now() WHERE id=? AND status IN $abertas", motivo, it) }
    }

    // ---- resumo e questões
    /**
     * Grava um resumo.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param topicoId tópico.
     * @param conteudo texto do resumo.
     */
    fun inserirResumo(id: UUID, usuario: UUID, topicoId: UUID, conteudo: String) {
        jdbc.update("INSERT INTO resumo(id,usuario_id,topico_id,conteudo) VALUES (?,?,?,?)", id, usuario, topicoId, conteudo)
    }

    /**
     * Resumo mais recente do tópico.
     * @param topicoId tópico.
     * @return texto e data, ou nulo.
     */
    fun resumoRecente(topicoId: UUID): Pair<String, Instant>? = jdbc.query(
        "SELECT conteudo, criado_em FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id LIMIT 1", { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }, topicoId,
    ).firstOrNull()

    /**
     * Resumos do tópico, do mais novo ao mais antigo.
     * @param topicoId tópico.
     * @return pares (texto, data).
     */
    fun resumos(topicoId: UUID): List<Pair<String, Instant>> = jdbc.query(
        "SELECT conteudo, criado_em FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id", { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }, topicoId,
    )

    /**
     * Mantém só os [maximo] resumos mais recentes do tópico (o histórico não cresce sem limite).
     * @param topicoId tópico.
     * @param maximo quantos manter.
     */
    fun aparaResumos(topicoId: UUID, maximo: Int) {
        jdbc.update("DELETE FROM resumo WHERE topico_id = ? AND id NOT IN (SELECT id FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id LIMIT ?)", topicoId, topicoId, maximo)
    }

    /**
     * Conta as questões do tópico.
     * @param topicoId tópico.
     * @return quantidade.
     */
    fun totalQuestoes(topicoId: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?", Int::class.java, topicoId)!!
    /**
     * Diz se já existe questão com o hash do enunciado no tópico.
     * @param topicoId tópico.
     * @param hash hash do enunciado normalizado.
     * @return verdadeiro se existe.
     */
    fun existeHash(topicoId: UUID, hash: String): Boolean = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM questao WHERE topico_id = ? AND enunciado_hash = ?)", Boolean::class.java, topicoId, hash)!!
    /**
     * Enunciados mais recentes, para o modelo evitar repetir.
     * @param topicoId tópico.
     * @param n máximo de enunciados.
     * @return os enunciados, do mais novo ao mais antigo.
     */
    fun enunciadosRecentes(topicoId: UUID, n: Int): List<String> =
        jdbc.query("SELECT enunciado FROM questao WHERE topico_id = ? ORDER BY criado_em DESC LIMIT ?", { rs, _ -> rs.getString(1) }, topicoId, n)

    /**
     * Grava uma questão aprovada e suas ligações com os trechos de apoio.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param topicoId tópico.
     * @param enunciado pergunta.
     * @param alternativasJson alternativas em JSON.
     * @param correta índice da correta.
     * @param hash hash do enunciado normalizado.
     * @param vetor embedding do enunciado.
     * @param modeloEmbedding modelo do embedding.
     * @param trechos trechos que sustentam a questão.
     * @param dificuldade nível.
     */
    fun inserirQuestao(
        id: UUID, usuario: UUID, topicoId: UUID, enunciado: String, alternativasJson: String, correta: Int, hash: String, vetor: List<Double>, modeloEmbedding: String, trechos: Collection<UUID>, dificuldade: String = "media",
    ) {
        jdbc.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding,dificuldade) VALUES (?,?,?,?,?::jsonb,?,?,?::vector,?,?)",
            id, usuario, topicoId, enunciado, alternativasJson, correta, hash, vetor.joinToString(",", "[", "]"), modeloEmbedding, dificuldade,
        )
        trechos.forEach { jdbc.update("INSERT INTO questao_trecho(questao_id,trecho_id,usuario_id) VALUES (?,?,?)", id, it, usuario) }
    }

    /**
     * Questões do tópico, da mais antiga à mais nova.
     * @param topicoId tópico.
     * @return as questões.
     */
    fun questoes(topicoId: UUID): List<QuestaoLinha> = jdbc.query(
        "SELECT id, enunciado, alternativas::text, resposta_correta, criado_em, dica, dificuldade FROM questao WHERE topico_id = ? ORDER BY criado_em, id",
        { rs, _ -> QuestaoLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7)) }, topicoId,
    )

    /**
     * Questões já respondidas do tópico (têm agendamento): última resposta certa ou errada e próxima revisão. O gabarito só sai para elas.
     * @param topicoId tópico.
     * @return mapa de questão para (última correta, próxima revisão).
     */
    fun respondidas(topicoId: UUID): Map<UUID, Pair<Boolean, java.time.LocalDate>> = jdbc.query(
        """SELECT q.id, (SELECT r.correta FROM resposta r WHERE r.questao_id = q.id ORDER BY r.respondida_em DESC, r.criado_em DESC LIMIT 1), p.proxima_revisao
           FROM questao q JOIN progresso_questao p ON p.questao_id = q.id WHERE q.topico_id = ?""",
        { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getBoolean(2) to rs.getObject(3, java.time.LocalDate::class.java)) }, topicoId,
    ).toMap()

    /**
     * Trechos que sustentam cada questão do tópico (questao_trecho), para mostrar a fonte na tela.
     * @param topicoId tópico.
     * @return um apoio por par questão-trecho.
     */
    fun apoios(topicoId: UUID): List<ApoioLinha> = jdbc.query(
        """SELECT qt.questao_id, m.nome_arquivo, t.ordem, t.numero_pagina FROM questao_trecho qt JOIN questao q ON q.id = qt.questao_id
           JOIN trecho t ON t.id = qt.trecho_id JOIN material m ON m.id = t.material_id WHERE q.topico_id = ? ORDER BY t.material_id, t.ordem""",
        { rs, _ -> ApoioLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getObject(4) as Int?) }, topicoId,
    )

    /**
     * A geração de questões mais recente já terminada (para mostrar o resultado depois de recarregar a página).
     * @param topicoId tópico.
     * @return o registro, ou nulo.
     */
    fun ultimaDeQuestoes(topicoId: UUID): RegistroTopico? = jdbc.query(
        "SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao = 'questoes' AND status IN ('concluida','falhou') ORDER BY criado_em DESC LIMIT 1", ::registro, topicoId,
    ).firstOrNull()
}
