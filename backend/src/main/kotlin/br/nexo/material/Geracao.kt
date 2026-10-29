package br.nexo.material

import br.nexo.NexoProperties
import br.nexo.conta.ErroNegocio
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.UsuarioAtual
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Estado de uma geração (resumo ou questões), para a tela fazer polling.
 * @property id registro da geração.
 * @property operacao resumo ou questoes.
 * @property status pendente, em_andamento, concluida ou falhou.
 * @property progresso 0 a 100.
 * @property aprovadas questões aprovadas na verificação.
 * @property rejeitadas questões rejeitadas.
 * @property repetidas questões descartadas por repetição.
 * @property gravadas questões efetivamente gravadas.
 * @property taxaRejeicao rejeitadas ÷ verificadas, ou nulo.
 * @property mensagem texto para o usuário quando falhou ou foi cancelada.
 * @property verificacaoId registro da verificação, se houver.
 * @property motivo código do motivo da falha.
 */
data class GeracaoDto(
    val id: UUID, val operacao: String, val status: String, val progresso: Int, val aprovadas: Int, val rejeitadas: Int, val repetidas: Int,
    val gravadas: Int, val taxaRejeicao: Double?, val mensagem: String?, val verificacaoId: UUID?, val motivo: String?,
)
/**
 * Resposta ao pedido de geração.
 * @property registroId registro da geração.
 * @property verificacaoId registro da verificação (só em questões).
 */
data class Pedido(val registroId: UUID, val verificacaoId: UUID?)
/** `titulo`: nome que o estudante deu ao material (nulo = vale o nome do arquivo). */
data class FonteDto(val materialId: UUID, val nomeArquivo: String, val inicio: Int, val fim: Int, val titulo: String? = null)
/**
 * Resumo de um tópico.
 * @property conteudo texto do resumo.
 * @property criadoEm quando foi gerado.
 */
data class ResumoDto(val conteudo: String, val criadoEm: java.time.Instant)
/**
 * Detalhe de um tópico com fontes, resumo e contagens.
 * @property id tópico.
 * @property assuntoId pasta.
 * @property nome nome.
 * @property livre se é tópico livre.
 * @property fontes materiais e faixas que o alimentam.
 * @property resumo resumo mais recente.
 * @property totalQuestoes questões gravadas.
 * @property maximoQuestoes máximo por tópico.
 * @property maximoResumos máximo de resumos guardados.
 * @property geracaoAberta geração em andamento, se houver.
 * @property ultimaGeracao última geração de questões.
 * @property orientacao orientação padrão do estudante para o conteúdo gerado, se houver.
 */
data class TopicoDetalhe(
    val id: UUID, val assuntoId: UUID, val nome: String, val livre: Boolean, val fontes: List<FonteDto>, val resumo: ResumoDto?,
    val totalQuestoes: Int, val maximoQuestoes: Int, val maximoResumos: Int, val geracaoAberta: UUID?, val ultimaGeracao: GeracaoDto?, val orientacao: String? = null,
)
/**
 * Trecho que apoia uma questão.
 * @property nomeArquivo arquivo do material.
 * @property ordem posição do trecho.
 * @property numeroPagina página, se houver.
 */
data class ApoioDto(val nomeArquivo: String, val ordem: Int, val numeroPagina: Int?)
/**
 * Questão devolvida pela API. A resposta correta só vai depois de respondida.
 * @property id identificador.
 * @property enunciado pergunta.
 * @property alternativas alternativas.
 * @property respostaCorreta índice da correta, só se já respondida.
 * @property respondida se o usuário já respondeu.
 * @property ultimaCorreta se a última resposta estava certa.
 * @property proximaRevisao data da próxima revisão.
 * @property criadoEm criação.
 * @property dica dica gravada.
 * @property dificuldade nível.
 * @property apoio trechos que sustentam a questão.
 */
data class QuestaoDto(val id: UUID, val enunciado: String, val alternativas: List<String>, val respostaCorreta: Int?, val respondida: Boolean, val ultimaCorreta: Boolean?, val proximaRevisao: java.time.LocalDate?, val criadoEm: java.time.Instant, val dica: String?, val dificuldade: String, val apoio: List<ApoioDto>)

