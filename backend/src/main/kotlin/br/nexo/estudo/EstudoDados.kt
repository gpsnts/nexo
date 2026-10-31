package br.nexo.estudo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Dados mínimos de uma questão para validar a resposta.
 * @property respostaCorreta índice da correta.
 * @property alternativas quantidade de alternativas.
 */
data class QuestaoParaResponder(val respostaCorreta: Int, val alternativas: Int)
/**
 * Tópico com questões vencidas.
 * @property id tópico.
 * @property nome nome.
 * @property vencidas questões vencidas.
 * @property taxaErro taxa de erro nas respostas do tópico.
 */
data class TopicoParaRevisar(val id: UUID, val nome: String, val vencidas: Int, val taxaErro: Double)
/**
 * Questão vencida para revisão.
 * @property id questão.
 * @property topicoId tópico.
 * @property enunciado pergunta.
 * @property alternativas alternativas em JSON.
 * @property proximaRevisao data de vencimento.
 * @property dica dica, se houver.
 * @property dificuldade nível da questão.
 */
data class QuestaoVencida(val id: UUID, val topicoId: UUID, val enunciado: String, val alternativas: String, val proximaRevisao: LocalDate, val dica: String?, val dificuldade: String = "media")
/**
 * Tópico estudado há pouco.
 * @property id tópico.
 * @property nome nome.
 */
data class TopicoRecente(val id: UUID, val nome: String)
/**
 * Respostas de um tópico numa semana.
 * @property assuntoId assunto.
 * @property assunto nome do assunto.
 * @property topicoId tópico.
 * @property topico nome do tópico.
 * @property semana segunda-feira da semana.
 * @property total respostas.
 * @property acertos respostas certas.
 */
data class LinhaSemanal(val assuntoId: UUID, val assunto: String, val topicoId: UUID, val topico: String, val semana: LocalDate, val total: Int, val acertos: Int)

/** JDBC dentro de transação gerenciada: a RLS filtra por conta, então nenhuma consulta repete `usuario_id` no WHERE (como `Dados`). */
@Repository
class EstudoDados(private val jdbc: JdbcTemplate) {
    /**
     * Busca os dados mínimos da questão.
     * @param id questão.
     * @return a questão, ou nulo se não for do usuário (RLS).
     */
    fun questao(id: UUID): QuestaoParaResponder? = jdbc.query(
        "SELECT resposta_correta, jsonb_array_length(alternativas) FROM questao WHERE id = ?", { rs, _ -> QuestaoParaResponder(rs.getInt(1), rs.getInt(2)) }, id,
    ).firstOrNull()

    /**
     * Grava uma resposta.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param questaoId questão respondida.
     * @param alternativa índice escolhido.
     * @param correta se acertou.
     * @param quando instante da resposta.
     * @param provaId prova em que foi dada, ou nulo.
     */
    fun inserirResposta(id: UUID, usuario: UUID, questaoId: UUID, alternativa: Int, correta: Boolean, quando: Instant, provaId: UUID? = null) {
        jdbc.update(
            "INSERT INTO resposta(id,usuario_id,questao_id,alternativa_escolhida,correta,respondida_em,prova_id) VALUES (?,?,?,?,?,?,?)",
            id, usuario, questaoId, alternativa, correta, Timestamp.from(quando), provaId,
        )
    }

    /**
     * Cria o agendamento inicial se não existir (devolve `primeira`) e trava a linha até o fim da transação: duas respostas simultâneas à mesma questão se serializam. Quem perde a corrida do INSERT espera o commit do outro e vê `primeira = false`.
     * @param usuario dono.
     * @param questaoId questão.
     * @param inicial estado a gravar se ainda não houver.
     * @return o estado atual e se esta é a primeira resposta.
     */
    fun travarAgendamento(usuario: UUID, questaoId: UUID, inicial: Agendamento): Pair<Agendamento, Boolean> {
        val primeira = jdbc.update(
            "INSERT INTO progresso_questao(id,usuario_id,questao_id,repeticoes,intervalo_dias,fator_facilidade,ultima_revisao,proxima_revisao) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (usuario_id, questao_id) DO NOTHING",
            UUID.randomUUID(), usuario, questaoId, inicial.repeticoes, inicial.intervaloDias, inicial.fator, inicial.ultimaRevisao, inicial.proximaRevisao,
        ) == 1
        val atual = jdbc.queryForObject(
            "SELECT repeticoes, intervalo_dias, fator_facilidade, ultima_revisao, proxima_revisao FROM progresso_questao WHERE questao_id = ? FOR UPDATE",
            { rs, _ -> Agendamento(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getObject(4, LocalDate::class.java), rs.getObject(5, LocalDate::class.java)) }, questaoId,
        )!!
        return atual to primeira
    }

