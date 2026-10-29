package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dica por questão e chat por tópico: geração sob demanda, cache, registros, limites, histórico e isolamento entre contas. */
class TutorTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    @AfterEach fun restaurar() { python.propostaTopicos = "[]"; python.statusGeracao = null; python.textoChat = null; python.aoReceber = null }

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun topico(c: Conta) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}"}""", c.token).corpo["id"].asString()

    private fun questao(c: Conta, topico: String): String {
        val id = UUID.randomUUID()
        dono.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,'Q?','[\"a\",\"b\",\"c\"]',1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
            id, c.id, topico, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
        )
        return id.toString()
    }

    private fun registros(c: Conta, operacao: String) = dono.queryForList("SELECT status || ':' || tokens_entrada || ':' || tokens_saida FROM registro_geracao WHERE usuario_id = ? AND operacao = ? ORDER BY criado_em", String::class.java, c.id, operacao).filterNotNull()
    private fun chamadas(rota: String) = python.chamadas.count { it == rota }

    @Test
    fun `dica e gerada na primeira vez, guardada na questao e reaproveitada sem nova chamada`() {
        val c = conta(); val t = topico(c); val q = questao(c, t)
        val antes = chamadas("dica")
        val r = chamar("POST", "/questoes/$q/dica", token = c.token)
        assertEquals(200, r.status); assertEquals("Dica de teste", r.corpo["dica"].asString())
        assertEquals(antes + 1, chamadas("dica"))
        assertEquals(listOf("concluida:15:6"), registros(c, "dica"))
        assertEquals("Dica de teste", dono.queryForObject("SELECT dica FROM questao WHERE id = ?::uuid", String::class.java, q))
        assertEquals("Dica de teste", chamar("POST", "/questoes/$q/dica", token = c.token).corpo["dica"].asString())
        assertEquals(antes + 1, chamadas("dica"), "a segunda leitura não chama o modelo")
        assertEquals(1, registros(c, "dica").size, "nem cria registro")
        assertEquals("Dica de teste", chamar("GET", "/topicos/$t/questoes", token = c.token).corpo[0]["dica"].asString())
        // a dica vai ao modelo junto do gabarito (que o prompt manda não revelar) e do tópico
        assertTrue(python.corpos.last { it.startsWith("dica:") }.contains("\"correta\":1"))
    }

    @Test
    fun `dica de questao sem trechos citados em topico de material usa os trechos do topico e nao conta como livre`() {
        val c = conta()
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":5}]"""
        val material = enviarArquivo(c.token, "m.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=5 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        val t = chamar("GET", "/materiais/$material/topicos", token = c.token).corpo[0]["id"].asString()
        val q = questao(c, t)  // sem linhas em questao_trecho: o material que ela citava foi excluído
        assertEquals(200, chamar("POST", "/questoes/$q/dica", token = c.token).status)
        assertEquals(listOf("material"), dono.queryForList("SELECT tipo_entrada FROM registro_geracao WHERE usuario_id = ? AND operacao = 'dica'", String::class.java, c.id))
        val enviados = mapper.readTree(python.corpos.last { it.startsWith("dica:") }.substringAfter(":"))["trechos"]
        assertTrue(enviados.size() > 0 && enviados[0]["conteudo"].asString().startsWith("Trecho 1"), "usa os trechos do tópico")
    }

    @Test
    fun `dica de questao de outra conta e 404 sem chamar o modelo nem registrar`() {
        val a = conta(); val b = conta(); val qb = questao(b, topico(b))
        val antes = chamadas("dica")
        assertEquals(404, chamar("POST", "/questoes/$qb/dica", token = a.token).status)
        assertEquals(404, chamar("POST", "/questoes/${UUID.randomUUID()}/dica", token = a.token).status)
        assertEquals(antes, chamadas("dica")); assertEquals(0, registros(a, "dica").size)
        assertEquals(null, dono.queryForObject("SELECT dica FROM questao WHERE id = ?::uuid", String::class.java, qb))
    }

    @Test
    fun `falha do modelo na dica da 503, deixa registro falhou e nao grava dica`() {
        val c = conta(); val q = questao(c, topico(c))
        python.statusGeracao = 500
        val r = chamar("POST", "/questoes/$q/dica", token = c.token)
        assertEquals(503, r.status); assertEquals("ia_indisponivel", r.corpo["codigo"].asString())
        assertEquals(1, registros(c, "dica").size); assertTrue(registros(c, "dica")[0].startsWith("falhou"))
        assertEquals(null, dono.queryForObject("SELECT dica FROM questao WHERE id = ?::uuid", String::class.java, q))
        python.statusGeracao = null
        assertEquals(200, chamar("POST", "/questoes/$q/dica", token = c.token).status)
    }

    @Test
    fun `dica e chat respeitam o limite de operacoes com IA`() {
        val c = conta(); val t = topico(c); val q = questao(c, t)
        repeat(30) { dono.update("INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),?,?::uuid,'resumo','topico_livre','m','concluida',0,0,0,0,0,0,100,now())", c.id, t) }
        val dica = chamadas("dica"); val chat = chamadas("chat")
        assertEquals("limite_operacoes", chamar("POST", "/questoes/$q/dica", token = c.token).corpo["codigo"].asString())
        val r = chamar("POST", "/topicos/$t/chat", """{"mensagem":"oi"}""", c.token)
        assertEquals(429, r.status); assertEquals("limite_operacoes", r.corpo["codigo"].asString())
        assertEquals(dica, chamadas("dica")); assertEquals(chat, chamadas("chat"))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM mensagem_chat WHERE usuario_id = ?", Int::class.java, c.id))
    }

    @Test
    fun `chat grava pergunta e resposta, mantem o historico em ordem e manda as falas anteriores ao modelo`() {
        val c = conta(); val t = topico(c)
        val r = chamar("POST", "/topicos/$t/chat", """{"mensagem":"  O que é calor?  "}""", c.token)
        assertEquals(200, r.status)
        assertEquals(listOf("usuario", "assistente"), r.corpo.toList().map { it["papel"].asString() })
        assertEquals("O que é calor?", r.corpo[0]["conteudo"].asString()); assertEquals("Resposta sobre: O que é calor?", r.corpo[1]["conteudo"].asString())
        chamar("POST", "/topicos/$t/chat", """{"mensagem":"E temperatura?"}""", c.token)
        val h = chamar("GET", "/topicos/$t/chat", token = c.token).corpo.toList()
        assertEquals(listOf("usuario", "assistente", "usuario", "assistente"), h.map { it["papel"].asString() })
        assertEquals("E temperatura?", h[2]["conteudo"].asString())
        val corpo2 = python.corpos.last { it.startsWith("chat:") }
        assertTrue(corpo2.contains("O que é calor?") && corpo2.contains("\"pergunta\":\"E temperatura?\""), "o histórico acompanha a pergunta")
        assertEquals(listOf("concluida:40:10", "concluida:40:10"), registros(c, "chat"))
    }

    @Test
    fun `chat valida a mensagem, o topico e o limite de mensagens`() {
        val c = conta(); val t = topico(c)
        listOf("""{"mensagem":""}""", """{"mensagem":"   "}""", "{}", """{"mensagem":"${"x".repeat(2001)}"}""").forEach { assertEquals(400, chamar("POST", "/topicos/$t/chat", it, c.token).status, it.take(30)) }
        assertEquals(404, chamar("POST", "/topicos/${UUID.randomUUID()}/chat", """{"mensagem":"oi"}""", c.token).status)
        repeat(199) { dono.update("INSERT INTO mensagem_chat(id,usuario_id,topico_id,papel,conteudo) VALUES (gen_random_uuid(),?,?::uuid,'usuario','m')", c.id, t) }
        val r = chamar("POST", "/topicos/$t/chat", """{"mensagem":"oi"}""", c.token)
        assertEquals(409, r.status); assertEquals("limite_chat_topico", r.corpo["codigo"].asString())
        assertEquals(204, chamar("DELETE", "/topicos/$t/chat", token = c.token).status)
        assertEquals(200, chamar("POST", "/topicos/$t/chat", """{"mensagem":"oi"}""", c.token).status)
    }

    @Test
    fun `falha do modelo no chat da 503, nao grava mensagens e deixa registro falhou`() {
        val c = conta(); val t = topico(c)
        python.statusGeracao = 500
        assertEquals(503, chamar("POST", "/topicos/$t/chat", """{"mensagem":"oi"}""", c.token).status)
        assertEquals(0, chamar("GET", "/topicos/$t/chat", token = c.token).corpo.size())
        assertTrue(registros(c, "chat").single().startsWith("falhou"))
    }

    @Test
    fun `A nao le, nao escreve nem limpa o chat de B`() {
        val a = conta(); val b = conta(); val t = topico(b)
        chamar("POST", "/topicos/$t/chat", """{"mensagem":"segredo de B"}""", b.token)
        val antes = chamadas("chat")
        assertEquals(404, chamar("GET", "/topicos/$t/chat", token = a.token).status)
        assertEquals(404, chamar("POST", "/topicos/$t/chat", """{"mensagem":"oi"}""", a.token).status)
        assertEquals(404, chamar("DELETE", "/topicos/$t/chat", token = a.token).status)
        assertEquals(antes, chamadas("chat"))
        assertEquals(2, chamar("GET", "/topicos/$t/chat", token = b.token).corpo.size(), "o chat de B segue intacto")
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM mensagem_chat WHERE usuario_id = ?", Int::class.java, a.id))
    }

    @Test
    fun `chat de topico de material usa so os trechos da faixa e busca pelo trecho da pergunta quando nao cabe no contexto`() {
        val c = conta()
        python.propostaTopicos = """[{"nome":"A","inicio":1,"fim":5},{"nome":"B","inicio":6,"fim":30}]"""
        val m = enviarArquivo(c.token, "m.pdf", "%PDF-1.4 PAGINAS=3 TRECHOS=30 nonce=${UUID.randomUUID()}".toByteArray()).corpo["materialId"].asString()
        val (a, b) = chamar("GET", "/materiais/$m/topicos", token = c.token).corpo.toList().map { it["id"].asString() }
        val emb = chamadas("embeddings")
        assertEquals(200, chamar("POST", "/topicos/$a/chat", """{"mensagem":"pergunta curta"}""", c.token).status)
        assertEquals(emb, chamadas("embeddings"), "tópico A cabe no contexto: sem busca")
        assertTrue(python.corpos.last { it.startsWith("chat:") }.contains("Trecho 1 do material"))
        assertEquals(200, chamar("POST", "/topicos/$b/chat", """{"mensagem":"pergunta sobre o fim"}""", c.token).status)
        assertEquals(emb + 1, chamadas("embeddings"), "tópico B não cabe: busca vetorial pela pergunta")
        val corpo = python.corpos.last { it.startsWith("chat:") }
        assertTrue(corpo.contains("Trecho 6 do material") || corpo.contains("Trecho 7 do material"), "traz trechos da faixa de B")
        assertTrue(!corpo.contains("\"conteudo\":\"Trecho 1 do material"), "nada de fora da faixa de B")
    }

    private fun simultaneas(n: Int, acao: (Int) -> BaseIntegracao.Resposta): List<BaseIntegracao.Resposta> {
        val largada = java.util.concurrent.CountDownLatch(1); val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        val resultados = (0 until n).map { i -> pool.submit<BaseIntegracao.Resposta> { largada.await(); acao(i) } }
        largada.countDown()
        return resultados.map { it.get() }.also { pool.shutdown() }
    }

    @Test
    fun `chamadas simultaneas de chat respeitam o limite por hora (o registro nasce antes da chamada ao modelo)`() {
        val c = conta(); val t = topico(c)
        repeat(25) { dono.update("INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),?,?::uuid,'resumo','topico_livre','m','concluida',0,0,0,0,0,0,100,now())", c.id, t) }
        python.aoReceber = { if (it == "chat") Thread.sleep(200) }   // amplia a janela em que as chamadas se sobrepõem
        val antes = chamadas("chat")
        val r = simultaneas(12) { chamar("POST", "/topicos/$t/chat", "{\"mensagem\":\"pergunta $it\"}", c.token) }
        assertEquals(5, r.count { it.status == 200 }, r.map { it.status }.toString())
        assertEquals(7, r.count { it.status == 429 && it.corpo["codigo"].asString() == "limite_operacoes" })
        assertEquals(5, chamadas("chat") - antes, "o modelo só foi chamado pelas que passaram")
        assertEquals(30, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ?", Int::class.java, c.id))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM registro_geracao WHERE usuario_id = ? AND status = 'em_andamento'", Int::class.java, c.id), "nenhum registro fica aberto")
    }

    @Test
    fun `chamadas simultaneas de dica respeitam a cota semanal, que ja conta a vaga reservada`() {
        val c = conta(); val t = topico(c)
        val perguntas = List(6) { questao(c, t) }
        dono.update("INSERT INTO registro_geracao(id,usuario_id,topico_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),?,?::uuid,'resumo','topico_livre','m','concluida',0,0,0.0299,0,0,0,100,now())", c.id, t) // 299 de 300 créditos
        python.aoReceber = { if (it == "dica") Thread.sleep(200) }
        val antes = chamadas("dica")
        val r = simultaneas(6) { chamar("POST", "/questoes/${perguntas[it]}/dica", token = c.token) }
        assertEquals(1, r.count { it.status == 200 }, r.map { it.status }.toString())
        assertEquals(5, r.count { it.status == 429 && it.corpo["codigo"].asString() == "limite_creditos" })
        assertEquals(1, chamadas("dica") - antes)
    }

    @Test
    fun `resposta longa do tutor e cortada ao gravar e cada fala reenviada vai cortada, sem travar o chat`() {
        val c = conta(); val t = topico(c)
        python.textoChat = "x".repeat(7000)
        val primeira = chamar("POST", "/topicos/$t/chat", "{\"mensagem\":\"oi\"}", c.token)
        assertEquals(200, primeira.status)
        assertEquals(6000, primeira.corpo[1]["conteudo"].asString().length, "resposta gravada no máximo em nexo.chat.resposta-max-caracteres")
        dono.update("INSERT INTO mensagem_chat(id,usuario_id,topico_id,papel,conteudo) VALUES (gen_random_uuid(),?,?::uuid,'assistente',?)", c.id, t, "y".repeat(5000))
        assertEquals(200, chamar("POST", "/topicos/$t/chat", "{\"mensagem\":\"e agora?\"}", c.token).status)
        val historico = mapper.readTree(python.corpos.last { it.startsWith("chat:") }.substringAfter(":"))["historico"].toList()
        assertTrue(historico.isNotEmpty() && historico.all { it["conteudo"].asString().length <= 3500 }, "o serviço de IA recusa falas de mais de 4000 caracteres")
    }

    @Test
    fun `sem login as rotas do tutor respondem 401`() {
        val id = UUID.randomUUID()
        assertEquals(401, chamar("POST", "/questoes/$id/dica").status)
        assertEquals(401, chamar("GET", "/topicos/$id/chat").status)
        assertEquals(401, chamar("POST", "/topicos/$id/chat", """{"mensagem":"oi"}""").status)
        assertEquals(401, chamar("DELETE", "/topicos/$id/chat").status)
    }
}
