package br.nexo.estudo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Questão lida para gerar a dica.
 * @property topicoId tópico.
 * @property enunciado pergunta.
 * @property alternativas alternativas em JSON.
 * @property respostaCorreta índice da correta.
 * @property dica dica já gravada, se houver.
 */
data class QuestaoDaDica(val topicoId: UUID, val enunciado: String, val alternativas: String, val respostaCorreta: Int, val dica: String?)
/**
 * Mensagem do chat lida do banco.
 * @property id identificador.
 * @property papel usuario ou assistente.
 * @property conteudo texto.
 * @property criadoEm quando foi gravada.
 */
data class MensagemLinha(val id: UUID, val papel: String, val conteudo: String, val criadoEm: Instant)

/** JDBC da dica e do chat, dentro de transação gerenciada: a RLS filtra a conta. */
@Repository
class TutorDados(private val jdbc: JdbcTemplate) {
    /**
     * Lê a questão para a dica.
     * @param id questão.
     * @return a questão, ou nulo se não for do usuário (RLS).
     */
    fun questao(id: UUID): QuestaoDaDica? = jdbc.query(
        "SELECT topico_id, enunciado, alternativas::text, resposta_correta, dica FROM questao WHERE id = ?",
        { rs, _ -> QuestaoDaDica(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)) }, id,
    ).firstOrNull()

    /**
     * Os trechos que sustentam a questão (verificação de fidelidade), na ordem do material. Vazio em tópico livre.
     * @param id questão.
     * @return o texto dos trechos.
     */
    fun trechosDaQuestao(id: UUID): List<String> = jdbc.query(
        "SELECT t.conteudo FROM questao_trecho qt JOIN trecho t ON t.id = qt.trecho_id WHERE qt.questao_id = ? ORDER BY t.material_id, t.ordem",
        { rs, _ -> rs.getString(1) }, id,
    )

    /**
     * Só grava se ainda não houver dica (duas chamadas ao mesmo tempo: vale a primeira).
     * @param id questão.
     * @param texto texto da dica.
     * @return verdadeiro se gravou.
     */
    fun gravarDica(id: UUID, texto: String): Boolean = jdbc.update("UPDATE questao SET dica = ? WHERE id = ? AND dica IS NULL", texto, id) > 0
    /**
     * Lê a dica gravada.
     * @param id questão.
     * @return a dica, ou nulo se não houver.
     */
    fun dicaDe(id: UUID): String? = jdbc.query("SELECT dica FROM questao WHERE id = ?", { rs, _ -> rs.getString(1) }, id).firstOrNull()

    /**
     * As [limite] mais recentes, devolvidas da mais antiga para a mais nova. Em empate de horário (mesma transação) o estudante vem antes do tutor.
     * @param topicoId tópico.
     * @param limite máximo de mensagens.
     * @return as mensagens em ordem cronológica.
     */
    fun mensagens(topicoId: UUID, limite: Int): List<MensagemLinha> = jdbc.query(
        """SELECT id, papel, conteudo, criado_em FROM (
             SELECT id, papel, conteudo, criado_em FROM mensagem_chat WHERE topico_id = ? ORDER BY criado_em DESC, (papel = 'usuario') ASC LIMIT ?) ultimas
           ORDER BY criado_em, (papel = 'usuario') DESC""",
        { rs, _ -> MensagemLinha(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()) }, topicoId, limite,
    )

    /**
     * Conta as mensagens do chat do tópico.
     * @param topicoId tópico.
     * @return quantidade de mensagens.
     */
    fun totalMensagens(topicoId: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM mensagem_chat WHERE topico_id = ?", Int::class.java, topicoId)!!

    /**
     * Grava uma mensagem do chat.
     * @param id identificador gerado pelo backend.
     * @param usuario dono.
     * @param topicoId tópico.
     * @param papel usuario ou assistente.
     * @param conteudo texto.
     */
    fun inserirMensagem(id: UUID, usuario: UUID, topicoId: UUID, papel: String, conteudo: String) {
        jdbc.update("INSERT INTO mensagem_chat(id,usuario_id,topico_id,papel,conteudo) VALUES (?,?,?,?,?)", id, usuario, topicoId, papel, conteudo)
    }

    /**
     * Apaga todas as mensagens do chat do tópico.
     * @param topicoId tópico.
     */
    fun limparChat(topicoId: UUID) { jdbc.update("DELETE FROM mensagem_chat WHERE topico_id = ?", topicoId) }
}
