package br.nexo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Prova com questões novas, com a geração na hora (a montagem roda dentro do pedido): mistura com o banco, orientação, limites e isolamento. */
class ProvaComNovasTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pasta(c: Conta, nome: String) = chamar("POST", "/assuntos", """{"nome":"$nome"}""", c.token).corpo["id"].asString()
    private fun topico(c: Conta, pasta: String) = chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}","assuntoId":"$pasta"}""", c.token).corpo["id"].asString()

    private fun questoesNoBanco(c: Conta, topico: String, n: Int) = repeat(n) {
        dono.update(
            "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,?,'[\"a\",\"b\",\"c\"]',1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
            UUID.randomUUID(), c.id, topico, "Banco ${UUID.randomUUID()}", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
        )
    }

    private fun pastaCom(c: Conta, vararg noBanco: Int): Pair<String, List<String>> {
        val p = pasta(c, "Pasta ${UUID.randomUUID()}")
        return p to noBanco.map { n -> topico(c, p).also { questoesNoBanco(c, it, n) } }
    }

    private fun pedir(c: Conta, assuntos: List<String>, quantidade: Int, novas: Int, orientacao: String? = null, separadas: Boolean = false) = chamar(
        "POST", "/provas",
        """{"assuntos":[${assuntos.joinToString(",") { """{"assuntoId":"$it"}""" }}],"quantidade":$quantidade,"novas":$novas,"separadas":$separadas${orientacao?.let { ""","orientacao":"$it"""" } ?: ""}}""", c.token,
    )

    private fun detalhe(c: Conta, id: String) = chamar("GET", "/provas/$id", token = c.token)
    private fun nQuestoes(topico: String) = dono.queryForObject("SELECT count(*) FROM questao WHERE topico_id = ?::uuid", Int::class.java, topico)!!

    @AfterEach fun restaurar() { python.reprovar = { false }; python.corpos.clear(); python.statusGeracao = null }

    @Test
    fun `prova so de questoes novas reparte pelos topicos, guarda no banco e leva a orientacao ao modelo`() {
        val c = conta(); val (p, ts) = pastaCom(c, 0, 0, 0)
        val r = pedir(c, listOf(p), 6, 6, "no estilo da banca FCC, com foco em casos práticos")
        assertEquals(201, r.status, r.corpo.toString())
        assertEquals("montando", r.corpo["provas"][0]["status"].asString(), "a resposta do pedido é anterior à montagem")
        val id = r.corpo["provas"][0]["id"].asString()
        val d = detalhe(c, id).corpo
        assertEquals("gerada", d["prova"]["status"].asString()); assertEquals(6, d["prova"]["quantidade"].asInt()); assertEquals(6, d["prova"]["questoesGeradas"].asInt())
        assertEquals(6, d["questoes"].size()); assertEquals("no estilo da banca FCC, com foco em casos práticos", d["prova"]["orientacao"].asString())
        assertEquals(listOf(2, 2, 2), ts.map { nQuestoes(it) }, "6 novas em 3 tópicos: duas em cada, e ficam no banco dos tópicos")
        assertTrue(python.corpos.filter { it.startsWith("questoes:") }.all { it.contains("banca FCC") }, "a orientação vai em todos os pedidos de geração")
    }

    @Test
    fun `prova mista completa com o banco e as questoes novas entram na prova`() {
        val c = conta(); val (p, ts) = pastaCom(c, 4)
        val d = pedir(c, listOf(p), 6, 2).corpo["provas"][0]["id"].asString().let { detalhe(c, it).corpo }
        assertEquals("gerada", d["prova"]["status"].asString()); assertEquals(6, d["questoes"].size()); assertEquals(2, d["prova"]["questoesGeradas"].asInt())
        assertEquals(6, nQuestoes(ts[0]), "4 do banco e 2 novas")
        assertEquals(6, d["questoes"].toList().map { it["id"].asString() }.toSet().size, "sem questão repetida na prova")
    }

    @Test
    fun `sem novas continua como antes e o banco insuficiente sugere gerar as que faltam`() {
        val c = conta(); val (p, _) = pastaCom(c, 3)
        val r = pedir(c, listOf(p), 5, 0)
        assertEquals(409, r.status); assertEquals("questoes_insuficientes", r.corpo["codigo"].asString()); assertEquals(3, r.corpo["disponiveis"].asInt())
        assertEquals(201, pedir(c, listOf(p), 5, 2).status, "com 2 novas, o banco precisa dar só 3")
        val (outra, _) = pastaCom(c, 3)
        assertEquals(409, pedir(c, listOf(outra), 6, 2).status, "faltariam questões do banco")
    }

    @Test
    fun `pedidos invalidos`() {
        val c = conta(); val (p, _) = pastaCom(c, 5)
        assertEquals(400, pedir(c, listOf(p), 3, 4).status)
        assertEquals(400, pedir(c, listOf(p), 3, -1).status)
        assertEquals(400, pedir(c, listOf(p), 3, 1, "x".repeat(501)).status)
        assertEquals(201, pedir(c, listOf(p), 3, 0, "   ").status, "orientação em branco é ignorada")
    }

    @Test
    fun `o teto de questoes por topico vale para as novas`() {
        val c = conta(); val (p, ts) = pastaCom(c, 49)
        val r = pedir(c, listOf(p), 3, 3)
        assertEquals(409, r.status); assertEquals("limite_questoes_topico", r.corpo["codigo"].asString()); assertEquals(1, r.corpo["disponiveis"].asInt())
        assertEquals(49, nQuestoes(ts[0]))
        assertEquals(201, pedir(c, listOf(p), 3, 1).status)
    }

    @Test
    fun `a cota de IA precisa cobrir a estimativa antes de comecar`() {
        val c = conta(); val (p, ts) = pastaCom(c, 0)
        dono.update(
            "INSERT INTO registro_geracao(id,usuario_id,operacao,tipo_entrada,modelo_ia,status,tokens_entrada,tokens_saida,custo_estimado_usd,questoes_aprovadas,questoes_rejeitadas,questoes_repetidas,progresso_percentual,finalizada_em) VALUES (gen_random_uuid(),?,'resumo','topico_livre','m','concluida',0,0,0.0185,0,0,0,100,now())",
            c.id,
        )  // 185 de 200 créditos
        val r = pedir(c, listOf(p), 3, 3)  // 3 novas x 6 créditos = 18 > 15
        assertEquals(429, r.status); assertEquals("limite_creditos", r.corpo["codigo"].asString())
        assertEquals(18, r.corpo["necessarios"].asInt()); assertEquals(15, r.corpo["restantes"].asInt())
        assertEquals(0, nQuestoes(ts[0]), "nada foi gerado")
        assertEquals(201, pedir(c, listOf(p), 2, 2).status, "12 créditos cabem em 15")
        assertEquals(6, chamar("GET", "/uso", token = c.token).corpo["creditosPorQuestaoNova"].asInt())
    }

    @Test
    fun `se a verificacao reprovar tudo a prova sai com as do banco e informa quantas faltaram`() {
        val c = conta(); val (p, _) = pastaCom(c, 2)
        python.reprovar = { true }
        val d = pedir(c, listOf(p), 5, 3).corpo["provas"][0]["id"].asString().let { detalhe(c, it).corpo }["prova"]
        assertEquals("gerada", d["status"].asString()); assertEquals(2, d["quantidade"].asInt()); assertEquals(5, d["solicitadas"].asInt()); assertEquals(0, d["questoesGeradas"].asInt())
    }

    @Test
    fun `sem nenhuma questao a prova falha e pode ser excluida`() {
        val c = conta(); val (p, _) = pastaCom(c, 0)
        python.reprovar = { true }
        val id = pedir(c, listOf(p), 3, 3).corpo["provas"][0]["id"].asString()
        val d = detalhe(c, id).corpo["prova"]
        assertEquals("falhou", d["status"].asString()); assertEquals("sem_questoes", d["motivoFalha"].asString())
        assertEquals(409, chamar("POST", "/provas/$id/iniciar", token = c.token).status)
        assertEquals(204, chamar("DELETE", "/provas/$id", token = c.token).status)
    }

    @Test
    fun `duas geracoes seguidas que falham interrompem a montagem e o motivo e a falha da IA`() {
        val c = conta(); val (p, _) = pastaCom(c, 0, 0, 0)
        python.statusGeracao = 400  // o serviço de IA recusa o pedido: a geração termina como falhou, sem nova tentativa
        val antes = python.chamadas.count { it == "questoes" }
        val id = pedir(c, listOf(p), 6, 6).corpo["provas"][0]["id"].asString()
        val d = detalhe(c, id).corpo["prova"]
        assertEquals("falhou", d["status"].asString()); assertEquals("falha_ia", d["motivoFalha"].asString())
        assertEquals(2, python.chamadas.count { it == "questoes" } - antes, "parou na segunda falha, sem tentar o terceiro tópico")
    }

    @Test
    fun `a questao de outra operacao no mesmo topico nao entra como nova da prova`() {
        val c = conta(); val (p, ts) = pastaCom(c, 0)
        // o estudante gera questões à mão no mesmo tópico no momento em que a montagem pede as dela
        python.aoReceber = { if (it == "questoes" && python.corpos.count { x -> x.startsWith("questoes:") } == 1) {
            dono.update(
                "INSERT INTO questao(id,usuario_id,topico_id,enunciado,alternativas,resposta_correta,enunciado_hash,embedding,modelo_embedding) VALUES (?,?,?::uuid,'Feita à mão',?::jsonb,1,?,array_fill(0.01::real, ARRAY[1536])::vector,'m')",
                UUID.randomUUID(), c.id, ts[0], """["a","b","c"]""", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
            )
        } }
        try {
            val id = pedir(c, listOf(p), 2, 2).corpo["provas"][0]["id"].asString()
            val d = detalhe(c, id).corpo
            assertEquals(2, d["prova"]["questoesGeradas"].asInt())
            assertEquals(3, nQuestoes(ts[0]), "duas novas e a feita à mão")
            assertEquals(2, d["questoes"].size())
        } finally { python.aoReceber = null }
    }

    @Test
    fun `provas separadas com novas ganham o mesmo grupo e cada uma tem as suas`() {
        val c = conta(); val (p1, _) = pastaCom(c, 0); val (p2, _) = pastaCom(c, 0)
        val r = pedir(c, listOf(p1, p2), 3, 3, separadas = true).corpo
        assertEquals(2, r["provas"].size()); assertTrue(!r["grupoId"].isNull)
        r["provas"].forEach { assertEquals(3, detalhe(c, it["id"].asString()).corpo["questoes"].size()) }
    }

    @Test
    fun `prova que ainda monta nao inicia nem recebe resposta, e a abandonada vira falha`() {
        val c = conta(); val (p, ts) = pastaCom(c, 1)
        val q = dono.queryForObject("SELECT id FROM questao WHERE topico_id = ?::uuid", UUID::class.java, ts[0])!!
        val montando = UUID.randomUUID(); val velha = UUID.randomUUID()
        dono.update("INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,questoes_novas) VALUES (?,?,5,'montando',5)", montando, c.id)
        dono.update("INSERT INTO prova(id,usuario_id,quantidade_solicitada,status,questoes_novas,atualizado_em) VALUES (?,?,5,'montando',5, now() - interval '1 hour')", velha, c.id)
        assertEquals("prova_nao_pronta", chamar("POST", "/provas/$montando/iniciar", token = c.token).corpo["codigo"].asString())
        assertEquals(409, chamar("POST", "/questoes/$q/respostas", """{"alternativa":1,"provaId":"$montando"}""", c.token).status)
        assertEquals("montando", detalhe(c, montando.toString()).corpo["prova"]["status"].asString())
        val v = detalhe(c, velha.toString()).corpo["prova"]
        assertEquals("falhou", v["status"].asString()); assertEquals("interrompida", v["motivoFalha"].asString())
    }

    @Test
    fun `uma conta nao monta prova com assunto de outra nem enxerga a dela`() {
        val a = conta(); val b = conta(); val (pa, _) = pastaCom(a, 3); val (pb, tb) = pastaCom(b, 0)
        assertEquals(404, pedir(b, listOf(pa), 2, 2).status, "assunto de A")
        val idA = pedir(a, listOf(pa), 2, 1).corpo["provas"][0]["id"].asString()
        assertEquals(404, detalhe(b, idA).status)
        assertEquals(404, chamar("DELETE", "/provas/$idA", token = b.token).status)
        val idB = pedir(b, listOf(pb), 2, 2).corpo["provas"][0]["id"].asString()
        val questoesB = detalhe(b, idB).corpo["questoes"].toList().map { it["id"].asString() }
        assertEquals(2, questoesB.size)
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM questao WHERE usuario_id = ? AND id = ANY (?::uuid[])", Int::class.java, a.id, questoesB.toTypedArray()), "as novas de B são de B")
        assertEquals(2, nQuestoes(tb[0]))
        assertNotEquals(a.id, b.id)
    }
}

