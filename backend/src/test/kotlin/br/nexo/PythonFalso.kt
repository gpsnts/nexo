package br.nexo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serviço de processamento de mentira para os testes do backend . O comportamento sai de marcadores no CONTEÚDO do arquivo:
 * `PAGINAS=n` (páginas do PDF), `TRECHOS=n` (quantos trechos devolver, 5 por padrão), `SEMTEXTO` (422 sem_texto) e `QUEBRAINSPECAO` e `QUEBRAPROCESSAR` (500 sempre na etapa).
 * Arquivo que começa com `%PDF-` é pdf, com `PK` é docx e o resto é txt.
 */
class PythonFalso(val segredo: String) {
    val chamadas = CopyOnWriteArrayList<String>()
    val falhasEmbeddings = AtomicInteger(0)  // quantas chamadas de embeddings devolvem 500 antes de funcionar
    @Volatile var propostaReorganizar: String = "[]"
    @Volatile var codigoReorganizar: String? = null  // com statusReorganizar: devolve {"detail":{"codigo":...}} como o serviço real faz com os erros do provedor
    @Volatile var statusReorganizar: Int? = null  // reorganizar responde este status enquanto definido
    @Volatile var atrasoReorganizarMs = 0L
    @Volatile var statusInspecao: Int? = null  // inspecionar responde este status (ex.: 401 de segredo errado) enquanto definido
    @Volatile var falharProcessar = false  // processar devolve 422 sem_texto enquanto ligado
    @Volatile var propostaTopicos: String = "[]"
    @Volatile var segredoRecebidoErrado = false
    @Volatile var modeloEmbedding = "embedding-falso"  // nome do modelo de embedding em /saude (um nome sem "falso" simula embedding real)
    @Volatile var modeloLinguagem = "linguagem-falso"  // nome devolvido em /saude e nas respostas (um nome sem "falso" simula modelo real)
    @Volatile var textoChat: String? = null  // resposta do chat no lugar do eco da pergunta (para testar respostas longas)
    @Volatile var statusGeracao: Int? = null  // resumo, questões e verificação respondem este status enquanto definido
    @Volatile var reprovar: (String) -> Boolean = { false }  // a verificação reprova o enunciado quando devolve true
    @Volatile var semApoio = false  // a verificação aprova mas não cita trechos
    @Volatile var naoSustentada = false  // a verificação diz que a resposta marcada não é a única correta
    val roteiroQuestoes = java.util.concurrent.ConcurrentLinkedQueue<String>()  // JSON do array "questoes" das próximas chamadas (vazio: gera válidas e únicas)
    val corpos = CopyOnWriteArrayList<String>()  // "rota:corpo" de cada chamada de geração
    @Volatile var aoReceber: ((String) -> Unit)? = null  // executado quando uma rota de geração é chamada, antes de responder (simula o usuário agindo no meio de uma geração)
    private val sequencia = AtomicInteger(0)
    private val json = tools.jackson.databind.json.JsonMapper.builder().build()
    private val servidor: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${servidor.address.port}"

    init {
        servidor.createContext("/saude") { chamadas += "saude"; responder(it, 200, """{"status":"UP","modelo_linguagem":"$modeloLinguagem","modelo_embedding":"$modeloEmbedding"}""") }
        servidor.createContext("/interno/inspecionar") { interno(it) { x, arq -> inspecionar(x, arq) } }
        servidor.createContext("/interno/processar") { interno(it) { x, arq -> processar(x, arq) } }
        servidor.createContext("/interno/embeddings") { interno(it) { x, corpo -> embeddings(x, corpo) } }
        servidor.createContext("/interno/reorganizar") { interno(it) { x, _ ->
            chamadas += "reorganizar"
            if (atrasoReorganizarMs > 0) Thread.sleep(atrasoReorganizarMs)
            statusReorganizar?.let { s -> return@interno responder(x, s, codigoReorganizar?.let { c -> """{"detail":{"codigo":"$c","mensagem":"do provedor"}}""" } ?: """{"detail":"falha"}""") }
            responder(x, 200, """{"topicos":$propostaReorganizar,"tokens_entrada":21,"tokens_saida":9,"modelo":"$modeloLinguagem"}""")
        } }
        servidor.createContext("/interno/topicos") { interno(it) { x, _ -> chamadas += "topicos"; responder(x, 200, """{"topicos":$propostaTopicos,"tokens_entrada":11,"tokens_saida":7,"modelo":"$modeloLinguagem"}""") } }
        servidor.createContext("/interno/resumo") { interno(it) { x, c -> geracao(x, "resumo", c) {
            responder(x, 200, """{"texto":"Resumo de teste","tokens_entrada":30,"tokens_saida":12,"modelo":"$modeloLinguagem"}""")
        } } }
        servidor.createContext("/interno/questoes") { interno(it) { x, c -> geracao(x, "questoes", c) {
            val n = json.readTree(c)["quantidade"].asInt()
            val roteiro = roteiroQuestoes.poll()
            val questoes = roteiro ?: (1..n).joinToString(",", "[", "]") {
                val k = sequencia.incrementAndGet()
                """{"enunciado":"Pergunta única $k sobre o tema?","correta":"Certa $k","erradas":["Errada A","Errada B","Errada C"],"ordens":[1]}"""
            }
            responder(x, 200, """{"questoes":$questoes,"tokens_entrada":50,"tokens_saida":40,"modelo":"$modeloLinguagem"}""")
        } } }
        servidor.createContext("/interno/verificar") { interno(it) { x, c -> geracao(x, "verificar", c) {
            val ok = !reprovar(json.readTree(c)["questao"]["enunciado"].asString())
            responder(x, 200, """{"sustentada":$ok,"unica_correta":${!naoSustentada},"ordens":${if (semApoio || !ok) "[]" else "[1]"},"motivo":"teste","tokens_entrada":20,"tokens_saida":5,"modelo":"$modeloLinguagem"}""")
        } } }
        servidor.createContext("/interno/dica") { interno(it) { x, c -> geracao(x, "dica", c) {
            responder(x, 200, """{"texto":"Dica de teste","tokens_entrada":15,"tokens_saida":6,"modelo":"$modeloLinguagem"}""")
        } } }
        servidor.createContext("/interno/chat") { interno(it) { x, c -> geracao(x, "chat", c) {
            responder(x, 200, """{"texto":${json.writeValueAsString(textoChat ?: ("Resposta sobre: " + json.readTree(c)["pergunta"].asString()))},"tokens_entrada":40,"tokens_saida":10,"modelo":"$modeloLinguagem"}""")
        } } }
        servidor.createContext("/interno/nome") { interno(it) { x, c -> geracao(x, "nome", c) {
            responder(x, 200, """{"texto":"Nome sugerido de teste","tokens_entrada":25,"tokens_saida":4,"modelo":"$modeloLinguagem"}""")
        } } }
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

    private fun geracao(x: HttpExchange, rota: String, corpo: ByteArray, resposta: () -> Unit) {
        chamadas += rota
        corpos += "$rota:${String(corpo)}"
        aoReceber?.invoke(rota)
        statusGeracao?.let { return responder(x, it, """{"detail":"falha"}""") }
        resposta()
    }

    private fun responder(x: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray()
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }
}
