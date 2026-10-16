package br.nexo.material

import br.nexo.NexoProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Falha do serviço de processamento. [retentavel] vale para 5xx e erro de rede; 4xx traz o [codigo] do serviço (sem_texto, formato_invalido...). */
class FalhaProcessamento(val codigo: String, message: String, val retentavel: Boolean = false) : RuntimeException(message)

data class Inspecao(val tipo: String, val paginas: Int?, val caracteres: Int?)
data class TrechoExtraido(val ordem: Int, val numeroPagina: Int?, val conteudo: String, val titulo: String?, val nivel: Int? = null)
data class Extracao(val tipo: String, val paginas: Int?, val caracteres: Int, val trechos: List<TrechoExtraido>)
data class Vetores(val vetores: List<List<Double>>, val tokens: Int, val modelo: String)
data class TopicoProposto(val nome: String, val inicio: Int, val fim: Int)
data class PropostaTopicos(val topicos: List<TopicoProposto>, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
data class TopicoAtual(val nome: String, val inicio: Int, val fim: Int, val congelado: Boolean)
data class ResumoTrecho(val ordem: Int, val nivel: Int?, val titulo: String?, val inicio: String?)

/** `ordem` é o rótulo do trecho dentro do contexto enviado (1, 2, 3...), não a ordem no material: tópicos de vários materiais repetiriam números. */
data class TrechoConteudo(val ordem: Int, val conteudo: String)
data class Resumido(val texto: String, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
data class QuestaoGerada(val enunciado: String, val correta: String, val erradas: List<String>, val ordens: List<Int>)
data class Geradas(val questoes: List<QuestaoGerada>, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)
data class Verificada(val sustentada: Boolean, val unicaCorreta: Boolean, val ordens: List<Int>, val motivo: String, val tokensEntrada: Int, val tokensSaida: Int, val modelo: String)

/** Contrato interno com o serviço Python (ADR 0024). Ele não acessa o banco: recebe e devolve dados. */
interface Processamento {
    fun inspecionar(nome: String, dados: ByteArray): Inspecao
    fun processar(nome: String, dados: ByteArray): Extracao
    fun embeddings(textos: List<String>): Vetores
    fun topicos(trechos: List<ResumoTrecho>): PropostaTopicos
    fun reorganizar(trechos: List<ResumoTrecho>, atuais: List<TopicoAtual>): PropostaTopicos
    fun modelos(): Pair<String, String>  // (linguagem, embedding), do /saude
    fun resumir(topico: String, trechos: List<TrechoConteudo>): Resumido
    fun gerarQuestoes(topico: String, trechos: List<TrechoConteudo>, quantidade: Int, evitar: List<String>): Geradas
    fun verificar(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Verificada
}

@Component
class ProcessamentoHttp(private val props: NexoProperties, private val json: JsonMapper) : Processamento {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build()  // HTTP/2 por padrão manda `Upgrade: h2c` e o uvicorn registra um aviso a cada chamada
    private val base get() = props.processamento.url.trimEnd('/')

    override fun inspecionar(nome: String, dados: ByteArray): Inspecao =
        enviarArquivo("/interno/inspecionar", nome, dados).let { Inspecao(it["tipo"].asString(), it["paginas"]?.takeIf { n -> !n.isNull }?.asInt(), it["caracteres"]?.takeIf { n -> !n.isNull }?.asInt()) }

    override fun processar(nome: String, dados: ByteArray): Extracao = enviarArquivo("/interno/processar", nome, dados).let { r ->
        Extracao(
            r["tipo"].asString(), r["paginas"]?.takeIf { !it.isNull }?.asInt(), r["caracteres"].asInt(),
            r["trechos"].toList().map { TrechoExtraido(it["ordem"].asInt(), it["numero_pagina"]?.takeIf { n -> !n.isNull }?.asInt(), it["conteudo"].asString(), it["titulo"]?.takeIf { n -> !n.isNull }?.asString(), it["nivel"]?.takeIf { n -> !n.isNull }?.asInt()) },
        )
    }

    override fun embeddings(textos: List<String>): Vetores =
        enviarJson("/interno/embeddings", mapOf("textos" to textos)).let { r -> Vetores(r["vetores"].toList().map { v -> v.toList().map { it.asDouble() } }, r["tokens"].asInt(), r["modelo"].asString()) }

    override fun topicos(trechos: List<ResumoTrecho>): PropostaTopicos =
        enviarJson("/interno/topicos", mapOf("trechos" to trechos.map { mapOf("ordem" to it.ordem, "nivel" to it.nivel, "titulo" to it.titulo, "inicio" to it.inicio) })).let { r ->
            PropostaTopicos(r["topicos"].toList().map { TopicoProposto(it["nome"].asString(), it["inicio"].asInt(), it["fim"].asInt()) }, r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    override fun reorganizar(trechos: List<ResumoTrecho>, atuais: List<TopicoAtual>): PropostaTopicos =
        enviarJson(
            "/interno/reorganizar",
            mapOf(
                "trechos" to trechos.map { mapOf("ordem" to it.ordem, "nivel" to it.nivel, "titulo" to it.titulo, "inicio" to it.inicio) },
                "atuais" to atuais.map { mapOf("nome" to it.nome, "inicio" to it.inicio, "fim" to it.fim, "congelado" to it.congelado) },
            ),
        ).let { r -> PropostaTopicos(r["topicos"].toList().map { TopicoProposto(it["nome"].asString(), it["inicio"].asInt(), it["fim"].asInt()) }, r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString()) }

    private fun contexto(topico: String, trechos: List<TrechoConteudo>) = mutableMapOf<String, Any>("topico" to topico, "trechos" to trechos.map { mapOf("ordem" to it.ordem, "conteudo" to it.conteudo) })

    override fun resumir(topico: String, trechos: List<TrechoConteudo>): Resumido =
        enviarJson("/interno/resumo", contexto(topico, trechos)).let { Resumido(it["texto"].asString(), it["tokens_entrada"].asInt(), it["tokens_saida"].asInt(), it["modelo"].asString()) }

    override fun gerarQuestoes(topico: String, trechos: List<TrechoConteudo>, quantidade: Int, evitar: List<String>): Geradas =
        enviarJson("/interno/questoes", contexto(topico, trechos).apply { put("quantidade", quantidade); put("evitar", evitar) }).let { r ->
            Geradas(
                r["questoes"].toList().map { q -> QuestaoGerada(q["enunciado"].asString(), q["correta"].asString(), q["erradas"].toList().map { it.asString() }, q["ordens"].toList().map { it.asInt() }) },
                r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString(),
            )
        }

    override fun verificar(topico: String, trechos: List<TrechoConteudo>, enunciado: String, alternativas: List<String>, correta: Int): Verificada =
        enviarJson("/interno/verificar", contexto(topico, trechos).apply { put("questao", mapOf("enunciado" to enunciado, "alternativas" to alternativas, "correta" to correta)) }).let { r ->
            Verificada(r["sustentada"].asBoolean(), r["unica_correta"].asBoolean(), r["ordens"].toList().map { it.asInt() }, r["motivo"]?.asString() ?: "", r["tokens_entrada"].asInt(), r["tokens_saida"].asInt(), r["modelo"].asString())
        }

    private class Saude(val em: Long, val modelos: Pair<String, String>)
    @Volatile private var saude: Saude? = null

    /**
     * Nomes dos modelos, do /saude. Chamado no começo de toda operação com IA, então precisa falhar rápido com o serviço fora do ar (uma tentativa,
     * `saude-timeout`): antes eram 3 tentativas de 10 s e o pedido segurava uma thread do servidor por 33 s. O resultado vale por `cache-modelos`.
     */
    override fun modelos(): Pair<String, String> {
        val agora = System.nanoTime()
        val modelos = saude?.takeIf { agora - it.em < props.processamento.cacheModelos.toNanos() }?.modelos ?: run {
            val r = com(tentativas = 1) { HttpRequest.newBuilder(URI("$base/saude")).timeout(props.processamento.saudeTimeout).GET().build() }
            (r["modelo_linguagem"].asString() to r["modelo_embedding"].asString()).also { saude = Saude(agora, it) }
        }
        // Produção com provedor de IA falso entregaria conteúdo de demonstração como se fosse real: recusa antes de qualquer operação (chamado no começo de todas).
        if (props.ambiente == "producao" && (modelos.first.contains("falso") || modelos.second.contains("falso")))
            throw FalhaProcessamento("modelo_falso", "O serviço de processamento usa provedor de IA falso em produção (${modelos.first}, ${modelos.second})")
        // Modelo real sem preço configurado gastaria dinheiro sem que o limite de gasto (US$ por janela) pudesse enxergar: recusa antes de qualquer chamada.
        if (!modelos.first.contains("falso") && (props.geracao.precoLinguagem.entradaUsdPorMilhao == null || props.geracao.precoLinguagem.saidaUsdPorMilhao == null))
            throw FalhaProcessamento("preco_ausente", "Modelo de linguagem real (${modelos.first}) sem preço configurado: defina PRECO_LINGUAGEM_ENTRADA_USD_POR_MILHAO e PRECO_LINGUAGEM_SAIDA_USD_POR_MILHAO")
        if (!modelos.second.contains("falso") && props.precoEmbedding(modelos.second) == null)
            throw FalhaProcessamento("preco_ausente", "Modelo de embedding real (${modelos.second}) sem preço: defina PRECO_EMBEDDING_USD_POR_MILHAO (valor do painel do provedor) ou acrescente o modelo em nexo.material.precos-embedding-usd-por-milhao")
        return modelos
    }

    private fun enviarJson(rota: String, corpo: Any): JsonNode = com {
        HttpRequest.newBuilder(URI(base + rota)).timeout(props.processamento.timeout).header("Content-Type", "application/json")
            .header("X-Nexo-Segredo", props.processamento.segredo).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build()
    }

    private fun enviarArquivo(rota: String, nome: String, dados: ByteArray): JsonNode = com {
        val limite = "nexo" + UUID.randomUUID().toString().replace("-", "")
        val corpo = ByteArrayOutputStream().apply {
            write("--$limite\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"arquivo.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(dados)
            write("\r\n--$limite--\r\n".toByteArray())
        }.toByteArray()
        HttpRequest.newBuilder(URI(base + rota)).timeout(props.processamento.timeout).header("Content-Type", "multipart/form-data; boundary=$limite")
            .header("X-Nexo-Segredo", props.processamento.segredo).POST(HttpRequest.BodyPublishers.ofByteArray(corpo)).build()
    }

    /** Tenta até `tentativas` vezes em 5xx e erro de rede, com espera crescente; 4xx sobe na hora (repetir não adianta). */
    private fun com(tentativas: Int = props.processamento.tentativas, requisicao: () -> HttpRequest): JsonNode {
        var ultima: FalhaProcessamento? = null
        for (n in 1..tentativas) {
            try {
                val r = http.send(requisicao(), HttpResponse.BodyHandlers.ofString())
                if (r.statusCode() == 200) return json.readTree(r.body())
                val detalhe = runCatching { json.readTree(r.body())["detail"] }.getOrNull()
                val codigo = detalhe?.takeIf { it.isObject }?.get("codigo")?.asString() ?: "falha_ia"
                if (r.statusCode() < 500) throw FalhaProcessamento(codigo, "Processamento recusou (HTTP ${r.statusCode()}): ${r.body().take(200)}")
                ultima = FalhaProcessamento(if (codigo == "falha_ia") "falha_ia" else codigo, "Processamento falhou (HTTP ${r.statusCode()}): ${r.body().take(200)}", true)
            } catch (e: FalhaProcessamento) {
                throw e
            } catch (e: IOException) {
                ultima = FalhaProcessamento("falha_ia", "Processamento indisponível: ${e.javaClass.simpleName}", true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FalhaProcessamento("falha_ia", "Interrompido")
            }
            log.warn("Serviço de processamento: tentativa {} de {} falhou ({})", n, tentativas, ultima.message)
            if (n < tentativas) Thread.sleep(props.processamento.espera.toMillis() * n)
        }
        throw ultima!!
    }
}
