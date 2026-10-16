package br.nexo.material

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TopicoLinha(val id: UUID, val assuntoId: UUID, val nome: String)
data class FonteLinha(val materialId: UUID, val nomeArquivo: String, val inicio: Int, val fim: Int)
data class TopicoLivreDto(val id: UUID, val nome: String, val criadoEm: Instant, val totalQuestoes: Int, val temResumo: Boolean, val gerando: Boolean = false)
data class TrechoBase(val id: UUID, val materialId: UUID, val ordem: Int, val conteudo: String)
data class RegistroTopico(
    val id: UUID, val topicoId: UUID, val operacao: String, val status: String, val progresso: Int, val modelo: String,
    val aprovadas: Int, val rejeitadas: Int, val repetidas: Int, val tokensEntrada: Int, val tokensSaida: Int, val custo: BigDecimal,
    val criadoEm: Instant, val atualizadoEm: Instant, val motivo: String?,
)
data class QuestaoLinha(val id: UUID, val enunciado: String, val alternativas: String, val respostaCorreta: Int, val criadoEm: Instant)
data class ApoioLinha(val questaoId: UUID, val nomeArquivo: String, val ordem: Int, val numeroPagina: Int?)

/** Totais de uma operação com IA, gravados de uma vez a cada etapa (valores absolutos, não incrementos). */
class Acumulado {
    var tokensEntrada = 0; var tokensSaida = 0; var custo: BigDecimal = BigDecimal.ZERO
    var aprovadas = 0; var rejeitadas = 0; var repetidas = 0
}

/** JDBC da F3 (resumo, questões, registros por tópico). Sempre em transação gerenciada: a RLS filtra a conta, então nenhuma consulta repete `usuario_id`. */
@Repository
class GeracaoDados(private val jdbc: JdbcTemplate) {
    private val abertas = "('pendente','em_andamento')"
    private val operacoes = "('resumo','questoes','verificacao_questao')"

