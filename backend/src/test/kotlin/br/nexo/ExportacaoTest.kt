package br.nexo

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exportação de desempenho e questões em CSV e JSON: conteúdo, escape, isolamento entre contas e volume. */
class ExportacaoTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private val http = HttpClient.newHttpClient()

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun topico(c: Conta, nome: String) = chamar("POST", "/topicos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()

    private fun questao(c: Conta, topico: String, enunciado: String, alternativas: String = """["a","b","c"]"""): String {
        val id = UUID.randomUUID()
        dono.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,?,?::jsonb,1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
            id, c.id, topico, enunciado, alternativas, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
        )
        return id.toString()
    }

    private fun baixar(caminho: String, token: String?): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$porta$caminho"))
        if (token != null) b.header("Authorization", "Bearer $token")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `questoes em CSV saem com cabecalho, escape de virgula aspas e quebra de linha, alternativas juntas e formula neutralizada`() {
        val c = conta(); val t = topico(c, "Tema")
        questao(c, t, "Qual, \"entre\" estas?\nSegunda linha", """["um","dois"]""")
        questao(c, t, "=1+1", """["=cmd","outra"]""")
        val r = baixar("/exportacao?conteudo=questoes&formato=csv", c.token)
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Disposition").get().contains("nexo-questoes.csv"))
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("text/csv"))
        val corpo = r.body()
        assertTrue(corpo.startsWith("﻿assunto,topico,questao_id,enunciado,alternativas,resposta_correta,dificuldade,criada_em\r\n"), corpo.take(120))
        assertTrue(corpo.contains("\"Qual, \"\"entre\"\" estas?\nSegunda linha\",um | dois,1,media,"), corpo)
        assertTrue(corpo.contains(",'=1+1,'=cmd | outra,"), "texto que começa como fórmula ganha apóstrofo: $corpo")
    }

    @Test
    fun `desempenho em JSON traz uma linha por resposta`() {
        val c = conta(); val t = topico(c, "Tema"); val q = questao(c, t, "Q?")
        assertEquals(200, chamar("POST", "/questoes/$q/respostas", """{"alternativa":1}""", c.token).status)
        assertEquals(200, chamar("POST", "/questoes/$q/respostas", """{"alternativa":0}""", c.token).status)
        val r = baixar("/exportacao?conteudo=desempenho&formato=json", c.token)
        assertEquals(200, r.statusCode())
        val linhas = mapper.readTree(r.body())
        assertEquals(2, linhas.size())
        assertEquals(q, linhas[0]["questao_id"].asString()); assertEquals("Tema", linhas[0]["topico"].asString())
        assertEquals(listOf(true, false), linhas.values().asSequence().map { it["correta"].asBoolean() }.toList())
    }

    @Test
    fun `cada conta exporta so os proprios dados`() {
        val a = conta(); val b = conta()
        val qa = questao(a, topico(a, "Tema de A"), "Enunciado secreto de A")
        chamar("POST", "/questoes/$qa/respostas", """{"alternativa":1}""", a.token)
        val qb = questao(b, topico(b, "Tema de B"), "Enunciado de B")
        chamar("POST", "/questoes/$qb/respostas", """{"alternativa":1}""", b.token)
        for (conteudo in listOf("questoes", "desempenho")) for (formato in listOf("csv", "json")) {
            val corpo = baixar("/exportacao?conteudo=$conteudo&formato=$formato", b.token).body()
            assertFalse(corpo.contains("Tema de A") || corpo.contains(qa) || corpo.contains("secreto"), "$conteudo/$formato de B não pode trazer dados de A")
            assertTrue(corpo.contains("Tema de B") && corpo.contains(qb), "$conteudo/$formato de B traz os dados dele")
        }
    }

    @Test
    fun `sem dados, formato ou conteudo invalido e sem login`() {
        val c = conta()
        val vazio = baixar("/exportacao?conteudo=questoes&formato=csv", c.token)
        assertEquals(404, vazio.statusCode()); assertEquals("sem_dados", mapper.readTree(vazio.body())["codigo"].asString())
        questao(c, topico(c, "T"), "Q?")
        assertEquals(400, baixar("/exportacao?conteudo=questoes&formato=xml", c.token).statusCode())
        assertEquals(400, baixar("/exportacao?conteudo=senhas&formato=csv", c.token).statusCode())
        assertEquals(401, baixar("/exportacao?conteudo=questoes&formato=csv", null).statusCode())
        assertEquals(404, baixar("/exportacao?conteudo=desempenho&formato=csv", c.token).statusCode(), "tem questão, mas nenhuma resposta")
    }

    @Test
    fun `volume - 20 mil questoes e 20 mil respostas saem em arquivo valido`() {
        val c = conta(); val t = topico(c, "Volume")
        val n = 20_000
        dono.update(
            """INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding)
               SELECT gen_random_uuid(), ?, ?::uuid, 'Enunciado número ' || g || ' com texto razoavelmente longo para simular uma pergunta de verdade, com vírgula e acentuação?', '["primeira alternativa","segunda alternativa","terceira alternativa","quarta alternativa"]'::jsonb, 1,
                      md5(g::text) || md5(g::text || 'x'), array_fill(0.01::real, ARRAY[1536])::vector, 'm' FROM generate_series(1, ?) g""", c.id, t, n,
        )
        dono.update(
            "INSERT INTO resposta(id,usuario_id,questao_id,alternativa_escolhida,correta,respondida_em) SELECT gen_random_uuid(), usuario_id, id, 1, true, now() FROM questao WHERE usuario_id = ?", c.id,
        )
        dono.execute("ANALYZE questao"); dono.execute("ANALYZE resposta")  // sem estatísticas o plano de dados recém-inseridos não representa o de produção
        for ((conteudo, formato) in listOf("questoes" to "csv", "questoes" to "json", "desempenho" to "csv", "desempenho" to "json")) {
            val arq = Files.createTempFile("exportacao", ".$formato")
            val ini = System.nanoTime()
            val r = http.send(HttpRequest.newBuilder(URI("http://localhost:$porta/exportacao?conteudo=$conteudo&formato=$formato")).header("Authorization", "Bearer ${c.token}").build(), HttpResponse.BodyHandlers.ofFile(arq))
            val ms = (System.nanoTime() - ini) / 1_000_000
            assertEquals(200, r.statusCode())
            val tamanho = Files.size(arq)
            if (formato == "csv") assertEquals(n + 1L, Files.lines(arq).use { it.count() }, "cabeçalho mais uma linha por registro")
            else assertEquals(n, mapper.readTree(Files.readString(arq)).size())
            println("EXPORTACAO-MEDIDA $conteudo/$formato: $n linhas, ${tamanho / 1024} KiB, $ms ms")
            Files.delete(arq)
        }
    }

    @Test
    fun `memoria da exportacao nao cresce com o tamanho - 20 mil e 100 mil questoes`() {
        val c = conta(); val t = topico(c, "Memoria")
        /** Pico de memória usada (em MiB) acima da linha de base enquanto baixa o arquivo para o disco, amostrado a cada 10 ms. */
        fun pico(): Long {
            System.gc(); Thread.sleep(300)
            val rt = Runtime.getRuntime()
            val base = rt.totalMemory() - rt.freeMemory()
            var maximo = base
            val parar = java.util.concurrent.atomic.AtomicBoolean(false)
            val amostrador = Thread { while (!parar.get()) { maximo = maxOf(maximo, rt.totalMemory() - rt.freeMemory()); Thread.sleep(10) } }.also { it.start() }
            val arq = Files.createTempFile("exportacao-memoria", ".csv")
            http.send(HttpRequest.newBuilder(URI("http://localhost:$porta/exportacao?conteudo=questoes&formato=csv")).header("Authorization", "Bearer ${c.token}").build(), HttpResponse.BodyHandlers.ofFile(arq))
            parar.set(true); amostrador.join()
            val tamanho = Files.size(arq) / (1024 * 1024); Files.delete(arq)
            return (maximo - base) / (1024 * 1024) * 1000 + tamanho  // pico * 1000 + tamanho do arquivo, só para devolver os dois
        }
        fun semear(n: Int) {
            dono.update("DELETE FROM questao WHERE usuario_id = ?", c.id)
            dono.update(
                """INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding)
                   SELECT gen_random_uuid(), ?, ?::uuid, 'Enunciado número ' || g || ' com texto razoavelmente longo para simular uma pergunta de verdade, com vírgula e acentuação?', '["primeira alternativa","segunda alternativa","terceira alternativa","quarta alternativa"]'::jsonb, 1,
                          md5(g::text) || md5(g::text || 'x'), array_fill(0.01::real, ARRAY[1536])::vector, 'm' FROM generate_series(1, ?) g""", c.id, t, n,
            )
            dono.execute("ANALYZE questao")
        }
        semear(20_000); pico()  // aquecimento
        val a = pico(); semear(100_000); val b = pico()
        println("EXPORTACAO-MEMORIA 20 mil questões: pico +${a / 1000} MiB (arquivo de ${a % 1000} MiB); 100 mil: pico +${b / 1000} MiB (arquivo de ${b % 1000} MiB)")
        // o pico inclui lixo ainda não coletado (limite superior); o que importa é que cinco vezes mais dados não cinco vezes mais memória
        assertTrue(b / 1000 - a / 1000 < 40, "cinco vezes mais questões (${b % 1000} MiB de arquivo contra ${a % 1000}) subiram o pico de ${a / 1000} para ${b / 1000} MiB")
    }
}
