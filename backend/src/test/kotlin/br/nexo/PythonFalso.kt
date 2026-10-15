package br.nexo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serviço de processamento de mentira para os testes do backend (contrato do ADR 0024). O comportamento sai de marcadores no CONTEÚDO do arquivo:
 * `PAGINAS=n` (páginas do PDF), `TRECHOS=n` (quantos trechos devolver, 5 por padrão), `SEMTEXTO` (422 sem_texto) e `QUEBRAINSPECAO` e `QUEBRAPROCESSAR` (500 sempre na etapa).
 * Arquivo que começa com `%PDF-` é pdf, com `PK` é docx e o resto é txt.
 */
class PythonFalso(val segredo: String) {
    val chamadas = CopyOnWriteArrayList<String>()
    val falhasEmbeddings = AtomicInteger(0)  // quantas chamadas de embeddings devolvem 500 antes de funcionar
    @Volatile var propostaReorganizar: String = "[]"
    @Volatile var statusReorganizar: Int? = null  // reorganizar responde este status enquanto definido
    @Volatile var atrasoReorganizarMs = 0L
    @Volatile var statusInspecao: Int? = null  // inspecionar responde este status (ex.: 401 de segredo errado) enquanto definido
    @Volatile var falharProcessar = false  // processar devolve 422 sem_texto enquanto ligado
    @Volatile var propostaTopicos: String = "[]"
    @Volatile var segredoRecebidoErrado = false
    private val servidor: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${servidor.address.port}"

    init {
        servidor.createContext("/saude") { responder(it, 200, """{"status":"UP","modelo_linguagem":"linguagem-falso","modelo_embedding":"embedding-falso"}""") }
        servidor.createContext("/interno/inspecionar") { interno(it) { x, arq -> inspecionar(x, arq) } }
        servidor.createContext("/interno/processar") { interno(it) { x, arq -> processar(x, arq) } }
        servidor.createContext("/interno/embeddings") { interno(it) { x, corpo -> embeddings(x, corpo) } }
        servidor.createContext("/interno/reorganizar") { interno(it) { x, _ ->
            chamadas += "reorganizar"
            if (atrasoReorganizarMs > 0) Thread.sleep(atrasoReorganizarMs)
            statusReorganizar?.let { s -> return@interno responder(x, s, """{"detail":"falha"}""") }
            responder(x, 200, """{"topicos":$propostaReorganizar,"tokens_entrada":21,"tokens_saida":9,"modelo":"linguagem-falso"}""")
        } }
        servidor.createContext("/interno/topicos") { interno(it) { x, _ -> chamadas += "topicos"; responder(x, 200, """{"topicos":$propostaTopicos,"tokens_entrada":11,"tokens_saida":7,"modelo":"linguagem-falso"}""") } }
        servidor.executor = java.util.concurrent.Executors.newCachedThreadPool()
        servidor.start()
    }

    fun parar() = servidor.stop(0)

    private fun interno(x: HttpExchange, tratar: (HttpExchange, ByteArray) -> Unit) {
        try {
            if (x.requestHeaders.getFirst("X-Nexo-Segredo") != segredo) {
                segredoRecebidoErrado = true
                return responder(x, 401, """{"detail":"não autorizado"}""")
            }
            val corpo = x.requestBody.readAllBytes()
            val ct = x.requestHeaders.getFirst("Content-Type") ?: ""
            tratar(x, if (ct.startsWith("multipart/")) arquivoDe(corpo, ct.substringAfter("boundary=")) else corpo)
        } catch (e: Exception) {
            responder(x, 500, """{"detail":"erro do falso: $e"}""")
        }
    }

    private fun arquivoDe(corpo: ByteArray, limite: String): ByteArray {
        val texto = String(corpo, Charsets.ISO_8859_1)
        val ini = texto.indexOf("\r\n\r\n") + 4
        val fim = texto.indexOf("\r\n--$limite--", ini)
        return corpo.copyOfRange(ini, fim)
    }

    private fun marca(arq: ByteArray, nome: String, padrao: Int) = Regex("$nome=(\\d+)").find(String(arq, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toInt() ?: padrao

    private fun inspecionar(x: HttpExchange, arq: ByteArray) {
        chamadas += "inspecionar"
        statusInspecao?.let { return responder(x, it, """{"detail":"recusado"}""") }
        val t = String(arq, Charsets.ISO_8859_1)
        when {
            "QUEBRAINSPECAO" in t -> responder(x, 500, """{"detail":"quebrou"}""")
            "INVALIDO" in t -> responder(x, 422, """{"detail":{"codigo":"formato_invalido","mensagem":"arquivo ilegível"}}""")
            t.startsWith("%PDF-") -> responder(x, 200, """{"tipo":"pdf","paginas":${marca(arq, "PAGINAS", 3)},"caracteres":null}""")
            t.startsWith("PK") -> responder(x, 200, """{"tipo":"docx","paginas":null,"caracteres":${marca(arq, "CARACTERES", 1000)}}""")
            else -> responder(x, 200, """{"tipo":"txt","paginas":null,"caracteres":${marca(arq, "CARACTERES", arq.size)}}""")
        }
    }

    private fun processar(x: HttpExchange, arq: ByteArray) {
        chamadas += "processar"
        val t = String(arq, Charsets.ISO_8859_1)
        if ("QUEBRAPROCESSAR" in t) return responder(x, 500, """{"detail":"quebrou"}""")
        if (falharProcessar || "SEMTEXTO" in t) return responder(x, 422, """{"detail":{"codigo":"sem_texto","mensagem":"sem texto extraível"}}""")
        val n = marca(arq, "TRECHOS", 5)
        // SECOES=1,6,11 marca os trechos que abrem com título de nível 1 (os demais não têm título, salvo o trecho 1 "Introdução" sem nível)
        val secoes = Regex("SECOES=([0-9,]+)").find(t)?.groupValues?.get(1)?.split(",")?.map { it.toInt() }?.toSet() ?: emptySet()
        val trechos = (1..n).joinToString(",") {
            val titulo = if (it in secoes) "\"Capítulo $it\"" else if (it == 1) "\"Introdução\"" else "null"
            """{"ordem":$it,"numero_pagina":${if (t.startsWith("%PDF-")) it else "null"},"conteudo":"Trecho $it do material de teste.","titulo":$titulo,"nivel":${if (it in secoes) 1 else "null"}}"""
        }
        responder(x, 200, """{"tipo":"pdf","paginas":null,"caracteres":${n * 30},"trechos":[$trechos]}""")
    }

    private fun embeddings(x: HttpExchange, corpo: ByteArray) {
        chamadas += "embeddings"
        if (falhasEmbeddings.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return responder(x, 500, """{"detail":"falha temporária"}""")
        val n = Regex("\"textos\":\\[(.*)]", RegexOption.DOT_MATCHES_ALL).find(String(corpo))!!.groupValues[1].split("\",\"").size
        val vetor = (1..1536).joinToString(",", "[", "]") { "0.01" }
        responder(x, 200, """{"vetores":[${List(n) { vetor }.joinToString(",")}],"tokens":${n * 10},"modelo":"embedding-falso"}""")
    }

    private fun responder(x: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray()
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }
}