    fun topico(id: UUID): TopicoLinha? =
        jdbc.query("SELECT id, assunto_id, nome FROM topico WHERE id = ?", { rs, _ -> TopicoLinha(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3)) }, id).firstOrNull()

    /** Trava a linha do tópico até o fim da transação: dois pedidos ao mesmo tempo não passam juntos pela checagem de "já há geração em andamento". */
    fun travarTopico(id: UUID): TopicoLinha? =
        jdbc.query("SELECT id, assunto_id, nome FROM topico WHERE id = ? FOR UPDATE", { rs, _ -> TopicoLinha(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3)) }, id).firstOrNull()

    fun topicosLivres(limite: Int): List<TopicoLivreDto> = jdbc.query(
        """SELECT t.id, t.nome, t.criado_em, (SELECT count(*) FROM questao q WHERE q.topico_id = t.id) AS questoes, EXISTS (SELECT 1 FROM resumo r WHERE r.topico_id = t.id) AS resumo
           FROM topico t WHERE NOT EXISTS (SELECT 1 FROM topico_fonte f WHERE f.topico_id = t.id) ORDER BY t.criado_em DESC LIMIT ?""",
        { rs, _ -> TopicoLivreDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4), rs.getBoolean(5)) }, limite,
    )

    /** Tópicos com resumo ou questões sendo gerados agora (operação aberta com pulso recente: uma abandonada num reinício não conta). */
    fun topicosGerando(pulsoSegundos: Long): Set<UUID> = jdbc.query(
        "SELECT DISTINCT topico_id FROM registro_geracao WHERE topico_id IS NOT NULL AND operacao IN $operacoes AND status IN $abertas AND atualizado_em > now() - make_interval(secs => ?)",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, pulsoSegundos.toDouble(),
    ).toSet()

    fun materiaisGerando(pulsoSegundos: Long): Set<UUID> = jdbc.query(
        """SELECT DISTINCT f.material_id FROM registro_geracao r JOIN topico_fonte f ON f.topico_id = r.topico_id
           WHERE r.operacao IN $operacoes AND r.status IN $abertas AND r.atualizado_em > now() - make_interval(secs => ?)""",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, pulsoSegundos.toDouble(),
    ).toSet()

    /** Gerações (resumo ou questões) abertas do usuário, com pulso recente: uma abandonada num reinício não conta. */
    fun emAndamentoDoUsuario(pulsoSegundos: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM registro_geracao WHERE operacao IN ('resumo','questoes') AND status IN $abertas AND atualizado_em > now() - make_interval(secs => ?)",
        Int::class.java, pulsoSegundos.toDouble(),
    )!!

    /** Registro de um pedido que nunca começou (fila cheia): some, em vez de ficar como falha e gastar a cota do usuário. */
    fun removerRegistrosAbertos(ids: List<UUID>) {
        ids.forEach { jdbc.update("DELETE FROM registro_geracao WHERE id = ? AND status IN $abertas", it) }
    }

    fun fontes(topicoId: UUID): List<FonteLinha> = jdbc.query(
        """SELECT f.material_id, m.nome_arquivo, f.ordem_trecho_inicial, f.ordem_trecho_final FROM topico_fonte f JOIN material m ON m.id = f.material_id
           WHERE f.topico_id = ? ORDER BY f.ordem_trecho_inicial""",
        { rs, _ -> FonteLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getInt(4)) }, topicoId,
    )

    /** Soma dos caracteres de todas as faixas das fontes: decide se o tópico cabe inteiro no contexto. */
    fun caracteresDasFaixas(topicoId: UUID): Long = jdbc.queryForObject(
        """SELECT coalesce(sum(length(t.conteudo)), 0) FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ?""",
        Long::class.java, topicoId,
    )!!

    private fun trecho(rs: java.sql.ResultSet, n: Int) = TrechoBase(rs.getObject("id", UUID::class.java), rs.getObject("material_id", UUID::class.java), rs.getInt("ordem"), rs.getString("conteudo"))

    fun trechosDasFaixas(topicoId: UUID): List<TrechoBase> = jdbc.query(
        """SELECT t.id, t.material_id, t.ordem, t.conteudo FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ? ORDER BY f.ordem_trecho_inicial, t.ordem""",
        ::trecho, topicoId,
    )

    /** Busca vetorial EXATA (sem índice aproximado, regra 9), filtrada por material e pela faixa de cada fonte do tópico. */
    fun trechosMaisSimilares(topicoId: UUID, vetor: List<Double>, limite: Int, naoCobertosPrimeiro: Boolean = false): List<TrechoBase> = jdbc.query(
        """SELECT t.id, t.material_id, t.ordem, t.conteudo FROM topico_fonte f JOIN trecho t ON t.material_id = f.material_id AND t.ordem BETWEEN f.ordem_trecho_inicial AND f.ordem_trecho_final
           WHERE f.topico_id = ?
           ORDER BY ${if (naoCobertosPrimeiro) "EXISTS (SELECT 1 FROM questao_trecho qt WHERE qt.trecho_id = t.id)," else ""} t.embedding <=> ?::vector, t.material_id, t.ordem LIMIT ?""",
        ::trecho, topicoId, vetor.joinToString(",", "[", "]"), limite,
    )

    // ---- registros
    fun criarRegistro(id: UUID, usuario: UUID, topicoId: UUID, livre: Boolean, operacao: String, modelo: String) {
        jdbc.update(
            """INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual)
               VALUES (?,?,?,?,?,?,'pendente',0,0,0,0,0,0,0)""",
            id, usuario, topicoId, operacao, if (livre) "topico_livre" else "material", modelo,
        )
    }

    /**
     * Grava o estado da operação. Em `concluida` o progresso é 100 e em `em_andamento` nunca passa de 99 (CHECK do banco e regra 14).
     * Só mexe em operação ainda aberta: uma operação cancelada (ou dada como abandonada) não é reaberta nem sobrescrita pela tarefa que ainda corria.
     * Devolve falso quando a operação já não estava aberta.
     */
    fun gravar(id: UUID, status: String, pct: Int, modelo: String, a: Acumulado, motivo: String? = null): Boolean = jdbc.update(
        """UPDATE registro_geracao SET status=?, progresso_percentual=?, modelo_ia=?, tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?,
                  questoes_aprovadas=?, questoes_rejeitadas=?, questoes_repetidas=?, motivo_falha=?, finalizada_em = CASE WHEN ? IN ('concluida','falhou') THEN now() ELSE NULL END
           WHERE id=? AND status IN $abertas""",
        status, if (status == "concluida") 100 else pct.coerceIn(0, 99), modelo, a.tokensEntrada, a.tokensSaida, a.custo, a.aprovadas, a.rejeitadas, a.repetidas,
        if (status == "falhou") motivo else null, status, id,
    ) > 0

    fun estaAberto(id: UUID): Boolean = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM registro_geracao WHERE id = ? AND status IN $abertas)", Boolean::class.java, id)!!

    /** O que uma tarefa cancelada gastou até parar (a chamada ao modelo em curso não se interrompe): só tokens e custo, o estado fica como o cancelamento deixou. */
    fun gravarConsumo(id: UUID, a: Acumulado) {
        jdbc.update("UPDATE registro_geracao SET tokens_entrada=?, tokens_saida=?, custo_estimado_usd=?, questoes_aprovadas=?, questoes_rejeitadas=?, questoes_repetidas=? WHERE id=?",
            a.tokensEntrada, a.tokensSaida, a.custo, a.aprovadas, a.rejeitadas, a.repetidas, id)
    }

    private fun registro(rs: java.sql.ResultSet, n: Int) = RegistroTopico(
        rs.getObject("id", UUID::class.java), rs.getObject("topico_id", UUID::class.java), rs.getString("operacao"), rs.getString("status"), rs.getInt("progresso_percentual"),
        rs.getString("modelo_ia"), rs.getInt("questoes_aprovadas"), rs.getInt("questoes_rejeitadas"), rs.getInt("questoes_repetidas"), rs.getInt("tokens_entrada"),
        rs.getInt("tokens_saida"), rs.getBigDecimal("custo_estimado_usd"), rs.getTimestamp("criado_em").toInstant(), rs.getTimestamp("atualizado_em").toInstant(), rs.getString("motivo_falha"),
    )

    fun registro(id: UUID): RegistroTopico? =
        jdbc.query("SELECT * FROM registro_geracao WHERE id = ? AND topico_id IS NOT NULL AND operacao IN $operacoes", ::registro, id).firstOrNull()

    fun registrosAbertos(topicoId: UUID): List<RegistroTopico> =
        jdbc.query("SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao IN $operacoes AND status IN $abertas ORDER BY criado_em", ::registro, topicoId)

    /** A verificação criada junto com a geração de questões (um pedido por tópico por vez, então a primeira criada depois dela é a dela). */
    fun verificacaoDe(questoes: RegistroTopico): RegistroTopico? = jdbc.query(
        "SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao = 'verificacao_questao' AND criado_em >= ? ORDER BY criado_em LIMIT 1", ::registro,
        questoes.topicoId, java.sql.Timestamp.from(questoes.criadoEm),
    ).firstOrNull()

    fun falharAbertos(ids: List<UUID>, motivo: String) {
        ids.forEach { jdbc.update("UPDATE registro_geracao SET status='falhou', motivo_falha=?, finalizada_em=now() WHERE id=? AND status IN $abertas", motivo, it) }
    }

    // ---- resumo e questões
    fun inserirResumo(id: UUID, usuario: UUID, topicoId: UUID, conteudo: String) {
        jdbc.update("INSERT INTO resumo(id,usuario_id,topico_id,conteudo) VALUES (?,?,?,?)", id, usuario, topicoId, conteudo)
    }

    fun resumoRecente(topicoId: UUID): Pair<String, Instant>? = jdbc.query(
        "SELECT conteudo, criado_em FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id LIMIT 1", { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }, topicoId,
    ).firstOrNull()

    fun resumos(topicoId: UUID): List<Pair<String, Instant>> = jdbc.query(
        "SELECT conteudo, criado_em FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id", { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }, topicoId,
    )

    /** Mantém só os [maximo] resumos mais recentes do tópico (o histórico não cresce sem limite). */
    fun aparaResumos(topicoId: UUID, maximo: Int) {
        jdbc.update("DELETE FROM resumo WHERE topico_id = ? AND id NOT IN (SELECT id FROM resumo WHERE topico_id = ? ORDER BY criado_em DESC, id LIMIT ?)", topicoId, topicoId, maximo)
    }

    fun totalQuestoes(topicoId: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?", Int::class.java, topicoId)!!
    fun existeHash(topicoId: UUID, hash: String): Boolean = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM questao WHERE topico_id = ? AND enunciado_hash = ?)", Boolean::class.java, topicoId, hash)!!
    fun enunciadosRecentes(topicoId: UUID, n: Int): List<String> =
        jdbc.query("SELECT enunciado FROM questao WHERE topico_id = ? ORDER BY criado_em DESC LIMIT ?", { rs, _ -> rs.getString(1) }, topicoId, n)

    fun inserirQuestao(
        id: UUID, usuario: UUID, topicoId: UUID, enunciado: String, alternativasJson: String, correta: Int, hash: String, vetor: List<Double>, modeloEmbedding: String, trechos: Collection<UUID>,
    ) {
        jdbc.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?,?,?::jsonb,?,?,?::vector,?)",
            id, usuario, topicoId, enunciado, alternativasJson, correta, hash, vetor.joinToString(",", "[", "]"), modeloEmbedding,
        )
        trechos.forEach { jdbc.update("INSERT INTO questao_trecho(questao_id,trecho_id,usuario_id) VALUES (?,?,?)", id, it, usuario) }
    }

    fun questoes(topicoId: UUID): List<QuestaoLinha> = jdbc.query(
        "SELECT id, enunciado, alternativas::text, resposta_correta, criado_em FROM questao WHERE topico_id = ? ORDER BY criado_em, id",
        { rs, _ -> QuestaoLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant()) }, topicoId,
    )

    /** Trechos que sustentam cada questão do tópico (questao_trecho), para mostrar a fonte na tela. */
    fun apoios(topicoId: UUID): List<ApoioLinha> = jdbc.query(
        """SELECT qt.questao_id, m.nome_arquivo, t.ordem, t.numero_pagina FROM questao_trecho qt JOIN questao q ON q.id = qt.questao_id
           JOIN trecho t ON t.id = qt.trecho_id JOIN material m ON m.id = t.material_id WHERE q.topico_id = ? ORDER BY t.material_id, t.ordem""",
        { rs, _ -> ApoioLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getObject(4) as Int?) }, topicoId,
    )

    /** A geração de questões mais recente já terminada (para mostrar o resultado depois de recarregar a página). */
    fun ultimaDeQuestoes(topicoId: UUID): RegistroTopico? = jdbc.query(
        "SELECT * FROM registro_geracao WHERE topico_id = ? AND operacao = 'questoes' AND status IN ('concluida','falhou') ORDER BY criado_em DESC LIMIT 1", ::registro, topicoId,
    ).firstOrNull()
}