/** A operação foi cancelada (ou dada como abandonada) enquanto a tarefa corria: a tarefa para sem gravar mais nada. */
private class Cancelada : RuntimeException()

val DIFICULDADES = setOf("facil", "media", "dificil")

/**
 * Tarefa de geração na fila.
 * @property usuarioId dono.
 * @property topicoId tópico.
 * @property operacao resumo ou questoes.
 * @property quantidade questões pedidas.
 * @property registroId registro da geração.
 * @property verificacaoId registro da verificação.
 * @property dificuldade nível pedido.
 * @property orientacao orientação já resolvida (a do pedido ou a gravada no tópico), ou nulo.
 */
private class Tarefa3(val usuarioId: UUID, val topicoId: UUID, val operacao: String, val quantidade: Int, val registroId: UUID, val verificacaoId: UUID?, val dificuldade: String = "media", val orientacao: String? = null)

/**
 * SHA-256 do enunciado normalizado (minúsculas, sem acentos, sem pontuação, espaços únicos).
 * @param enunciado texto da pergunta.
 * @return o hash em hexadecimal.
 */
fun hashDoEnunciado(enunciado: String): String {
    val n = Normalizer.normalize(enunciado.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}\\s]"), "").replace(Regex("\\s+"), " ").trim()
    return MessageDigest.getInstance("SHA-256").digest(n.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Resumo, questões e verificação de fidelidade, em executor próprio (mesmo molde do [Indexador]). O estado é o do banco
 * (`registro_geracao`); a fila é só memória. Thread de segundo plano não tem usuário: tudo roda em `UsuarioAtual.como` e cada etapa abre a
 * PRÓPRIA transação curta; nenhuma transação fica aberta durante uma chamada HTTP. Só entram em `questao` as aprovadas; as rejeitadas só
 * contam em `registro_geracao`.
 */
@Service
class Geracao(
    private val props: NexoProperties,
    private val dados: GeracaoDados,
    private val conteudo: ConteudoBase,
    private val proc: Processamento,
    private val tx: TransactionTemplate,
    private val limites: LimitesIa,
    private val relogio: Clock,
    private val json: JsonMapper,
    private val auditoria: Auditoria,
    private val precos: PrecosIa,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    val ativos: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val pool = ThreadPoolExecutor(props.geracao.threads, props.geracao.threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(props.geracao.fila)) { r ->
        Thread(r, "geracao").apply { isDaemon = true }
    }

    /**
     * Usuário autenticado.
     * @return id do usuário do JWT.
     * @throws ErroNegocio 401 se não houver usuário.
     */
    private fun usuario() = UsuarioAtual.id() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Não autenticado.", "nao_autenticado")
    /**
     * Erro padrão de tópico inexistente.
     * @return erro 404 `nao_encontrado`.
     */
    private fun topicoNaoEncontrado() = ErroNegocio(HttpStatus.NOT_FOUND, "Tópico não encontrado.", "nao_encontrado")
    /**
     * Erro de IA indisponível.
     * @param codigo código da falha do serviço de IA.
     * @return erro 503 `ia_indisponivel` com mensagem clara.
     */
    private fun indisponivel(codigo: String? = null) = ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, MensagensIa.deCodigo(codigo), "ia_indisponivel")

    // ---------------------------------------------------------------- pedidos
    /**
     * Pede um resumo do tópico.
     * @param topicoId tópico.
     * @param orientacao orientação só para esta geração; nulo usa a gravada no tópico, texto em branco gera sem orientação.
     * @return os registros criados.
     * @throws ErroNegocio ver [pedir].
     */
    fun pedirResumo(topicoId: UUID, orientacao: String? = null): Pedido = pedir(topicoId, "resumo", 0, orientacaoPedida = orientacao)

    /**
     * Pede questões do tópico.
     * @param topicoId tópico.
     * @param quantidade de 1 até `questoesPorPedido`.
     * @param dificuldade facil, media ou dificil.
     * @param orientacao orientação só para esta geração; nulo usa a gravada no tópico, texto em branco gera sem orientação.
     * @return os registros criados.
     * @throws ErroNegocio 400 se dificuldade ou quantidade forem inválidas; demais erros em [pedir].
     */
    fun pedirQuestoes(topicoId: UUID, quantidade: Int, dificuldade: String = "media", orientacao: String? = null): Pedido {
        if (dificuldade !in DIFICULDADES) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Escolha a dificuldade: fácil, média ou difícil.", "dados_invalidos")
        if (quantidade !in 1..props.geracao.questoesPorPedido) throw ErroNegocio(HttpStatus.BAD_REQUEST, "Peça de 1 a ${props.geracao.questoesPorPedido} questões.", "dados_invalidos")
        return pedir(topicoId, "questoes", quantidade, dificuldade, orientacao)
    }

    /**
     * Valida limites, cria os registros (geração e, em questões, verificação) e enfileira a tarefa.
     * @param topicoId tópico.
     * @param operacao resumo ou questoes.
     * @param quantidade questões pedidas (0 no resumo).
     * @param dificuldade nível das questões.
     * @param orientacaoPedida orientação só deste pedido (nulo = usar a gravada no tópico).
     * @return os registros criados.
     * @throws ErroNegocio 404 tópico; 409 geração em andamento, gerações simultâneas ou limite de questões; 429 limites de IA; 503 IA indisponível ou fila cheia.
     */
    private fun pedir(topicoId: UUID, operacao: String, quantidade: Int, dificuldade: String = "media", orientacaoPedida: String? = null): Pedido {
        val uid = usuario()
        tx.executeWithoutResult { dados.topico(topicoId) ?: throw topicoNaoEncontrado() }  // 404 antes de qualquer chamada ao serviço de IA
        val modelo = try { proc.modelos().first } catch (e: FalhaProcessamento) { throw indisponivel(e.codigo) }
        val t = tx.execute {
            val topico = dados.travarTopico(topicoId) ?: throw topicoNaoEncontrado()
            recuperarAbandonados(topicoId)
            if (dados.registrosAbertos(topicoId).isNotEmpty()) throw ErroNegocio(HttpStatus.CONFLICT, "Já há uma geração em andamento neste tópico. Aguarde terminar.", "geracao_em_andamento")
            val abertas = dados.emAndamentoDoUsuario(props.geracao.pulsoMaximo.seconds)
            if (abertas >= props.geracao.simultaneasPorUsuario)
                throw ErroNegocio(HttpStatus.CONFLICT, "Você já tem $abertas ${if (abertas == 1) "geração em andamento" else "gerações em andamento"}. Aguarde uma terminar para pedir outra.", "geracoes_simultaneas")
            if (operacao == "questoes") {
                val restantes = props.geracao.questoesPorTopico - dados.totalQuestoes(topicoId)
                if (quantidade > restantes) throw ErroNegocio(HttpStatus.CONFLICT, "Este tópico aceita no máximo ${props.geracao.questoesPorTopico} questões (restam $restantes).", "limite_questoes_topico")
            }
            limites.exigirDisponivel()
            val livre = dados.fontes(topicoId).isEmpty()
            val registro = UUID.randomUUID()
            dados.criarRegistro(registro, uid, topico.id, livre, operacao, modelo)
            val verificacao = if (operacao == "questoes") UUID.randomUUID().also { dados.criarRegistro(it, uid, topico.id, livre, "verificacao_questao", modelo) } else null
            Tarefa3(uid, topicoId, operacao, quantidade, registro, verificacao, dificuldade, if (orientacaoPedida != null) TopicoService.limparOrientacao(orientacaoPedida) else topico.orientacao)
        }!!
        submeter(t)
        return Pedido(t.registroId, t.verificacaoId)
    }

    /**
     * Enfileira a tarefa (ou executa na hora, se `assincrono` for falso).
     * @param t tarefa.
     * @throws ErroNegocio 503 `fila_cheia` se a fila estiver cheia (os registros abertos são removidos).
     */
    private fun submeter(t: Tarefa3) {
        ativos += t.registroId
        val corpo = Runnable { executar(t) }
        if (!props.geracao.assincrono) return corpo.run()
        try {
            pool.execute(corpo)
        } catch (e: RejectedExecutionException) {
            ativos -= t.registroId
            tx.executeWithoutResult { dados.removerRegistrosAbertos(listOfNotNull(t.registroId, t.verificacaoId)) }  // nunca começou: não gasta a cota do usuário
            throw ErroNegocio(HttpStatus.SERVICE_UNAVAILABLE, "Muitas gerações na fila. Tente novamente em instantes.", "fila_cheia")
        }
    }

    // ---------------------------------------------------------------- execução
    /**
     * Contexto de uma execução: acumuladores de tokens e custo do registro principal e do da verificação.
     * @property t tarefa em execução.
     */
    private class Ctx(val t: Tarefa3) {
        val q = Acumulado()      // registro do resumo ou de `questoes`
        val v = Acumulado()      // registro `verificacao_questao`
        var modelo = ""
    }

    /**
     * Executa a tarefa como o usuário dono; cancelamento e falhas fecham os registros com o que já foi gasto.
     * @param t tarefa.
     * @return nada.
     */
    private fun executar(t: Tarefa3) = UsuarioAtual.como(t.usuarioId) {
        val c = Ctx(t)
        try {
            if (t.operacao == "resumo") rodarResumo(c) else rodarQuestoes(c)
        } catch (e: Cancelada) {
            log.info("Geração {} cancelada", t.registroId)
            runCatching { tx.executeWithoutResult { dados.gravarConsumo(t.registroId, c.q); t.verificacaoId?.let { dados.gravarConsumo(it, c.v) } } }
        } catch (e: Exception) {
            if (e is FalhaProcessamento) log.warn("Geração {} falhou: {}", t.registroId, e.message) else log.error("Geração {} falhou", t.registroId, e)
            val motivo = if (e is FalhaProcessamento) "falha_ia" else "erro_interno"
            runCatching { tx.executeWithoutResult { gravar(c, "falhou", 0, motivo) } }.onFailure { log.error("Não foi possível marcar {} como falhou", t.registroId, it) }
        } finally {
            ativos -= t.registroId
        }
    }

    /**
     * Grava status, progresso e consumo nos registros da execução.
     * @param c contexto da execução.
     * @param status novo status.
     * @param pct progresso.
     * @param motivo código do motivo, se falhou.
     * @return falso se a operação já não estava aberta (cancelada no meio do caminho).
     */
    private fun gravar(c: Ctx, status: String, pct: Int, motivo: String? = null): Boolean {
        val aberta = dados.gravar(c.t.registroId, status, pct, c.modelo.ifBlank { "desconhecido" }, c.q, motivo)
        c.t.verificacaoId?.let { dados.gravar(it, status, pct, c.modelo.ifBlank { "desconhecido" }, c.v, motivo) }
        return aberta
    }

    /**
     * Ponto de parada: antes de cada chamada ao modelo e antes de gravar, a tarefa confere se o usuário não cancelou.
     * @param c contexto da execução.
     * @throws Cancelada se o registro já não estiver aberto.
     */
    private fun continuar(c: Ctx) {
        if (tx.execute { dados.estaAberto(c.t.registroId) } != true) throw Cancelada()
    }

    /**
     * busca o embedding do nome só se o tópico não cabe no contexto (chamada HTTP fora da transação) e monta a base.
     * @param c contexto da execução.
     * @return o conteúdo-base do tópico.
     */
    private fun prepararBase(c: Ctx): Base {
        val topico = tx.execute { dados.topico(c.t.topicoId) } ?: throw FalhaProcessamento("falha_ia", "Tópico removido")
        val vetor = if (tx.execute { conteudo.precisaDeBusca(c.t.topicoId) } == true) proc.embeddings(listOf(topico.nome)) else null
        if (vetor != null) {
            if (vetor.vetores.size != 1 || vetor.vetores[0].size != Indexador.DIMENSAO) throw FalhaProcessamento("falha_ia", "Embedding fora do esperado")
            c.q.tokensEntrada += vetor.tokens
            c.q.custo = c.q.custo.add(conteudo.custoEmbedding(vetor.modelo, vetor.tokens))
        }
        return tx.execute { conteudo.obter(topico, vetor, variar = c.t.operacao == "questoes") }!!
    }

    /**
     * Gera o resumo: monta a base, chama o modelo e grava o resumo com o registro concluído, numa transação.
     * @param c contexto da execução.
     * @throws FalhaProcessamento se o modelo falhar ou devolver resumo vazio.
     */
    private fun rodarResumo(c: Ctx) {
        continuar(c)  // cancelada ainda na fila: não gasta nem a chamada de embedding
        tx.executeWithoutResult { gravar(c, "em_andamento", 5) }
        val base = prepararBase(c)
        continuar(c)
        tx.executeWithoutResult { gravar(c, "em_andamento", 30) }
        val r = proc.resumir(base.topico.nome, base.paraModelo(), c.t.orientacao)
        c.q.tokensEntrada += r.tokensEntrada; c.q.tokensSaida += r.tokensSaida; c.modelo = r.modelo
        c.q.custo = c.q.custo.add(precos.custoLinguagem(r.modelo, r.tokensEntrada, r.tokensSaida))
        if (r.texto.isBlank()) throw FalhaProcessamento("falha_ia", "Resumo vazio")
        tx.executeWithoutResult {
            dados.inserirResumo(UUID.randomUUID(), c.t.usuarioId, c.t.topicoId, r.texto.trim())
            dados.aparaResumos(c.t.topicoId, props.geracao.resumosPorTopico)
            if (!gravar(c, "concluida", 100)) throw Cancelada()  // cancelada durante a chamada: desfaz o resumo (nada fica gravado)
        }
    }

    /**
     * Gera questões em rodadas (até `tentativasPorQuestao`): verifica a fidelidade, descarta repetidas e grava só as aprovadas.
     * @param c contexto da execução.
     * @throws FalhaProcessamento se o modelo não devolver nenhuma questão válida.
     */
    private fun rodarQuestoes(c: Ctx) {
        val n = c.t.quantidade
        continuar(c)
        tx.executeWithoutResult { gravar(c, "em_andamento", 3) }
        val base = prepararBase(c)
        continuar(c)
        val trechos = base.paraModelo()
        val evitar = tx.execute { dados.enunciadosRecentes(c.t.topicoId, props.geracao.enunciadosAEvitar) }!!.toMutableList()
        val vistos = mutableSetOf<String>()
        var geradas = 0
        var gravadas = 0
        /**
         * Progresso corrente da geração de questões.
         * @return percentual de 10 a 95 conforme as questões gravadas.
         */
        fun pct() = 10 + 85 * gravadas / n

        tx.executeWithoutResult { gravar(c, "em_andamento", 10) }
        for (rodada in 1..props.tentativasPorQuestao) {
            if (gravadas >= n) break  // repetida não ocupa vaga: a rodada seguinte gera outra
            val falta = n - gravadas
            continuar(c)
            val g = proc.gerarQuestoes(base.topico.nome, trechos, falta, evitar, c.t.dificuldade, c.t.orientacao)
            c.q.tokensEntrada += g.tokensEntrada; c.q.tokensSaida += g.tokensSaida; c.modelo = g.modelo
            c.q.custo = c.q.custo.add(precos.custoLinguagem(g.modelo, g.tokensEntrada, g.tokensSaida))
            geradas += g.questoes.size
            for (q in g.questoes.take(falta)) {
                continuar(c)
                val alternativas = (listOf(q.correta) + q.erradas).shuffled()
                val correta = alternativas.indexOf(q.correta)
                // a resposta precisa ser sustentada pelos trechos USADOS na geração; mandar só os citados evita pagar o contexto inteiro por questão.
                val citados = if (base.livre) trechos else trechos.filter { it.ordem in q.ordens }
                val v = proc.verificar(base.topico.nome, citados, q.enunciado, alternativas, correta)
                c.v.tokensEntrada += v.tokensEntrada; c.v.tokensSaida += v.tokensSaida
                c.v.custo = c.v.custo.add(precos.custoLinguagem(v.modelo, v.tokensEntrada, v.tokensSaida))
                val apoio = v.ordens.filter { it in 1..trechos.size }.toSet()
                if (!(v.sustentada && v.unicaCorreta && (base.livre || apoio.isNotEmpty()))) {
                    c.v.rejeitadas++
                    tx.executeWithoutResult { gravar(c, "em_andamento", pct()) }
                    continue
                }
                // "Aprovada" só entra na contagem quando o destino dela está decidido (gravada ou descartada por repetição): cancelar no meio não deixa `gravadas` errada.
                val hash = hashDoEnunciado(q.enunciado)
                if (!vistos.add(hash) || tx.execute { dados.existeHash(c.t.topicoId, hash) } == true) {
                    c.v.aprovadas++; c.q.repetidas++
                    tx.executeWithoutResult { gravar(c, "em_andamento", pct()) }
                    continue
                }
                continuar(c)
                val emb = proc.embeddings(listOf(q.enunciado))
                if (emb.vetores.size != 1 || emb.vetores[0].size != Indexador.DIMENSAO) throw FalhaProcessamento("falha_ia", "Embedding fora do esperado")
                c.q.tokensEntrada += emb.tokens
                c.q.custo = c.q.custo.add(conteudo.custoEmbedding(emb.modelo, emb.tokens))
                try {
                    tx.executeWithoutResult {
                        if (!dados.estaAberto(c.t.registroId)) throw Cancelada()
                        dados.inserirQuestao(
                            UUID.randomUUID(), c.t.usuarioId, c.t.topicoId, q.enunciado.trim(), json.writeValueAsString(alternativas), correta, hash, emb.vetores[0], emb.modelo,
                            apoio.map { base.trechos[it - 1].id }, c.t.dificuldade,
                        )
                    }
                    gravadas++; c.v.aprovadas++
                    evitar += q.enunciado
                } catch (e: DataIntegrityViolationException) {  // corrida com outra gravação do mesmo enunciado: o banco também impede
                    c.v.aprovadas++; c.q.repetidas++
                }
                tx.executeWithoutResult { gravar(c, "em_andamento", pct()) }
            }
        }
        if (geradas == 0) throw FalhaProcessamento("falha_ia", "O modelo não devolveu nenhuma questão válida")
        continuar(c)
        tx.executeWithoutResult { gravar(c, "concluida", 100) }
    }

    // ---------------------------------------------------------------- consultas
    /**
     * Operação aberta há mais que o pulso, sem tarefa viva neste processo (reinício): vira falhou. Dentro de transação.
     * @param topicoId tópico.
     */
    private fun recuperarAbandonados(topicoId: UUID) {
        val abertos = dados.registrosAbertos(topicoId)
        val abandonados = abertos.filter { it.id !in ativos && Duration.between(it.atualizadoEm, relogio.instant()) > props.geracao.pulsoMaximo }
        // verificação e geração andam juntas: se uma morreu, a outra também
        val vivos = abertos.any { it.id in ativos }
        if (abandonados.isNotEmpty() && !vivos) dados.falharAbertos(abertos.map { it.id }, "interrompido")
    }

    /**
     * Consulta o estado de uma geração, recuperando antes as abandonadas.
     * @param registroId registro da geração.
     * @return o estado.
     * @throws ErroNegocio 404 se não existir.
     */
    fun consultar(registroId: UUID): GeracaoDto {
        tx.executeWithoutResult { dados.registro(registroId)?.let { recuperarAbandonados(it.topicoId) } }
        return tx.execute { montar(dados.registro(registroId) ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Operação não encontrada.", "nao_encontrado")) }!!
    }

    /**
     * Cancela uma geração em andamento. Marca os registros como `falhou/cancelada` na hora (a tela já mostra) e a tarefa para no próximo ponto de parada; a chamada ao modelo que estiver em curso não se interrompe, então seus tokens ainda entram no registro. Questões já gravadas ficam. Idempotente: operação já terminada devolve o estado atual.
     * @param registroId registro da geração.
     * @return o estado após o cancelamento.
     * @throws ErroNegocio 404 se não existir.
     */
    fun cancelar(registroId: UUID): GeracaoDto = tx.execute {
        val r = dados.registro(registroId)?.takeIf { it.operacao != "verificacao_questao" } ?: throw ErroNegocio(HttpStatus.NOT_FOUND, "Operação não encontrada.", "nao_encontrado")
        if (r.status in setOf("pendente", "em_andamento")) {
            auditoria.registrar("geracao_cancelada", usuario(), "registro=${r.id} operacao=${r.operacao}")
            dados.falharAbertos(listOfNotNull(r.id, if (r.operacao == "questoes") dados.verificacaoDe(r)?.id else null), "cancelada")
        }
        montar(dados.registro(registroId)!!)
    }!!

    /**
     * Monta o DTO de uma geração, combinando o registro principal com o da verificação.
     * @param r registro principal ou de verificação.
     * @return o estado para a tela.
     */
    private fun montar(r: RegistroTopico): GeracaoDto {
        val v = if (r.operacao == "questoes") dados.verificacaoDe(r) else r.takeIf { it.operacao == "verificacao_questao" }
        val aprovadas = v?.aprovadas ?: 0
        val rejeitadas = v?.rejeitadas ?: 0
        val gravadas = if (r.operacao == "questoes") aprovadas - r.repetidas else 0
        val mantidas = if (gravadas > 0) " As questões já gravadas foram mantidas." else ""
        val mensagem = when {
            r.status != "falhou" -> null
            r.motivo == "cancelada" -> "Geração cancelada.$mantidas"
            else -> "A geração não foi concluída: o modelo de linguagem falhou ou ficou indisponível. Tente novamente.$mantidas"
        }
        return GeracaoDto(
            r.id, r.operacao, r.status, r.progresso, aprovadas, rejeitadas, r.repetidas, gravadas,
            if (aprovadas + rejeitadas > 0) rejeitadas.toDouble() / (aprovadas + rejeitadas) else null, mensagem, v?.takeIf { it.id != r.id }?.id, r.motivo,
        )
    }

    /**
     * Detalhe do tópico, com fontes, resumo e contagens.
     * @param topicoId tópico.
     * @return o detalhe.
     * @throws ErroNegocio 404 se não existir.
     */
    fun detalhe(topicoId: UUID): TopicoDetalhe {
        tx.executeWithoutResult { dados.topico(topicoId) ?: throw topicoNaoEncontrado(); recuperarAbandonados(topicoId) }
        return tx.execute {
            val t = dados.topico(topicoId)!!
            val fontes = dados.fontes(topicoId).map { FonteDto(it.materialId, it.nomeArquivo, it.inicio, it.fim, it.titulo) }
            TopicoDetalhe(
                t.id, t.assuntoId, t.nome, fontes.isEmpty(), fontes, dados.resumoRecente(topicoId)?.let { ResumoDto(it.first, it.second) },
                dados.totalQuestoes(topicoId), props.geracao.questoesPorTopico, props.geracao.resumosPorTopico, dados.registrosAbertos(topicoId).firstOrNull { it.operacao != "verificacao_questao" }?.id,
                dados.ultimaDeQuestoes(topicoId)?.let { montar(it) }, t.orientacao,
            )
        }!!
    }

    /**
     * Resumos guardados do tópico.
     * @param topicoId tópico.
     * @return os resumos mais recentes.
     * @throws ErroNegocio 404 se o tópico não existir.
     */
    fun resumos(topicoId: UUID): List<ResumoDto> = tx.execute {
        dados.topico(topicoId) ?: throw topicoNaoEncontrado()
        dados.resumos(topicoId).map { ResumoDto(it.first, it.second) }
    }!!

    /**
     * Tópicos livres do usuário.
     * @param limite máximo de itens.
     * @return os tópicos livres, marcando os que estão gerando.
     */
    fun topicosLivres(limite: Int): List<TopicoLivreDto> = tx.execute {
        val gerando = dados.topicosGerando(props.geracao.pulsoMaximo.seconds)
        dados.topicosLivres(limite).map { it.copy(gerando = it.id in gerando) }
    }!!

    /**
     * Questões do tópico, sem a resposta correta das ainda não respondidas.
     * @param topicoId tópico.
     * @return as questões.
     * @throws ErroNegocio 404 se o tópico não existir.
     */
    fun questoes(topicoId: UUID): List<QuestaoDto> = tx.execute {
        dados.topico(topicoId) ?: throw topicoNaoEncontrado()
        val apoios = dados.apoios(topicoId).groupBy { it.questaoId }
        val respondidas = dados.respondidas(topicoId)
        dados.questoes(topicoId).map { q ->
            val r = respondidas[q.id]
            QuestaoDto(q.id, q.enunciado, json.readTree(q.alternativas).toList().map { it.asString() }, q.respostaCorreta.takeIf { r != null }, r != null, r?.first, r?.second, q.criadoEm, q.dica, q.dificuldade, apoios[q.id].orEmpty().map { ApoioDto(it.nomeArquivo, it.ordem, it.numeroPagina) })
        }
    }!!

    override fun destroy() = pool.shutdown()
}
