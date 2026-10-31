package br.nexo.estudo

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.UsuarioAtual
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Um assunto escolhido para a prova.
 * @property assuntoId pasta (assunto) de onde saem as questões.
 * @property topicoLimiteId último tópico já estudado; nulo = entram todos os tópicos do assunto.
 */
data class ProvaAssuntoRequest(val assuntoId: UUID, val topicoLimiteId: UUID? = null)
/**
 * Corpo de `POST /provas`.
 * @property assuntos assuntos escolhidos, na ordem que vale para repartir a sobra da divisão.
 * @property quantidade número de questões pedido.
 * @property separadas verdadeiro gera uma prova por assunto, cada uma com a quantidade total; falso mistura os assuntos numa só.
 * @property limiteMinutos limite de tempo opcional, em minutos.
 */
data class ProvaRequest(
    @field:NotEmpty(message = "Escolha ao menos um assunto.") val assuntos: List<@Valid ProvaAssuntoRequest> = emptyList(),
    @field:Min(1, message = "Informe a quantidade de questões.") val quantidade: Int = 0,
    val separadas: Boolean = false,
    val limiteMinutos: Int? = null,
)
/**
 * Assunto de uma prova, com o tópico-limite.
 * @property id assunto.
 * @property nome nome do assunto.
 * @property topicoLimiteId tópico-limite, se houve.
 * @property topicoLimite nome do tópico-limite.
 */
data class ProvaAssuntoDto(val id: UUID, val nome: String, val topicoLimiteId: UUID?, val topicoLimite: String?)
/**
 * Prova resumida (lista e cabeçalho do detalhe).
 * @property id prova.
 * @property grupoId identificador do pedido quando as provas foram separadas; nulo em prova única.
 * @property status gerada, em_andamento ou concluida.
 * @property quantidade questões da prova.
 * @property respondidas questões já respondidas.
 * @property acertos respostas certas.
 * @property limiteMinutos limite de tempo, se houver.
 * @property iniciadaEm início da prova.
 * @property prazo instante em que o tempo acaba (nulo sem limite ou sem início).
 * @property concluidaEm fim da prova.
 * @property criadaEm criação.
 * @property assuntos assuntos e tópicos-limite.
 */
data class ProvaResumoDto(
    val id: UUID, val grupoId: UUID?, val status: String, val quantidade: Int, val respondidas: Int, val acertos: Int, val limiteMinutos: Int?,
    val iniciadaEm: Instant?, val prazo: Instant?, val concluidaEm: Instant?, val criadaEm: Instant, val assuntos: List<ProvaAssuntoDto>,
)
/**
 * Questão de uma prova. O gabarito só aparece depois que a questão é respondida.
 * @property ordem posição na prova.
 * @property id questão.
 * @property topicoId tópico.
 * @property topico nome do tópico.
 * @property assunto nome do assunto (pasta) do tópico hoje.
 * @property enunciado pergunta.
 * @property alternativas alternativas.
 * @property dica dica gravada, se houver.
 * @property dificuldade facil, media ou dificil.
 * @property respondida se já foi respondida nesta prova.
 * @property alternativaEscolhida alternativa marcada (só se respondida).
 * @property correta se acertou (só se respondida).
 * @property respostaCorreta gabarito (só se respondida).
 */
data class ProvaQuestaoDto(
    val ordem: Int, val id: UUID, val topicoId: UUID, val topico: String, val assunto: String, val enunciado: String, val alternativas: List<String>, val dica: String?, val dificuldade: String,
    val respondida: Boolean, val alternativaEscolhida: Int?, val correta: Boolean?, val respostaCorreta: Int?,
)
/**
 * Prova completa.
 * @property prova cabeçalho.
 * @property agora relógio do servidor, para a tela medir o tempo restante sem depender do relógio do aparelho.
 * @property questoes questões na ordem da prova.
 */
data class ProvaDto(val prova: ProvaResumoDto, val agora: Instant, val questoes: List<ProvaQuestaoDto>)
/**
 * Provas criadas por um pedido.
 * @property grupoId grupo das provas separadas (nulo se só uma prova foi criada).
 * @property provas provas criadas.
 */
data class ProvasCriadasDto(val grupoId: UUID?, val provas: List<ProvaResumoDto>)

