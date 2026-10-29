package br.nexo.estudo

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.UsuarioAtual
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** `JsonNode` e não `Int`: o Jackson converteria 1.5 em 1 e a resposta cairia na alternativa errada. */
data class RespostaRequest(val alternativa: tools.jackson.databind.JsonNode? = null)
/**
 * Resposta de uma questão.
 * @property correta se acertou.
 * @property respostaCorreta índice da alternativa correta.
 * @property proximaRevisao data da próxima revisão da questão.
 */
data class RespostaDto(val correta: Boolean, val respostaCorreta: Int, val proximaRevisao: LocalDate)
/**
 * Tópico com questões vencidas.
 * @property id tópico.
 * @property nome nome do tópico.
 * @property vencidas quantidade de questões vencidas.
 * @property taxaErro respostas incorretas ÷ respostas do tópico.
 */
data class TopicoRevisaoDto(val id: UUID, val nome: String, val vencidas: Int, val taxaErro: Double)
/**
 * Questão da sessão de revisão (sem a resposta correta).
 * @property id questão.
 * @property topicoId tópico.
 * @property topico nome do tópico.
 * @property enunciado texto da pergunta.
 * @property alternativas alternativas.
 * @property dica dica gravada, se houver.
 * @property dificuldade facil, media ou dificil.
 */
data class QuestaoRevisaoDto(val id: UUID, val topicoId: UUID, val topico: String, val enunciado: String, val alternativas: List<String>, val dica: String?, val dificuldade: String)
/**
 * Tópico resumido (atalho para gerar questões novas).
 * @property id tópico.
 * @property nome nome do tópico.
 */
data class TopicoSimplesDto(val id: UUID, val nome: String)
/**
 * Sessão de revisão do dia.
 * @property topicos tópicos com questões vencidas.
 * @property questoes questões da sessão, limitadas por `questoesPorSessaoRevisao`.
 * @property recentes tópicos estudados há pouco.
 */
data class RevisaoDto(val topicos: List<TopicoRevisaoDto>, val questoes: List<QuestaoRevisaoDto>, val recentes: List<TopicoSimplesDto>)
/**
 * Respostas de uma semana.
 * @property semana segunda-feira da semana.
 * @property total respostas.
 * @property acertos respostas certas.
 */
data class PontoDto(val semana: LocalDate, val total: Int, val acertos: Int)
/**
 * Série semanal de um tópico.
 * @property id tópico.
 * @property nome nome do tópico.
 * @property total respostas no período.
 * @property acertos respostas certas no período.
 * @property pontos pontos por semana.
 */
data class SerieDto(val id: UUID, val nome: String, val total: Int, val acertos: Int, val pontos: List<PontoDto>)
/**
 * Desempenho de um assunto, com a série de cada tópico.
 * @property id assunto.
 * @property nome nome do assunto.
 * @property total respostas no período.
 * @property acertos respostas certas no período.
 * @property pontos pontos por semana.
 * @property topicos séries por tópico.
 */
data class AssuntoDesempenhoDto(val id: UUID, val nome: String, val total: Int, val acertos: Int, val pontos: List<PontoDto>, val topicos: List<SerieDto>)
/**
 * Tópico com o pior desempenho.
 * @property id tópico.
 * @property nome nome do tópico.
 * @property assunto nome do assunto.
 * @property total respostas.
 * @property acertos respostas certas.
 * @property percentual percentual de acerto.
 */
data class TopicoFracoDto(val id: UUID, val nome: String, val assunto: String, val total: Int, val acertos: Int, val percentual: Double)
/**
 * Painel de desempenho.
 * @property vazio se não há respostas no período.
 * @property semanas segundas-feiras do período.
 * @property assuntos desempenho por assunto.
 * @property fracos tópicos mais fracos.
 */
data class DesempenhoDto(val vazio: Boolean, val semanas: List<LocalDate>, val assuntos: List<AssuntoDesempenhoDto>, val fracos: List<TopicoFracoDto>)