    /**
     * Grava o novo agendamento.
     * @param questaoId questão.
     * @param a novo estado.
     */
    fun gravarAgendamento(questaoId: UUID, a: Agendamento) {
        jdbc.update(
            "UPDATE progresso_questao SET repeticoes=?, intervalo_dias=?, fator_facilidade=?, ultima_revisao=?, proxima_revisao=? WHERE questao_id=?",
            a.repeticoes, a.intervaloDias, a.fator, a.ultimaRevisao, a.proximaRevisao, questaoId,
        )
    }

    /**
     * Tópicos com questões vencidas: mais vencidas primeiro e, no empate, maior taxa de erro (respostas incorretas ÷ respostas do tópico).
     * @param hoje data de corte.
     * @return os tópicos em ordem de urgência.
     */
    fun topicosParaRevisar(hoje: LocalDate): List<TopicoParaRevisar> = jdbc.query(
        """SELECT t.id, t.nome, count(*) AS vencidas,
                  COALESCE((SELECT count(*) FILTER (WHERE NOT r.correta)::float8 / NULLIF(count(*), 0) FROM resposta r JOIN questao q2 ON q2.id = r.questao_id WHERE q2.topico_id = t.id), 0) AS taxa
           FROM progresso_questao p JOIN questao q ON q.id = p.questao_id JOIN topico t ON t.id = q.topico_id
           WHERE p.proxima_revisao <= ? GROUP BY t.id, t.nome ORDER BY vencidas DESC, taxa DESC, t.id""",
        { rs, _ -> TopicoParaRevisar(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getDouble(4)) }, hoje,
    )

    /**
     * Questões vencidas de um tópico, da mais antiga à mais nova.
     * @param topicoId tópico.
     * @param hoje data de corte.
     * @param limite máximo de questões.
     * @return as questões vencidas.
     */
    fun vencidasDoTopico(topicoId: UUID, hoje: LocalDate, limite: Int): List<QuestaoVencida> = jdbc.query(
        """SELECT q.id, q.topico_id, q.enunciado, q.alternativas::text, p.proxima_revisao, q.dica, q.dificuldade FROM progresso_questao p JOIN questao q ON q.id = p.questao_id
           WHERE q.topico_id = ? AND p.proxima_revisao <= ? ORDER BY p.proxima_revisao, q.criado_em, q.id LIMIT ?""",
        { rs, _ -> QuestaoVencida(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate::class.java), rs.getString(6), rs.getString(7)) }, topicoId, hoje, limite,
    )

    /**
     * Tópicos em que o estudante respondeu algo, do mais recente para o mais antigo: servem de atalho para gerar questões novas.
     * @param limite máximo de tópicos.
     * @return os tópicos recentes.
     */
    fun topicosRecentes(limite: Int): List<TopicoRecente> = jdbc.query(
        """SELECT t.id, t.nome FROM topico t JOIN (SELECT q.topico_id, max(r.respondida_em) AS ultima FROM resposta r JOIN questao q ON q.id = r.questao_id GROUP BY q.topico_id) x ON x.topico_id = t.id
           ORDER BY x.ultima DESC, t.id LIMIT ?""",
        { rs, _ -> TopicoRecente(rs.getObject(1, UUID::class.java), rs.getString(2)) }, limite,
    )

    /**
     * Respostas desde [desde] por assunto, tópico e semana (segunda a domingo no [fuso]; `date_trunc('week')` do Postgres começa na segunda).
     * @param desde início do período.
     * @param fuso nome do fuso (ex.: America/Sao_Paulo).
     * @return uma linha por tópico e semana.
     */
    fun respostasPorSemana(desde: Instant, fuso: String): List<LinhaSemanal> = jdbc.query(
        """SELECT a.id, a.nome, t.id, t.nome, date_trunc('week', r.respondida_em AT TIME ZONE ?)::date AS semana, count(*), count(*) FILTER (WHERE r.correta)
           FROM resposta r JOIN questao q ON q.id = r.questao_id JOIN topico t ON t.id = q.topico_id JOIN assunto a ON a.id = t.assunto_id
           WHERE r.respondida_em >= ? GROUP BY a.id, a.nome, t.id, t.nome, semana ORDER BY a.nome, t.nome, semana""",
        { rs, _ -> LinhaSemanal(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getObject(5, LocalDate::class.java), rs.getInt(6), rs.getInt(7)) },
        fuso, Timestamp.from(desde),
    )
}