/** Divisão da quantidade pedida entre os assuntos de uma prova. */
object CotaProva {
    /**
     * Reparte [pedido] igualmente; a sobra da divisão vai, uma a uma, aos primeiros assuntos, e o que um assunto não tem para dar é redistribuído entre os demais.
     * @param pedido quantidade total pedida.
     * @param disponiveis questões elegíveis de cada assunto, na ordem escolhida.
     * @return a cota de cada assunto (soma [pedido] se as questões elegíveis bastarem; senão, soma todas as elegíveis).
     */
    fun dividir(pedido: Int, disponiveis: List<Int>): List<Int> {
        val n = disponiveis.size
        val cota = MutableList(n) { minOf(pedido / n + if (it < pedido % n) 1 else 0, disponiveis[it]) }
        var falta = pedido - cota.sum()
        while (falta > 0) {
            var moveu = false
            for (i in 0 until n) if (falta > 0 && cota[i] < disponiveis[i]) { cota[i]++; falta--; moveu = true }
            if (!moveu) break
        }
        return cota
    }
}

/** Quantas provas a lista devolve (as mais recentes). */
private const val LIMITE_LISTA = 200

/** Montagem e resposta de provas por escopo; cada consulta é da conta autenticada (a RLS faz prova de outra conta virar 404). */
@Service
class Provas(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val relogio: Clock,
    private val json: JsonMapper,
) {
    /**
     * Usuário autenticado.
     * @return id do usuário do JWT.
     * @throws ErroNegocio 401 se não houver usuário.
     */
    private fun uid() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")

    /**
     * Instante atual do relógio da aplicação.
     * @return o instante.
     */
    private fun agora(): Instant = Instant.now(relogio)

    /**
     * Prova não encontrada (inexistente ou de outra conta).
     * @return o erro 404.
     */
    private fun naoEncontrada() = ErroNegocio(HttpStatus.NOT_FOUND, "Prova não encontrada.", "nao_encontrado")

    /**
     * Assunto escolhido, com a posição do tópico-limite e as questões elegíveis.
     * @property pedido assunto e limite pedidos.
     * @property nome nome do assunto.
     * @property ordemLimite posição do tópico-limite no assunto; nula = sem limite.
     * @property disponiveis questões elegíveis.
     */
    private class Escopo(val pedido: ProvaAssuntoRequest, val nome: String, val ordemLimite: Int?, val disponiveis: Int)

    /**
     * Confere o assunto e o tópico-limite e conta as questões elegíveis (tópicos até a posição do limite).
     * @param p assunto e limite pedidos.
     * @return o escopo resolvido.
     * @throws ErroNegocio 404 se o assunto não existir; 400 se o tópico-limite não for do assunto.
     */
    private fun escopo(p: ProvaAssuntoRequest): Escopo {
        val nome = jdbc.query("SELECT nome FROM assunto WHERE id = ?", { rs, _ -> rs.getString(1) }, p.assuntoId).firstOrNull()
            ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Assunto não encontrado.", "assunto_nao_encontrado")
        val ordem = p.topicoLimiteId?.let { limite ->
            jdbc.query("SELECT ordem FROM topico WHERE id = ? AND assunto_id = ?", { rs, _ -> rs.getInt(1) }, limite, p.assuntoId).firstOrNull()
                ?: throw ErroNegocio(HttpStatus.BAD_REQUEST, "O tópico-limite precisa ser um tópico do assunto \"$nome\".", "topico_limite_invalido")
        }
        val n = jdbc.queryForObject(
            "SELECT count(*) FROM questao q JOIN topico t ON t.id = q.topico_id WHERE t.assunto_id = ? AND (?::int IS NULL OR t.ordem <= ?)", Int::class.java, p.assuntoId, ordem, ordem,
        )!!
        return Escopo(p, nome, ordem, n)
    }

    /**
     * Sorteia questões elegíveis de um assunto.
     * @param e escopo do assunto.
     * @param quantidade quantas sortear.
     * @return ids das questões sorteadas.
     */
    private fun sortear(e: Escopo, quantidade: Int): List<UUID> = jdbc.query(
        "SELECT q.id FROM questao q JOIN topico t ON t.id = q.topico_id WHERE t.assunto_id = ? AND (?::int IS NULL OR t.ordem <= ?) ORDER BY random() LIMIT ?",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, e.pedido.assuntoId, e.ordemLimite, e.ordemLimite, quantidade,
    )

    /**
     * Grava uma prova (cabeçalho, assuntos e questões em ordem sorteada).
     * @param assuntos escopos que a prova cobre.
     * @param questoes questões escolhidas.
     * @param quantidade quantidade pedida.
     * @param grupo grupo das provas separadas, ou nulo.
     * @param limite limite de tempo em minutos, ou nulo.
     * @return o id da prova.
     */
    private fun gravar(assuntos: List<Escopo>, questoes: List<UUID>, quantidade: Int, grupo: UUID?, limite: Int?): UUID {
        val id = UUID.randomUUID()
        val u = uid()
        jdbc.update("INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,grupo_id,limite_minutos) VALUES (?,?,?,'gerada',?,?)", id, u, quantidade, grupo, limite)
        assuntos.forEach { jdbc.update("INSERT INTO prova_assunto(prova_id,assunto_id,usuario_id,topico_limite_id) VALUES (?,?,?,?)", id, it.pedido.assuntoId, u, it.pedido.topicoLimiteId) }
        questoes.shuffled().forEachIndexed { i, q -> jdbc.update("INSERT INTO prova_questao(prova_id,questao_id,usuario_id,ordem) VALUES (?,?,?,?)", id, q, u, i + 1) }
        return id
    }

    /**
     * Monta a(s) prova(s) do pedido: valida, divide a quantidade entre os assuntos, sorteia as questões e grava, numa transação.
     * @param r pedido.
     * @return as provas criadas e o grupo (quando separadas em mais de uma).
     * @throws ErroNegocio 400 se o pedido for inválido; 404 se um assunto não existir; 409 `questoes_insuficientes` se faltarem questões.
     */
    fun criar(r: ProvaRequest): ProvasCriadasDto {
        val max = props.prova
        if (r.quantidade > max.quantidadeMaxima) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Uma prova pode ter até ${max.quantidadeMaxima} questões.", "dados_invalidos")
        if (r.assuntos.size > max.assuntosMaximos) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha até ${max.assuntosMaximos} assuntos.", "dados_invalidos")
        if (r.assuntos.map { it.assuntoId }.toSet().size != r.assuntos.size) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O mesmo assunto foi escolhido duas vezes.", "dados_invalidos")
        if (r.limiteMinutos != null && r.limiteMinutos !in 1..max.limiteMinutosMaximo) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O limite de tempo vai de 1 a ${max.limiteMinutosMaximo} minutos.", "dados_invalidos")
        val ids = tx.execute {
            val escopos = r.assuntos.map(::escopo)
            val insuficiente = { disponiveis: Int, onde: String ->
                ErroNegocio(
                    HttpStatus.CONFLICT, "Há só $disponiveis questões $onde e você pediu ${r.quantidade}. Reduza a quantidade ou gere mais questões.", "questoes_insuficientes",
                    mapOf("disponiveis" to disponiveis, "pedidas" to r.quantidade),
                )
            }
            if (r.separadas) {
                escopos.firstOrNull { it.disponiveis < r.quantidade }?.let { throw insuficiente(it.disponiveis, "no assunto \"${it.nome}\"") }
                val grupo = if (escopos.size > 1) UUID.randomUUID() else null
                grupo to escopos.map { gravar(listOf(it), sortear(it, r.quantidade), r.quantidade, grupo, r.limiteMinutos) }
            } else {
                val total = escopos.sumOf { it.disponiveis }
                if (total < r.quantidade) throw insuficiente(total, "nos assuntos escolhidos")
                val cotas = CotaProva.dividir(r.quantidade, escopos.map { it.disponiveis })
                val questoes = escopos.indices.flatMap { sortear(escopos[it], cotas[it]) }
                null to listOf(gravar(escopos, questoes, r.quantidade, null, r.limiteMinutos))
            }
        }
        return ProvasCriadasDto(ids.first, tx.execute { ids.second.map { resumo(it) } })
    }

    /**
     * Conclui, com o fim do prazo como instante de conclusão, as provas iniciadas cujo tempo acabou.
     * @param agora instante de referência.
     */
    private fun encerrarVencidas(agora: Instant) {
        jdbc.update(
            "UPDATE prova SET status = 'concluida', concluida_em = iniciada_em + make_interval(mins => limite_minutos) " +
                "WHERE status = 'em_andamento' AND limite_minutos IS NOT NULL AND iniciada_em + make_interval(mins => limite_minutos) <= ?",
            Timestamp.from(agora),
        )
    }

    /**
     * Lê o cabeçalho da prova com o andamento e os assuntos.
     * @param id prova.
     * @return o resumo.
     * @throws ErroNegocio 404 se a prova não for do usuário.
     */
    private fun resumo(id: UUID): ProvaResumoDto = listarResumos("WHERE p.id = ?", null, id).firstOrNull() ?: throw naoEncontrada()

    /**
     * Lista resumos de provas.
     * @param filtro cláusula WHERE (ou vazia) sobre `prova p`.
     * @param limite máximo de provas; nulo = sem limite.
     * @param args argumentos do filtro.
     * @return resumos, do mais novo ao mais antigo.
     */
    private fun listarResumos(filtro: String, limite: Int?, vararg args: Any): List<ProvaResumoDto> {
        val ordem = "ORDER BY p.criado_em DESC, p.id${limite?.let { " LIMIT $it" } ?: ""}"
        // só os assuntos das provas que vão aparecer: a conta pode ter milhares de provas e a lista mostra as mais recentes
        val assuntos = jdbc.query(
            "SELECT pa.prova_id, a.id, a.nome, pa.topico_limite_id, t.nome FROM prova_assunto pa JOIN assunto a ON a.id = pa.assunto_id LEFT JOIN topico t ON t.id = pa.topico_limite_id " +
                "WHERE pa.prova_id IN (SELECT p.id FROM prova p $filtro $ordem) ORDER BY a.nome",
            { rs, _ -> rs.getObject(1, UUID::class.java) to ProvaAssuntoDto(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getString(5)) }, *args,
        ).groupBy({ it.first }, { it.second })
        return jdbc.query(
            """SELECT p.id, p.grupo_id, p.status, p.limite_minutos, p.iniciada_em, p.concluida_em, p.criado_em,
                      (SELECT count(*) FROM prova_questao pq WHERE pq.prova_id = p.id) AS total,
                      (SELECT count(DISTINCT r.questao_id) FROM resposta r WHERE r.prova_id = p.id) AS respondidas,
                      (SELECT count(DISTINCT r.questao_id) FROM resposta r WHERE r.prova_id = p.id AND r.correta) AS acertos
               FROM prova p $filtro $ordem""",
            { rs, _ -> resumoDe(rs, assuntos) }, *args,
        )
    }

    /**
     * Converte uma linha de prova no resumo.
     * @param rs linha atual.
     * @param assuntos assuntos de todas as provas, por prova.
     * @return o resumo.
     */
    private fun resumoDe(rs: ResultSet, assuntos: Map<UUID, List<ProvaAssuntoDto>>): ProvaResumoDto {
        val id = rs.getObject("id", UUID::class.java)
        val limite = rs.getObject("limite_minutos") as Int?
        val inicio = rs.getTimestamp("iniciada_em")?.toInstant()
        return ProvaResumoDto(
            id, rs.getObject("grupo_id", UUID::class.java), rs.getString("status"), rs.getInt("total"), rs.getInt("respondidas"), rs.getInt("acertos"), limite,
            inicio, if (limite != null && inicio != null) inicio.plus(Duration.ofMinutes(limite.toLong())) else null,
            rs.getTimestamp("concluida_em")?.toInstant(), rs.getTimestamp("criado_em").toInstant(), assuntos[id].orEmpty(),
        )
    }

    /**
     * Lista as provas do usuário (provas do mesmo pedido têm o mesmo `grupoId`), concluindo as que passaram do tempo.
     * @return as [LIMITE_LISTA] provas mais recentes, da mais nova à mais antiga.
     */
    fun listar(): List<ProvaResumoDto> = tx.execute { encerrarVencidas(agora()); listarResumos("", LIMITE_LISTA) }!!

    /**
     * Prova com as questões. O gabarito de uma questão só vem depois de respondida.
     * @param id prova.
     * @return a prova completa.
     * @throws ErroNegocio 404 se a prova não for do usuário.
     */
    fun detalhe(id: UUID): ProvaDto = tx.execute { encerrarVencidas(agora()); montar(id) }!!

    /**
     * Monta o detalhe sem encerrar vencidas (o chamador já fez isso).
     * @param id prova.
     * @return a prova completa.
     */
    private fun montar(id: UUID): ProvaDto {
        val resumo = resumo(id)
        val questoes = jdbc.query(
            """SELECT pq.ordem, q.id, q.topico_id, t.nome, q.enunciado, q.alternativas::text, q.dica, q.dificuldade, q.resposta_correta, r.alternativa_escolhida, r.correta, a.nome AS assunto
               FROM prova_questao pq JOIN questao q ON q.id = pq.questao_id JOIN topico t ON t.id = q.topico_id JOIN assunto a ON a.id = t.assunto_id
               LEFT JOIN resposta r ON r.prova_id = pq.prova_id AND r.questao_id = pq.questao_id
               WHERE pq.prova_id = ? ORDER BY pq.ordem""",
            { rs, _ ->
                val escolhida = rs.getObject("alternativa_escolhida") as Int?
                ProvaQuestaoDto(
                    rs.getInt("ordem"), rs.getObject("id", UUID::class.java), rs.getObject("topico_id", UUID::class.java), rs.getString("nome"), rs.getString("assunto"), rs.getString("enunciado"),
                    json.readTree(rs.getString(6)).toList().map { it.asString() }, rs.getString("dica"), rs.getString("dificuldade"),
                    escolhida != null, escolhida, rs.getObject("correta") as Boolean?, if (escolhida != null) rs.getInt("resposta_correta") else null,
                )
            }, id,
        )
        return ProvaDto(resumo, agora(), questoes)
    }

    /**
     * Inicia a prova (começa a contar o tempo, se houver limite). Repetir não muda o início.
     * @param id prova.
     * @return a prova.
     * @throws ErroNegocio 404 se a prova não for do usuário.
     */
    fun iniciar(id: UUID): ProvaDto = tx.execute {
        encerrarVencidas(agora())
        val status = jdbc.query("SELECT status FROM prova WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: throw naoEncontrada()
        if (status == "gerada") jdbc.update("UPDATE prova SET status = 'em_andamento', iniciada_em = ? WHERE id = ?", Timestamp.from(agora()), id)
        montar(id)
    }!!

    /**
     * Confere se a resposta pode ser dada na prova e, se for a primeira, inicia a prova. Chamado dentro da transação da resposta.
     * @param provaId prova.
     * @param questaoId questão respondida.
     * @throws ErroNegocio 404 se a prova não for do usuário ou a questão não fizer parte dela; 409 se a prova já terminou ou a questão já foi respondida nela.
     */
    fun aceitarResposta(provaId: UUID, questaoId: UUID) {
        encerrarVencidas(agora())
        val status = jdbc.query("SELECT status FROM prova WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, provaId).firstOrNull() ?: throw naoEncontrada()
        if (status == "concluida") throw ErroNegocio(HttpStatus.CONFLICT, "Esta prova já terminou.", "prova_encerrada")
        if (jdbc.queryForObject("SELECT count(*) FROM prova_questao WHERE prova_id = ? AND questao_id = ?", Int::class.java, provaId, questaoId) == 0)
            throw ErroNegocio(HttpStatus.NOT_FOUND, "Esta questão não faz parte da prova.", "nao_encontrado")
        if (jdbc.queryForObject("SELECT count(*) FROM resposta WHERE prova_id = ? AND questao_id = ?", Int::class.java, provaId, questaoId)!! > 0)
            throw ErroNegocio(HttpStatus.CONFLICT, "Você já respondeu esta questão na prova.", "ja_respondida")
        if (status == "gerada") jdbc.update("UPDATE prova SET status = 'em_andamento', iniciada_em = ? WHERE id = ?", Timestamp.from(agora()), provaId)
    }

    /**
     * Conclui a prova quando todas as questões foram respondidas. Chamado dentro da transação da resposta, depois de gravá-la.
     * @param provaId prova.
     */
    fun concluirSeCompleta(provaId: UUID) {
        jdbc.update(
            """UPDATE prova SET status = 'concluida', concluida_em = ? WHERE id = ? AND status = 'em_andamento'
               AND (SELECT count(*) FROM prova_questao WHERE prova_id = prova.id) = (SELECT count(DISTINCT questao_id) FROM resposta WHERE prova_id = prova.id)""",
            Timestamp.from(agora()), provaId,
        )
    }

    /**
     * Exclui a prova. As respostas dadas nela continuam (viram respostas avulsas), para o histórico e o painel de desempenho não mudarem.
     * @param id prova.
     * @return nada.
     * @throws ErroNegocio 404 se a prova não for do usuário.
     */
    fun excluir(id: UUID) = tx.executeWithoutResult {
        jdbc.query("SELECT id FROM prova WHERE id = ? FOR UPDATE", { rs, _ -> 1 }, id).firstOrNull() ?: throw naoEncontrada()
        jdbc.update("UPDATE resposta SET prova_id = NULL WHERE prova_id = ?", id)
        jdbc.update("DELETE FROM prova_questao WHERE prova_id = ?", id)
        jdbc.update("DELETE FROM prova_assunto WHERE prova_id = ?", id)
        jdbc.update("DELETE FROM prova WHERE id = ?", id)
    }

    /**
     * Cria uma nova prova com as mesmas questões de uma prova concluída, em outra ordem, com o mesmo limite de tempo.
     * @param id prova original.
     * @return a nova prova.
     * @throws ErroNegocio 404 se a prova não for do usuário; 409 se ainda não terminou.
     */
    fun refazer(id: UUID): ProvaResumoDto = tx.execute {
        encerrarVencidas(agora())
        val origem = resumo(id)
        if (origem.status != "concluida") throw ErroNegocio(HttpStatus.CONFLICT, "Só dá para refazer uma prova que já terminou.", "prova_nao_concluida")
        val novo = UUID.randomUUID()
        val u = uid()
        jdbc.update("INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,limite_minutos) VALUES (?,?,?,'gerada',?)", novo, u, origem.quantidade, origem.limiteMinutos)
        jdbc.update("INSERT INTO prova_assunto(prova_id,assunto_id,usuario_id,topico_limite_id) SELECT ?, assunto_id, usuario_id, topico_limite_id FROM prova_assunto WHERE prova_id = ?", novo, id)
        val questoes = jdbc.query("SELECT questao_id FROM prova_questao WHERE prova_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id)
        questoes.shuffled().forEachIndexed { i, q -> jdbc.update("INSERT INTO prova_questao(prova_id,questao_id,usuario_id,ordem) VALUES (?,?,?,?)", novo, q, u, i + 1) }
        resumo(novo)
    }!!
}

/** Rotas das provas por escopo. */
@RestController
class ProvasController(private val provas: Provas) {
    /**
     * Monta uma ou mais provas.
     * @param r pedido (assuntos, tópicos-limite, quantidade, única ou separadas, limite de tempo).
     * @return 201 com as provas criadas.
     */
    @PostMapping("/provas")
    fun criar(@Valid @RequestBody r: ProvaRequest) = ResponseEntity.status(HttpStatus.CREATED).body(provas.criar(r))

    /**
     * Lista as provas do usuário.
     * @return as provas, da mais nova à mais antiga.
     */
    @GetMapping("/provas")
    fun listar() = provas.listar()

    /**
     * Abre uma prova com suas questões.
     * @param id prova.
     * @return a prova.
     */
    @GetMapping("/provas/{id}")
    fun detalhe(@PathVariable id: UUID) = provas.detalhe(id)

    /**
     * Inicia a prova (começa o tempo, se houver limite).
     * @param id prova.
     * @return a prova.
     */
    @PostMapping("/provas/{id}/iniciar")
    fun iniciar(@PathVariable id: UUID) = provas.iniciar(id)

    /**
     * Exclui uma prova (as respostas dadas continuam no histórico).
     * @param id prova.
     * @return 204.
     */
    @DeleteMapping("/provas/{id}")
    fun excluir(@PathVariable id: UUID): ResponseEntity<Void> { provas.excluir(id); return ResponseEntity.noContent().build() }

    /**
     * Refaz uma prova concluída.
     * @param id prova original.
     * @return 201 com a nova prova.
     */
    @PostMapping("/provas/{id}/refazer")
    fun refazer(@PathVariable id: UUID) = ResponseEntity.status(HttpStatus.CREATED).body(provas.refazer(id))
}