/** O usuário sai sempre do JWT; a RLS faz questão de outra conta virar 404. */
@Service
class Estudo(
    private val props: NexoProperties,
    private val dados: EstudoDados,
    private val tx: TransactionTemplate,
    private val relogio: Clock,
    private val json: JsonMapper,
) {
    /**
     * Data de hoje no fuso configurado.
     * @return a data local.
     */
    private fun hoje(): LocalDate = LocalDate.now(relogio.withZone(props.fuso))

    /**
     * Registra a resposta, agenda a próxima revisão (SM-2) e devolve o resultado, numa transação.
     * @param questaoId questão respondida.
     * @param alternativa índice da alternativa escolhida.
     * @return se acertou, qual era a correta e a próxima revisão.
     * @throws ErroNegocio 404 se a questão não for do usuário; 400 se a alternativa não existir.
     */
    fun responder(questaoId: UUID, alternativa: Int): RespostaDto = tx.execute {
        val q = dados.questao(questaoId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Questão não encontrada.", "nao_encontrado")
        if (alternativa !in 0 until q.alternativas) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha uma das alternativas.", "dados_invalidos")
        val uid = UsuarioAtual.id()!!
        val hoje = hoje()
        val correta = alternativa == q.respostaCorreta
        dados.inserirResposta(UUID.randomUUID(), uid, questaoId, alternativa, correta, Instant.now(relogio))
        val p = props.revisao
        val (atual, primeira) = dados.travarAgendamento(uid, questaoId, Agendamento(p.repeticoesInicial, p.intervaloInicial, p.fatorInicial, hoje, hoje))
        val novo = reagendar(atual, primeira, correta, hoje, p)
        if (novo != atual) dados.gravarAgendamento(questaoId, novo)
        RespostaDto(correta, q.respostaCorreta, novo.proximaRevisao)
    }!!

    /**
     * Monta a sessão de revisão: tópicos com questões vencidas (mais vencidas primeiro) e até `questoesPorSessaoRevisao` questões.
     * @param escolhidos tópicos a incluir na sessão; nulo ou vazio = todos. Ids que não são do usuário (ou sem questões vencidas) são ignorados. A lista `topicos` da resposta traz sempre todos.
     * @param comQuestoes falso devolve só os tópicos e os recentes, sem montar as questões (usado para a contagem da tela inicial).
     * @return tópicos, questões e tópicos recentes.
     */
    fun revisao(escolhidos: Set<UUID>? = null, comQuestoes: Boolean = true): RevisaoDto = tx.execute {
        val hoje = hoje()
        val topicos = dados.topicosParaRevisar(hoje)
        val questoes = mutableListOf<QuestaoRevisaoDto>()
        for (t in if (comQuestoes) topicos.filter { escolhidos.isNullOrEmpty() || it.id in escolhidos } else emptyList()) {
            val falta = props.questoesPorSessaoRevisao - questoes.size
            if (falta <= 0) break
            dados.vencidasDoTopico(t.id, hoje, falta).forEach {
                questoes += QuestaoRevisaoDto(it.id, it.topicoId, t.nome, it.enunciado, json.readTree(it.alternativas).toList().map { a -> a.asString() }, it.dica, it.dificuldade)
            }
        }
        RevisaoDto(topicos.map { TopicoRevisaoDto(it.id, it.nome, it.vencidas, it.taxaErro) }, questoes, dados.topicosRecentes(props.painel.topicosRecentes).map { TopicoSimplesDto(it.id, it.nome) })
    }!!

    /**
     * Painel semanal de desempenho (segunda a domingo, no fuso configurado).
     * @param semanasPedidas número de semanas, ou nulo para o padrão (limitado ao máximo).
     * @return desempenho por assunto e tópico e os tópicos mais fracos.
     */
    fun desempenho(semanasPedidas: Int?): DesempenhoDto = tx.execute {
        val n = (semanasPedidas ?: props.painel.semanas).coerceIn(1, props.painel.semanasMaximo)
        val segunda = hoje().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val primeira = segunda.minusWeeks(n - 1L)
        val linhas = dados.respostasPorSemana(primeira.atStartOfDay(props.fuso).toInstant(), props.fuso.id)
        /**
         * Soma as linhas em pontos por semana.
         * @param l linhas semanais de um assunto ou tópico.
         * @return pontos ordenados por semana.
         */
        fun pontos(l: List<LinhaSemanal>) = l.groupBy { it.semana }.toSortedMap().map { (s, v) -> PontoDto(s, v.sumOf { it.total }, v.sumOf { it.acertos }) }
        val assuntos = linhas.groupBy { it.assuntoId }.map { (id, l) ->
            AssuntoDesempenhoDto(id, l.first().assunto, l.sumOf { it.total }, l.sumOf { it.acertos }, pontos(l),
                l.groupBy { it.topicoId }.map { (tid, tl) -> SerieDto(tid, tl.first().topico, tl.sumOf { it.total }, tl.sumOf { it.acertos }, pontos(tl)) })
        }
        val fracos = assuntos.flatMap { a -> a.topicos.map { t -> TopicoFracoDto(t.id, t.nome, a.nome, t.total, t.acertos, t.acertos * 100.0 / t.total) } }
            .filter { it.total >= props.painel.minimoRespostas }.sortedWith(compareBy({ it.percentual }, { -it.total })).take(props.painel.fracos)
        DesempenhoDto(linhas.isEmpty(), (0 until n).map { primeira.plusWeeks(it.toLong()) }, assuntos, fracos)
    }!!
}

/** Rotas de estudo: responder questão, revisão do dia e painel de desempenho. */
@RestController
class EstudoController(private val estudo: Estudo) {
    /**
     * Responde a uma questão.
     * @param id questão.
     * @param r alternativa escolhida (número inteiro).
     * @return o resultado da resposta.
     */
    @PostMapping("/questoes/{id}/respostas")
    fun responder(@PathVariable id: UUID, @RequestBody r: RespostaRequest) = estudo.responder(id, r.alternativa?.takeIf { it.isInt }?.asInt() ?: -1)

    /**
     * Sessão de revisão do dia.
     * @param topicos ids dos tópicos a revisar, separados por vírgula; sem o parâmetro, todos.
     * @param resumo verdadeiro devolve só os tópicos vencidos e os recentes, sem as questões.
     * @return tópicos vencidos e questões da sessão.
     */
    @GetMapping("/revisao")
    fun revisao(@RequestParam(required = false) topicos: List<UUID>?, @RequestParam(defaultValue = "false") resumo: Boolean) = estudo.revisao(topicos?.toSet(), !resumo)

    /**
     * Painel de desempenho.
     * @param semanas número de semanas, opcional.
     * @return o painel.
     */
    @GetMapping("/desempenho")
    fun desempenho(@RequestParam(required = false) semanas: Int?) = estudo.desempenho(semanas)
}
