package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Responder, SM-2, revisão e painel: regras, fuso, prioridade e isolamento entre contas. */
class EstudoTest : BaseIntegracao() {
    @Autowired lateinit var relogio: java.time.Clock
    private val rel get() = relogio as RelogioTeste

    @AfterEach fun soltarRelogio() = rel.liberar()

    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun topico(c: Conta, nome: String = "Tema ${UUID.randomUUID()}") = chamar("POST", "/topicos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()

    /** Questão de 3 alternativas com a correta no índice 1, gravada pelo dono (a geração tem os seus testes). */
    private fun questao(c: Conta, topico: String): String {
        val id = UUID.randomUUID()
        val hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        dono.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,'Q?','[\"a\",\"b\",\"c\"]',1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
            id, c.id, topico, hash,
        )
        return id.toString()
    }

    private fun responder(c: Conta, questao: String, alternativa: Int) = chamar("POST", "/questoes/$questao/respostas", """{"alternativa":$alternativa}""", c.token)
    private fun progresso(questao: String) = dono.queryForMap("SELECT repeticoes, intervalo_dias, fator_facilidade, proxima_revisao::text AS proxima FROM progresso_questao WHERE questao_id = ?::uuid", questao)
    private fun estado(questao: String) = progresso(questao).let { "${it["repeticoes"]}/${it["intervalo_dias"]}/${it["fator_facilidade"]}/${it["proxima"]}" }

