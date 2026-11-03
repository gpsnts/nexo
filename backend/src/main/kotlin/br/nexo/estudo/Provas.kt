package br.nexo.estudo

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.material.LimitesIa
import br.nexo.material.TopicoService
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
 * @property novas quantas das questões da prova devem ser geradas agora (de 0 até [quantidade]); as demais saem do banco de questões.
 * @property orientacao orientação do estudante para as questões novas (banca, vestibular, foco); em branco usa a orientação gravada em cada tópico.
 */
data class ProvaRequest(
    @field:NotEmpty(message = "Escolha ao menos um assunto.") val assuntos: List<@Valid ProvaAssuntoRequest> = emptyList(),
    @field:Min(1, message = "Informe a quantidade de questões.") val quantidade: Int = 0,
    val separadas: Boolean = false,
    val limiteMinutos: Int? = null,
    @field:Min(0, message = "A quantidade de questões novas não pode ser negativa.") val novas: Int = 0,
    val orientacao: String? = null,
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
 * @property status montando (questões novas sendo geradas), gerada, em_andamento, concluida ou falhou.
 * @property quantidade questões da prova (zero enquanto monta).
 * @property solicitadas questões pedidas; maior que [quantidade] quando faltaram questões.
 * @property questoesNovas questões novas pedidas para a prova.
 * @property questoesGeradas questões novas já geradas.
 * @property orientacao orientação dada às questões novas, se houve.
 * @property motivoFalha motivo, quando a montagem falhou.
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
    val solicitadas: Int = quantidade, val questoesNovas: Int = 0, val questoesGeradas: Int = 0, val orientacao: String? = null, val motivoFalha: String? = null,
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

/**
 * Prova com questões novas a montar em segundo plano.
 * @property usuarioId dono da prova.
 * @property provaId prova (já gravada com status montando).
 * @property quantidade questões pedidas para a prova.
 * @property orientacao orientação para as questões novas; nulo usa a de cada tópico.
 * @property planos quantas questões novas gerar em cada tópico.
 * @property escopos assuntos (com a posição do tópico-limite) de onde o banco completa a prova.
 */
data class TarefaMontagem(val usuarioId: UUID, val provaId: UUID, val quantidade: Int, val orientacao: String?, val planos: List<PlanoTopico>, val escopos: List<EscopoMontagem>)
/**
 * Questões novas a gerar em um tópico.
 * @property topicoId tópico.
 * @property quantidade questões.
 */
data class PlanoTopico(val topicoId: UUID, val quantidade: Int)
/**
 * Assunto de uma prova em montagem.
 * @property assuntoId assunto.
 * @property ordemLimite posição do tópico-limite; nula = todos os tópicos.
 */
data class EscopoMontagem(val assuntoId: UUID, val ordemLimite: Int?)
/**
 * Resultado do pedido de prova: o que responder à tela e o que montar em segundo plano.
 * @property criadas provas criadas.
 * @property tarefas provas com questões novas, ainda por montar (vazio quando todas as questões vieram do banco).
 */
data class Criacao(val criadas: ProvasCriadasDto, val tarefas: List<TarefaMontagem>)

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
    private val limites: LimitesIa,
    private val ativas: ProvasAtivas,
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
     * @param excluir questões que não podem sair (as novas, já escolhidas).
     * @return ids das questões sorteadas.
     */
    private fun sortear(e: Escopo, quantidade: Int, excluir: Collection<UUID> = emptyList()): List<UUID> = sortearDe(e.pedido.assuntoId, e.ordemLimite, quantidade, excluir)

    /**
     * Sorteia questões de um assunto até a posição do tópico-limite.
     * @param assuntoId assunto.
     * @param ordemLimite posição do tópico-limite; nula = todos os tópicos.
     * @param quantidade quantas sortear.
     * @param excluir questões que não podem sair.
     * @return ids das questões sorteadas.
     */
    private fun sortearDe(assuntoId: UUID, ordemLimite: Int?, quantidade: Int, excluir: Collection<UUID>): List<UUID> = jdbc.query(
        "SELECT q.id FROM questao q JOIN topico t ON t.id = q.topico_id WHERE t.assunto_id = ? AND (?::int IS NULL OR t.ordem <= ?) AND NOT (q.id = ANY (?::uuid[])) ORDER BY random() LIMIT ?",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, assuntoId, ordemLimite, ordemLimite, excluir.map { it.toString() }.toTypedArray(), quantidade,
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
     * Monta a(s) prova(s) do pedido: valida, divide a quantidade entre os assuntos, sorteia as questões do banco e grava, numa transação.
     * Com questões novas (`novas` maior que zero), a prova é gravada com status `montando` e devolvida em [Criacao.tarefas] para ser montada em segundo plano.
     * @param r pedido.
     * @return as provas criadas e, se houver questões novas, o que montar depois.
     * @throws ErroNegocio 400 se o pedido for inválido; 404 se um assunto não existir; 409 `questoes_insuficientes` se faltarem questões no banco, ou `limite_questoes_topico` se os tópicos não comportarem as novas; 429 se a cota de IA não cobrir as novas.
     */
    fun criar(r: ProvaRequest): Criacao {
        val max = props.prova
        if (r.quantidade > max.quantidadeMaxima) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Uma prova pode ter até ${max.quantidadeMaxima} questões.", "dados_invalidos")
        if (r.novas > r.quantidade) throw ErroNegocio(HttpStatus.BAD_REQUEST, "As questões novas não podem passar do total da prova.", "dados_invalidos")
        if (r.assuntos.size > max.assuntosMaximos) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha até ${max.assuntosMaximos} assuntos.", "dados_invalidos")
        if (r.assuntos.map { it.assuntoId }.toSet().size != r.assuntos.size) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O mesmo assunto foi escolhido duas vezes.", "dados_invalidos")
        if (r.limiteMinutos != null && r.limiteMinutos !in 1..max.limiteMinutosMaximo) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O limite de tempo vai de 1 a ${max.limiteMinutosMaximo} minutos.", "dados_invalidos")
        val orientacao = TopicoService.limparOrientacao(r.orientacao)
        val tarefas = mutableListOf<TarefaMontagem>()
        val ids = tx.execute {
            val escopos = r.assuntos.map(::escopo)
            val doBanco = r.quantidade - r.novas
            val insuficiente = { disponiveis: Int, onde: String ->
                ErroNegocio(
                    HttpStatus.CONFLICT, "Há só $disponiveis questões $onde e você pediu $doBanco. Reduza a quantidade, peça mais questões novas ou gere mais questões.", "questoes_insuficientes",
                    mapOf("disponiveis" to disponiveis, "pedidas" to doBanco),
                )
            }
            val provas = if (r.separadas) escopos.map { listOf(it) } else listOf(escopos)
            val grupo = if (r.separadas && escopos.size > 1) UUID.randomUUID() else null
            if (r.separadas) escopos.firstOrNull { it.disponiveis < doBanco }?.let { throw insuficiente(it.disponiveis, "no assunto \"${it.nome}\"") }
            else if (escopos.sumOf { it.disponiveis } < doBanco) throw insuficiente(escopos.sumOf { it.disponiveis }, "nos assuntos escolhidos")
            if (r.novas > 0) limites.exigirCreditos(r.novas * max.creditosPorQuestaoNova * provas.size)
            val criadas = provas.map { grupoEscopos ->
                if (r.novas == 0) {
                    val cotas = CotaProva.dividir(r.quantidade, grupoEscopos.map { it.disponiveis })
                    gravar(grupoEscopos, grupoEscopos.indices.flatMap { sortear(grupoEscopos[it], cotas[it]) }, r.quantidade, grupo, r.limiteMinutos)
                } else {
                    val planos = planejarNovas(grupoEscopos, r.novas)
                    val id = gravarMontando(grupoEscopos, r.quantidade, grupo, r.limiteMinutos, orientacao, r.novas)
                    tarefas += TarefaMontagem(uid(), id, r.quantidade, orientacao, planos, grupoEscopos.map { EscopoMontagem(it.pedido.assuntoId, it.ordemLimite) })
                    id
                }
            }
            grupo to criadas
        }!!
        return Criacao(ProvasCriadasDto(ids.first, tx.execute { ids.second.map { resumo(it) } }!!), tarefas)
    }

    /**
     * Reparte as questões novas entre os tópicos dos assuntos de uma prova, preferindo os tópicos com menos questões e respeitando o teto de questões por tópico.
     * @param escopos assuntos da prova (com a posição do tópico-limite).
     * @param novas quantas questões novas gerar no total.
     * @return quantas gerar em cada tópico (só os tópicos que recebem alguma).
     * @throws ErroNegocio 409 `limite_questoes_topico` se os tópicos escolhidos não comportarem tantas questões novas.
     */
    private fun planejarNovas(escopos: List<Escopo>, novas: Int): List<PlanoTopico> {
        val teto = props.geracao.questoesPorTopico
        /** Um tópico do escopo: quantas questões já tem e quantas ainda cabem. */
        data class Vaga(val topico: UUID, val ocupadas: Int, val livres: Int)
        val porAssunto = escopos.map { e ->
            jdbc.query(
                "SELECT t.id, (SELECT count(*) FROM questao q WHERE q.topico_id = t.id) FROM topico t WHERE t.assunto_id = ? AND (?::int IS NULL OR t.ordem <= ?) ORDER BY t.ordem",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }, e.pedido.assuntoId, e.ordemLimite, e.ordemLimite,
            ).map { (id, n) -> Vaga(id, n, (teto - n).coerceAtLeast(0)) }
        }
        val cotas = CotaProva.dividir(novas, porAssunto.map { v -> v.sumOf { it.livres } })
        val total = cotas.sum()
        if (total < novas) throw ErroNegocio(
            HttpStatus.CONFLICT, "Os tópicos escolhidos aceitam só mais $total questões novas (o limite é de $teto por tópico). Peça menos questões novas.", "limite_questoes_topico",
            mapOf("disponiveis" to total, "pedidas" to novas),
        )
        val planos = linkedMapOf<UUID, Int>()
        porAssunto.forEachIndexed { i, vagas ->
            val ocupadas = vagas.associate { it.topico to it.ocupadas }.toMutableMap()
            val livres = vagas.associate { it.topico to it.livres }.toMutableMap()
            repeat(cotas[i]) {
                val escolhido = vagas.filter { livres.getValue(it.topico) > 0 }.minByOrNull { ocupadas.getValue(it.topico) }!!.topico
                planos.merge(escolhido, 1, Int::plus); ocupadas[escolhido] = ocupadas.getValue(escolhido) + 1; livres[escolhido] = livres.getValue(escolhido) - 1
            }
        }
        return planos.map { PlanoTopico(it.key, it.value) }
    }

    /**
     * Grava o cabeçalho de uma prova que ainda vai receber questões novas (status `montando`, sem questões).
     * @param assuntos escopos da prova.
     * @param quantidade questões pedidas.
     * @param grupo grupo das provas separadas, ou nulo.
     * @param limite limite de tempo em minutos, ou nulo.
     * @param orientacao orientação para as questões novas, ou nulo.
     * @param novas questões novas pedidas.
     * @return o id da prova.
     */
    private fun gravarMontando(assuntos: List<Escopo>, quantidade: Int, grupo: UUID?, limite: Int?, orientacao: String?, novas: Int): UUID {
        val id = UUID.randomUUID()
        val u = uid()
        jdbc.update(
            "INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,grupo_id,limite_minutos,orientacao,questoes_novas) VALUES (?,?,?,'montando',?,?,?,?)",
            id, u, quantidade, grupo, limite, orientacao, novas,
        )
        assuntos.forEach { jdbc.update("INSERT INTO prova_assunto(prova_id,assunto_id,usuario_id,topico_limite_id) VALUES (?,?,?,?)", id, it.pedido.assuntoId, u, it.pedido.topicoLimiteId) }
        return id
    }

    /**
     * Diz se a prova ainda existe (a montagem para quando o estudante a exclui).
     * @param provaId prova.
     * @return verdadeiro se existe.
     */
    fun existe(provaId: UUID): Boolean = tx.execute { jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM prova WHERE id = ?)", Boolean::class.java, provaId) }!!

    /**
     * Diz se a prova ainda está sendo montada (existe e não foi concluída nem marcada como falha).
     * @param provaId prova.
     * @return verdadeiro se o status é `montando`.
     */
    fun montando(provaId: UUID): Boolean = tx.execute { jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM prova WHERE id = ? AND status = 'montando')", Boolean::class.java, provaId) }!!

    /**
     * Anota quantas questões novas já foram geradas e marca o pulso da montagem (o `atualizado_em` muda a cada gravação).
     * @param provaId prova.
     * @param geradas questões novas geradas até agora.
     */
    fun registrarGeradas(provaId: UUID, geradas: Int) {
        tx.executeWithoutResult { jdbc.update("UPDATE prova SET questoes_geradas = LEAST(?, questoes_novas) WHERE id = ? AND status = 'montando'", geradas, provaId) }
    }

    /**
     * Fecha a montagem: completa com questões do banco o que as novas não cobriram e grava a prova; sem nenhuma questão, ela falha.
     * Se faltarem questões, a prova sai com as que há (a tela mostra quantas foram pedidas e quantas entraram).
     * @param t prova em montagem.
     * @param novas questões novas efetivamente geradas.
     * @param motivo código do motivo, usado se a prova ficar sem questão nenhuma.
     */
    fun fecharMontagem(t: TarefaMontagem, novas: List<UUID>, motivo: String) {
        tx.executeWithoutResult {
            // prova excluída (cancelamento) ou já encerrada: nada a fechar
            if (jdbc.query("SELECT status FROM prova WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, t.provaId).firstOrNull() != "montando") return@executeWithoutResult
            val escolhidas = novas.take(t.quantidade)
            val faltam = t.quantidade - escolhidas.size
            val disponiveis = t.escopos.map { e ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM questao q JOIN topico tp ON tp.id = q.topico_id WHERE tp.assunto_id = ? AND (?::int IS NULL OR tp.ordem <= ?) AND NOT (q.id = ANY (?::uuid[]))",
                    Int::class.java, e.assuntoId, e.ordemLimite, e.ordemLimite, escolhidas.map { it.toString() }.toTypedArray(),
                )!!
            }
            val cotas = CotaProva.dividir(faltam, disponiveis)
            val doBanco = t.escopos.indices.flatMap { sortearDe(t.escopos[it].assuntoId, t.escopos[it].ordemLimite, cotas[it], escolhidas) }
            val todas = escolhidas + doBanco
            if (todas.isEmpty()) {
                jdbc.update("UPDATE prova SET status = 'falhou', motivo_falha = ? WHERE id = ?", motivo, t.provaId)
            } else {
                todas.shuffled().forEachIndexed { i, q -> jdbc.update("INSERT INTO prova_questao(prova_id,questao_id,usuario_id,ordem) VALUES (?,?,?,?)", t.provaId, q, t.usuarioId, i + 1) }
                jdbc.update("UPDATE prova SET status = 'gerada', questoes_geradas = LEAST(?, questoes_novas) WHERE id = ?", escolhidas.size, t.provaId)
            }
        }
    }

    /**
     * Marca uma prova em montagem como falha.
     * @param provaId prova.
     * @param motivo código do motivo.
     */
    fun falharMontagem(provaId: UUID, motivo: String) {
        tx.executeWithoutResult { jdbc.update("UPDATE prova SET status = 'falhou', motivo_falha = ? WHERE id = ? AND status = 'montando'", motivo, provaId) }
    }

    /**
     * Marca como falhas as provas que ficaram em `montando` sem sinal de vida e que este servidor não está montando nem tem na fila (reinício no meio da montagem).
     */
    private fun recuperarAbandonadas() {
        jdbc.update(
            "UPDATE prova SET status = 'falhou', motivo_falha = 'interrompida' WHERE status = 'montando' AND atualizado_em < ? AND NOT (id = ANY (?::uuid[]))",
            Timestamp.from(agora().minus(props.geracao.pulsoMaximo)), ativas.todas().map { it.toString() }.toTypedArray(),
        )
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
            """SELECT p.id, p.grupo_id, p.status, p.limite_minutos, p.iniciada_em, p.concluida_em, p.criado_em, p.quantidade_solicitada, p.questoes_novas, p.questoes_geradas, p.orientacao, p.motivo_falha,
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
            rs.getInt("quantidade_solicitada"), rs.getInt("questoes_novas"), rs.getInt("questoes_geradas"), rs.getString("orientacao"), rs.getString("motivo_falha"),
        )
    }

    /**
     * Lista as provas do usuário (provas do mesmo pedido têm o mesmo `grupoId`), concluindo as que passaram do tempo.
     * @return as [LIMITE_LISTA] provas mais recentes, da mais nova à mais antiga.
     */
    fun listar(): List<ProvaResumoDto> = tx.execute { encerrarVencidas(agora()); recuperarAbandonadas(); listarResumos("", LIMITE_LISTA) }!!

    /**
     * Prova com as questões. O gabarito de uma questão só vem depois de respondida.
     * @param id prova.
     * @return a prova completa.
     * @throws ErroNegocio 404 se a prova não for do usuário.
     */
    fun detalhe(id: UUID): ProvaDto = tx.execute { encerrarVencidas(agora()); recuperarAbandonadas(); montar(id) }!!

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
        if (status == "montando" || status == "falhou") throw ErroNegocio(HttpStatus.CONFLICT, "Esta prova ainda não está pronta.", "prova_nao_pronta")
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
        if (status == "montando" || status == "falhou") throw ErroNegocio(HttpStatus.CONFLICT, "Esta prova ainda não está pronta.", "prova_nao_pronta")
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
class ProvasController(private val provas: Provas, private val montagem: MontagemProvas) {
    /**
     * Monta uma ou mais provas.
     * @param r pedido (assuntos, tópicos-limite, quantidade, questões novas, orientação, única ou separadas, limite de tempo).
     * @return 201 com as provas criadas; as que têm questões novas voltam em `montando` e são montadas em segundo plano.
     */
    @PostMapping("/provas")
    fun criar(@Valid @RequestBody r: ProvaRequest): ResponseEntity<ProvasCriadasDto> {
        val c = provas.criar(r)
        montagem.submeter(c.tarefas)
        return ResponseEntity.status(HttpStatus.CREATED).body(c.criadas)
    }

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