/** Montagem de verdade em segundo plano: a prova aparece `montando`, termina sozinha e, se for excluída no meio, a montagem para. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=false", "nexo.prova.montagem-assincrona=true", "nexo.prova.montagem-espera=PT0.05S", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class, RelogioTesteConfig::class)
class ProvaComNovasAssincronaTest : BaseIntegracao() {
    class Conta(val token: String, val id: UUID)

    private fun conta(): Conta {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        return Conta(token, dono.queryForObject("SELECT id FROM usuario WHERE email = ?", UUID::class.java, email)!!)
    }

    private fun pastaComTopicos(c: Conta, n: Int): String {
        val p = chamar("POST", "/assuntos", """{"nome":"Pasta ${UUID.randomUUID()}"}""", c.token).corpo["id"].asString()
        repeat(n) { chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}","assuntoId":"$p"}""", c.token) }
        return p
    }

    private fun esperar(ate: Long = 20_000, condicao: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + ate
        while (System.currentTimeMillis() < fim) { if (condicao()) return true; Thread.sleep(50) }
        return false
    }

    @AfterEach fun soltar() { python.aoReceber = null }

    @Test
    fun `a prova aparece montando e termina sozinha em segundo plano`() {
        val c = conta(); val p = pastaComTopicos(c, 2)
        python.aoReceber = { if (it == "questoes") Thread.sleep(300) }
        val id = chamar("POST", "/provas", """{"assuntos":[{"assuntoId":"$p"}],"quantidade":4,"novas":4}""", c.token).corpo["provas"][0]["id"].asString()
        assertEquals("montando", chamar("GET", "/provas/$id", token = c.token).corpo["prova"]["status"].asString())
        assertTrue(esperar { chamar("GET", "/provas/$id", token = c.token).corpo["prova"]["status"].asString() == "gerada" }, "a prova deveria terminar")
        assertEquals(4, chamar("GET", "/provas/$id", token = c.token).corpo["questoes"].size())
    }

    @Test
    fun `excluir a prova durante a montagem interrompe o que falta gerar`() {
        val c = conta(); val p = pastaComTopicos(c, 4)
        python.aoReceber = { if (it == "questoes") Thread.sleep(400) }
        val antes = python.chamadas.count { it == "questoes" }
        val id = chamar("POST", "/provas", """{"assuntos":[{"assuntoId":"$p"}],"quantidade":4,"novas":4}""", c.token).corpo["provas"][0]["id"].asString()
        assertTrue(esperar { python.chamadas.count { it == "questoes" } > antes }, "a primeira geração começou")
        assertEquals(204, chamar("DELETE", "/provas/$id", token = c.token).status)
        Thread.sleep(2500)
        assertTrue(python.chamadas.count { it == "questoes" } - antes <= 2, "depois da exclusão não sobram 4 gerações: ${python.chamadas.count { it == "questoes" } - antes}")
        assertEquals(0, dono.queryForObject("SELECT count(*) FROM prova WHERE id = ?::uuid", Int::class.java, id))
    }
}


/** Prova que espera a vez na fila da montagem (poucas threads) não é dada por abandonada, mesmo sem pulso por mais tempo que o limite. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nexo.conta.assincrono=false", "nexo.material.assincrono=false", "nexo.geracao.assincrono=false", "nexo.geracao.pulso-maximo=PT1S", "nexo.prova.montagem-assincrona=true", "nexo.prova.montagem-espera=PT0.05S", "nexo.limite-publico.requisicoes=100000"],
)
@org.springframework.context.annotation.Import(EmailTesteConfig::class, RelogioTesteConfig::class)
class ProvaNaFilaDaMontagemTest : BaseIntegracao() {
    @AfterEach fun soltar() { python.aoReceber = null }

    @Test
    fun `tres provas para duas threads, a terceira espera na fila sem virar falha`() {
        val email = "${UUID.randomUUID()}@x.com"
        val token = cadastrar(email = email).corpo["token"].asString()
        python.aoReceber = { if (it == "questoes") Thread.sleep(1500) }
        val ids = (1..3).map {
            val p = chamar("POST", "/assuntos", """{"nome":"Pasta ${UUID.randomUUID()}"}""", token).corpo["id"].asString()
            chamar("POST", "/topicos", """{"nome":"Tema ${UUID.randomUUID()}","assuntoId":"$p"}""", token)
            chamar("POST", "/provas", """{"assuntos":[{"assuntoId":"$p"}],"quantidade":2,"novas":2}""", token).corpo["provas"][0]["id"].asString()
        }
        val vistos = mutableSetOf<String>()
        val fim = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < fim) {
            val status = ids.map { chamar("GET", "/provas/$it", token = token).corpo["prova"]["status"].asString() }
            vistos += status
            if (status.all { it == "gerada" }) break
            Thread.sleep(200)
        }
        assertTrue("falhou" !in vistos, "nenhuma prova pode virar falha só por esperar: $vistos")
        assertEquals(setOf("gerada"), ids.map { chamar("GET", "/provas/$it", token = token).corpo["prova"]["status"].asString() }.toSet())
    }
}