    @Test
    fun `sequencia certo certo certo errado certo reagenda pelo SM-2 e o gabarito so aparece depois de responder`() {
        val c = conta(); val t = topico(c); val q = questao(c, t)
        fun lista() = chamar("GET", "/topicos/$t/questoes", token = c.token).corpo[0]
        assertTrue(lista()["respostaCorreta"].isNull); assertEquals(false, lista()["respondida"].asBoolean())

        val dias = listOf("2026-03-02" to 1, "2026-03-03" to 1, "2026-03-09" to 1, "2026-03-24" to 0, "2026-03-25" to 1)
        val esperado = listOf("1/1/2.50/2026-03-03", "2/6/2.50/2026-03-09", "3/15/2.50/2026-03-24", "0/1/2.30/2026-03-25", "1/1/2.40/2026-03-26")
        dias.forEachIndexed { i, (dia, alt) ->
            rel.fixar("${dia}T15:00:00Z")
            val r = responder(c, q, alt)
            assertEquals(200, r.status); assertEquals(alt == 1, r.corpo["correta"].asBoolean()); assertEquals(1, r.corpo["respostaCorreta"].asInt())
            assertEquals(esperado[i], estado(q), "passo ${i + 1}")
            assertEquals(esperado[i].substringAfterLast('/'), r.corpo["proximaRevisao"].asString())
        }
        assertEquals(5, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, q))
        assertEquals(1, lista()["respostaCorreta"].asInt()); assertEquals(true, lista()["ultimaCorreta"].asBoolean())
    }

    @Test
    fun `alternativa invalida e questao inexistente`() {
        val c = conta(); val q = questao(c, topico(c))
        assertEquals("dados_invalidos", responder(c, q, 3).corpo["codigo"].asString())
        assertEquals(400, responder(c, q, -1).status)
        assertEquals(400, chamar("POST", "/questoes/$q/respostas", "{}", c.token).status)
        assertEquals(404, responder(c, UUID.randomUUID().toString(), 0).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, q))
        assertEquals(401, chamar("POST", "/questoes/$q/respostas", """{"alternativa":0}""").status)
    }

    @Test
    fun `entradas malformadas viram 400 e nao gravam nada`() {
        val c = conta(); val q = questao(c, topico(c))
        listOf("{", "", "{\"alternativa\":\"a\"}", "{\"alternativa\":99999999999}", "{\"alternativa\":1.5}", "[]").forEach {
            assertEquals(400, chamar("POST", "/questoes/$q/respostas", it, c.token).status, "corpo: $it")
        }
        assertEquals(400, chamar("POST", "/questoes/nao-e-uuid/respostas", "{\"alternativa\":0}", c.token).status)
        assertEquals(400, chamar("GET", "/desempenho?semanas=abc", token = c.token).status)
        assertEquals(26, chamar("GET", "/desempenho?semanas=9999", token = c.token).corpo["semanas"].size(), "janela limitada ao máximo")
        assertEquals(1, chamar("GET", "/desempenho?semanas=-5", token = c.token).corpo["semanas"].size())
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, q))
    }

    @Test
    fun `A nao responde questao de B e nada de B muda`() {
        val a = conta(); val b = conta(); val qb = questao(b, topico(b))
        assertEquals(404, responder(a, qb, 1).status)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, qb))
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM progresso_questao WHERE questao_id = ?::uuid", Int::class.java, qb))
    }

    @Test
    fun `respostas simultaneas a mesma questao deixam um so agendamento coerente`() {
        val c = conta(); val q = questao(c, topico(c))
        rel.fixar("2026-03-02T15:00:00Z")
        val n = 8; val largada = CountDownLatch(1); val pool = Executors.newFixedThreadPool(n)
        val status = (1..n).map { pool.submit<Int> { largada.await(); responder(c, q, 1).status } }
        largada.countDown()
        assertEquals(List(n) { 200 }, status.map { it.get() }); pool.shutdown()
        assertEquals(1, dono.queryForObject("SELECT count(*) FROM progresso_questao WHERE questao_id = ?::uuid", Int::class.java, q))
        assertEquals(n, dono.queryForObject("SELECT count(*) FROM resposta WHERE questao_id = ?::uuid", Int::class.java, q))
        assertEquals("1/1/2.50/2026-03-03", estado(q), "só a primeira conta; as demais são acertos antecipados")
    }

    @Test
    fun `revisao ordena por vencidas depois taxa de erro, corta em 20 e nao mostra o gabarito`() {
        val c = conta()
        val ta = topico(c, "A"); val tb = topico(c, "B"); val tc = topico(c, "C")
        val qa = List(2) { questao(c, ta) }; val qb = List(2) { questao(c, tb) }; val qc = questao(c, tc)
        rel.fixar("2026-03-02T15:00:00Z")
        qa.forEach { responder(c, it, 0) }                  // A: 2 erradas (taxa 1,0)
        responder(c, qb[0], 0); responder(c, qb[1], 1)      // B: 1 errada e 1 certa (taxa 0,5)
        responder(c, qc, 0)                                 // C: 1 vencida
        assertEquals(0, chamar("GET", "/revisao", token = c.token).corpo["questoes"].size(), "nada vence no mesmo dia")
        rel.fixar("2026-03-03T15:00:00Z")
        val r = chamar("GET", "/revisao", token = c.token).corpo
        assertEquals(listOf("A", "B", "C"), r["topicos"].toList().map { it["nome"].asString() }, "empate de vencidas (A e B) vai para a maior taxa de erro")
        assertEquals(listOf(2, 2, 1), r["topicos"].toList().map { it["vencidas"].asInt() })
        assertEquals(5, r["questoes"].size()); assertEquals("A", r["questoes"][0]["topico"].asString())
        assertTrue(r["questoes"][0].has("alternativas") && !r["questoes"][0].has("respostaCorreta"))

        val grande = topico(c, "Grande")
        repeat(25) { val q = questao(c, grande); dono.update("INSERT INTO progresso_questao(id,usuario_id,questao_id,repeticoes,intervalo_dias,fator_facilidade,ultima_revisao,proxima_revisao) VALUES (gen_random_uuid(),?,?::uuid,1,1,2.50,'2026-03-01','2026-03-02')", c.id, q) }
        val g = chamar("GET", "/revisao", token = c.token).corpo
        assertEquals("Grande", g["topicos"][0]["nome"].asString()); assertEquals(25, g["topicos"][0]["vencidas"].asInt())
        assertEquals(20, g["questoes"].size(), "sessão com até 20 questões")
    }

    @Test
    fun `revisao pode ser restrita a topicos escolhidos e ignora ids de outra conta`() {
        val c = conta(); val outra = conta()
        val ta = topico(c, "A"); val tb = topico(c, "B"); val tx = topico(outra, "X")
        val qa = List(2) { questao(c, ta) }; val qb = questao(c, tb); val qx = questao(outra, tx)
        rel.fixar("2026-03-02T15:00:00Z")
        qa.forEach { responder(c, it, 0) }; responder(c, qb, 0); responder(outra, qx, 0)
        rel.fixar("2026-03-03T15:00:00Z")
        val soB = chamar("GET", "/revisao?topicos=$tb", token = c.token).corpo
        assertEquals(listOf("B"), soB["questoes"].toList().map { it["topico"].asString() }.distinct())
        assertEquals(listOf("A", "B"), soB["topicos"].toList().map { it["nome"].asString() }.sorted(), "a lista de tópicos continua completa")
        val intruso = chamar("GET", "/revisao?topicos=$tx", token = c.token).corpo
        assertEquals(0, intruso["questoes"].size(), "tópico de outra conta não entra")
        assertEquals(3, chamar("GET", "/revisao", token = c.token).corpo["questoes"].size(), "sem filtro, tudo como antes")
        assertEquals(400, chamar("GET", "/revisao?topicos=nao-e-uuid", token = c.token).status)
    }

    @Test
    fun `revisao resumida devolve os topicos vencidos sem montar as questoes`() {
        val c = conta(); val ta = topico(c, "A"); val q = questao(c, ta)
        rel.fixar("2026-03-02T15:00:00Z"); responder(c, q, 0)
        rel.fixar("2026-03-03T15:00:00Z")
        val r = chamar("GET", "/revisao?resumo=true", token = c.token).corpo
        assertEquals(listOf("A"), r["topicos"].toList().map { it["nome"].asString() })
        assertEquals(1, r["topicos"][0]["vencidas"].asInt())
        assertEquals(0, r["questoes"].size())
        assertEquals(1, chamar("GET", "/revisao", token = c.token).corpo["questoes"].size(), "sem o parâmetro continua completa")
    }

    @Test
    fun `revisao sempre oferece os topicos estudados recentemente para gerar questoes novas, mesmo sem nada vencido`() {
        val c = conta(); val outra = conta()
        val ta = topico(c, "A"); val tb = topico(c, "B"); val tc = topico(c, "C"); val tnunca = topico(c, "Nunca respondido")
        val qa = questao(c, ta); val qb = questao(c, tb); val qc = questao(c, tc); questao(c, tnunca)
        rel.fixar("2026-03-02T15:00:00Z"); responder(c, qa, 1)
        rel.fixar("2026-03-02T16:00:00Z"); responder(c, qc, 1)
        rel.fixar("2026-03-02T17:00:00Z"); responder(c, qb, 1)
        val r = chamar("GET", "/revisao", token = c.token).corpo
        assertEquals(0, r["questoes"].size(), "nada vence no mesmo dia")
        assertEquals(listOf("B", "C", "A"), r["recentes"].toList().map { it["nome"].asString() }, "do mais recente ao mais antigo, sem o tópico nunca respondido")
        val tbOutro = topico(outra, "Do outro"); rel.fixar("2026-03-02T18:00:00Z"); responder(outra, questao(outra, tbOutro), 1)
        assertEquals(listOf("B", "C", "A"), chamar("GET", "/revisao", token = c.token).corpo["recentes"].toList().map { it["nome"].asString() }, "nada de outra conta")
        assertEquals(listOf("Do outro"), chamar("GET", "/revisao", token = outra.token).corpo["recentes"].toList().map { it["nome"].asString() })
        repeat(6) { n -> val t = topico(c, "T$n"); rel.fixar("2026-03-03T1$n:00:00Z"); responder(c, questao(c, t), 1) }
        assertEquals(5, chamar("GET", "/revisao", token = c.token).corpo["recentes"].size(), "no máximo nexo.painel.topicos-recentes")
    }

    @Test
    fun `revisao e painel de A nao contam nada de B`() {
        val a = conta(); val b = conta()
        val qb = questao(b, topico(b)); rel.fixar("2026-03-02T15:00:00Z"); responder(b, qb, 0)
        rel.fixar("2026-03-10T15:00:00Z")
        assertEquals(0, chamar("GET", "/revisao", token = a.token).corpo["topicos"].size())
        assertTrue(chamar("GET", "/desempenho", token = a.token).corpo["vazio"].asBoolean())
        assertEquals(1, chamar("GET", "/revisao", token = b.token).corpo["questoes"].size())
        assertEquals(1, chamar("GET", "/desempenho", token = b.token).corpo["assuntos"][0]["total"].asInt())
    }

    @Test
    fun `painel agrupa por semana de segunda a domingo no fuso de Sao Paulo e lista os mais fracos`() {
        val c = conta(); val t = topico(c, "Fraco"); val q = questao(c, t)
        rel.fixar("2026-03-09T02:30:00Z"); assertTrue(responder(c, q, 1).corpo["correta"].asBoolean())  // domingo 08/03 23:30 em São Paulo
        assertEquals("2026-03-09", responder(c, q, 1).corpo["proximaRevisao"].asString(), "a data de revisão também é a de São Paulo")
        rel.fixar("2026-03-09T03:30:00Z"); responder(c, q, 0)                                              // segunda 09/03 00:30 em São Paulo
        rel.fixar("2026-03-09T20:00:00Z"); responder(c, q, 0)
        rel.fixar("2026-03-10T15:00:00Z")
        val d = chamar("GET", "/desempenho?semanas=2", token = c.token).corpo
        assertEquals(listOf("2026-03-02", "2026-03-09"), d["semanas"].toList().map { it.asString() })
        val pontos = d["assuntos"][0]["pontos"].toList().map { "${it["semana"].asString()}:${it["acertos"].asInt()}/${it["total"].asInt()}" }
        assertEquals(listOf("2026-03-02:2/2", "2026-03-09:0/2"), pontos)
        assertEquals("Geral", d["assuntos"][0]["nome"].asString()); assertEquals("Fraco", d["assuntos"][0]["topicos"][0]["nome"].asString())
        assertEquals(1, d["fracos"].size()); assertEquals(50.0, d["fracos"][0]["percentual"].asDouble())
        assertEquals(0, chamar("GET", "/desempenho?semanas=1", token = c.token).corpo["assuntos"][0]["pontos"][0]["total"].asInt() - 2, "a janela de 1 semana só vê a semana atual")
    }

    @Test
    fun `topico com menos respostas que o minimo nao entra nos mais fracos`() {
        val c = conta(); val q = questao(c, topico(c))
        rel.fixar("2026-03-02T15:00:00Z"); responder(c, q, 0)
        val d = chamar("GET", "/desempenho", token = c.token).corpo
        assertEquals(1, d["assuntos"].size()); assertEquals(0, d["fracos"].size())
    }
}
