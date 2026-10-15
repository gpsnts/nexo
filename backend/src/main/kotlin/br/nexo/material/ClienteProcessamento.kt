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

/** Contrato interno com o serviço Python (ADR 0024). Ele não acessa o banco: recebe e devolve dados. */
interface Processamento {
    fun inspecionar(nome: String, dados: ByteArray): Inspecao
    fun processar(nome: String, dados: ByteArray): Extracao
    fun embeddings(textos: List<String>): Vetores
    fun topicos(trechos: List<ResumoTrecho>): PropostaTopicos
    fun reorganizar(trechos: List<ResumoTrecho>, atuais: List<TopicoAtual>): PropostaTopicos
    fun modelos(): Pair<String, String>  // (linguagem, embedding), do /saude
}

@Component
class ProcessamentoHttp(private val props: NexoProperties, private val json: JsonMapper) : Processamento {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
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

    override fun modelos(): Pair<String, String> {
        val r = com { HttpRequest.newBuilder(URI("$base/saude")).timeout(java.time.Duration.ofSeconds(10)).GET().build() }
        return r["modelo_linguagem"].asString() to r["modelo_embedding"].asString()
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
    private fun com(requisicao: () -> HttpRequest): JsonNode {
        var ultima: FalhaProcessamento? = null
        for (n in 1..props.processamento.tentativas) {
            try {
                val r = http.send(requisicao(), HttpResponse.BodyHandlers.ofString())
                if (r.statusCode() == 200) return json.readTree(r.body())
                val detalhe = runCatching { json.readTree(r.body())["detail"] }.getOrNull()
                val codigo = detalhe?.takeIf { it.isObject }?.get("codigo")?.asString() ?: "falha_ia"
                if (r.statusCode() < 500) throw FalhaProcessamento(codigo, "Processamento recusou (HTTP ${r.statusCode()}): ${r.body().take(200)}")
                ultima = FalhaProcessamento("falha_ia", "Processamento falhou (HTTP ${r.statusCode()})", true)
            } catch (e: FalhaProcessamento) {
                throw e
            } catch (e: IOException) {
                ultima = FalhaProcessamento("falha_ia", "Processamento indisponível: ${e.javaClass.simpleName}", true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FalhaProcessamento("falha_ia", "Interrompido")
            }
            log.warn("Serviço de processamento: tentativa {} de {} falhou ({})", n, props.processamento.tentativas, ultima.message)
            if (n < props.processamento.tentativas) Thread.sleep(props.processamento.espera.toMillis() * n)
        }
        throw ultima!!
    }
}
